package dev.mackerel.d1jdbc;

import dev.mackerel.d1jdbc.internal.D1Limits;
import dev.mackerel.d1jdbc.transport.Capabilities;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * Minimal {@link DatabaseMetaData} for Cloudflare D1. Feature queries return
 * sensible booleans and transport-specific capabilities
 * ({@link Capabilities#supportsAtomicBatch()} drives
 * {@link #supportsTransactions()}); size limits reflect the confirmed D1
 * platform limits in {@link D1Limits} (DESIGN 9-1), with {@code 0} meaning
 * "unknown / no documented limit" per JDBC convention. Catalog-introspection
 * methods that return a {@link ResultSet} are not implemented and throw
 * {@link SQLFeatureNotSupportedException}.
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

    private static SQLFeatureNotSupportedException catalog(String name) {
        return new SQLFeatureNotSupportedException(
                name + " catalog introspection is not implemented");
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern,
            String procedureNamePattern) throws SQLException {
        throw catalog("getProcedures");
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern,
            String procedureNamePattern, String columnNamePattern) throws SQLException {
        throw catalog("getProcedureColumns");
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern,
            String[] types) throws SQLException {
        throw catalog("getTables");
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        throw catalog("getSchemas");
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        throw catalog("getSchemas");
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        throw catalog("getCatalogs");
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        throw catalog("getTableTypes");
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern,
            String columnNamePattern) throws SQLException {
        throw catalog("getColumns");
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table,
            String columnNamePattern) throws SQLException {
        throw catalog("getColumnPrivileges");
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern,
            String tableNamePattern) throws SQLException {
        throw catalog("getTablePrivileges");
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table,
            int scope, boolean nullable) throws SQLException {
        throw catalog("getBestRowIdentifier");
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table)
            throws SQLException {
        throw catalog("getVersionColumns");
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table)
            throws SQLException {
        throw catalog("getPrimaryKeys");
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table)
            throws SQLException {
        throw catalog("getImportedKeys");
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table)
            throws SQLException {
        throw catalog("getExportedKeys");
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema,
            String parentTable, String foreignCatalog, String foreignSchema,
            String foreignTable) throws SQLException {
        throw catalog("getCrossReference");
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        throw catalog("getTypeInfo");
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table,
            boolean unique, boolean approximate) throws SQLException {
        throw catalog("getIndexInfo");
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern,
            int[] types) throws SQLException {
        throw catalog("getUDTs");
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern)
            throws SQLException {
        throw catalog("getSuperTypes");
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        throw catalog("getSuperTables");
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern,
            String attributeNamePattern) throws SQLException {
        throw catalog("getAttributes");
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        throw catalog("getClientInfoProperties");
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern,
            String functionNamePattern) throws SQLException {
        throw catalog("getFunctions");
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern,
            String functionNamePattern, String columnNamePattern) throws SQLException {
        throw catalog("getFunctionColumns");
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern,
            String tableNamePattern, String columnNamePattern) throws SQLException {
        throw catalog("getPseudoColumns");
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
