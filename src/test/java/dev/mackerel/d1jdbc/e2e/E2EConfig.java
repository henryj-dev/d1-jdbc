package dev.mackerel.d1jdbc.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Assumptions;

/**
 * Resolves opt-in E2E configuration and builds the driver JDBC URLs.
 *
 * <p>Resolution order for every key: (1) environment variable, then (2) a
 * gitignored {@code e2e.local.properties} at the repository root. When neither
 * source supplies the credentials for a transport, {@link #assumeRest()} /
 * {@link #assumeProxy()} abort the calling test via a JUnit assumption so the
 * offline suite is never disturbed — the tests are reported SKIPPED, not failed.
 *
 * <p>Keys:
 * <ul>
 *   <li>REST: {@code D1_ACCOUNT_ID}, {@code D1_DATABASE_ID}, {@code D1_API_TOKEN}</li>
 *   <li>Proxy: {@code D1_PROXY_URL}, {@code D1_PROXY_SECRET}</li>
 * </ul>
 */
final class E2EConfig {

    static final String ACCOUNT_ID = "D1_ACCOUNT_ID";
    static final String DATABASE_ID = "D1_DATABASE_ID";
    static final String API_TOKEN = "D1_API_TOKEN";
    static final String PROXY_URL = "D1_PROXY_URL";
    static final String PROXY_SECRET = "D1_PROXY_SECRET";

    /** Public Cloudflare D1 REST API base (matches {@code RestTransport}). */
    static final String API_BASE = "https://api.cloudflare.com/client/v4";

    private static final Properties FILE_PROPS = loadLocalProperties();

    private E2EConfig() {
    }

    /** Env var wins, then {@code e2e.local.properties}; {@code null} if absent/blank. */
    static String get(String key) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String fromFile = FILE_PROPS.getProperty(key);
        return (fromFile != null && !fromFile.isBlank()) ? fromFile.trim() : null;
    }

    // ---------------------------------------------------------- availability

    static boolean hasRest() {
        return get(ACCOUNT_ID) != null && get(DATABASE_ID) != null && get(API_TOKEN) != null;
    }

    static boolean hasProxy() {
        return get(PROXY_URL) != null && get(PROXY_SECRET) != null;
    }

    /** Skip (not fail) the test unless REST credentials are present. */
    static void assumeRest() {
        Assumptions.assumeTrue(hasRest(),
                "REST E2E skipped: set " + ACCOUNT_ID + ", " + DATABASE_ID + " and "
                        + API_TOKEN + " (env or e2e.local.properties) to run.");
    }

    /** Skip (not fail) the test unless proxy credentials are present. */
    static void assumeProxy() {
        Assumptions.assumeTrue(hasProxy(),
                "Proxy E2E skipped: set " + PROXY_URL + " and " + PROXY_SECRET
                        + " (env or e2e.local.properties) to run.");
    }

    // --------------------------------------------------------------- getters

    static String accountId() {
        return get(ACCOUNT_ID);
    }

    static String databaseId() {
        return get(DATABASE_ID);
    }

    static String apiToken() {
        return get(API_TOKEN);
    }

    static String proxySecret() {
        return get(PROXY_SECRET);
    }

    // ------------------------------------------------------------ JDBC URLs

    /** {@code jdbc:cloudflare-d1:rest://<acct>/<db>?token=<tok>} */
    static String restJdbcUrl() {
        return "jdbc:cloudflare-d1:rest://" + accountId() + "/" + databaseId()
                + "?token=" + enc(apiToken());
    }

    /**
     * {@code jdbc:cloudflare-d1:proxy://<host>/<path>?token=<secret>} derived from
     * {@code D1_PROXY_URL}. An {@code http://} proxy adds {@code &scheme=http}.
     */
    static String proxyJdbcUrl() {
        String raw = get(PROXY_URL);
        String normalized = raw.contains("://") ? raw : "https://" + raw;
        URI uri = URI.create(normalized);
        String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
        String authority = uri.getHost();
        if (uri.getPort() != -1) {
            authority = authority + ":" + uri.getPort();
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        StringBuilder url = new StringBuilder("jdbc:cloudflare-d1:proxy://")
                .append(authority)
                .append(path)
                .append("?token=").append(enc(proxySecret()));
        if ("http".equalsIgnoreCase(scheme)) {
            url.append("&scheme=http");
        }
        return url.toString();
    }

    /** Direct REST {@code /raw} endpoint (used by the atomicity probe). */
    static String restRawEndpoint() {
        return API_BASE + "/accounts/" + accountId() + "/d1/database/" + databaseId() + "/raw";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // --------------------------------------------------------- file loading

    private static Properties loadLocalProperties() {
        Properties props = new Properties();
        Path found = findUpwards("e2e.local.properties");
        if (found != null) {
            try (InputStream in = Files.newInputStream(found)) {
                props.load(in);
            } catch (IOException ignored) {
                // Missing/unreadable file just means "no file source"; env may still supply keys.
            }
        }
        return props;
    }

    /** Walk from the working directory up to the filesystem root looking for {@code name}. */
    private static Path findUpwards(String name) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
