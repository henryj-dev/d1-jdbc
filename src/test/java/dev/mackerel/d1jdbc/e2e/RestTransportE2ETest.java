package dev.mackerel.d1jdbc.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Live REST-transport E2E tests (DESIGN section 4). Auto-skipped when REST
 * credentials are absent. Uses a dedicated {@code d1_jdbc_e2e} table dropped in
 * {@code @BeforeAll}/{@code @AfterAll} so user data is never touched.
 */
@Tag("e2e")
class RestTransportE2ETest {

    private static final String TABLE = "d1_jdbc_e2e";
    private static Connection conn;

    @BeforeAll
    static void openAndCreateTable() throws Exception {
        E2EConfig.assumeRest();
        Class.forName("dev.mackerel.d1jdbc.D1Driver"); // ensure DriverManager registration
        conn = DriverManager.getConnection(E2EConfig.restJdbcUrl());
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
    void preparedInsertRoundTripsStringBooleanBytesAndWasNullOnNull() throws SQLException {
        byte[] payload = "d1-blob".getBytes(StandardCharsets.UTF_8);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + TABLE + "(id, name, flag, payload) VALUES (?, ?, ?, ?)")) {
            ps.setInt(1, 1);
            ps.setString(2, "alice");
            ps.setBoolean(3, true);
            ps.setBytes(4, payload);
            assertEquals(1, ps.executeUpdate());
        }
        // Second row carries a NULL payload to exercise wasNull().
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + TABLE + "(id, name, flag, payload) VALUES (?, ?, ?, ?)")) {
            ps.setInt(1, 2);
            ps.setString(2, "bob");
            ps.setBoolean(3, false);
            ps.setNull(4, java.sql.Types.BLOB);
            assertEquals(1, ps.executeUpdate());
        }

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT name, flag, payload FROM " + TABLE + " ORDER BY id")) {
            assertTrue(rs.next(), "first row present");
            assertEquals("alice", rs.getString("name"), "TEXT round-trips");
            assertTrue(rs.getBoolean("flag"), "INTEGER 1 reads back as true");
            assertArrayEquals(payload, rs.getBytes("payload"), "BLOB round-trips");
            assertFalse(rs.wasNull(), "payload was not null on row 1");

            assertTrue(rs.next(), "second row present");
            assertEquals("bob", rs.getString("name"));
            assertFalse(rs.getBoolean("flag"), "INTEGER 0 reads back as false");
            assertNull(rs.getBytes("payload"), "NULL BLOB reads back as null");
            assertTrue(rs.wasNull(), "wasNull() true after reading a NULL cell");

            assertFalse(rs.next(), "exactly two rows");
        }
    }

    @Test
    void generatedKeysReturnRowIdAfterInsertAndAreEmptyAfterSelect() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("INSERT INTO " + TABLE + "(id, name) VALUES (100, 'gk')");
            try (ResultSet keys = s.getGeneratedKeys()) {
                assertTrue(keys.next(), "an INSERT yields a generated key");
                assertEquals(100L, keys.getLong(1), "generated key is the new rowid");
                assertFalse(keys.next(), "exactly one generated key");
            }
        }
        try (Statement s = conn.createStatement()) {
            s.executeQuery("SELECT id FROM " + TABLE).close();
            try (ResultSet keys = s.getGeneratedKeys()) {
                assertFalse(keys.next(), "a SELECT produces no generated keys");
            }
        }
    }

    @Test
    void updateCountReflectsMetaChanges() throws SQLException {
        try (Statement s = conn.createStatement()) {
            int inserted = s.executeUpdate(
                    "INSERT INTO " + TABLE + "(id, name) VALUES (200, 'u1'), (201, 'u2')");
            assertEquals(2, inserted, "executeUpdate returns meta.changes for the insert");

            int updated = s.executeUpdate("UPDATE " + TABLE + " SET flag = 1");
            assertEquals(2, updated, "getUpdateCount path reflects rows changed");
            assertEquals(2, s.getUpdateCount(), "getUpdateCount matches the last change count");
        }
    }
}
