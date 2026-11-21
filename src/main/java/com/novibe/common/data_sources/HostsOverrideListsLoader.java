package com.novibe.common.data_sources;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Service
public class HostsOverrideListsLoader extends ListLoader<HostsOverrideListsLoader.BypassRoute> {

    private static final Set<String> EXCLUDED_DOMAIN_ROOTS = Set.of(
            "instagram.com", "cdninstagram.com",
            "facebook.com", "facebook.net", "fbcdn.net", "fb.com", "fbsbx.com",
            "canva.com", "canva-apps.com", "canva.dev"
    );
    private final Set<String> priorityDomains = ConcurrentHashMap.newKeySet();

    public static void useChatGptOverrideIp(String ip) {
        ListLoader.useChatGptOverrideIp(ip);
    }

    public record BypassRoute(String ip, String website) {
    }

    @Override
    protected void resetSpecialDomains() {
        priorityDomains.clear();
        priorityDomains.add("ws.chatgpt.com");
    }

    @Override
    protected void collectSpecialDomains(String body) {
        boolean priorityBlock = false;
        for (String line : body.split("\\R")) {
            String header = line.stripLeading().toLowerCase(Locale.ROOT);
            if (header.startsWith("#")) {
                priorityBlock = header.startsWith("# chatgpt (openai)")
                        || header.startsWith("# google gemini ai")
                        || header.startsWith("# google ai");
                continue;
            }
            if (!priorityBlock || line.isBlank()) continue;

            String[] fields = line.strip().split("\\s+");
            if (fields.length < 2) continue;
            for (int i = 1; i < fields.length; i++) {
                String domain = normalizeDomain(fields[i]);
                if (!domain.isBlank() && !domain.startsWith("#") && !isExcludedDomain(domain)) {
                    priorityDomains.add(domain);
                }
            }
        }
    }

    public boolean isPriorityDomain(String domain) {
        return priorityDomains.contains(normalizeDomain(domain));
    }

    private static String normalizeDomain(String domain) {
        String normalized = domain.strip().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("*.")) normalized = normalized.substring(2);
        while (normalized.startsWith("www.")) normalized = normalized.substring(4);
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    @Override
    protected String listType() {
        return "Override";
    }

    @Override
    protected Predicate<String> filterRelatedLines() {
        return line -> isIpv4HostsEntry(line)
                && !HostsBlockListsLoader.isBlock(line)
                && !isExcludedDomain(extractWebsite(line));
    }

    private static boolean isIpv4HostsEntry(String line) {
        String[] fields = line.strip().split("\\s+", 3);
        if (fields.length < 2) return false;
        String[] octets = fields[0].split("\\.", -1);
        if (octets.length != 4) return false;
        try {
            for (String octet : octets) {
                int value = Integer.parseInt(octet);
                if (octet.isEmpty() || value < 0 || value > 255) return false;
            }
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    @Override
    protected BypassRoute toObject(String line) {
        String[] fields = line.strip().split("\\s+", 3);
        if (fields.length < 2) {
            throw new IllegalArgumentException("Invalid hosts entry: " + line);
        }
        String ip = fields[0];
        String website = removeWWW(fields[1]);
        return new BypassRoute(ip, website);
    }

    public static boolean isExcludedDomain(String domain) {
        if (domain == null || domain.isBlank()) return false;
        String normalized = normalizeDomain(domain);
        return EXCLUDED_DOMAIN_ROOTS.stream().anyMatch(root ->
                normalized.equals(root) || normalized.endsWith("." + root));
    }

    private static String extractWebsite(String hostsEntry) {
        String[] fields = hostsEntry.strip().split("\\s+", 3);
        return fields.length >= 2 ? fields[1] : "";
    }
}
