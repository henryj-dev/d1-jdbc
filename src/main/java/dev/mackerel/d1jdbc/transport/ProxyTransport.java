package dev.mackerel.d1jdbc.transport;

import dev.mackerel.d1jdbc.D1Codec;
import dev.mackerel.d1jdbc.D1JdbcUrl;
import dev.mackerel.d1jdbc.internal.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Transport B: a self-deployed proxy Worker (see {@code proxy/}).
 *
 * <p>Posts to {@code https://<host>/<base>/query} and {@code .../batch}. The
 * Worker forwards to its native D1 binding, so batches are truly atomic via
 * {@code db.batch(...)} ({@code supportsAtomicBatch = true}). The response body
 * is a D1 result passthrough (no CF envelope): the Worker normalizes it to
 * {@code {columns, rows, meta}} (and an array thereof for {@code /batch}).
 *
 * <p>The session bookmark header ({@code x-cf-d1-session-commit-token}) is
 * passed through in both directions to preserve read-your-write consistency.
 */
public final class ProxyTransport implements D1Transport {

    private static final String BOOKMARK_HEADER = "x-cf-d1-session-commit-token";

    private final HttpClient client;
    private final String queryEndpoint;
    private final String batchEndpoint;
    private final String secret;
    private final Duration requestTimeout;

    public ProxyTransport(D1JdbcUrl url) {
        String scheme = url.property("scheme", "https");
        String base = scheme + "://" + url.authority();
        String path = url.path();
        if (path != null && !path.isEmpty()) {
            base = base + "/" + trimSlashes(path);
        }
        this.queryEndpoint = base + "/query";
        this.batchEndpoint = base + "/batch";
        this.secret = url.token();
        int connectTimeout = url.intProperty("connectTimeoutMillis", 10_000);
        this.requestTimeout = Duration.ofMillis(url.intProperty("requestTimeoutMillis", 30_000));
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();
    }

    @Override
    public D1QueryResult query(String sql, List<Object> params, String bookmark) {
        String body = D1Codec.buildQueryBody(sql, params);
        HttpResponse<String> response = send(queryEndpoint, body, bookmark);
        String returnedBookmark = response.headers()
                .firstValue(BOOKMARK_HEADER).orElse(bookmark);
        Map<String, Object> resultObj;
        try {
            resultObj = Json.parseObject(response.body());
        } catch (RuntimeException parseError) {
            throw new TransportException("D1 proxy returned a non-JSON body (HTTP "
                    + response.statusCode() + ")", "08006", 0, parseError);
        }
        return D1Codec.parseResult(resultObj, returnedBookmark);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<D1QueryResult> batch(List<D1Request> stmts, String bookmark) {
        String body = D1Codec.buildBatchBody(stmts);
        HttpResponse<String> response = send(batchEndpoint, body, bookmark);
        String returnedBookmark = response.headers()
                .firstValue(BOOKMARK_HEADER).orElse(bookmark);

        Object parsed;
        try {
            parsed = Json.parse(response.body());
        } catch (RuntimeException parseError) {
            throw new TransportException("D1 proxy returned a non-JSON batch body (HTTP "
                    + response.statusCode() + ")", "08006", 0, parseError);
        }
        List<Object> elements;
        if (parsed instanceof List) {
            elements = (List<Object>) parsed;
        } else if (parsed instanceof Map) {
            // Tolerate a {results:[...]} wrapper if the Worker adds one.
            Object node = ((Map<String, Object>) parsed).get("results");
            elements = (node instanceof List) ? (List<Object>) node : List.of(parsed);
        } else {
            elements = List.of();
        }

        List<D1QueryResult> out = new ArrayList<>(elements.size());
        for (Object el : elements) {
            Map<String, Object> obj = (el instanceof Map) ? (Map<String, Object>) el : Map.of();
            out.add(D1Codec.parseResult(obj, returnedBookmark));
        }
        return out;
    }

    private HttpResponse<String> send(String endpoint, String body, String bookmark) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (secret != null && !secret.isEmpty()) {
                builder.header("Authorization", "Bearer " + secret);
            }
            if (bookmark != null && !bookmark.isEmpty()) {
                builder.header(BOOKMARK_HEADER, bookmark);
            }
            HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw proxyError(response);
            }
            return response;
        } catch (java.io.IOException e) {
            throw new TransportException("HTTP I/O error calling D1 proxy Worker: "
                    + e.getMessage(), "08006", 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("Interrupted calling D1 proxy Worker", "08006", 0, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static TransportException proxyError(HttpResponse<String> response) {
        String message = "D1 proxy request failed (HTTP " + response.statusCode() + ")";
        String bodyText = response.body();
        if (bodyText != null && !bodyText.isEmpty()) {
            try {
                Object parsed = Json.parse(bodyText);
                if (parsed instanceof Map) {
                    Object err = ((Map<String, Object>) parsed).get("error");
                    if (err != null) {
                        message = String.valueOf(err);
                    }
                } else {
                    message = bodyText;
                }
            } catch (RuntimeException ignore) {
                message = bodyText;
            }
        }
        return new TransportException(message, D1Codec.sqlStateFor(message), 0, null);
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, "proxy");
    }

    @Override
    public void close() {
        // HttpClient has no explicit close on Java 17.
    }

    private static String trimSlashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '/') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(start, end);
    }
}
