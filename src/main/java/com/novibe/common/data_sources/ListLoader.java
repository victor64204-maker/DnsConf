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

      return lines.stream()
        .distinct()
        .map(this::toObject)
        .collect(Collectors.toCollection(ArrayList::new));

@SneakyThrows
private String fetchList(String url) {
    Log.io("Loading %s list from url: %s".formatted(listType(), url));

    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .build();

    String body = client.send(
            request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
    ).body();

    // Обрабатываем только официальный GeoHide hosts
    if (url.contains("GeoHideDNS") && url.endsWith("/hosts")) {

        StringBuilder result = new StringBuilder();

        boolean chatGptBlock = false;

        for (String line : body.split("\\R")) {

            if (line.startsWith("# ChatGPT (OpenAI)")) {
                chatGptBlock = true;
                result.append(line).append("\n");
                continue;
            }

            if (chatGptBlock && line.startsWith("#") && !line.startsWith("# ChatGPT")) {
                chatGptBlock = false;
            }

            if (chatGptBlock) {
                line = line.replace("45.155.204.190", "95.182.120.241");
                line = line.replace("37.230.192.51", "95.182.120.241");
            }

            result.append(line).append("\n");
        }

        body = result.toString();
    }

    return body;
}
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
import java.util.List;
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

        return lines.stream()
                .distinct()
                .map(this::toObject)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @SneakyThrows
    private String fetchList(String url) {
        Log.io("Loading %s list from url: %s".formatted(listType(), url));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .build();

        String body = client.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        ).body();

        if (url.contains("GeoHideDNS")) {
            StringBuilder result = new StringBuilder();
            boolean chatGptBlock = false;

            for (String line : body.split("\\R")) {
                if (line.startsWith("# ChatGPT (OpenAI)")) {
                    chatGptBlock = true;
                    result.append(line).append("\n");
                    continue;
                }

                if (chatGptBlock && line.startsWith("#") && !line.startsWith("# ChatGPT")) {
                    chatGptBlock = false;
                }

                if (chatGptBlock) {
                    line = line.replace("45.155.204.190", "95.182.120.241");
                    line = line.replace("37.230.192.51", "95.182.120.241");
                }

                result.append(line).append("\n");
            }

            body = result.toString();
        }

        return body;
    }

    protected String removeWWW(String domain) {
        if (domain.startsWith("www.")) {
            return domain.substring("www.".length());
        }
        return domain;
    }
}
