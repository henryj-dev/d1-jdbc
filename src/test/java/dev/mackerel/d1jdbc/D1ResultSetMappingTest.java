package dev.mackerel.d1jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import dev.mackerel.d1jdbc.testutil.MockTransport;
import dev.mackerel.d1jdbc.transport.D1Meta;
import dev.mackerel.d1jdbc.transport.D1QueryResult;

/** Cell-to-Java mapping and cursor behavior of {@link D1ResultSet} (DESIGN 4-3). */
class D1ResultSetMappingTest {

    private static D1Connection connectionReturning(D1QueryResult canned) throws SQLException {
        D1JdbcUrl url = D1JdbcUrl.parse(
                "jdbc:cloudflare-d1:proxy://host/base?token=t", null);
        MockTransport transport = new MockTransport().setDefaultResult(canned);
        return new D1Connection(url, transport);
    }

    private static List<Object> row(Object... cells) {
        return new ArrayList<>(Arrays.asList(cells));
    }

    private static D1QueryResult oneRow() {
        List<String> cols = List.of("id", "name", "score", "data", "flag", "maybe");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row(1L, "alice", 9.5, new byte[] {1, 2, 3}, 1L, null));
        return new D1QueryResult(cols, rows, D1Meta.EMPTY, null);
    }

    @Test
    void readsTypedColumnsByOneBasedIndex() throws SQLException {
        try (D1Connection c = connectionReturning(oneRow())) {
            ResultSet rs = c.createStatement().executeQuery("SELECT *");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(1L, rs.getLong(1));
            assertEquals("alice", rs.getString(2));
            assertEquals(9.5, rs.getDouble(3));
            assertArrayEquals(new byte[] {1, 2, 3}, rs.getBytes(4));
            assertTrue(rs.getBoolean(5));
        }
    }

    @Test
    void wasNullReflectsLastRead() throws SQLException {
        try (D1Connection c = connectionReturning(oneRow())) {
            ResultSet rs = c.createStatement().executeQuery("SELECT *");
            rs.next();
            rs.getString(2);
            assertFalse(rs.wasNull(), "non-null column read");
            Object v = rs.getObject(6);
            assertNull(v);
            assertTrue(rs.wasNull(), "null column read sets wasNull");
            assertEquals(0, rs.getInt(6), "null coerces to 0");
            assertTrue(rs.wasNull());
        }
    }

    @Test
    void findColumnIsOneBasedAndCaseInsensitive() throws SQLException {
        try (D1Connection c = connectionReturning(oneRow())) {
            ResultSet rs = c.createStatement().executeQuery("SELECT *");
            assertEquals(1, rs.findColumn("id"));
            assertEquals(2, rs.findColumn("NAME"), "case-insensitive");
            assertEquals(4, rs.findColumn("Data"));
            rs.next();
            assertEquals("alice", rs.getString("name"), "read by label");
        }
    }

    @Test
    void findColumnUnknownLabelThrows() throws SQLException {
        try (D1Connection c = connectionReturning(oneRow())) {
            ResultSet rs = c.createStatement().executeQuery("SELECT *");
            assertThrows(SQLException.class, () -> rs.findColumn("nope"));
        }
    }

    @Test
    void nextIsForwardOnlyOverMultipleRows() throws SQLException {
        List<String> cols = List.of("id");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row(10L));
        rows.add(row(20L));
        rows.add(row(30L));
        D1QueryResult canned = new D1QueryResult(cols, rows, D1Meta.EMPTY, null);
        try (D1Connection c = connectionReturning(canned)) {
            ResultSet rs = c.createStatement().executeQuery("SELECT id");
            assertTrue(rs.isBeforeFirst());
            assertTrue(rs.next());
            assertEquals(10L, rs.getLong(1));
            assertTrue(rs.next());
            assertEquals(20L, rs.getLong(1));
            assertTrue(rs.next());
            assertEquals(30L, rs.getLong(1));
            assertFalse(rs.next(), "exhausted");
            assertFalse(rs.next(), "stays exhausted");
            assertThrows(SQLException.class, () -> rs.previous(), "forward-only");
        }
    }

    @Test
    void emptyResultSetHasNoRows() throws SQLException {
        D1QueryResult empty = new D1QueryResult(List.of("id"), List.of(), D1Meta.EMPTY, null);
        try (D1Connection c = connectionReturning(empty)) {
            ResultSet rs = c.createStatement().executeQuery("SELECT id WHERE 1=0");
            assertFalse(rs.isBeforeFirst(), "no rows -> not before-first");
            assertFalse(rs.next());
        }
    }

    @Test
    void columnIndexOutOfRangeThrows() throws SQLException {
        try (D1Connection c = connectionReturning(oneRow())) {
            ResultSet rs = c.createStatement().executeQuery("SELECT *");
            rs.next();
            assertThrows(SQLException.class, () -> rs.getInt(99));
        }
    }
}
