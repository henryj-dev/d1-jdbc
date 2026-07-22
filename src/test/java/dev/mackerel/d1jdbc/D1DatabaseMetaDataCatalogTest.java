package dev.mackerel.d1jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mackerel.d1jdbc.testutil.MockTransport;
import dev.mackerel.d1jdbc.transport.D1Meta;
import dev.mackerel.d1jdbc.transport.D1QueryResult;

/**
 * Offline catalog-introspection tests for {@link D1DatabaseMetaData}, driven
 * by {@link MockTransport} serving canned {@code sqlite_master}/pragma shapes
 * as observed live on D1 (see the probe notes in D1DatabaseMetaData).
 */
class D1DatabaseMetaDataCatalogTest {

    private static D1JdbcUrl url() throws SQLException {
        return D1JdbcUrl.parse("jdbc:cloudflare-d1:proxy://host/base?token=t", null);
    }

    private static D1QueryResult result(List<String> columns, List<List<Object>> rows) {
        return new D1QueryResult(columns, rows, D1Meta.EMPTY, null);
    }

    /** Canned {@code SELECT name, type FROM sqlite_master ... ORDER BY type, name}. */
    private static D1QueryResult masterNameType(Object[]... rows) {
        List<List<Object>> data = new ArrayList<>();
        for (Object[] row : rows) {
            data.add(Arrays.asList(row));
        }
        return result(List.of("name", "type"), data);
    }

    /** Canned {@code SELECT name FROM sqlite_master ... ORDER BY name}. */
    private static D1QueryResult masterNames(String... names) {
        List<List<Object>> data = new ArrayList<>();
        for (String name : names) {
            data.add(Arrays.asList((Object) name));
        }
        return result(List.of("name"), data);
    }

    /** Canned {@code PRAGMA table_info} rows: cid, name, type, notnull, dflt_value, pk. */
    private static D1QueryResult tableInfo(Object[]... rows) {
        List<List<Object>> data = new ArrayList<>();
        for (Object[] row : rows) {
            data.add(Arrays.asList(row));
        }
        return result(List.of("cid", "name", "type", "notnull", "dflt_value", "pk"), data);
    }

