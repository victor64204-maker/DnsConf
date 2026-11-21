package com.novibe.common.data_sources;

import com.novibe.common.util.Log;
import lombok.Cleanup;
import lombok.Setter;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Setter(onMethod_ = @Autowired)
public abstract class ListLoader<T> {
    private static volatile String CHATGPT_OVERRIDE_IP = "193.233.112.88";
    private static final String GOOGLE_AI_OVERRIDE_IP = "193.233.112.88";
    private static final Set<String> LEGACY_GEOHIDE_IPS = Set.of("45.155.204.190", "37.230.192.51");
    private static final Pattern GEOHIDE_IP_COMMENT = Pattern.compile("^\\s*#\\s*((?:\\d{1,3}\\.){3}\\d{1,3})\\s*$");
    private static final Pattern HOSTS_ENTRY = Pattern.compile("^(\\s*)((?:\\d{1,3}\\.){3}\\d{1,3})(\\s+.*)$");
    private HttpClient client;

    public static void useChatGptOverrideIp(String ip) {
        if (ip == null || !ip.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")) throw new IllegalArgumentException("Invalid IPv4 address");
        for (String octet : ip.split("\\.")) if (Integer.parseInt(octet) > 255) throw new IllegalArgumentException("Invalid IPv4 address");
        CHATGPT_OVERRIDE_IP = ip;
    }

    protected abstract T toObject(String line);
    protected abstract String listType();
    protected abstract Predicate<String> filterRelatedLines();

    @SneakyThrows @SuppressWarnings("preview")
    public List<T> fetchWebsites(List<String> urls) {
        @Cleanup var scope = StructuredTaskScope.open();
        List<StructuredTaskScope.Subtask<String>> requests = new ArrayList<>();
        urls.stream().map(url -> scope.fork(() -> fetchList(url))).forEach(requests::add);
        scope.join();
        return requests.stream().map(StructuredTaskScope.Subtask::get).map(String::stripIndent)
                .flatMap(s -> Pattern.compile("\\r?\\n").splitAsStream(s)).filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#")).map(line -> line.toLowerCase(Locale.ROOT))
                .filter(filterRelatedLines()).distinct().map(this::toObject).collect(Collectors.toCollection(ArrayList::new));
    }

    @SneakyThrows
    private String fetchList(String url) {
        Log.io("Loading %s list from url: %s".formatted(listType(), url));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Source returned HTTP " + response.statusCode() + ": " + url);
        String body = response.body();
        if (body == null || body.isBlank()) throw new IllegalStateException("Source returned an empty body: " + url);
        return rewriteSpecialBlocks(body);
    }

    private String rewriteSpecialBlocks(String body) {
        String[] lines = body.split("\\R", -1);
        boolean hasWsHost = false;
        for (String candidate : lines) {
            Matcher m = HOSTS_ENTRY.matcher(candidate);
            if (m.matches() && m.group(3).strip().split("\\s+", 2)[0].equalsIgnoreCase("ws.chatgpt.com")) { hasWsHost = true; break; }
        }
        Set<String> geoHideIps = extractGeoHideIps(lines);
        StringBuilder result = new StringBuilder(body.length());
        boolean chat = false, google = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i], header = line.stripLeading().toLowerCase(Locale.ROOT);
            if (header.startsWith("# chatgpt (openai)")) { Log.io("Using GeoHide ChatGPT override IP: " + CHATGPT_OVERRIDE_IP); chat = true; google = false; }
            else if (header.startsWith("# google gemini ai") || header.startsWith("# google ai")) { Log.io("Using GeoHide Google AI override IP: " + GOOGLE_AI_OVERRIDE_IP); chat = false; google = true; }
            else if (header.startsWith("#")) { chat = false; google = false; }
            else if (chat) line = replaceGeoHideIp(line, CHATGPT_OVERRIDE_IP, geoHideIps);
            else if (google) line = replaceGeoHideIp(line, GOOGLE_AI_OVERRIDE_IP, geoHideIps);
            result.append(line);
            if (header.startsWith("# chatgpt (openai)") && !hasWsHost) result.append((char) 10).append(CHATGPT_OVERRIDE_IP).append(" ws.chatgpt.com");
            if (i < lines.length - 1) result.append((char) 10);
        }
        return result.toString();
    }

    private Set<String> extractGeoHideIps(String[] lines) {
        Set<String> ips = new HashSet<>(LEGACY_GEOHIDE_IPS);
        boolean inHeader = false;
        for (String line : lines) {
            String n = line.strip().toLowerCase(Locale.ROOT);
            if (!inHeader && n.contains("ip-адреса принадлежат geohide dns")) { inHeader = true; continue; }
            if (inHeader && n.contains("остальные ip-адреса принадлежат")) break;
            if (inHeader) { Matcher m = GEOHIDE_IP_COMMENT.matcher(line); if (m.matches()) ips.add(m.group(1)); }
        }
        return ips;
    }

    private String replaceGeoHideIp(String line, String replacement, Set<String> ips) {
        Matcher m = HOSTS_ENTRY.matcher(line);
        return m.matches() && ips.contains(m.group(2)) ? m.group(1) + replacement + m.group(3) : line;
    }

    protected String removeWWW(String domain) { return domain.startsWith("www.") ? domain.substring(4) : domain; }
}
