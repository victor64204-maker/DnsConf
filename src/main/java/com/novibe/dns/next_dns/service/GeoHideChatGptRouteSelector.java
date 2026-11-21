package com.novibe.dns.next_dns.service;

import com.novibe.common.util.Log;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeoHideChatGptRouteSelector {
    private static final String GEOHIDE_ROOT = "https://raw.githubusercontent.com/Internet-Helper/GeoHideDNS/refs/heads/main/hosts/hosts";
    private static final Pattern IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public String selectIfGeoHideConfigured(List<String> sources) {
        if (sources.stream().noneMatch(GeoHideChatGptRouteSelector::isGeoHideHostsUrl)) return null;

        for (String region : List.of("eu", "us")) {
            String url = regionalUrl(region);
            Log.step("Checking GeoHide " + region.toUpperCase() + " ChatGPT candidates");
            List<String> candidates = fetchChatGptIps(url);
            for (String ip : candidates) {
                boolean chat = probe(ip, "chatgpt.com");
                boolean websocket = chat && probe(ip, "ws.chatgpt.com");
                Log.io("GeoHide " + region.toUpperCase() + " ChatGPT " + ip
                        + " chatgpt.com=" + (chat ? "OK" : "failed")
                        + " ws.chatgpt.com=" + (websocket ? "OK" : "failed"));
                if (chat && websocket) {
                    Log.common("Selected GeoHide " + region.toUpperCase() + " ChatGPT IP: " + ip);
                    return ip;
                }
            }
        }
        throw new IllegalStateException("No GeoHide EU/US ChatGPT IP passed HTTPS checks for chatgpt.com and ws.chatgpt.com; NextDNS was not changed.");
    }

    private List<String> fetchChatGptIps(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("GeoHide source HTTP " + response.statusCode() + ": " + url);
            }
            Set<String> ips = new LinkedHashSet<>();
            boolean inChatGptBlock = false;
            for (String raw : response.body().split("\\R")) {
                String line = raw.strip();
                if (line.startsWith("#")) {
                    String header = line.toLowerCase();
                    if (header.startsWith("# chatgpt (openai)")) inChatGptBlock = true;
                    else if (inChatGptBlock) break;
                    continue;
                }
                if (!inChatGptBlock || line.isBlank()) continue;
                String[] columns = line.split("\\s+");
                if (columns.length < 2 || !columns[1].toLowerCase().endsWith("chatgpt.com")) continue;
                if (isIpv4(columns[0])) ips.add(columns[0]);
            }
            if (ips.isEmpty()) throw new IllegalStateException("No ChatGPT IPs in GeoHide regional source: " + url);
            Log.io("Loaded " + ips.size() + " ChatGPT candidates from " + url);
            return List.copyOf(ips);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading GeoHide regional source: " + url, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load GeoHide regional source: " + url, e);
        }
    }

    private boolean probe(String ip, String hostname) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(ip), 443), 5000);
            socket.setSoTimeout(5000);
            SSLSocket tls = (SSLSocket) SSLSocketFactory.getDefault().createSocket(socket, hostname, 443, true);
            tls.setSoTimeout(5000);
            tls.getSSLParameters().setServerNames(List.of(new javax.net.ssl.SNIHostName(hostname)));
            tls.startHandshake();
            var writer = new java.io.OutputStreamWriter(tls.getOutputStream(), StandardCharsets.US_ASCII);
            writer.write("GET / HTTP/1.1\\r\\nHost: " + hostname
                    + "\\r\\nConnection: Upgrade\\r\\nUpgrade: websocket"
                    + "\\r\\nSec-WebSocket-Version: 13\\r\\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\\r\\n\\r\\n");
            writer.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(tls.getInputStream(), StandardCharsets.US_ASCII));
            String status = reader.readLine();
            return status != null && status.matches("HTTP/1\\.[01] [1-4]\\d\\d.*");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isGeoHideHostsUrl(String source) {
        String normalized = source.toLowerCase();
        return normalized.contains("raw.githubusercontent.com/internet-helper/geohidedns/")
                && normalized.contains("/hosts/");
    }

    private static String regionalUrl(String region) {
        return GEOHIDE_ROOT.replace("/hosts/hosts", "/hosts/" + region + "/hosts");
    }

    private static boolean isIpv4(String value) {
        if (!IPV4.matcher(value).matches()) return false;
        for (String part : value.split("\\.")) {
            try {
                if (Integer.parseInt(part) > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
