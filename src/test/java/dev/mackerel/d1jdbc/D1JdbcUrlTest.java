package dev.mackerel.d1jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/** URL parsing and property merging (DESIGN 3-2). */
class D1JdbcUrlTest {

    @Test
    void parsesRestUrlIntoAccountAndDatabase() throws SQLException {
        D1JdbcUrl u = D1JdbcUrl.parse(
                "jdbc:cloudflare-d1:rest://acct123/db-uuid-9?token=api-tok", null);
        assertEquals(D1JdbcUrl.Mode.REST, u.mode());
        assertEquals("acct123", u.accountId());
        assertEquals("db-uuid-9", u.databaseId());
        assertEquals("api-tok", u.token());
    }

    @Test
    void parsesProxyUrlIntoHostAndPath() throws SQLException {
        D1JdbcUrl u = D1JdbcUrl.parse(
                "jdbc:cloudflare-d1:proxy://my-worker.workers.dev/d1?token=shh", null);
        assertEquals(D1JdbcUrl.Mode.PROXY, u.mode());
        assertEquals("my-worker.workers.dev", u.authority());
        assertEquals("d1", u.path());
        assertEquals("shh", u.token());
    }

    @Test
    void proxyHostMayIncludePort() throws SQLException {
        D1JdbcUrl u = D1JdbcUrl.parse(
                "jdbc:cloudflare-d1:proxy://localhost:8787/base?token=t", null);
        assertEquals("localhost:8787", u.authority());
        assertEquals("base", u.path());
    }

    @Test
    void programmaticPropertiesOverrideQueryString() throws SQLException {
        Properties info = new Properties();
        info.setProperty("token", "override-token");
        D1JdbcUrl u = D1JdbcUrl.parse(
                "jdbc:cloudflare-d1:rest://acct/db?token=query-token", info);
        assertEquals("override-token", u.token(), "info Properties win over query-string");
    }

    @Test
    void queryStringUsedWhenNotOverridden() throws SQLException {
        Properties info = new Properties();
        info.setProperty("connectTimeoutMillis", "5000");
        D1JdbcUrl u = D1JdbcUrl.parse(
                "jdbc:cloudflare-d1:rest://acct/db?token=query-token", info);
        assertEquals("query-token", u.token());
        assertEquals(5000, u.intProperty("connectTimeoutMillis", 10000));
    }

    @Test
    void urlDecodesEncodedTokenValue() throws SQLException {
        D1JdbcUrl u = D1JdbcUrl.parse(
                "jdbc:cloudflare-d1:rest://acct/db?token=a%20b%2Bc", null);
        assertEquals("a b+c", u.token());
    }

    @Test
    void acceptsUrlTrueForDriverPrefix() {
        assertTrue(D1JdbcUrl.acceptsUrl("jdbc:cloudflare-d1:rest://a/b?token=x"));
        assertTrue(D1JdbcUrl.acceptsUrl("jdbc:cloudflare-d1:proxy://a/b?token=x"));
    }

    @Test
    void acceptsUrlFalseForForeignOrNull() {
        assertFalse(D1JdbcUrl.acceptsUrl("jdbc:postgresql://host/db"));
        assertFalse(D1JdbcUrl.acceptsUrl("jdbc:mysql://host/db"));
        assertFalse(D1JdbcUrl.acceptsUrl(null));
    }

    @Test
    void foreignUrlIsRejected() {
        SQLException ex = assertThrows(SQLException.class,
                () -> D1JdbcUrl.parse("jdbc:postgresql://host/db", null));
        assertEquals("08001", ex.getSQLState());
    }

    @Test
    void unknownTransportModeIsRejected() {
        assertThrows(SQLException.class,
                () -> D1JdbcUrl.parse("jdbc:cloudflare-d1:carrier-pigeon://a/b", null));
    }

    @Test
    void missingSchemeSeparatorIsRejected() {
        assertThrows(SQLException.class,
                () -> D1JdbcUrl.parse("jdbc:cloudflare-d1:rest-no-separator", null));
    }
}
