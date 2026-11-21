package com.novibe.dns.next_dns;

import com.novibe.common.DnsTaskRunner;
import com.novibe.common.data_sources.HostsOverrideListsLoader;
import com.novibe.common.util.EnvParser;
import com.novibe.common.util.Log;
import com.novibe.dns.next_dns.service.NextDnsRewriteService;
import com.novibe.dns.next_dns.service.GeoHideChatGptRouteSelector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.novibe.common.config.EnvironmentVariables.REDIRECT;

@Service
@RequiredArgsConstructor
public class NextDnsTaskRunner implements DnsTaskRunner {

    private final HostsOverrideListsLoader overrideListsLoader;
    private final NextDnsRewriteService nextDnsRewriteService;
    private final GeoHideChatGptRouteSelector routeSelector;

    @Override
    public void run() {
        Log.global("NextDNS Rewrite");
        Log.common("""
        Script updates only source-managed Rewrite rules.
        Existing Denylist and DNS bypass settings are left untouched.
        User-managed Rewrite rules are preserved; agreed Instagram/Facebook/Canva rules are removed.
        """);

        List<String> rewriteSources = EnvParser.parse(REDIRECT);
        if (rewriteSources.isEmpty()) {
            throw new IllegalStateException("No REDIRECT sources configured; refusing to change existing Rewrite rules.");
        }

        String chatGptIp = routeSelector.selectIfGeoHideConfigured(rewriteSources);
        if (chatGptIp != null) HostsOverrideListsLoader.useChatGptOverrideIp(chatGptIp);

        Log.step("Obtain rewrite lists from %s sources".formatted(rewriteSources.size()));
        List<HostsOverrideListsLoader.BypassRoute> overrides = overrideListsLoader.fetchWebsites(rewriteSources);
        Log.common("Loaded %s valid Rewrite domains".formatted(overrides.size()));

        Log.step("Build Rewrite updates");
        Map<String, com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto> rewrites =
                nextDnsRewriteService.buildNewRewrites(overrides);
        Log.common("Prepared %s unique Rewrite rules".formatted(rewrites.size()));

        Log.step("Reconcile and verify NextDNS Rewrite rules");
        nextDnsRewriteService.replaceAllAndVerify(rewrites);

        Log.global("FINISHED");
    }
}
