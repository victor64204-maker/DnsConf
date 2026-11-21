package com.novibe.dns.next_dns.service;

import com.novibe.common.data_sources.HostsOverrideListsLoader;
import com.novibe.common.util.Log;
import com.novibe.dns.next_dns.http.NextDnsRateLimitedApiProcessor;
import com.novibe.dns.next_dns.http.NextDnsRewriteClient;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class NextDnsRewriteService {

    private static final int NEXTDNS_REWRITE_LIMIT = 500;

    private final NextDnsRewriteClient nextDnsRewriteClient;
    private final HostsOverrideListsLoader hostsOverrideListsLoader;

    public Map<String, CreateRewriteDto> buildNewRewrites(List<HostsOverrideListsLoader.BypassRoute> overrides) {
        Map<String, CreateRewriteDto> rewriteDtos = new LinkedHashMap<>();
        overrides.stream()
                .sorted(Comparator.comparing((HostsOverrideListsLoader.BypassRoute route) ->
                        !hostsOverrideListsLoader.isPriorityDomain(route.website())))
                .forEach(route -> rewriteDtos.putIfAbsent(route.website(),
                        new CreateRewriteDto(route.website(), route.ip())));
        return rewriteDtos;
    }

    /**
     * Reconciles source rules and removes only the agreed excluded domains.
     * Unrelated Rewrite rules, Denylist, and DNS bypass settings are preserved.
     * NextDNS accepts at most 500 Rewrite rules per profile.
     */
    public void replaceAllAndVerify(Map<String, CreateRewriteDto> expectedRewrites) {
        if (expectedRewrites == null || expectedRewrites.isEmpty()) {
            throw new IllegalStateException("Rewrite source produced no valid domains; existing NextDNS Rewrite rules were left untouched.");
        }

        Map<String, CreateRewriteDto> allExpected = new LinkedHashMap<>();
        expectedRewrites.values().stream()
                .sorted(Comparator.comparing((CreateRewriteDto rule) ->
                        !hostsOverrideListsLoader.isPriorityDomain(rule.name())))
                .forEach(rule -> allExpected.putIfAbsent(normalize(rule.name()), rule));

        List<RewriteDto> existing = getExistingRewrites();
        Set<String> sourceNames = allExpected.keySet();
        int unrelatedCount = (int) existing.stream()
                .filter(rule -> !HostsOverrideListsLoader.isExcludedDomain(rule.name()))
                .filter(rule -> !sourceNames.contains(normalize(rule.name())))
                .count();
        int capacity = NEXTDNS_REWRITE_LIMIT - unrelatedCount;
        List<CreateRewriteDto> priorityRules = allExpected.values().stream()
                .filter(rule -> hostsOverrideListsLoader.isPriorityDomain(rule.name()))
                .toList();
        if (capacity < priorityRules.size()) {
            throw new IllegalStateException(("NextDNS Rewrite limit is %s; %s unrelated rules leave room for only %s source rules, "
                    + "but %s ChatGPT/Gemini rules have priority. No NextDNS changes were made.")
                    .formatted(NEXTDNS_REWRITE_LIMIT, unrelatedCount, Math.max(0, capacity), priorityRules.size()));
        }

        LinkedHashMap<String, CreateRewriteDto> selected = new LinkedHashMap<>();
        for (CreateRewriteDto rule : allExpected.values()) {
            if (selected.size() >= capacity) break;
            selected.put(normalize(rule.name()), rule);
        }
        int omittedCount = allExpected.size() - selected.size();
        Log.common(("NextDNS rewrite capacity: %s total; preserving %s unrelated; applying %s source rules "
                + "(%s ChatGPT/Gemini priority); omitting %s lower-priority source rules.")
                .formatted(NEXTDNS_REWRITE_LIMIT, unrelatedCount, selected.size(), priorityRules.size(), omittedCount));

        List<RewriteDto> toDelete = new ArrayList<>();
        Set<String> keptSelectedRules = new HashSet<>();
        for (RewriteDto current : existing) {
            String name = normalize(current.name());
            if (HostsOverrideListsLoader.isExcludedDomain(name)) {
                toDelete.add(current);
                continue;
            }
            CreateRewriteDto desired = selected.get(name);
            if (desired == null) {
                if (sourceNames.contains(name)) toDelete.add(current);
                continue;
            }
            if (desired.content().equals(current.content()) && keptSelectedRules.add(name)) continue;
            toDelete.add(current);
        }

        if (!toDelete.isEmpty()) {
            long excludedCount = toDelete.stream()
                    .filter(rule -> HostsOverrideListsLoader.isExcludedDomain(rule.name()))
                    .count();
            long sourceCount = toDelete.size() - excludedCount;
            if (excludedCount > 0) Log.io("Removing %s excluded Rewrite rules".formatted(excludedCount));
            if (sourceCount > 0) Log.io("Removing %s outdated, duplicate, or lower-priority source Rewrite rules".formatted(sourceCount));
            NextDnsRateLimitedApiProcessor.callApi(toDelete.stream().map(RewriteDto::id).toList(),
                    nextDnsRewriteClient::deleteRewriteById);
        }

        List<RewriteDto> remaining = nextDnsRewriteClient.fetchRewrites();
        Set<String> alreadyCorrect = remaining.stream()
                .map(rule -> normalize(rule.name()) + "=" + rule.content())
                .collect(java.util.stream.Collectors.toSet());
        List<CreateRewriteDto> requests = selected.values().stream()
                .filter(rule -> !alreadyCorrect.contains(normalize(rule.name()) + "=" + rule.content()))
                .toList();
        if (!requests.isEmpty()) {
            Log.io("Creating %s missing or changed source Rewrite rules".formatted(requests.size()));
            NextDnsRateLimitedApiProcessor.callApi(requests, nextDnsRewriteClient::saveRewrite);
        }

        Log.io("Verifying applied Rewrite rules in NextDNS");
        List<RewriteDto> actual = nextDnsRewriteClient.fetchRewrites();
        Set<String> received = actual.stream()
                .map(rule -> normalize(rule.name()) + "=" + rule.content())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> missing = selected.values().stream()
                .map(rule -> normalize(rule.name()) + "=" + rule.content())
                .filter(rule -> !received.contains(rule))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> excludedStillPresent = actual.stream()
                .map(RewriteDto::name)
                .filter(HostsOverrideListsLoader::isExcludedDomain)
                .collect(java.util.stream.Collectors.toSet());

        if (!missing.isEmpty() || !excludedStillPresent.isEmpty() || actual.size() > NEXTDNS_REWRITE_LIMIT) {
            throw new IllegalStateException(("NextDNS Rewrite verification failed: missing=%s, excluded rules still present=%s, "
                    + "actual rule count=%s, limit=%s")
                    .formatted(missing.size(), excludedStillPresent.size(), actual.size(), NEXTDNS_REWRITE_LIMIT));
        }
        Log.common(("Rewrite verified: applied %s prioritized source rules, omitted %s lower-priority source rules, "
                + "preserved %s unrelated existing rules; excluded roots removed.")
                .formatted(selected.size(), omittedCount, unrelatedCount));
    }

    private static String normalize(String hostname) {
        return hostname.strip().toLowerCase(Locale.ROOT);
    }

    public List<RewriteDto> getExistingRewrites() {
        Log.io("Fetching existing rewrites from NextDNS");
        return nextDnsRewriteClient.fetchRewrites();
    }

    public void saveRewrites(List<CreateRewriteDto> createRewriteDtos) {
        Log.io("Saving %s new rewrites to NextDNS...".formatted(createRewriteDtos.size()));
        NextDnsRateLimitedApiProcessor.callApi(createRewriteDtos, nextDnsRewriteClient::saveRewrite);
    }

    public void removeAll() {
        List<RewriteDto> list = getExistingRewrites();
        List<String> ids = list.stream().map(RewriteDto::id).toList();
        Log.io("Removing %s rewrites from NextDNS".formatted(ids.size()));
        NextDnsRateLimitedApiProcessor.callApi(ids, nextDnsRewriteClient::deleteRewriteById);
    }
}
