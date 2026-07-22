package dev.mackerel.d1jdbc;

import dev.mackerel.d1jdbc.transport.D1Meta;
import dev.mackerel.d1jdbc.transport.D1QueryResult;
import dev.mackerel.d1jdbc.transport.D1Request;
import dev.mackerel.d1jdbc.transport.TransportException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC {@link Statement} for ad-hoc SQL. Queries always use the
 * {@code ROWS_AND_COLUMNS} format (DESIGN 4-1); {@code getUpdateCount()} comes
 * from {@code meta.changes} and {@code getGeneratedKeys()} from
 * {@code meta.last_row_id} (DESIGN 4-4).
 *
 * <p>{@link D1PreparedStatement} extends this and reuses the execution core.
 */
public class D1Statement implements Statement {

    protected final D1Connection connection;
    protected boolean closed = false;
    protected int fetchSize = 0;
    protected int maxRows = 0;
    protected SQLWarning warnings;

    protected D1ResultSet currentResultSet;
    protected long currentUpdateCount = -1;
    protected D1Meta lastMeta = D1Meta.EMPTY;

    private final List<String> batch = new ArrayList<>();

    D1Statement(D1Connection connection) {
        this.connection = connection;
    }

    protected void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("Statement is closed", "HY010");
        }
        connection.checkOpen();
    }

    // -------------------------------------------------------- execution core

    /**
     * Execute a read immediately (reads are never buffered) and materialize the
     * {@link ResultSet}.
     */
    protected ResultSet doQuery(String sql, List<Object> params) throws SQLException {
        checkOpen();
        try {
            D1QueryResult result = connection.transport()
                    .query(sql, params, connection.bookmark());
            connection.updateBookmark(result.bookmark());
            lastMeta = result.meta();
            currentUpdateCount = -1;
            currentResultSet = new D1ResultSet(this, result);
            return currentResultSet;
        } catch (TransportException e) {
            throw D1Codec.toSQLException(e);
        }
    }

    /**
     * Execute a write. When {@code autoCommit=false} the statement is buffered
     * for the next {@link Connection#commit()} and {@code 0} is returned (the
     * real change count is only known after the atomic batch runs).
     */
    protected long doUpdate(String sql, List<Object> params) throws SQLException {
        checkOpen();
        currentResultSet = null;
        if (!connection.isAutoCommitInternal()) {
            connection.bufferStatement(sql, params);
            currentUpdateCount = 0;
            return 0;
        }
        try {
            D1QueryResult result = connection.transport()
                    .query(sql, params, connection.bookmark());
            connection.updateBookmark(result.bookmark());
            lastMeta = result.meta();
            currentUpdateCount = result.meta().changes();
            return currentUpdateCount;
        } catch (TransportException e) {
            throw D1Codec.toSQLException(e);
        }
    }

    /**
     * Generic execute. Runs the statement and reports whether it produced a
     * result set (non-empty columns) or an update count.
     */
    protected boolean doExecute(String sql, List<Object> params) throws SQLException {
        checkOpen();
        if (!connection.isAutoCommitInternal()) {
            // In a manual transaction, treat as a buffered write.
            connection.bufferStatement(sql, params);
            currentResultSet = null;
            currentUpdateCount = 0;
            return false;
        }
        try {
            D1QueryResult result = connection.transport()
                    .query(sql, params, connection.bookmark());
            connection.updateBookmark(result.bookmark());
            lastMeta = result.meta();
            if (!result.columns().isEmpty()) {
                currentResultSet = new D1ResultSet(this, result);
                currentUpdateCount = -1;
                return true;
            }
            currentResultSet = null;
            currentUpdateCount = result.meta().changes();
            return false;
        } catch (TransportException e) {
            throw D1Codec.toSQLException(e);
        }
    }

    // ----------------------------------------------------------- JDBC surface

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        return doQuery(sql, List.of());
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        return (int) doUpdate(sql, List.of());
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        return doUpdate(sql, List.of());
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        return doExecute(sql, List.of());
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return (int) doUpdate(sql, List.of());
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return (int) doUpdate(sql, List.of());
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return (int) doUpdate(sql, List.of());
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return doExecute(sql, List.of());
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return doExecute(sql, List.of());
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return doExecute(sql, List.of());
    }

    // --------------------------------------------------------------- batch

    @Override
    public void addBatch(String sql) throws SQLException {
        checkOpen();
        batch.add(sql);
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        batch.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        checkOpen();
        List<String> toRun = new ArrayList<>(batch);
        batch.clear();
        if (toRun.isEmpty()) {
            return new int[0];
        }
        List<D1Request> reqs = new ArrayList<>(toRun.size());
        for (String sql : toRun) {
            reqs.add(new D1Request(sql, List.of()));
        }
        try {
            List<D1QueryResult> results =
                    connection.transport().batch(reqs, connection.bookmark());
            int[] counts = new int[results.size()];
            for (int i = 0; i < results.size(); i++) {
                D1QueryResult r = results.get(i);
                connection.updateBookmark(r.bookmark());
                counts[i] = (int) r.meta().changes();
            }
            return counts;
        } catch (TransportException e) {
            throw D1Codec.toSQLException(e);
        }
    }

    // -------------------------------------------------------------- results

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkOpen();
        return currentResultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        checkOpen();
        return (int) currentUpdateCount;
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        checkOpen();
        return currentUpdateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        checkOpen();
        // Single result set per execution; nothing more.
        if (currentResultSet != null) {
            currentResultSet.close();
            currentResultSet = null;
        }
        currentUpdateCount = -1;
        return false;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return getMoreResults();
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        checkOpen();
        List<String> cols = List.of("last_row_id");
        List<List<Object>> rows = new ArrayList<>(1);
        // Only an INSERT that actually wrote a row yields a generated key. After a
        // SELECT, a no-op, or a buffered (autoCommit=false) write, lastMeta is EMPTY
        // and JDBC expects an empty ResultSet — not a fabricated rowid of 0.
        if (lastMeta.changes() > 0 && lastMeta.lastRowId() != 0) {
            List<Object> row = new ArrayList<>(1);
            row.add(lastMeta.lastRowId());
            rows.add(row);
        }
        D1QueryResult keys = new D1QueryResult(cols, rows, lastMeta, connection.bookmark());
        return new D1ResultSet(this, keys);
    }

    // -------------------------------------------------------------- config

    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkOpen();
        // No streaming cursor in D1; fetch size is advisory only (DESIGN 4-3).
        this.fetchSize = rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return fetchSize;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != ResultSet.FETCH_FORWARD) {
            throw new SQLFeatureNotSupportedException("Only FETCH_FORWARD is supported");
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        checkOpen();
        this.maxRows = max;
    }

    @Override
    public int getMaxRows() throws SQLException {
        checkOpen();
        return maxRows;
    }

    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        setMaxRows((int) max);
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        return maxRows;
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        checkOpen();
        return 0;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        checkOpen();
        // Unbounded; ignored.
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        checkOpen();
        // No JDBC escape processing; SQL is passed through verbatim.
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        checkOpen();
        return 0;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        checkOpen();
        // HTTP timeouts are configured via URL properties; ignored here.
    }

    @Override
    public void cancel() throws SQLException {
        throw new SQLFeatureNotSupportedException("cancel is not supported");
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException("Named cursors are not supported");
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        checkOpen();
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetType() throws SQLException {
        checkOpen();
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        checkOpen();
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public Connection getConnection() throws SQLException {
        checkOpen();
        return connection;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        warnings = null;
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        if (currentResultSet != null) {
            currentResultSet.close();
            currentResultSet = null;
        }
        batch.clear();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        checkOpen();
        // Pooling hint ignored.
    }

    @Override
    public boolean isPoolable() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        checkOpen();
        // No-op: statements are not auto-closed on result-set close.
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        checkOpen();
        return false;
    }

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
