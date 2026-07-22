package dev.mackerel.d1jdbc;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Parses the driver JDBC URL and merges any {@link Properties} passed to
 * {@code connect()}.
 *
 * <p>Supported forms (DESIGN 3-2):
 * <pre>
 *   jdbc:cloudflare-d1:rest://&lt;account_id&gt;/&lt;database_id&gt;?token=&lt;API_TOKEN&gt;
 *   jdbc:cloudflare-d1:proxy://&lt;worker-host&gt;/&lt;path&gt;?token=&lt;SHARED_SECRET&gt;
 * </pre>
 *
 * <p>For {@code rest}: {@code authority} is the account id, {@code path} is the
 * database id. For {@code proxy}: {@code authority} is the Worker host
 * (optionally {@code host:port}), {@code path} is the base path on that Worker.
 */
public final class D1JdbcUrl {

    public static final String PREFIX = "jdbc:cloudflare-d1:";

    public enum Mode {
        REST, PROXY
    }

    private final Mode mode;
    private final String authority;
    private final String path;
    private final Map<String, String> properties;

    private D1JdbcUrl(Mode mode, String authority, String path, Map<String, String> properties) {
        this.mode = mode;
        this.authority = authority;
        this.path = path;
        this.properties = properties;
    }

    /** Whether the given URL is one this driver accepts. */
    public static boolean acceptsUrl(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    /**
     * Parse a URL and merge {@code info}. Query-string properties take precedence
     * only if not overridden by {@code info}; values in {@code info} win.
     *
     * @throws SQLException if the URL is not a valid driver URL
     */
    public static D1JdbcUrl parse(String url, Properties info) throws SQLException {
        if (!acceptsUrl(url)) {
            throw new SQLException("Not a Cloudflare D1 JDBC URL: " + url, "08001");
        }
        String rest = url.substring(PREFIX.length()); // e.g. rest://acct/db?token=...

        int schemeSep = rest.indexOf("://");
        if (schemeSep < 0) {
            throw new SQLException("Malformed D1 URL, missing '://': " + url, "08001");
        }
        String modeToken = rest.substring(0, schemeSep).toLowerCase();
        Mode mode;
        switch (modeToken) {
            case "rest":
                mode = Mode.REST;
                break;
            case "proxy":
                mode = Mode.PROXY;
                break;
            default:
                throw new SQLException("Unknown D1 transport mode '" + modeToken
                        + "', expected 'rest' or 'proxy'", "08001");
        }

        String afterScheme = rest.substring(schemeSep + 3);

        String query = "";
        int q = afterScheme.indexOf('?');
        if (q >= 0) {
            query = afterScheme.substring(q + 1);
            afterScheme = afterScheme.substring(0, q);
        }

        String authority;
        String path;
        int slash = afterScheme.indexOf('/');
        if (slash < 0) {
            authority = afterScheme;
            path = "";
        } else {
            authority = afterScheme.substring(0, slash);
            path = afterScheme.substring(slash + 1);
        }

        if (authority.isEmpty()) {
            throw new SQLException("Malformed D1 URL, missing authority: " + url, "08001");
        }

        Map<String, String> props = new LinkedHashMap<>();
        parseQuery(query, props);
        // Properties passed programmatically override query-string values.
        if (info != null) {
            for (String name : info.stringPropertyNames()) {
                props.put(name, info.getProperty(name));
            }
        }

        return new D1JdbcUrl(mode, authority, path, props);
    }

    private static void parseQuery(String query, Map<String, String> out) throws SQLException {
        if (query == null || query.isEmpty()) {
            return;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key;
            String value;
            if (eq < 0) {
                key = urlDecode(pair);
                value = "";
            } else {
                key = urlDecode(pair.substring(0, eq));
                value = urlDecode(pair.substring(eq + 1));
            }
            out.put(key, value);
        }
    }

    private static String urlDecode(String s) throws SQLException {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new SQLException("Failed to URL-decode '" + s + "'", "08001", e);
        }
    }

    public Mode mode() {
        return mode;
    }

    public String authority() {
        return authority;
    }

    public String path() {
        return path;
    }

    /** REST: the Cloudflare account id (URL authority). */
    public String accountId() {
        return authority;
    }

    /** REST: the D1 database id/uuid (URL path). */
    public String databaseId() {
        return path;
    }

    /** Auth token / shared secret (the {@code token} property), or {@code null}. */
    public String token() {
        return properties.get("token");
    }

    public String property(String name) {
        return properties.get(name);
    }

    public String property(String name, String defaultValue) {
        return properties.getOrDefault(name, defaultValue);
    }

    public int intProperty(String name, int defaultValue) {
        String v = properties.get(name);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public Map<String, String> properties() {
        return properties;
    }
}
