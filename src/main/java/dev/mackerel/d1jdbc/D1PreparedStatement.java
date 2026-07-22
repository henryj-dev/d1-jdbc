package dev.mackerel.d1jdbc;

import dev.mackerel.d1jdbc.internal.D1Limits;
import dev.mackerel.d1jdbc.internal.D1Value;
import dev.mackerel.d1jdbc.transport.D1Request;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * JDBC {@link PreparedStatement}. D1 has no server-side prepared statements
 * (DESIGN section 5.2): this is a client-side template that binds positional
 * {@code ?} parameters and serializes each via {@link D1Value} into the
 * D1-allowed wire set (number / string / null / byte[]).
 *
 * <p>With {@code autoCommit=false}, writes buffer into the connection for the
 * next atomic {@link java.sql.Connection#commit() commit}.
 */
public final class D1PreparedStatement extends D1Statement implements PreparedStatement {

    private final String sql;
    private final ArrayList<Object> params = new ArrayList<>();
    private final List<List<Object>> paramBatch = new ArrayList<>();

    D1PreparedStatement(D1Connection connection, String sql) {
        super(connection);
        this.sql = sql;
    }

    private void setParam(int parameterIndex, Object wireValue) throws SQLException {
        if (parameterIndex < 1) {
            throw new SQLException("Parameter index must be >= 1, was " + parameterIndex, "07009");
        }
        while (params.size() < parameterIndex) {
            params.add(null);
        }
        params.set(parameterIndex - 1, wireValue);
    }

    /** Snapshot the currently bound parameters as an ordered list. */
    private List<Object> currentParams() {
        return new ArrayList<>(params);
    }

    // -------------------------------------------------------------- execute

    @Override
    public ResultSet executeQuery() throws SQLException {
        return doQuery(sql, currentParams());
    }

    @Override
    public int executeUpdate() throws SQLException {
        return (int) doUpdate(sql, currentParams());
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        return doUpdate(sql, currentParams());
    }

    @Override
    public boolean execute() throws SQLException {
        return doExecute(sql, currentParams());
    }

    @Override
    public void addBatch() throws SQLException {
        checkOpen();
        paramBatch.add(currentParams());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Chunked into transport batches of at most
     * {@link D1Limits#MAX_ATOMIC_BATCH_STATEMENTS}; NOT guaranteed atomic across
     * chunks (see {@link D1Statement#executeBatch()}).
     */
    @Override
    public int[] executeBatch() throws SQLException {
        return toIntCounts(executeLargeBatch());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Same chunking and atomicity caveats as {@link #executeBatch()}.
     */
    @Override
    public long[] executeLargeBatch() throws SQLException {
        checkOpen();
        List<List<Object>> toRun = new ArrayList<>(paramBatch);
        paramBatch.clear();
        if (toRun.isEmpty()) {
            return new long[0];
        }
        List<D1Request> reqs = new ArrayList<>(toRun.size());
        for (List<Object> p : toRun) {
            reqs.add(new D1Request(sql, p));
        }
        return runBatchChunked(reqs);
    }

    // ------------------------- String-arg forms are illegal on PreparedStatement

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        throw new SQLException("executeQuery(String) cannot be called on a PreparedStatement");
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        throw new SQLException("executeUpdate(String) cannot be called on a PreparedStatement");
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        throw new SQLException("execute(String) cannot be called on a PreparedStatement");
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw new SQLException("addBatch(String) cannot be called on a PreparedStatement");
    }

    // --------------------------------------------------------------- setters

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        setParam(parameterIndex, null);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        setParam(parameterIndex, null);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        setParam(parameterIndex, x ? 1L : 0L);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        setParam(parameterIndex, (long) x);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        setParam(parameterIndex, (long) x);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        setParam(parameterIndex, (long) x);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        setParam(parameterIndex, x);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        setParam(parameterIndex, (double) x);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        setParam(parameterIndex, x);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        setParam(parameterIndex, D1Value.serialize(x));
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        checkStringSize(x);
        setParam(parameterIndex, x);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        if (x != null) {
            checkValueSize(x.length, "byte[]");
        }
        setParam(parameterIndex, x);
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        setParam(parameterIndex, D1Value.serialize(x));
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        setParam(parameterIndex, D1Value.serialize(x));
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        setParam(parameterIndex, D1Value.serialize(x));
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        setParam(parameterIndex, D1Value.serialize(x));
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        setParam(parameterIndex, D1Value.serialize(x));
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        setParam(parameterIndex, D1Value.serialize(x));
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        setParam(parameterIndex, D1Value.serialize(x));
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setParam(parameterIndex, D1Value.serialize(x));
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
            throws SQLException {
        setParam(parameterIndex, D1Value.serialize(x));
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        if (x == null) {
            setParam(parameterIndex, null);
            return;
        }
        long len = x.length();
        checkValueSize(len, "BLOB"); // before buffering: the limit is far below Integer.MAX_VALUE
        setParam(parameterIndex, x.getBytes(1, (int) len));
    }

    @Override
    public void clearParameters() throws SQLException {
        params.clear();
    }

    // ---------------------------------------------------- unsupported setters

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw unsupported("setAsciiStream");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        throw unsupported("setAsciiStream");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        throw unsupported("setAsciiStream");
    }

    @Override
    @Deprecated
    public void setUnicodeStream(int parameterIndex, InputStream x, int length)
            throws SQLException {
        throw unsupported("setUnicodeStream");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw unsupported("setBinaryStream");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length)
            throws SQLException {
        throw unsupported("setBinaryStream");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        throw unsupported("setBinaryStream");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length)
            throws SQLException {
        throw unsupported("setCharacterStream");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length)
            throws SQLException {
        throw unsupported("setCharacterStream");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        throw unsupported("setCharacterStream");
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        throw unsupported("setRef");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length)
            throws SQLException {
        throw unsupported("setBlob(InputStream)");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        throw unsupported("setBlob(InputStream)");
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        throw unsupported("setClob");
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw unsupported("setClob");
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        throw unsupported("setClob");
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        throw unsupported("setNClob");
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw unsupported("setNClob");
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        throw unsupported("setNClob");
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        throw unsupported("setArray");
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        checkStringSize(value);
        setParam(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length)
            throws SQLException {
        throw unsupported("setNCharacterStream");
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        throw unsupported("setNCharacterStream");
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        throw unsupported("setRowId");
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        throw unsupported("setSQLXML");
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        setParam(parameterIndex, x == null ? null : x.toString());
    }

    // ---------------------------------------------------------------- meta

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        // Column metadata is only known after execution (SQLite is dynamically typed).
        return currentResultSet == null ? null : currentResultSet.getMetaData();
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "ParameterMetaData is not available (no server-side prepare)");
    }

    private static SQLFeatureNotSupportedException unsupported(String name) {
        return new SQLFeatureNotSupportedException(name + " is not supported by the D1 driver");
    }

    // ------------------------------------------------------------ limit guards

    /** Guard a String value against {@link D1Limits#MAX_VALUE_BYTES} (UTF-8 encoded size). */
    private static void checkStringSize(String value) throws SQLException {
        if (value != null) {
            checkValueSize(D1Codec.utf8Length(value), "String");
        }
    }

    /**
     * Fail fast (SQLState {@code 22001}, data too long) when a bound value
     * exceeds the confirmed D1 per-value limit of
     * {@link D1Limits#MAX_VALUE_BYTES} bytes (DESIGN 9-1).
     */
    private static void checkValueSize(long bytes, String kind) throws SQLException {
        if (bytes > D1Limits.MAX_VALUE_BYTES) {
            throw new SQLException(
                    kind + " value is " + bytes + " bytes; D1 allows at most "
                            + D1Limits.MAX_VALUE_BYTES + " bytes per value (DESIGN 9-1)",
                    "22001");
        }
    }
}
