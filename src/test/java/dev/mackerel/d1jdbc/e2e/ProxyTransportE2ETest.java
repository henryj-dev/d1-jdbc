package dev.mackerel.d1jdbc.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Live proxy-transport E2E tests (DESIGN sections 5 and 6). Auto-skipped when
 * proxy credentials are absent. The proxy Worker forwards to its native D1
 * binding, so batches are truly atomic ({@code db.batch}) and the session
 * bookmark ({@code x-d1-bookmark}) is threaded for read-your-write.
 */
@Tag("e2e")
class ProxyTransportE2ETest {

    private static final String TABLE = "d1_jdbc_e2e";
    private static Connection conn;

    @BeforeAll
    static void openAndCreateTable() throws Exception {
        E2EConfig.assumeProxy();
        Class.forName("dev.mackerel.d1jdbc.D1Driver");
        conn = DriverManager.getConnection(E2EConfig.proxyJdbcUrl());
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("DROP TABLE IF EXISTS " + TABLE);
            s.executeUpdate("CREATE TABLE " + TABLE
                    + " (id INTEGER PRIMARY KEY, name TEXT UNIQUE, flag INTEGER, payload BLOB)");
        }
    }

    @AfterAll
    static void dropTableAndClose() throws SQLException {
        if (conn != null) {
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("DROP TABLE IF EXISTS " + TABLE);
            } finally {
                conn.close();
            }
        }
    }

    @BeforeEach
    void clearRows() throws SQLException {
        conn.setAutoCommit(true);
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("DELETE FROM " + TABLE);
        }
    }

    @Test
    void connectAndSelectOneReturnsOne() throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1")) {
            assertTrue(rs.next(), "SELECT 1 yields a row");
            assertEquals(1, rs.getInt(1), "SELECT 1 returns 1");
        }
    }

    @Test
    void insertSelectRoundTripsStringBooleanBytesAndWasNullOnNull() throws SQLException {
        byte[] payload = "proxy-blob".getBytes(StandardCharsets.UTF_8);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + TABLE + "(id, name, flag, payload) VALUES (?, ?, ?, ?)")) {
            ps.setInt(1, 1);
            ps.setString(2, "carol");
            ps.setBoolean(3, true);
            ps.setBytes(4, payload);
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + TABLE + "(id, name, flag, payload) VALUES (?, ?, ?, ?)")) {
            ps.setInt(1, 2);
            ps.setString(2, "dave");
            ps.setBoolean(3, false);
            ps.setNull(4, java.sql.Types.BLOB);
            assertEquals(1, ps.executeUpdate());
        }

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT name, flag, payload FROM " + TABLE + " ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("carol", rs.getString("name"));
            assertTrue(rs.getBoolean("flag"));
            assertArrayEquals(payload, rs.getBytes("payload"));
            assertFalse(rs.wasNull());

            assertTrue(rs.next());
            assertEquals("dave", rs.getString("name"));
            assertFalse(rs.getBoolean("flag"));
            assertNull(rs.getBytes("payload"));
            assertTrue(rs.wasNull(), "wasNull() true after reading a NULL cell");

            assertFalse(rs.next(), "exactly two rows");
        }
    }

    @Test
    void manualCommitOfConstraintViolatingBatchRollsBackAtomically() throws SQLException {
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("INSERT INTO " + TABLE + "(id, name) VALUES (10, 'dup')");
            s.executeUpdate("INSERT INTO " + TABLE + "(id, name) VALUES (11, 'dup')"); // UNIQUE viol.
            assertThrows(SQLException.class, conn::commit,
                    "atomic batch commit must throw when a statement violates a constraint");
        } finally {
            conn.setAutoCommit(true);
        }

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM " + TABLE + " WHERE name = 'dup'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1),
                    "db.batch is atomic: NEITHER row exists after a failed commit");
        }
    }

    @Test
    void bookmarkGivesReadYourWriteOnSameConnection() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("INSERT INTO " + TABLE + "(id, name) VALUES (20, 'bmk')");
        }
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT name FROM " + TABLE + " WHERE id = 20")) {
            assertTrue(rs.next(), "a subsequent SELECT on the same Connection sees its own write");
            assertEquals("bmk", rs.getString("name"));
        }
    }
}
