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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Setter(onMethod_ = @Autowired)
public abstract class ListLoader<T> {

    private HttpClient client;

    protected abstract T toObject(String line);

    protected abstract String listType();

    protected abstract Predicate<String> filterRelatedLines();

    @SneakyThrows
    @SuppressWarnings("preview")
    public List<T> fetchWebsites(List<String> urls) {
        @Cleanup var scope = StructuredTaskScope.open();

        List<StructuredTaskScope.Subtask<String>> requests = new ArrayList<>();

        urls.stream()
                .map(url -> scope.fork(() -> fetchList(url)))
                .forEach(requests::add);

        scope.join();

        List<String> lines = requests.stream()
                .map(StructuredTaskScope.Subtask::get)
                .map(String::stripIndent)
                .flatMap(s -> Pattern.compile("\\r?\\n").splitAsStream(s))
                .parallel()
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))
                .map(String::toLowerCase)
                .filter(filterRelatedLines())
                .toList();

        Map<String, String> uniqueDomains = new LinkedHashMap<>();

        for (String line : lines) {
            int delimiter = line.indexOf(' ');
            if (delimiter == -1) {
                continue;
            }

            String ip = line.substring(0, delimiter).trim();
            String domain = removeWWW(line.substring(delimiter + 1).trim());

            String existing = uniqueDomains.get(domain);

            if (existing == null) {
                uniqueDomains.put(domain, ip + " " + domain);
                continue;
            }

            // Если для домена встретился 37.230.192.51 — заменяем предыдущую запись
            if ("37.230.192.51".equals(ip)) {
                uniqueDomains.put(domain, ip + " " + domain);
            }
        }

        return uniqueDomains.values().stream()
                .map(this::toObject)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @SneakyThrows
    private String fetchList(String url) {
        Log.io("Loading %s list from url: %s".formatted(listType(), url));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    protected String removeWWW(String domain) {
        if (domain.startsWith("www.")) {
            return domain.substring("www.".length());
        }
        return domain;
    }

}
