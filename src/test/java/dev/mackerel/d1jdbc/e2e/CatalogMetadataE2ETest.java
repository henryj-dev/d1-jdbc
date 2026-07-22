package dev.mackerel.d1jdbc.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Live catalog-metadata E2E tests against real D1 over the REST transport
 * (auto-skipped without credentials). Uses dedicated {@code d1_jdbc_meta_e2e}
 * objects created in {@code @BeforeAll} and dropped in {@code @AfterAll} so
 * user data is never touched.
 */
@Tag("e2e")
class CatalogMetadataE2ETest {

    private static final String TABLE = "d1_jdbc_meta_e2e";
    private static final String VIEW = "d1_jdbc_meta_e2e_view";
    private static Connection conn;

    @BeforeAll
    static void openAndCreateSchema() throws Exception {
        E2EConfig.assumeRest();
        Class.forName("dev.mackerel.d1jdbc.D1Driver"); // ensure DriverManager registration
        conn = DriverManager.getConnection(E2EConfig.restJdbcUrl());
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("DROP VIEW IF EXISTS " + VIEW);
            s.executeUpdate("DROP TABLE IF EXISTS " + TABLE);
            s.executeUpdate("CREATE TABLE " + TABLE + " ("
                    + "id INTEGER PRIMARY KEY, "
                    + "name TEXT NOT NULL UNIQUE, "
                    + "score REAL DEFAULT 1.5, "
                    + "payload BLOB)");
            s.executeUpdate("CREATE VIEW " + VIEW + " AS SELECT id, name FROM " + TABLE);
        }
    }

    @AfterAll
    static void dropSchemaAndClose() throws SQLException {
        if (conn != null) {
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("DROP VIEW IF EXISTS " + VIEW);
                s.executeUpdate("DROP TABLE IF EXISTS " + TABLE);
            } finally {
                conn.close();
            }
        }
    }

    @Test
    void getTablesFindsTableAndViewAndHonorsTypesFilter() throws SQLException {
        DatabaseMetaData md = conn.getMetaData();

        boolean sawTable = false;
        boolean sawView = false;
        try (ResultSet rs = md.getTables(null, null, TABLE + "%", null)) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                String type = rs.getString("TABLE_TYPE");
                assertEquals("main", rs.getString("TABLE_CAT"));
                assertNull(rs.getString("TABLE_SCHEM"));
                if (TABLE.equals(name)) {
                    sawTable = true;
                    assertEquals("TABLE", type);
                } else if (VIEW.equals(name)) {
                    sawView = true;
                    assertEquals("VIEW", type);
                }
            }
        }
        assertTrue(sawTable, "getTables finds the e2e table");
        assertTrue(sawView, "getTables finds the e2e view");

        // types filter: VIEW only.
        try (ResultSet rs = md.getTables(null, null, TABLE + "%", new String[] {"VIEW"})) {
            assertTrue(rs.next());
            assertEquals(VIEW, rs.getString("TABLE_NAME"));
            assertEquals("VIEW", rs.getString("TABLE_TYPE"));
            assertFalse(rs.next(), "types={VIEW} excludes the base table");
        }

        // types filter: TABLE only.
        try (ResultSet rs = md.getTables(null, null, TABLE + "%", new String[] {"TABLE"})) {
            assertTrue(rs.next());
            assertEquals(TABLE, rs.getString("TABLE_NAME"));
            assertFalse(rs.next(), "types={TABLE} excludes the view");
        }
    }

    @Test
    void getTablesNeverExposesInternalTables() throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, null, null)) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                assertFalse(name.startsWith("sqlite_"),
                        "sqlite_% internals are hidden: " + name);
                assertFalse(name.startsWith("_cf_"),
                        "D1 _cf_% internals are hidden: " + name);
            }
        }
    }

    @Test
    void getColumnsReturnsNamesTypesNullabilityAndOrdinals() throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, TABLE, null)) {
            assertTrue(rs.next());
            assertEquals("id", rs.getString("COLUMN_NAME"));
            assertEquals(Types.INTEGER, rs.getInt("DATA_TYPE"));
            assertEquals("INTEGER", rs.getString("TYPE_NAME"));
            assertEquals(1, rs.getInt("ORDINAL_POSITION"));
            assertEquals("YES", rs.getString("IS_NULLABLE"));
            assertEquals("YES", rs.getString("IS_AUTOINCREMENT"),
                    "INTEGER PRIMARY KEY is a rowid alias");

            assertTrue(rs.next());
            assertEquals("name", rs.getString("COLUMN_NAME"));
            assertEquals(Types.VARCHAR, rs.getInt("DATA_TYPE"));
            assertEquals("TEXT", rs.getString("TYPE_NAME"));
            assertEquals(2, rs.getInt("ORDINAL_POSITION"));
            assertEquals("NO", rs.getString("IS_NULLABLE"), "NOT NULL column");
            assertEquals(DatabaseMetaData.columnNoNulls, rs.getInt("NULLABLE"));

            assertTrue(rs.next());
            assertEquals("score", rs.getString("COLUMN_NAME"));
            assertEquals(Types.DOUBLE, rs.getInt("DATA_TYPE"));
            assertEquals("REAL", rs.getString("TYPE_NAME"));
            assertEquals(3, rs.getInt("ORDINAL_POSITION"));
            assertEquals("YES", rs.getString("IS_NULLABLE"));
            assertEquals("1.5", rs.getString("COLUMN_DEF"), "declared default value");

            assertTrue(rs.next());
            assertEquals("payload", rs.getString("COLUMN_NAME"));
            assertEquals(Types.BLOB, rs.getInt("DATA_TYPE"));
            assertEquals("BLOB", rs.getString("TYPE_NAME"));
            assertEquals(4, rs.getInt("ORDINAL_POSITION"));

            assertFalse(rs.next(), "exactly four columns");
        }
    }

    @Test
    void getPrimaryKeysReturnsThePkColumn() throws SQLException {
        try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, TABLE)) {
            assertTrue(rs.next());
            assertEquals("main", rs.getString("TABLE_CAT"));
            assertEquals(TABLE, rs.getString("TABLE_NAME"));
            assertEquals("id", rs.getString("COLUMN_NAME"));
            assertEquals(1, rs.getShort("KEY_SEQ"));
            assertNull(rs.getString("PK_NAME"));
            assertFalse(rs.next(), "exactly one pk column");
        }
    }

    @Test
    void getIndexInfoSeesTheUniqueAutoIndex() throws SQLException {
        // TEXT NOT NULL UNIQUE creates sqlite_autoindex_<table>_1 on "name".
        boolean sawUniqueOnName = false;
        try (ResultSet rs = conn.getMetaData()
                .getIndexInfo(null, null, TABLE, true, false)) {
            while (rs.next()) {
                assertFalse(rs.getBoolean("NON_UNIQUE"), "unique=true filter");
                if ("name".equals(rs.getString("COLUMN_NAME"))) {
                    sawUniqueOnName = true;
                }
            }
        }
        assertTrue(sawUniqueOnName, "the UNIQUE constraint's auto-index is reported");
    }

    @Test
    void staticCatalogListsAndIdentifierQuoting() throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        assertEquals("\"", md.getIdentifierQuoteString());

        try (ResultSet rs = md.getCatalogs()) {
            assertTrue(rs.next());
            assertEquals("main", rs.getString("TABLE_CAT"));
            assertFalse(rs.next());
        }
        try (ResultSet rs = md.getSchemas()) {
            assertFalse(rs.next(), "D1 has no schemas");
        }
        try (ResultSet rs = md.getTableTypes()) {
            assertTrue(rs.next());
            assertEquals("TABLE", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("VIEW", rs.getString(1));
            assertFalse(rs.next());
        }
    }
}
