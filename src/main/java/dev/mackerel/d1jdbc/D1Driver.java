package dev.mackerel.d1jdbc;

import dev.mackerel.d1jdbc.transport.D1Transport;
import dev.mackerel.d1jdbc.transport.ProxyTransport;
import dev.mackerel.d1jdbc.transport.RestTransport;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC {@link Driver} for Cloudflare D1.
 *
 * <p>Self-registers with {@link DriverManager} on class load (both via the
 * static initializer and the {@code META-INF/services/java.sql.Driver}
 * ServiceLoader entry). Parses the URL via {@link D1JdbcUrl} and selects the
 * transport: {@code rest} -> {@link RestTransport}, {@code proxy} ->
 * {@link ProxyTransport}.
 *
 * <p>URL forms:
 * <pre>
 *   jdbc:cloudflare-d1:rest://&lt;account_id&gt;/&lt;database_id&gt;?token=&lt;API_TOKEN&gt;
 *   jdbc:cloudflare-d1:proxy://&lt;worker-host&gt;/&lt;path&gt;?token=&lt;SHARED_SECRET&gt;
 * </pre>
 */
public final class D1Driver implements Driver {

    public static final int MAJOR_VERSION = 0;
    public static final int MINOR_VERSION = 1;
    public static final String DRIVER_NAME = "Cloudflare D1 JDBC Driver";

    static {
        try {
            DriverManager.registerDriver(new D1Driver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        return D1JdbcUrl.acceptsUrl(url);
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            // Per the JDBC contract, return null so DriverManager can try the next driver.
            return null;
        }
        D1JdbcUrl parsed = D1JdbcUrl.parse(url, info);
        D1Transport transport = createTransport(parsed);
        return new D1Connection(parsed, transport);
    }

    private static D1Transport createTransport(D1JdbcUrl url) throws SQLException {
        switch (url.mode()) {
            case REST:
                return new RestTransport(url);
            case PROXY:
                return new ProxyTransport(url);
            default:
                throw new SQLException("Unsupported transport mode: " + url.mode());
        }
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        DriverPropertyInfo token = new DriverPropertyInfo("token",
                info == null ? null : info.getProperty("token"));
        token.description = "Cloudflare API token (rest) or shared secret (proxy).";
        token.required = false;

        DriverPropertyInfo connectTimeout =
                new DriverPropertyInfo("connectTimeoutMillis", "10000");
        connectTimeout.description = "HTTP connect timeout in milliseconds.";

        DriverPropertyInfo requestTimeout =
                new DriverPropertyInfo("requestTimeoutMillis", "30000");
        requestTimeout.description = "HTTP request timeout in milliseconds.";

        return new DriverPropertyInfo[] {token, connectTimeout, requestTimeout};
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging is not used by this driver");
    }
}
