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
 * Transport A: the public Cloudflare D1 REST API (no deployment required).
 *
 * <p>Posts {@code {sql, params}} to
 * {@code /accounts/{acct}/d1/database/{db}/raw} with a Bearer API token and
 * unwraps the standard CF envelope
 * {@code {result, success, errors[], messages[]}}. The {@code /raw} endpoint
 * returns {@code {results:{columns, rows}, meta}} per element, which is exactly
 * the {@code ROWS_AND_COLUMNS} shape the codec expects.
 *
 * <p>Atomic batches are not natively supported here: {@link #batch} runs the
 * statements sequentially (best-effort, non-atomic), so
 * {@code supportsAtomicBatch = false}.
 */
public final class RestTransport implements D1Transport {

    private static final String BOOKMARK_HEADER = "x-cf-d1-session-commit-token";
    private static final String DEFAULT_API_BASE = "https://api.cloudflare.com/client/v4";

    private final HttpClient client;
    private final String rawEndpoint;
    private final String token;
    private final Duration requestTimeout;

    public RestTransport(D1JdbcUrl url) {
        String apiBase = trimTrailingSlash(url.property("apiBase", DEFAULT_API_BASE));
        this.rawEndpoint = apiBase + "/accounts/" + url.accountId()
                + "/d1/database/" + url.databaseId() + "/raw";
        this.token = url.token();
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
        return sendSingle(body, bookmark);
    }

    @Override
    public List<D1QueryResult> batch(List<D1Request> stmts, String bookmark) {
        // REST has no clean atomic array endpoint; run sequentially (best-effort),
        // threading the session bookmark forward. supportsAtomicBatch() == false.
        List<D1QueryResult> out = new ArrayList<>(stmts.size());
        String current = bookmark;
        for (D1Request stmt : stmts) {
            D1QueryResult r = sendSingle(
                    D1Codec.buildQueryBody(stmt.sql(), stmt.params()), current);
            out.add(r);
            if (r.bookmark() != null) {
                current = r.bookmark();
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private D1QueryResult sendSingle(String body, String bookmark) {
        HttpResponse<String> response = send(rawEndpoint, body, bookmark);
        Map<String, Object> envelope;
        try {
            envelope = Json.parseObject(response.body());
        } catch (RuntimeException parseError) {
            // Cloudflare edge/gateway failures (502/503/1xxx) return HTML or plain
            // text, not the JSON envelope. Surface these as a TransportException so
            // the JDBC layer converts them to SQLException (DESIGN 4-5) rather than
            // letting a raw RuntimeException escape across the surface.
            throw nonJsonError(response, parseError);
        }

        boolean success = Boolean.TRUE.equals(envelope.get("success"));
        if (!success || response.statusCode() >= 400) {
            throw envelopeError(envelope, response.statusCode());
        }

        Object resultNode = envelope.get("result");
        Map<String, Object> resultObj = firstResult(resultNode);
        String returnedBookmark = response.headers()
                .firstValue(BOOKMARK_HEADER).orElse(bookmark);
        return D1Codec.parseResult(resultObj, returnedBookmark);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstResult(Object resultNode) {
        if (resultNode instanceof List) {
            List<Object> list = (List<Object>) resultNode;
            if (list.isEmpty()) {
                return Map.of();
            }
            Object first = list.get(0);
            return (first instanceof Map) ? (Map<String, Object>) first : Map.of();
        }
        if (resultNode instanceof Map) {
            return (Map<String, Object>) resultNode;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static TransportException envelopeError(Map<String, Object> envelope, int status) {
        Object errors = envelope.get("errors");
        String message = "D1 REST request failed (HTTP " + status + ")";
        int vendorCode = 0;
        if (errors instanceof List && !((List<Object>) errors).isEmpty()) {
            Object first = ((List<Object>) errors).get(0);
            if (first instanceof Map) {
                Map<String, Object> err = (Map<String, Object>) first;
                Object msg = err.get("message");
                if (msg != null) {
                    message = String.valueOf(msg);
                }
                Object code = err.get("code");
                if (code instanceof Number) {
                    vendorCode = ((Number) code).intValue();
                }
            }
        }
        return new TransportException(message, D1Codec.sqlStateFor(message), vendorCode, null);
    }

    private static TransportException nonJsonError(HttpResponse<String> response, Throwable cause) {
        String body = response.body();
        String snippet = (body == null) ? ""
                : (body.length() > 300 ? body.substring(0, 300) + "…" : body);
        String message = "D1 REST request failed (HTTP " + response.statusCode()
                + "): non-JSON response from Cloudflare"
                + (snippet.isBlank() ? "" : ": " + snippet.strip());
        // 08006: connection failure — the request did not reach a working D1 endpoint.
        return new TransportException(message, "08006", 0, cause);
    }

    private HttpResponse<String> send(String endpoint, String body, String bookmark) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (token != null && !token.isEmpty()) {
                builder.header("Authorization", "Bearer " + token);
            }
            if (bookmark != null && !bookmark.isEmpty()) {
                builder.header(BOOKMARK_HEADER, bookmark);
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new TransportException("HTTP I/O error calling D1 REST API: " + e.getMessage(),
                    "08006", 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("Interrupted calling D1 REST API", "08006", 0, e);
        }
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(false, true, "rest");
    }

    @Override
    public void close() {
        // HttpClient has no explicit close on Java 17.
    }

    private static String trimTrailingSlash(String s) {
        if (s != null && s.endsWith("/")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
