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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NextDnsRewriteService {
    private final NextDnsRewriteClient nextDnsRewriteClient;

    public Map<String, CreateRewriteDto> buildNewRewrites(List<HostsOverrideListsLoader.BypassRoute> overrides) {
        Map<String, CreateRewriteDto> rewriteDtos = new HashMap<>();
        overrides.forEach(route -> rewriteDtos.putIfAbsent(route.website(), new CreateRewriteDto(route.website(), route.ip())));
        return rewriteDtos;
    }

    /**
     * Reconciles source-managed Rewrite rules while preserving unrelated user rules.
     * Denylist and DNS bypass configuration are never accessed here.
     */
    public void replaceAllAndVerify(Map<String, CreateRewriteDto> expectedRewrites) {
        if (expectedRewrites == null || expectedRewrites.isEmpty()) {
            throw new IllegalStateException("Rewrite source produced no valid domains; existing NextDNS Rewrite rules were left untouched.");
        }

        Map<String, CreateRewriteDto> expected = expectedRewrites.values().stream().collect(Collectors.toMap(
                rule -> normalize(rule.name()), rule -> rule, (first, ignored) -> first, HashMap::new));
        List<RewriteDto> existing = getExistingRewrites();
        List<RewriteDto> toDelete = new ArrayList<>();
        Map<String, List<RewriteDto>> byName = existing.stream().collect(Collectors.groupingBy(rule -> normalize(rule.name())));
        int preserved = 0;

        for (RewriteDto current : existing) {
            String name = normalize(current.name());
            if (HostsOverrideListsLoader.isExcludedDomain(name)) {
                toDelete.add(current);
                continue;
            }
            CreateRewriteDto desired = expected.get(name);
            if (desired == null) {
                preserved++;
                continue;
            }
            if (desired.content().equals(current.content())) {
                List<RewriteDto> duplicates = byName.get(name);
                if (!duplicates.isEmpty() && duplicates.getFirst() == current) duplicates.removeFirst();
                else toDelete.add(current);
            } else {
                toDelete.add(current);
            }
        }

        if (!toDelete.isEmpty()) {
            Log.io("Removing %s stale, duplicate, changed, or excluded Rewrite rules".formatted(toDelete.size()));
            NextDnsRateLimitedApiProcessor.callApi(toDelete.stream().map(RewriteDto::id).toList(), nextDnsRewriteClient::deleteRewriteById);
        }

        List<RewriteDto> remaining = nextDnsRewriteClient.fetchRewrites();
        Set<String> alreadyCorrect = remaining.stream()
                .map(rule -> normalize(rule.name()) + "=" + rule.content())
                .collect(Collectors.toSet());
        List<CreateRewriteDto> toCreate = expected.values().stream()
                .filter(rule -> !alreadyCorrect.contains(normalize(rule.name()) + "=" + rule.content()))
                .toList();
        if (!toCreate.isEmpty()) {
            Log.io("Creating or updating %s source Rewrite rules".formatted(toCreate.size()));
            NextDnsRateLimitedApiProcessor.callApi(toCreate, nextDnsRewriteClient::saveRewrite);
        }

        Log.io("Verifying synchronized Rewrite rules in NextDNS");
        List<RewriteDto> actual = nextDnsRewriteClient.fetchRewrites();
        Set<String> actualRules = actual.stream()
                .map(rule -> normalize(rule.name()) + "=" + rule.content())
                .collect(Collectors.toSet());
        Set<String> missing = expected.values().stream()
                .map(rule -> normalize(rule.name()) + "=" + rule.content())
                .filter(rule -> !actualRules.contains(rule))
                .collect(Collectors.toSet());
        Set<String> excludedStillPresent = actual.stream()
                .map(RewriteDto::name)
                .filter(HostsOverrideListsLoader::isExcludedDomain)
                .collect(Collectors.toSet());
        if (!missing.isEmpty() || !excludedStillPresent.isEmpty()) {
            throw new IllegalStateException("NextDNS Rewrite verification failed: missing=" + missing.size()
                    + ", excluded rules still present=" + excludedStillPresent.size());
        }
        Log.common("Rewrite verified: %s source rules; preserved %s unrelated existing rules; excluded roots removed."
                .formatted(expected.size(), preserved));
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