    private static void assertColumns(ResultSet rs, String... expected) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        assertEquals(expected.length, md.getColumnCount(), "column count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], md.getColumnName(i + 1), "column " + (i + 1));
        }
    }

    private static final String[] GET_TABLES_COLUMNS = {
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
            "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME",
            "REF_GENERATION"};

    private static final String[] GET_COLUMNS_COLUMNS = {
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE",
            "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS",
            "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE",
            "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE",
            "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE",
            "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN"};

    private static final String[] GET_PRIMARY_KEYS_COLUMNS = {
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"};

    // -------------------------------------------------------------- getTables

    @Test
    void getTablesHasSpecColumnsHidesInternalsAndMapsTypes() throws SQLException {
        MockTransport t = new MockTransport();
        t.enqueue(masterNameType(
                new Object[] {"_cf_KV", "table"},
                new Object[] {"orders", "table"},
                new Object[] {"sqlite_sequence", "table"},
                new Object[] {"users", "table"},
                new Object[] {"v_users", "view"}));
        try (D1Connection c = new D1Connection(url(), t)) {
            ResultSet rs = c.getMetaData().getTables(null, null, null, null);
            assertColumns(rs, GET_TABLES_COLUMNS);

            assertTrue(rs.next());
            assertEquals("main", rs.getString("TABLE_CAT"), "single catalog is 'main'");
            assertNull(rs.getString("TABLE_SCHEM"), "D1 has no schemas");
            assertEquals("orders", rs.getString("TABLE_NAME"));
            assertEquals("TABLE", rs.getString("TABLE_TYPE"));

            assertTrue(rs.next());
            assertEquals("users", rs.getString("TABLE_NAME"),
                    "sqlite_% internals are excluded");

            assertTrue(rs.next());
            assertEquals("v_users", rs.getString("TABLE_NAME"));
            assertEquals("VIEW", rs.getString("TABLE_TYPE"));

            assertFalse(rs.next(), "_cf_KV and sqlite_sequence are hidden");
            assertNull(rs.getStatement(), "metadata result sets have no parent statement");
        }
    }

    @Test
    void getTablesAppliesTypesFilter() throws SQLException {
        MockTransport t = new MockTransport();
        t.setDefaultResult(masterNameType(
                new Object[] {"users", "table"},
                new Object[] {"v_users", "view"}));
        try (D1Connection c = new D1Connection(url(), t)) {
            DatabaseMetaData md = c.getMetaData();

            ResultSet views = md.getTables(null, null, null, new String[] {"VIEW"});
            assertTrue(views.next());
            assertEquals("v_users", views.getString("TABLE_NAME"));
            assertFalse(views.next());

            ResultSet tables = md.getTables(null, null, null, new String[] {"TABLE"});
            assertTrue(tables.next());
            assertEquals("users", tables.getString("TABLE_NAME"));
            assertFalse(tables.next());

            ResultSet none = md.getTables(null, null, null, new String[0]);
            assertFalse(none.next(), "empty types array matches nothing");
        }
    }

    @Test
    void getTablesAppliesLikePatternSemantics() throws SQLException {
        MockTransport t = new MockTransport();
        t.setDefaultResult(masterNameType(
                new Object[] {"order_items", "table"},
                new Object[] {"orders", "table"},
                new Object[] {"users", "table"}));
        try (D1Connection c = new D1Connection(url(), t)) {
            DatabaseMetaData md = c.getMetaData();

            ResultSet pct = md.getTables(null, null, "order%", null);
            assertTrue(pct.next());
            assertEquals("order_items", pct.getString("TABLE_NAME"));
            assertTrue(pct.next());
            assertEquals("orders", pct.getString("TABLE_NAME"));
            assertFalse(pct.next(), "% matches any suffix");

            ResultSet underscore = md.getTables(null, null, "user_", null);
            assertTrue(underscore.next());
            assertEquals("users", underscore.getString("TABLE_NAME"));
            assertFalse(underscore.next(), "_ matches exactly one character");

            ResultSet exact = md.getTables(null, null, "orders", null);
            assertTrue(exact.next());
            assertEquals("orders", exact.getString("TABLE_NAME"));
            assertFalse(exact.next(), "a pattern without wildcards is an exact match");

            ResultSet escaped = md.getTables(null, null, "order\\_items", null);
            assertTrue(escaped.next());
            assertEquals("order_items", escaped.getString("TABLE_NAME"));
            assertFalse(escaped.next(), "\\_ escapes the wildcard (getSearchStringEscape)");

            assertFalse(md.getTables(null, null, "nope%", null).next(),
                    "non-matching pattern yields no rows");
        }
    }

    @Test
    void getTablesHonorsCatalogArgument() throws SQLException {
        MockTransport t = new MockTransport();
        t.setDefaultResult(masterNameType(new Object[] {"users", "table"}));
        try (D1Connection c = new D1Connection(url(), t)) {
            DatabaseMetaData md = c.getMetaData();
            assertTrue(md.getTables("main", null, null, null).next(),
                    "'main' names our single catalog");
            assertTrue(md.getTables("", null, null, null).next(),
                    "empty catalog is treated leniently as no-filter");
            assertFalse(md.getTables("other", null, null, null).next(),
                    "a foreign catalog name yields no rows");
        }
    }

    // ------------------------------------------------------------- getColumns

    @Test
    void getColumnsHasSpecColumnsAndMapsPragmaFields() throws SQLException {
        MockTransport t = new MockTransport();
        t.enqueue(masterNames("users"));
        t.enqueue(tableInfo(
                new Object[] {0L, "id", "INTEGER", 0L, null, 1L},
                new Object[] {1L, "name", "TEXT", 1L, "'anon'", 0L},
                new Object[] {2L, "score", "REAL", 0L, null, 0L},
                new Object[] {3L, "payload", "BLOB", 0L, null, 0L}));
        try (D1Connection c = new D1Connection(url(), t)) {
            ResultSet rs = c.getMetaData().getColumns(null, null, "users", null);
            assertColumns(rs, GET_COLUMNS_COLUMNS);

            assertTrue(rs.next());
            assertEquals("main", rs.getString("TABLE_CAT"));
            assertNull(rs.getString("TABLE_SCHEM"));
            assertEquals("users", rs.getString("TABLE_NAME"));
            assertEquals("id", rs.getString("COLUMN_NAME"));
            assertEquals(Types.INTEGER, rs.getInt("DATA_TYPE"));
            assertEquals("INTEGER", rs.getString("TYPE_NAME"), "declared type text");
            assertEquals(DatabaseMetaData.columnNullable, rs.getInt("NULLABLE"));
            assertNull(rs.getString("COLUMN_DEF"));
            assertEquals(1, rs.getInt("ORDINAL_POSITION"), "1-based ordinal");
            assertEquals("YES", rs.getString("IS_NULLABLE"));
            assertEquals("YES", rs.getString("IS_AUTOINCREMENT"),
                    "single INTEGER PRIMARY KEY is a rowid alias");
            assertEquals("NO", rs.getString("IS_GENERATEDCOLUMN"));

            assertTrue(rs.next());
            assertEquals("name", rs.getString("COLUMN_NAME"));
            assertEquals(Types.VARCHAR, rs.getInt("DATA_TYPE"));
            assertEquals("TEXT", rs.getString("TYPE_NAME"));
            assertEquals(DatabaseMetaData.columnNoNulls, rs.getInt("NULLABLE"));
            assertEquals("'anon'", rs.getString("COLUMN_DEF"),
                    "dflt_value text is exposed as COLUMN_DEF");
            assertEquals(2, rs.getInt("ORDINAL_POSITION"));
            assertEquals("NO", rs.getString("IS_NULLABLE"));
            assertEquals("NO", rs.getString("IS_AUTOINCREMENT"));

            assertTrue(rs.next());
            assertEquals("score", rs.getString("COLUMN_NAME"));
            assertEquals(Types.DOUBLE, rs.getInt("DATA_TYPE"));
            assertEquals(3, rs.getInt("ORDINAL_POSITION"));

            assertTrue(rs.next());
            assertEquals("payload", rs.getString("COLUMN_NAME"));
            assertEquals(Types.BLOB, rs.getInt("DATA_TYPE"));
            assertEquals(4, rs.getInt("ORDINAL_POSITION"));

            assertFalse(rs.next());
            assertNull(rs.getStatement());
        }
    }

    @Test
    void getColumnsMapsDeclaredTypesBySqliteAffinity() throws SQLException {
        // declared type -> expected java.sql.Types value (affinity rules).
        Object[][] cases = {
                {"INT", Types.INTEGER},
                {"INTEGER", Types.INTEGER},
                {"TINYINT", Types.INTEGER},
                {"BIGINT", Types.BIGINT},
                {"UNSIGNED BIG INT", Types.BIGINT},
                {"CHARACTER(20)", Types.VARCHAR},
                {"VARCHAR(255)", Types.VARCHAR},
                {"CLOB", Types.VARCHAR},
                {"TEXT", Types.VARCHAR},
                {"BLOB", Types.BLOB},
                {"", Types.BLOB},          // no declared type -> BLOB affinity
                {"REAL", Types.DOUBLE},
                {"FLOAT", Types.DOUBLE},
                {"DOUBLE PRECISION", Types.DOUBLE},
                {"NUMERIC", Types.NUMERIC},
                {"DECIMAL(10,5)", Types.NUMERIC},
                {"DATE", Types.NUMERIC},
                {"BOOLEAN", Types.NUMERIC},
        };
        Object[][] pragmaRows = new Object[cases.length][];
        for (int i = 0; i < cases.length; i++) {
            pragmaRows[i] = new Object[] {(long) i, "c" + i, cases[i][0], 0L, null, 0L};
        }
        MockTransport t = new MockTransport();
        t.enqueue(masterNames("t"));
        t.enqueue(tableInfo(pragmaRows));
        try (D1Connection c = new D1Connection(url(), t)) {
            ResultSet rs = c.getMetaData().getColumns(null, null, "t", null);
            for (Object[] cs : cases) {
                assertTrue(rs.next());
                assertEquals(cs[1], rs.getInt("DATA_TYPE"),
                        "affinity mapping for declared type '" + cs[0] + "'");
                assertEquals(cs[0], rs.getString("TYPE_NAME"));
            }
            assertFalse(rs.next());
        }
    }

    @Test
    void getColumnsAppliesColumnNamePatternAndQuotesTableNames() throws SQLException {
        MockTransport t = new MockTransport();
        t.enqueue(masterNames("we\"ird"));
        t.enqueue(tableInfo(
                new Object[] {0L, "id", "INTEGER", 0L, null, 1L},
                new Object[] {1L, "id_copy", "INTEGER", 0L, null, 0L},
                new Object[] {2L, "name", "TEXT", 0L, null, 0L}));
        try (D1Connection c = new D1Connection(url(), t)) {
            ResultSet rs = c.getMetaData().getColumns(null, null, null, "id%");
            assertTrue(rs.next());
            assertEquals("id", rs.getString("COLUMN_NAME"));
            assertTrue(rs.next());
            assertEquals("id_copy", rs.getString("COLUMN_NAME"));
            assertFalse(rs.next(), "'name' does not match 'id%'");

            // The pragma call must double-quote and escape the identifier.
            assertEquals("PRAGMA table_info(\"we\"\"ird\")", t.queries.get(1).sql());
        }
    }

    @Test
    void getColumnsCompositePkIsNotAutoincrement() throws SQLException {
        MockTransport t = new MockTransport();
        t.enqueue(masterNames("pair"));
        t.enqueue(tableInfo(
                new Object[] {0L, "a", "INTEGER", 1L, null, 1L},
                new Object[] {1L, "b", "INTEGER", 1L, null, 2L}));
        try (D1Connection c = new D1Connection(url(), t)) {
            ResultSet rs = c.getMetaData().getColumns(null, null, "pair", null);
            assertTrue(rs.next());
            assertEquals("NO", rs.getString("IS_AUTOINCREMENT"),
                    "composite pk column is not a rowid alias");
            assertTrue(rs.next());
            assertEquals("NO", rs.getString("IS_AUTOINCREMENT"));
        }
    }

    // --------------------------------------------------------- getPrimaryKeys

    @Test
    void getPrimaryKeysSortsByPkOrdinalWithOneBasedKeySeq() throws SQLException {
        MockTransport t = new MockTransport();
        // pragma order deliberately differs from pk order.
        t.enqueue(tableInfo(
                new Object[] {0L, "b", "TEXT", 1L, null, 2L},
                new Object[] {1L, "note", "TEXT", 0L, null, 0L},
                new Object[] {2L, "a", "INTEGER", 1L, null, 1L}));
        try (D1Connection c = new D1Connection(url(), t)) {
            ResultSet rs = c.getMetaData().getPrimaryKeys(null, null, "pair");
            assertColumns(rs, GET_PRIMARY_KEYS_COLUMNS);

            assertTrue(rs.next());
            assertEquals("main", rs.getString("TABLE_CAT"));
            assertEquals("pair", rs.getString("TABLE_NAME"));
            assertEquals("a", rs.getString("COLUMN_NAME"));
            assertEquals(1, rs.getShort("KEY_SEQ"));
            assertNull(rs.getString("PK_NAME"));

            assertTrue(rs.next());
            assertEquals("b", rs.getString("COLUMN_NAME"));
            assertEquals(2, rs.getShort("KEY_SEQ"));

            assertFalse(rs.next());
            assertEquals("PRAGMA table_info(\"pair\")", t.queries.get(0).sql());
        }
    }

    @Test
    void getPrimaryKeysWithNullTableReturnsEmptyWithoutQuerying() throws SQLException {
        MockTransport t = new MockTransport();
        try (D1Connection c = new D1Connection(url(), t)) {
            ResultSet rs = c.getMetaData().getPrimaryKeys(null, null, null);
            assertColumns(rs, GET_PRIMARY_KEYS_COLUMNS);
            assertFalse(rs.next());
            assertTrue(t.queries.isEmpty(), "no transport round-trip for a null table");
        }
    }

    // -------------------------------------------- static lists and type info

    @Test
    void getTableTypesListsTableAndView() throws SQLException {
        try (D1Connection c = new D1Connection(url(), new MockTransport())) {
            ResultSet rs = c.getMetaData().getTableTypes();
            assertColumns(rs, "TABLE_TYPE");
            assertTrue(rs.next());
            assertEquals("TABLE", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("VIEW", rs.getString(1));
            assertFalse(rs.next());
        }
    }

    @Test
    void getCatalogsReturnsSingleMainRow() throws SQLException {
        try (D1Connection c = new D1Connection(url(), new MockTransport())) {
            ResultSet rs = c.getMetaData().getCatalogs();
            assertColumns(rs, "TABLE_CAT");
            assertTrue(rs.next());
            assertEquals("main", rs.getString("TABLE_CAT"));
            assertFalse(rs.next());
        }
    }

    @Test
    void getSchemasIsEmptyWithSpecColumns() throws SQLException {
        try (D1Connection c = new D1Connection(url(), new MockTransport())) {
            ResultSet rs = c.getMetaData().getSchemas();
            assertColumns(rs, "TABLE_SCHEM", "TABLE_CATALOG");
            assertFalse(rs.next());

            ResultSet rs2 = c.getMetaData().getSchemas("main", "%");
            assertColumns(rs2, "TABLE_SCHEM", "TABLE_CATALOG");
            assertFalse(rs2.next());
        }
    }

    @Test
    void getTypeInfoListsSqliteStorageClassesOrderedByDataType() throws SQLException {
        try (D1Connection c = new D1Connection(url(), new MockTransport())) {
            ResultSet rs = c.getMetaData().getTypeInfo();
            ResultSetMetaData md = rs.getMetaData();
            assertEquals(18, md.getColumnCount(), "getTypeInfo has 18 spec columns");
            assertEquals("TYPE_NAME", md.getColumnName(1));
            assertEquals("DATA_TYPE", md.getColumnName(2));
            assertEquals("NUM_PREC_RADIX", md.getColumnName(18));

            String[] expectedNames = {"NUMERIC", "INTEGER", "REAL", "TEXT", "BLOB"};
            int[] expectedTypes = {Types.NUMERIC, Types.INTEGER, Types.DOUBLE,
                    Types.VARCHAR, Types.BLOB};
            int previous = Integer.MIN_VALUE;
            for (int i = 0; i < expectedNames.length; i++) {
                assertTrue(rs.next());
                assertEquals(expectedNames[i], rs.getString("TYPE_NAME"));
                assertEquals(expectedTypes[i], rs.getInt("DATA_TYPE"));
                assertTrue(rs.getInt("DATA_TYPE") >= previous, "ordered by DATA_TYPE");
                previous = rs.getInt("DATA_TYPE");
                assertEquals(DatabaseMetaData.typeNullable, rs.getInt("NULLABLE"));
                assertEquals(DatabaseMetaData.typeSearchable, rs.getInt("SEARCHABLE"));
            }
            assertFalse(rs.next());
        }
    }

    // -------------------------------------------------------- imported keys

    @Test
    void getImportedKeysMapsForeignKeyListPragma() throws SQLException {
        MockTransport t = new MockTransport();
        // foreign_key_list shape: id, seq, table, from, to, on_update, on_delete, match
        t.enqueue(result(
                List.of("id", "seq", "table", "from", "to", "on_update", "on_delete", "match"),
                List.of(
                        Arrays.asList(0L, 0L, "users", "user_id", "id",
                                "NO ACTION", "CASCADE", "NONE"),
                        Arrays.asList(0L, 1L, "users", "user_ref", "ref",
                                "SET NULL", "RESTRICT", "NONE"))));
        try (D1Connection c = new D1Connection(url(), t)) {
            ResultSet rs = c.getMetaData().getImportedKeys(null, null, "orders");
            assertColumns(rs, "PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME",
                    "PKCOLUMN_NAME", "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME",
                    "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE",
                    "FK_NAME", "PK_NAME", "DEFERRABILITY");

            assertTrue(rs.next());
            assertEquals("users", rs.getString("PKTABLE_NAME"));
            assertEquals("id", rs.getString("PKCOLUMN_NAME"));
            assertEquals("orders", rs.getString("FKTABLE_NAME"));
            assertEquals("user_id", rs.getString("FKCOLUMN_NAME"));
            assertEquals(1, rs.getShort("KEY_SEQ"), "pragma seq 0 -> KEY_SEQ 1");
            assertEquals(DatabaseMetaData.importedKeyNoAction, rs.getInt("UPDATE_RULE"));
            assertEquals(DatabaseMetaData.importedKeyCascade, rs.getInt("DELETE_RULE"));
            assertNull(rs.getString("FK_NAME"));
            assertEquals(DatabaseMetaData.importedKeyNotDeferrable,
                    rs.getInt("DEFERRABILITY"));

            assertTrue(rs.next());
            assertEquals(2, rs.getShort("KEY_SEQ"));
            assertEquals(DatabaseMetaData.importedKeySetNull, rs.getInt("UPDATE_RULE"));
            assertEquals(DatabaseMetaData.importedKeyRestrict, rs.getInt("DELETE_RULE"));

            assertFalse(rs.next());
            assertEquals("PRAGMA foreign_key_list(\"orders\")", t.queries.get(0).sql());
        }
    }

    // ------------------------------------------------------------ index info

    @Test
    void getIndexInfoMapsIndexListAndIndexInfoPragmas() throws SQLException {
        MockTransport t = new MockTransport();
        // index_list shape: seq, name, unique, origin, partial
        t.enqueue(result(List.of("seq", "name", "unique", "origin", "partial"),
                List.of(
                        Arrays.asList(0L, "idx_plain", 0L, "c", 0L),
                        Arrays.asList(1L, "idx_unique", 1L, "u", 0L))));
        // index_info shape: seqno, cid, name — unique index served first (sorted).
        t.enqueue(result(List.of("seqno", "cid", "name"),
                List.of(Arrays.asList(0L, 1L, "name"))));
        t.enqueue(result(List.of("seqno", "cid", "name"),
                List.of(Arrays.asList(0L, 2L, "score"))));
        try (D1Connection c = new D1Connection(url(), t)) {
            ResultSet rs = c.getMetaData().getIndexInfo(null, null, "users", false, false);
            assertColumns(rs, "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE",
                    "INDEX_QUALIFIER", "INDEX_NAME", "TYPE", "ORDINAL_POSITION",
                    "COLUMN_NAME", "ASC_OR_DESC", "CARDINALITY", "PAGES",
                    "FILTER_CONDITION");

            assertTrue(rs.next());
            assertEquals("idx_unique", rs.getString("INDEX_NAME"),
                    "unique indexes sort first (NON_UNIQUE ascending)");
            assertFalse(rs.getBoolean("NON_UNIQUE"));
            assertEquals("name", rs.getString("COLUMN_NAME"));
            assertEquals(1, rs.getInt("ORDINAL_POSITION"));
            assertEquals(DatabaseMetaData.tableIndexOther, rs.getInt("TYPE"));

            assertTrue(rs.next());
            assertEquals("idx_plain", rs.getString("INDEX_NAME"));
            assertTrue(rs.getBoolean("NON_UNIQUE"));
            assertEquals("score", rs.getString("COLUMN_NAME"));

            assertFalse(rs.next());
        }
    }

    // ------------------------------- absent object kinds -> empty result sets

    /** One DatabaseMetaData catalog call, for table-driven empty-shape checks. */
    @FunctionalInterface
    private interface MetadataCall {
        ResultSet call(DatabaseMetaData md) throws SQLException;
    }

    private static void assertEmptyWithColumns(DatabaseMetaData md, MetadataCall call,
            String... expected) throws SQLException {
        ResultSet rs = call.call(md);
        assertColumns(rs, expected);
        assertFalse(rs.next(), "object kind does not exist in D1 -> zero rows");
    }

    @Test
    void absentObjectKindsReturnEmptySpecShapedResultSetsWithoutQuerying()
            throws SQLException {
        MockTransport t = new MockTransport();
        try (D1Connection c = new D1Connection(url(), t)) {
            DatabaseMetaData md = c.getMetaData();

            assertEmptyWithColumns(md, m -> m.getProcedures(null, null, null),
                    "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME",
                    "UNDEF1", "UNDEF2", "UNDEF3", "REMARKS", "PROCEDURE_TYPE",
                    "SPECIFIC_NAME");
            assertEmptyWithColumns(md, m -> m.getProcedureColumns(null, null, null, null),
                    "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME",
                    "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH",
                    "SCALE", "RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF",
                    "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
                    "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME");
            assertEmptyWithColumns(md, m -> m.getFunctions(null, null, null),
                    "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS",
                    "FUNCTION_TYPE", "SPECIFIC_NAME");
            assertEmptyWithColumns(md, m -> m.getFunctionColumns(null, null, null, null),
                    "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME",
                    "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH",
                    "SCALE", "RADIX", "NULLABLE", "REMARKS", "CHAR_OCTET_LENGTH",
                    "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME");
            assertEmptyWithColumns(md, m -> m.getUDTs(null, null, null, null),
                    "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE",
                    "REMARKS", "BASE_TYPE");
            assertEmptyWithColumns(md, m -> m.getAttributes(null, null, null, null),
                    "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME", "DATA_TYPE",
                    "ATTR_TYPE_NAME", "ATTR_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX",
                    "NULLABLE", "REMARKS", "ATTR_DEF", "SQL_DATA_TYPE",
                    "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION",
                    "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE",
                    "SOURCE_DATA_TYPE");
            assertEmptyWithColumns(md, m -> m.getSuperTypes(null, null, null),
                    "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME",
                    "SUPERTYPE_CAT", "SUPERTYPE_SCHEM", "SUPERTYPE_NAME");
            assertEmptyWithColumns(md, m -> m.getSuperTables(null, null, null),
                    "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME");
            assertEmptyWithColumns(md, m -> m.getTablePrivileges(null, null, null),
                    "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME",
                    "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE");
            assertEmptyWithColumns(md, m -> m.getColumnPrivileges(null, null, "t", null),
                    "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
                    "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE");
            assertEmptyWithColumns(md, m -> m.getVersionColumns(null, null, "t"),
                    "SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE",
                    "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN");
            assertEmptyWithColumns(md,
                    m -> m.getBestRowIdentifier(null, null, "t",
                            DatabaseMetaData.bestRowSession, true),
                    "SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE",
                    "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN");
            assertEmptyWithColumns(md, m -> m.getPseudoColumns(null, null, null, null),
                    "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
                    "DATA_TYPE", "COLUMN_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX",
                    "COLUMN_USAGE", "REMARKS", "CHAR_OCTET_LENGTH", "IS_NULLABLE");
            assertEmptyWithColumns(md, m -> m.getClientInfoProperties(),
                    "NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION");

            assertTrue(t.queries.isEmpty(),
                    "absent object kinds need no transport round-trip");
        }
    }

    // -------------------------------------------------------- exported keys

    private static final String[] FK_COLUMNS = {
            "PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME",
            "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME",
            "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME",
            "DEFERRABILITY"};

    /** Canned {@code PRAGMA foreign_key_list} rows: id, seq, table, from, to, on_update, on_delete, match. */
    private static D1QueryResult foreignKeyList(Object[]... rows) {
        List<List<Object>> data = new ArrayList<>();
        for (Object[] row : rows) {
            data.add(Arrays.asList(row));
        }
        return result(
                List.of("id", "seq", "table", "from", "to", "on_update", "on_delete", "match"),
                data);
    }

    @Test
    void getExportedKeysScansUserTablesAndReversesForeignKeys() throws SQLException {
        MockTransport t = new MockTransport();
        // userTableNames(null): sqlite_master name list (internals excluded).
        t.enqueue(masterNames("_cf_KV", "orders", "payments", "users"));
        // foreign_key_list("orders"): one FK to users, one to an unrelated table.
        t.enqueue(foreignKeyList(
                new Object[] {0L, 0L, "users", "user_id", "id",
                        "NO ACTION", "CASCADE", "NONE"},
                new Object[] {1L, 0L, "products", "product_id", "id",
                        "NO ACTION", "NO ACTION", "NONE"}));
        // foreign_key_list("payments"): composite FK to users.
        t.enqueue(foreignKeyList(
                new Object[] {0L, 0L, "users", "user_a", "a",
                        "SET NULL", "RESTRICT", "NONE"},
                new Object[] {0L, 1L, "users", "user_b", "b",
                        "SET NULL", "RESTRICT", "NONE"}));
        // foreign_key_list("users"): no FKs.
        t.enqueue(foreignKeyList());
        try (D1Connection c = new D1Connection(url(), t)) {
            ResultSet rs = c.getMetaData().getExportedKeys(null, null, "users");
            assertColumns(rs, FK_COLUMNS);

            assertTrue(rs.next());
            assertEquals("users", rs.getString("PKTABLE_NAME"));
            assertEquals("id", rs.getString("PKCOLUMN_NAME"));
            assertEquals("orders", rs.getString("FKTABLE_NAME"),
                    "ordered by FKTABLE_NAME, then KEY_SEQ");
            assertEquals("user_id", rs.getString("FKCOLUMN_NAME"));
            assertEquals(1, rs.getShort("KEY_SEQ"));
            assertEquals(DatabaseMetaData.importedKeyNoAction, rs.getInt("UPDATE_RULE"));
            assertEquals(DatabaseMetaData.importedKeyCascade, rs.getInt("DELETE_RULE"));
            assertEquals(DatabaseMetaData.importedKeyNotDeferrable,
                    rs.getInt("DEFERRABILITY"));

            assertTrue(rs.next());
            assertEquals("payments", rs.getString("FKTABLE_NAME"));
            assertEquals("user_a", rs.getString("FKCOLUMN_NAME"));
            assertEquals("a", rs.getString("PKCOLUMN_NAME"));
            assertEquals(1, rs.getShort("KEY_SEQ"));

            assertTrue(rs.next());
            assertEquals("payments", rs.getString("FKTABLE_NAME"));
            assertEquals("user_b", rs.getString("FKCOLUMN_NAME"));
            assertEquals(2, rs.getShort("KEY_SEQ"), "pragma seq 1 -> KEY_SEQ 2");

            assertFalse(rs.next(), "the FK to 'products' is not exported by 'users'");

            assertEquals(4, t.queries.size(),
                    "one sqlite_master scan plus one pragma per user table");
            assertEquals("PRAGMA foreign_key_list(\"orders\")", t.queries.get(1).sql());
            assertEquals("PRAGMA foreign_key_list(\"payments\")", t.queries.get(2).sql());
            assertEquals("PRAGMA foreign_key_list(\"users\")", t.queries.get(3).sql());
        }
    }

    @Test
    void getExportedKeysWithNullTableReturnsEmptyWithoutQuerying() throws SQLException {
        MockTransport t = new MockTransport();
        try (D1Connection c = new D1Connection(url(), t)) {
            ResultSet rs = c.getMetaData().getExportedKeys(null, null, null);
            assertColumns(rs, FK_COLUMNS);
            assertFalse(rs.next());
            assertTrue(t.queries.isEmpty(), "no transport round-trip for a null table");
        }
    }

    @Test
    void getCrossReferenceFiltersOneParentForeignPair() throws SQLException {
        MockTransport t = new MockTransport();
        t.enqueue(foreignKeyList(
                new Object[] {0L, 0L, "users", "user_id", "id",
                        "NO ACTION", "CASCADE", "NONE"},
                new Object[] {1L, 0L, "products", "product_id", "id",
                        "NO ACTION", "NO ACTION", "NONE"}));
        try (D1Connection c = new D1Connection(url(), t)) {
            ResultSet rs = c.getMetaData()
                    .getCrossReference(null, null, "users", null, null, "orders");
            assertColumns(rs, FK_COLUMNS);

            assertTrue(rs.next());
            assertEquals("users", rs.getString("PKTABLE_NAME"));
            assertEquals("id", rs.getString("PKCOLUMN_NAME"));
            assertEquals("orders", rs.getString("FKTABLE_NAME"));
            assertEquals("user_id", rs.getString("FKCOLUMN_NAME"));
            assertEquals(1, rs.getShort("KEY_SEQ"));

            assertFalse(rs.next(), "the FK to 'products' does not match parent 'users'");
            assertEquals(1, t.queries.size(),
                    "cross-reference needs only the foreign table's pragma");
            assertEquals("PRAGMA foreign_key_list(\"orders\")", t.queries.get(0).sql());
        }
    }

    @Test
    void getIndexInfoUniqueFlagFiltersNonUniqueIndexes() throws SQLException {
        MockTransport t = new MockTransport();
        t.enqueue(result(List.of("seq", "name", "unique", "origin", "partial"),
                List.of(
                        Arrays.asList(0L, "idx_plain", 0L, "c", 0L),
                        Arrays.asList(1L, "idx_unique", 1L, "u", 0L))));
        t.enqueue(result(List.of("seqno", "cid", "name"),
                List.of(Arrays.asList(0L, 1L, "name"))));
        try (D1Connection c = new D1Connection(url(), t)) {
            ResultSet rs = c.getMetaData().getIndexInfo(null, null, "users", true, false);
            assertTrue(rs.next());
            assertEquals("idx_unique", rs.getString("INDEX_NAME"));
            assertFalse(rs.next(), "unique=true hides non-unique indexes");
            assertEquals(2, t.queries.size(),
                    "index_info is not fetched for filtered-out indexes");
        }
    }
}
