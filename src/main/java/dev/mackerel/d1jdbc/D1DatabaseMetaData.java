package dev.mackerel.d1jdbc;

import dev.mackerel.d1jdbc.internal.D1Limits;
import dev.mackerel.d1jdbc.transport.Capabilities;
import dev.mackerel.d1jdbc.transport.D1Meta;
import dev.mackerel.d1jdbc.transport.D1QueryResult;
import dev.mackerel.d1jdbc.transport.TransportException;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@link DatabaseMetaData} for Cloudflare D1. Feature queries return
 * sensible booleans and transport-specific capabilities
 * ({@link Capabilities#supportsAtomicBatch()} drives
 * {@link #supportsTransactions()}); size limits reflect the confirmed D1
 * platform limits in {@link D1Limits} (DESIGN 9-1), with {@code 0} meaning
 * "unknown / no documented limit" per JDBC convention.
 *
 * <p>Catalog introspection ({@link #getTables getTables} /
 * {@link #getColumns getColumns} / {@link #getPrimaryKeys getPrimaryKeys} /
 * {@link #getImportedKeys getImportedKeys} / {@link #getIndexInfo getIndexInfo}
 * and friends) is implemented on top of {@code sqlite_master} and the SQLite
 * pragmas, executed through the connection's transport exactly like regular
 * statements. Catalog methods for object kinds that do not exist in D1
 * (procedures, UDTs, privileges, ...) return an <em>empty</em> result set with
 * the spec column layout — per JDBC convention "there are none" is an empty
 * list, not an error, and GUI tools (DBeaver, DataGrip) rely on that.
 */
public final class D1DatabaseMetaData implements DatabaseMetaData {

    /** Confirmed D1 column-count limit: 100 columns per table (DESIGN 9-1). */
    private static final int MAX_COLUMNS = 100;

    private final D1Connection connection;
    private final Capabilities capabilities;

    D1DatabaseMetaData(D1Connection connection) {
        this.connection = connection;
        this.capabilities = connection.capabilities();
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    // ------------------------------------------------------------- identity

    @Override
    public String getDriverName() {
        return D1Driver.DRIVER_NAME;
    }

    @Override
    public String getDriverVersion() {
        return D1Driver.MAJOR_VERSION + "." + D1Driver.MINOR_VERSION;
    }

    @Override
    public int getDriverMajorVersion() {
        return D1Driver.MAJOR_VERSION;
    }

    @Override
    public int getDriverMinorVersion() {
        return D1Driver.MINOR_VERSION;
    }

    @Override
    public String getDatabaseProductName() {
        return "Cloudflare D1";
    }

    @Override
    public String getDatabaseProductVersion() {
        return "SQLite (D1, transport=" + capabilities.transportName() + ")";
    }

    @Override
    public int getDatabaseMajorVersion() {
        return 3; // SQLite dialect major
    }

    @Override
    public int getDatabaseMinorVersion() {
        return 0;
    }

    @Override
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 2;
    }

    @Override
    public String getURL() {
        return D1JdbcUrl.PREFIX + capabilities.transportName() + "://"
                + connection.url().authority();
    }

    @Override
    public String getUserName() {
        return null;
    }

    @Override
    public String getSQLKeywords() {
        return "";
    }

    @Override
    public String getIdentifierQuoteString() {
        return "\"";
    }

    @Override
    public int getSQLStateType() {
        return sqlStateSQL;
    }

    // ------------------------------------------------------------- features

    @Override
    public boolean supportsBatchUpdates() {
        // executeBatch works on every transport (chunked; per-chunk atomicity
        // still depends on Capabilities.supportsAtomicBatch()).
        return true;
    }

    @Override
    public boolean supportsTransactions() {
        // Only atomic single-request batches; no interactive transactions.
        return capabilities.supportsAtomicBatch();
    }

    @Override
    public int getDefaultTransactionIsolation() {
        return Connection.TRANSACTION_NONE;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return level == Connection.TRANSACTION_NONE;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean supportsResultSetType(int type) {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY
                && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) {
        return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public boolean supportsSavepoints() {
        return false;
    }

    @Override
    public boolean supportsNamedParameters() {
        return false;
    }

    @Override
    public boolean supportsMultipleResultSets() {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        return true;
    }

    @Override
    public boolean supportsStoredProcedures() {
        return false;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() {
        return false;
    }

    @Override
    public boolean supportsUnion() {
        return true;
    }

    @Override
    public boolean supportsUnionAll() {
        return true;
    }

    @Override
    public boolean supportsColumnAliasing() {
        return true;
    }

    @Override
    public boolean supportsOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsFullOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsLimitedOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsGroupBy() {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() {
        return true;
    }

    @Override
    public boolean supportsOrderByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInExists() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInIns() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() {
        return true;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() {
        return true;
    }

    @Override
    public boolean supportsSelectForUpdate() {
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() {
        return true;
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() {
        return true;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() {
        return true;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() {
        return true;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() {
        return false;
    }

    @Override
    public boolean supportsCoreSQLGrammar() {
        return true;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() {
        return true;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() {
        return false;
    }

    @Override
    public boolean supportsSchemasInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean isCatalogAtStart() {
        return false;
    }

    @Override
    public String getCatalogSeparator() {
        return "";
    }

    @Override
    public String getCatalogTerm() {
        return "catalog";
    }

    @Override
    public String getSchemaTerm() {
        return "schema";
    }

    @Override
    public String getProcedureTerm() {
        return "procedure";
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public boolean supportsConvert() {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) {
        return false;
    }

    @Override
    public boolean supportsTableCorrelationNames() {
        return true;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean supportsNonNullableColumns() {
        return true;
    }

    @Override
    public boolean nullsAreSortedHigh() {
        return false;
    }

    @Override
    public boolean nullsAreSortedLow() {
        return true;
    }

    @Override
    public boolean nullsAreSortedAtStart() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() {
        return false;
    }

    @Override
    public boolean nullPlusNonNullIsNull() {
        return true;
    }

    @Override
    public boolean allProceduresAreCallable() {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() {
        return true;
    }

    @Override
    public boolean usesLocalFiles() {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() {
        return false;
    }

    @Override
    public boolean supportsLikeEscapeClause() {
        return true;
    }

    @Override
    public boolean supportsMultipleTransactions() {
        return true;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() {
        return false;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() {
        return capabilities.supportsAtomicBatch();
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() {
        return false;
    }

    @Override
    public String getNumericFunctions() {
        return "abs,max,min,round,random";
    }

    @Override
    public String getStringFunctions() {
        return "length,lower,upper,substr,trim,replace";
    }

    @Override
    public String getSystemFunctions() {
        return "";
    }

    @Override
    public String getTimeDateFunctions() {
        return "date,time,datetime,julianday,strftime";
    }

    @Override
    public String getSearchStringEscape() {
        return "\\";
    }

    @Override
    public String getExtraNameCharacters() {
        return "";
    }

    @Override
    public boolean supportsStatementPooling() {
        return false;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() {
        return false;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() {
        return false;
    }

    @Override
    public boolean locatorsUpdateCopy() {
        return false;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    // ---------------------------------------------------------- row/size limits

    @Override
    public int getMaxConnections() {
        return 0;
    }

    @Override
    public int getMaxStatements() {
        return 0;
    }

    @Override
    public int getMaxStatementLength() {
        return D1Limits.MAX_SQL_BYTES;
    }

    @Override
    public int getMaxColumnsInTable() {
        return MAX_COLUMNS;
    }

    @Override
    public int getMaxColumnsInSelect() {
        return MAX_COLUMNS;
    }

    @Override
    public int getMaxColumnsInGroupBy() {
        return MAX_COLUMNS;
    }

    @Override
    public int getMaxColumnsInOrderBy() {
        return MAX_COLUMNS;
    }

    @Override
    public int getMaxColumnsInIndex() {
        return MAX_COLUMNS;
    }

    @Override
    public int getMaxColumnNameLength() {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() {
        return 0;
    }

    @Override
    public int getMaxTablesInSelect() {
        return 0;
    }

    @Override
    public int getMaxUserNameLength() {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() {
        return 0;
    }

    @Override
    public int getMaxIndexLength() {
        return 0;
    }

    @Override
    public int getMaxRowSize() {
        return D1Limits.MAX_VALUE_BYTES;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() {
        return true;
    }

    @Override
    public int getMaxBinaryLiteralLength() {
        return D1Limits.MAX_VALUE_BYTES;
    }

    @Override
    public int getMaxCharLiteralLength() {
        return D1Limits.MAX_VALUE_BYTES;
    }

    // -------------------------- deletes/inserts/updates visibility

    @Override
    public boolean ownUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) {
        return false;
    }

    // ------------------------------------------------ catalog introspection
    //
    // Live-probe findings against real D1 over REST /raw (2026-07):
    //   - `SELECT name, type, sql FROM sqlite_master` works.
    //   - Both `PRAGMA table_info("t")` and the table-valued
    //     `SELECT * FROM pragma_table_info('t')` work and return identical
    //     column shapes (cid, name, type, notnull, dflt_value, pk). The plain
    //     PRAGMA form is used here because it is the simpler of the two and
    //     needs no extra quoting rules.
    //   - `PRAGMA table_list`, `PRAGMA index_list("t")`, `PRAGMA
    //     index_info("i")` and `PRAGMA foreign_key_list("t")` all work, so
    //     getImportedKeys/getIndexInfo are implemented as well.
    //   - Besides the usual `sqlite_%` internals, D1 injects its own internal
    //     shim table `_cf_KV`; both families are hidden from catalog results.

    /**
     * The single D1 database is exposed as catalog "main" (SQLite's own name
     * for the primary database). getCatalogs() returns exactly this row:
     * DBeaver/DataGrip render one database node and echo the name back into
     * getTables(catalog, ...), which is accepted below.
     */
    private static final String CATALOG_NAME = "main";

    /** Run a metadata query through the transport, like D1Statement.doQuery. */
    private D1QueryResult runMetadataQuery(String sql) throws SQLException {
        connection.checkOpen();
        try {
            D1QueryResult result = connection.transport()
                    .query(sql, List.of(), connection.bookmark());
            connection.updateBookmark(result.bookmark());
            return result;
        } catch (TransportException e) {
            throw D1Codec.toSQLException(e);
        }
    }

    /**
     * Build a metadata {@link ResultSet}. Metadata result sets have no parent
     * statement, so {@link ResultSet#getStatement()} returns {@code null}
     * (explicitly permitted by JDBC for DatabaseMetaData-produced results).
     * Cells must be D1 runtime types (Long / Double / String / byte[] / null).
     */
    private static ResultSet metadataResultSet(List<String> columns, List<List<Object>> rows) {
        return new D1ResultSet(null, new D1QueryResult(columns, rows, D1Meta.EMPTY, null));
    }

    /**
     * Quote an identifier for safe interpolation into a PRAGMA call:
     * double-quote and escape embedded double quotes. Names normally come from
     * sqlite_master, but getPrimaryKeys/getIndexInfo also accept caller input.
     */
    private static String quoteIdentifier(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /**
     * JDBC LIKE-style pattern match for catalog name filters: {@code null}
     * matches everything, {@code %} matches any substring, {@code _} matches
     * one character, and a backslash (this driver's
     * {@link #getSearchStringEscape()}) escapes the next character. Matching is
     * case-insensitive, mirroring SQLite's LIKE and this driver's
     * case-insensitive identifier handling.
     */
    private static boolean matchesLikePattern(String name, String pattern) {
        if (pattern == null) {
            return true;
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            } else if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(),
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(name)
                .matches();
    }

    /**
     * True when a non-null catalog argument names a catalog other than "main".
     * A null or empty catalog is treated as "do not filter" (lenient: some
     * tools pass "" where the spec means "tables without a catalog").
     */
    private static boolean catalogMismatch(String catalog) {
        return catalog != null && !catalog.isEmpty() && !catalog.equalsIgnoreCase(CATALOG_NAME);
    }

    /** Hide SQLite internals and D1's own `_cf_KV` shim (probe finding above). */
    private static boolean isInternalTable(String name) {
        return name.startsWith("sqlite_") || name.startsWith("_cf_");
    }

    /**
     * Map a SQLite declared column type to {@link Types} using SQLite's
     * affinity rules (https://sqlite.org/datatype3.html#determination_of_column_affinity),
     * checked in the same order SQLite applies them:
     * contains "INT" -> INTEGER (BIGINT when declared as such);
     * "CHAR"/"CLOB"/"TEXT" -> VARCHAR; "BLOB" or empty -> BLOB;
     * "REAL"/"FLOA"/"DOUB" -> DOUBLE; anything else -> NUMERIC.
     */
    private static int sqlTypeForDeclaredType(String declaredType) {
        if (declaredType == null || declaredType.isEmpty()) {
            return Types.BLOB;
        }
        String t = declaredType.toUpperCase(Locale.ROOT);
        if (t.contains("INT")) {
            // BIGINT for the 8-byte spellings from sqlite.org/datatype3.html.
            return (t.contains("BIGINT") || t.contains("BIG INT") || t.contains("INT8"))
                    ? Types.BIGINT : Types.INTEGER;
        }
        if (t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT")) {
            return Types.VARCHAR;
        }
        if (t.contains("BLOB")) {
            return Types.BLOB;
        }
        if (t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB")) {
            return Types.DOUBLE;
        }
        return Types.NUMERIC;
    }

    private static long asLong(Object cell) {
        return cell instanceof Number ? ((Number) cell).longValue() : 0L;
    }

    private static String asString(Object cell) {
        return cell == null ? null : String.valueOf(cell);
    }

    /**
     * Names of user tables/views from sqlite_master (internals excluded),
     * filtered by a JDBC LIKE pattern, ordered by name.
     */
    private List<String> userTableNames(String tableNamePattern) throws SQLException {
        D1QueryResult master = runMetadataQuery(
                "SELECT name FROM sqlite_master WHERE type IN ('table','view') ORDER BY name");
        List<String> names = new ArrayList<>();
        for (List<Object> row : master.rows()) {
            String name = asString(row.get(0));
            if (name == null || isInternalTable(name)) {
                continue;
            }
            if (matchesLikePattern(name, tableNamePattern)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * An empty metadata result set with the given spec columns, for object
     * kinds that do not exist in D1/SQLite: per JDBC convention "there are
     * none" is an empty list, not an error.
     */
    private static ResultSet emptyMetadata(String... columns) {
        return metadataResultSet(List.of(columns), new ArrayList<>());
    }

    // D1/SQLite has no stored procedures -> empty is honest. Columns 4-6 are
    // "reserved for future use" in the spec; the UNDEF1..3 names follow the
    // established SQLite (Xerial) driver practice.
    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern,
            String procedureNamePattern) throws SQLException {
        return emptyMetadata("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME",
                "UNDEF1", "UNDEF2", "UNDEF3", "REMARKS", "PROCEDURE_TYPE", "SPECIFIC_NAME");
    }

    // D1/SQLite has no stored procedures -> empty is honest.
    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern,
            String procedureNamePattern, String columnNamePattern) throws SQLException {
        return emptyMetadata("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME",
                "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME", "PRECISION",
                "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF",
                "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
                "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Backed by {@code sqlite_master} ({@code type IN ('table','view')}),
     * with SQLite/D1 internal tables hidden. {@code schemaPattern} is ignored
     * (D1 has no schemas; every row reports TABLE_SCHEM = null).
     */
    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern,
            String[] types) throws SQLException {
        List<String> cols = List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE",
                "REMARKS", "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME",
                "SELF_REFERENCING_COL_NAME", "REF_GENERATION");
        List<List<Object>> rows = new ArrayList<>();
        if (catalogMismatch(catalog)) {
            return metadataResultSet(cols, rows);
        }
        Set<String> wantedTypes = null;
        if (types != null) {
            wantedTypes = new HashSet<>();
            for (String t : types) {
                if (t != null) {
                    wantedTypes.add(t.toUpperCase(Locale.ROOT));
                }
            }
        }
        // 'table' < 'view' lexically, so this yields the spec ordering
        // (TABLE_TYPE, then TABLE_NAME; CAT/SCHEM are constant).
        D1QueryResult master = runMetadataQuery(
                "SELECT name, type FROM sqlite_master WHERE type IN ('table','view')"
                        + " ORDER BY type, name");
        for (List<Object> row : master.rows()) {
            String name = asString(row.get(0));
            if (name == null || isInternalTable(name)
                    || !matchesLikePattern(name, tableNamePattern)) {
                continue;
            }
            String tableType = "view".equalsIgnoreCase(asString(row.get(1))) ? "VIEW" : "TABLE";
            if (wantedTypes != null && !wantedTypes.contains(tableType)) {
                continue;
            }
            rows.add(Arrays.asList(CATALOG_NAME, null, name, tableType,
                    null, null, null, null, null, null));
        }
        return metadataResultSet(cols, rows);
    }

    /** D1 has no schemas: an empty result set with the spec columns. */
    @Override
    public ResultSet getSchemas() throws SQLException {
        return metadataResultSet(List.of("TABLE_SCHEM", "TABLE_CATALOG"), new ArrayList<>());
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        return getSchemas();
    }

    /**
     * A single catalog row, "main" (see {@link #CATALOG_NAME}): GUI tools show
     * one database node instead of treating the connection as catalog-less.
     */
    @Override
    public ResultSet getCatalogs() throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(Arrays.asList((Object) CATALOG_NAME));
        return metadataResultSet(List.of("TABLE_CAT"), rows);
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(Arrays.asList((Object) "TABLE"));
        rows.add(Arrays.asList((Object) "VIEW"));
        return metadataResultSet(List.of("TABLE_TYPE"), rows);
    }

    /**
     * {@inheritDoc}
     *
     * <p>One {@code PRAGMA table_info("t")} per matching table/view (the form
     * confirmed live against D1, see the probe notes above). TYPE_NAME is the
     * declared type text verbatim; DATA_TYPE follows SQLite affinity rules
     * ({@link #sqlTypeForDeclaredType}). IS_AUTOINCREMENT is a best effort:
     * "YES" only for a single-column INTEGER PRIMARY KEY (a rowid alias).
     */
    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern,
            String columnNamePattern) throws SQLException {
        List<String> cols = List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
                "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS",
                "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE",
                "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE",
                "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE",
                "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN");
        List<List<Object>> rows = new ArrayList<>();
        if (catalogMismatch(catalog)) {
            return metadataResultSet(cols, rows);
        }
        for (String table : userTableNames(tableNamePattern)) {
            D1QueryResult info = runMetadataQuery(
                    "PRAGMA table_info(" + quoteIdentifier(table) + ")");
            // Rowid-alias detection for IS_AUTOINCREMENT: exactly one pk
            // column and it is declared INTEGER.
            int pkColumns = 0;
            boolean pkIsInteger = false;
            for (List<Object> col : info.rows()) {
                if (asLong(col.get(5)) > 0) {
                    pkColumns++;
                    pkIsInteger = "INTEGER".equalsIgnoreCase(asString(col.get(2)));
                }
            }
            boolean rowIdAlias = pkColumns == 1 && pkIsInteger;
            for (List<Object> col : info.rows()) {
                // table_info shape: cid, name, type, notnull, dflt_value, pk
                String columnName = asString(col.get(1));
                if (columnName == null
                        || !matchesLikePattern(columnName, columnNamePattern)) {
                    continue;
                }
                String declaredType = asString(col.get(2));
                boolean notNull = asLong(col.get(3)) != 0;
                boolean isPk = asLong(col.get(5)) > 0;
                rows.add(Arrays.asList(
                        CATALOG_NAME,                          // TABLE_CAT
                        null,                                  // TABLE_SCHEM
                        table,                                 // TABLE_NAME
                        columnName,                            // COLUMN_NAME
                        (long) sqlTypeForDeclaredType(declaredType), // DATA_TYPE
                        declaredType == null ? "" : declaredType,    // TYPE_NAME
                        null,                                  // COLUMN_SIZE
                        null,                                  // BUFFER_LENGTH (unused)
                        null,                                  // DECIMAL_DIGITS
                        10L,                                   // NUM_PREC_RADIX
                        (long) (notNull ? columnNoNulls : columnNullable), // NULLABLE
                        null,                                  // REMARKS
                        asString(col.get(4)),                  // COLUMN_DEF
                        null,                                  // SQL_DATA_TYPE (unused)
                        null,                                  // SQL_DATETIME_SUB (unused)
                        null,                                  // CHAR_OCTET_LENGTH
                        asLong(col.get(0)) + 1,                // ORDINAL_POSITION (1-based)
                        notNull ? "NO" : "YES",                // IS_NULLABLE
                        null,                                  // SCOPE_CATALOG
                        null,                                  // SCOPE_SCHEMA
                        null,                                  // SCOPE_TABLE
                        null,                                  // SOURCE_DATA_TYPE
                        (rowIdAlias && isPk) ? "YES" : "NO",   // IS_AUTOINCREMENT
                        "NO"));                                // IS_GENERATEDCOLUMN
            }
        }
        return metadataResultSet(cols, rows);
    }

    // D1/SQLite has no column privileges (no GRANT/REVOKE) -> empty is honest.
    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table,
            String columnNamePattern) throws SQLException {
        return emptyMetadata("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
                "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE");
    }

    // D1/SQLite has no table privileges (no GRANT/REVOKE) -> empty is honest.
    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern,
            String tableNamePattern) throws SQLException {
        return emptyMetadata("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME",
                "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE");
    }

    // D1 exposes no stable row-identifier metadata (rowid is unsupported here,
    // see getRowIdLifetime) -> empty is honest.
    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table,
            int scope, boolean nullable) throws SQLException {
        return emptyMetadata("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
                "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN");
    }

    // D1/SQLite has no auto-updating version columns -> empty is honest.
    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table)
            throws SQLException {
        return emptyMetadata("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
                "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN");
    }

    /**
     * {@inheritDoc}
     *
     * <p>From the {@code pk} ordinals of {@code PRAGMA table_info}. Rows are
     * ordered by KEY_SEQ (the pk ordinal, 1-based) — the composite-key column
     * order, which is what GUI tools display — rather than by COLUMN_NAME.
     * PK_NAME is null (SQLite does not name the implicit primary-key
     * constraint). {@code table} is a literal name, not a pattern.
     */
    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table)
            throws SQLException {
        List<String> cols = List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME",
                "COLUMN_NAME", "KEY_SEQ", "PK_NAME");
        List<List<Object>> rows = new ArrayList<>();
        if (table == null || catalogMismatch(catalog)) {
            return metadataResultSet(cols, rows);
        }
        D1QueryResult info = runMetadataQuery(
                "PRAGMA table_info(" + quoteIdentifier(table) + ")");
        List<List<Object>> pkCols = new ArrayList<>();
        for (List<Object> col : info.rows()) {
            if (asLong(col.get(5)) > 0) {
                pkCols.add(col);
            }
        }
        pkCols.sort(java.util.Comparator.comparingLong(c -> asLong(c.get(5))));
        for (List<Object> col : pkCols) {
            rows.add(Arrays.asList(CATALOG_NAME, null, table,
                    asString(col.get(1)), asLong(col.get(5)), null));
        }
        return metadataResultSet(cols, rows);
    }

    /** The 14 spec columns shared by getImportedKeys/getExportedKeys/getCrossReference. */
    private static final List<String> FK_COLUMNS = List.of(
            "PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME",
            "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME",
            "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME",
            "DEFERRABILITY");

    /**
     * The {@link #FK_COLUMNS}-shaped rows for the foreign keys declared
     * <em>on</em> {@code fkTable} (one {@code PRAGMA foreign_key_list} call).
     * When {@code pkTableFilter} is non-null only rows referencing that parent
     * table are kept (matched case-insensitively, like SQLite table names).
     */
    private List<List<Object>> foreignKeyRows(String fkTable, String pkTableFilter)
            throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        D1QueryResult fks = runMetadataQuery(
                "PRAGMA foreign_key_list(" + quoteIdentifier(fkTable) + ")");
        for (List<Object> fk : fks.rows()) {
            // foreign_key_list shape: id, seq, table, from, to, on_update, on_delete, match
            String pkTable = asString(fk.get(2));
            if (pkTableFilter != null
                    && (pkTable == null || !pkTable.equalsIgnoreCase(pkTableFilter))) {
                continue;
            }
            rows.add(Arrays.asList(
                    CATALOG_NAME,                 // PKTABLE_CAT
                    null,                         // PKTABLE_SCHEM
                    pkTable,                      // PKTABLE_NAME
                    asString(fk.get(4)),          // PKCOLUMN_NAME (null = implicit pk)
                    CATALOG_NAME,                 // FKTABLE_CAT
                    null,                         // FKTABLE_SCHEM
                    fkTable,                      // FKTABLE_NAME
                    asString(fk.get(3)),          // FKCOLUMN_NAME
                    asLong(fk.get(1)) + 1,        // KEY_SEQ (pragma seq is 0-based)
                    foreignKeyRule(asString(fk.get(5))), // UPDATE_RULE
                    foreignKeyRule(asString(fk.get(6))), // DELETE_RULE
                    null,                         // FK_NAME
                    null,                         // PK_NAME
                    (long) importedKeyNotDeferrable));   // DEFERRABILITY
        }
        return rows;
    }

    /**
     * {@inheritDoc}
     *
     * <p>From {@code PRAGMA foreign_key_list("t")} (confirmed working on live
     * D1, see the probe notes above). PKCOLUMN_NAME may be null when the FK
     * references the parent's implicit primary key. FK_NAME/PK_NAME are null;
     * SQLite does not expose constraint names through this pragma.
     */
    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table)
            throws SQLException {
        if (table == null || catalogMismatch(catalog)) {
            return metadataResultSet(FK_COLUMNS, new ArrayList<>());
        }
        List<List<Object>> rows = foreignKeyRows(table, null);
        // Spec ordering: PKTABLE_CAT, PKTABLE_SCHEM, PKTABLE_NAME, KEY_SEQ.
        rows.sort(java.util.Comparator
                .comparing((List<Object> r) -> asString(r.get(2)))
                .thenComparingLong(r -> asLong(r.get(8))));
        return metadataResultSet(FK_COLUMNS, rows);
    }

    /** Map a foreign_key_list ON UPDATE/ON DELETE action to the JDBC rule constant. */
    private static long foreignKeyRule(String action) {
        String a = action == null ? "" : action.toUpperCase(Locale.ROOT);
        switch (a) {
            case "CASCADE":
                return importedKeyCascade;
            case "SET NULL":
                return importedKeySetNull;
            case "SET DEFAULT":
                return importedKeySetDefault;
            case "RESTRICT":
                return importedKeyRestrict;
            default: // "NO ACTION" and anything unrecognized
                return importedKeyNoAction;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>SQLite has no reverse foreign-key pragma, so this scans every user
     * table (same visibility rule as {@link #getTables getTables}) and keeps
     * the {@code PRAGMA foreign_key_list} rows that reference {@code table}.
     */
    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table)
            throws SQLException {
        if (table == null || catalogMismatch(catalog)) {
            return metadataResultSet(FK_COLUMNS, new ArrayList<>());
        }
        List<List<Object>> rows = new ArrayList<>();
        for (String fkTable : userTableNames(null)) {
            rows.addAll(foreignKeyRows(fkTable, table));
        }
        sortByFkTableAndKeySeq(rows);
        return metadataResultSet(FK_COLUMNS, rows);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code PRAGMA foreign_key_list} on the foreign (child) table, kept
     * only where the referenced table is {@code parentTable}.
     */
    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema,
            String parentTable, String foreignCatalog, String foreignSchema,
            String foreignTable) throws SQLException {
        if (parentTable == null || foreignTable == null
                || catalogMismatch(parentCatalog) || catalogMismatch(foreignCatalog)) {
            return metadataResultSet(FK_COLUMNS, new ArrayList<>());
        }
        List<List<Object>> rows = foreignKeyRows(foreignTable, parentTable);
        sortByFkTableAndKeySeq(rows);
        return metadataResultSet(FK_COLUMNS, rows);
    }

    /** Spec ordering for exported/cross-reference keys: FKTABLE_NAME, KEY_SEQ. */
    private static void sortByFkTableAndKeySeq(List<List<Object>> rows) {
        rows.sort(java.util.Comparator
                .comparing((List<Object> r) -> asString(r.get(6)))
                .thenComparingLong(r -> asLong(r.get(8))));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The five SQLite storage classes / type affinities, ordered by
     * DATA_TYPE per the spec: NUMERIC, INTEGER, REAL (DOUBLE), TEXT (VARCHAR),
     * BLOB. PRECISION for TEXT/BLOB is the confirmed D1 per-value ceiling
     * ({@link D1Limits#MAX_VALUE_BYTES}).
     */
    @Override
    public ResultSet getTypeInfo() throws SQLException {
        List<String> cols = List.of("TYPE_NAME", "DATA_TYPE", "PRECISION",
                "LITERAL_PREFIX", "LITERAL_SUFFIX", "CREATE_PARAMS", "NULLABLE",
                "CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE", "FIXED_PREC_SCALE",
                "AUTO_INCREMENT", "LOCAL_TYPE_NAME", "MINIMUM_SCALE", "MAXIMUM_SCALE",
                "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "NUM_PREC_RADIX");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(typeInfoRow("NUMERIC", Types.NUMERIC, 19L, null, null, false, false));
        rows.add(typeInfoRow("INTEGER", Types.INTEGER, 19L, null, null, false, true));
        rows.add(typeInfoRow("REAL", Types.DOUBLE, 15L, null, null, false, false));
        rows.add(typeInfoRow("TEXT", Types.VARCHAR, (long) D1Limits.MAX_VALUE_BYTES,
                "'", "'", true, false));
        rows.add(typeInfoRow("BLOB", Types.BLOB, (long) D1Limits.MAX_VALUE_BYTES,
                "X'", "'", false, false));
        return metadataResultSet(cols, rows);
    }

    private static List<Object> typeInfoRow(String typeName, int dataType, long precision,
            String literalPrefix, String literalSuffix, boolean caseSensitive,
            boolean autoIncrement) {
        return Arrays.asList(
                typeName,                        // TYPE_NAME
                (long) dataType,                 // DATA_TYPE
                precision,                       // PRECISION
                literalPrefix,                   // LITERAL_PREFIX
                literalSuffix,                   // LITERAL_SUFFIX
                null,                            // CREATE_PARAMS
                (long) typeNullable,             // NULLABLE
                caseSensitive ? 1L : 0L,         // CASE_SENSITIVE
                (long) typeSearchable,           // SEARCHABLE
                0L,                              // UNSIGNED_ATTRIBUTE
                0L,                              // FIXED_PREC_SCALE
                autoIncrement ? 1L : 0L,         // AUTO_INCREMENT
                null,                            // LOCAL_TYPE_NAME
                0L,                              // MINIMUM_SCALE
                0L,                              // MAXIMUM_SCALE
                null,                            // SQL_DATA_TYPE (unused)
                null,                            // SQL_DATETIME_SUB (unused)
                10L);                            // NUM_PREC_RADIX
    }

    /**
     * {@inheritDoc}
     *
     * <p>From {@code PRAGMA index_list("t")} plus one
     * {@code PRAGMA index_info("i")} per index (both confirmed working on live
     * D1, see the probe notes above). All indexes are reported as
     * {@code tableIndexOther}; CARDINALITY/PAGES are 0 (SQLite exposes no
     * statistics through these pragmas) and {@code approximate} is ignored.
     */
    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table,
            boolean unique, boolean approximate) throws SQLException {
        List<String> cols = List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE",
                "INDEX_QUALIFIER", "INDEX_NAME", "TYPE", "ORDINAL_POSITION", "COLUMN_NAME",
                "ASC_OR_DESC", "CARDINALITY", "PAGES", "FILTER_CONDITION");
        List<List<Object>> rows = new ArrayList<>();
        if (table == null || catalogMismatch(catalog)) {
            return metadataResultSet(cols, rows);
        }
        D1QueryResult list = runMetadataQuery(
                "PRAGMA index_list(" + quoteIdentifier(table) + ")");
        // index_list shape: seq, name, unique, origin, partial
        List<List<Object>> indexes = new ArrayList<>(list.rows());
        // Spec ordering: NON_UNIQUE, TYPE, INDEX_NAME, ORDINAL_POSITION.
        indexes.sort(java.util.Comparator
                .comparingLong((List<Object> ix) -> asLong(ix.get(2)) != 0 ? 0L : 1L)
                .thenComparing(ix -> asString(ix.get(1))));
        for (List<Object> ix : indexes) {
            boolean isUnique = asLong(ix.get(2)) != 0;
            if (unique && !isUnique) {
                continue;
            }
            String indexName = asString(ix.get(1));
            D1QueryResult info = runMetadataQuery(
                    "PRAGMA index_info(" + quoteIdentifier(indexName) + ")");
            for (List<Object> col : info.rows()) {
                // index_info shape: seqno, cid, name
                rows.add(Arrays.asList(
                        CATALOG_NAME,             // TABLE_CAT
                        null,                     // TABLE_SCHEM
                        table,                    // TABLE_NAME
                        isUnique ? 0L : 1L,       // NON_UNIQUE
                        null,                     // INDEX_QUALIFIER
                        indexName,                // INDEX_NAME
                        (long) tableIndexOther,   // TYPE
                        asLong(col.get(0)) + 1,   // ORDINAL_POSITION (1-based)
                        asString(col.get(2)),     // COLUMN_NAME (null = expression)
                        null,                     // ASC_OR_DESC (not exposed)
                        0L,                       // CARDINALITY
                        0L,                       // PAGES
                        null));                   // FILTER_CONDITION
            }
        }
        return metadataResultSet(cols, rows);
    }

    // D1/SQLite has no user-defined types -> empty is honest.
    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern,
            int[] types) throws SQLException {
        return emptyMetadata("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME",
                "DATA_TYPE", "REMARKS", "BASE_TYPE");
    }

    // D1/SQLite has no type hierarchies -> empty is honest.
    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern)
            throws SQLException {
        return emptyMetadata("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME",
                "SUPERTYPE_CAT", "SUPERTYPE_SCHEM", "SUPERTYPE_NAME");
    }

    // D1/SQLite has no table inheritance -> empty is honest.
    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        return emptyMetadata("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME");
    }

    // D1/SQLite has no UDTs, hence no UDT attributes -> empty is honest.
    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern,
            String attributeNamePattern) throws SQLException {
        return emptyMetadata("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME",
                "DATA_TYPE", "ATTR_TYPE_NAME", "ATTR_SIZE", "DECIMAL_DIGITS",
                "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "ATTR_DEF", "SQL_DATA_TYPE",
                "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION",
                "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE",
                "SOURCE_DATA_TYPE");
    }

    // This driver supports no client info properties -> empty is honest.
    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return emptyMetadata("NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION");
    }

    // D1 exposes no user-defined functions through the catalog -> empty is honest.
    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern,
            String functionNamePattern) throws SQLException {
        return emptyMetadata("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME",
                "REMARKS", "FUNCTION_TYPE", "SPECIFIC_NAME");
    }

    // D1 exposes no user-defined functions through the catalog -> empty is honest.
    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern,
            String functionNamePattern, String columnNamePattern) throws SQLException {
        return emptyMetadata("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME",
                "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME", "PRECISION",
                "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS",
                "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE",
                "SPECIFIC_NAME");
    }

    // D1/SQLite exposes no pseudo columns through the catalog -> empty is honest.
    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern,
            String tableNamePattern, String columnNamePattern) throws SQLException {
        return emptyMetadata("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
                "DATA_TYPE", "COLUMN_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX",
                "COLUMN_USAGE", "REMARKS", "CHAR_OCTET_LENGTH", "IS_NULLABLE");
    }

    // -------------------------------------------------------------- Wrapper

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
