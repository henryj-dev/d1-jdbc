package dev.mackerel.d1jdbc;

import dev.mackerel.d1jdbc.internal.D1Value;
import dev.mackerel.d1jdbc.transport.D1QueryResult;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * Forward-only, read-only {@link ResultSet}, fully materialized from a
 * {@code ROWS_AND_COLUMNS} response (DESIGN 4-3). Cells are the D1 runtime types
 * (Long / Double / String / byte[] / null); getters coerce as needed and
 * {@link #wasNull()} reflects the last read.
 */
public final class D1ResultSet implements ResultSet {

    private final D1Statement statement;
    private final List<String> columns;
    private final List<List<Object>> rows;

    private int cursor = -1; // before first
    private boolean wasNull = false;
    private boolean closed = false;
    private int fetchSizeHint = 0; // advisory only; no streaming cursor (DESIGN 4-3)

    D1ResultSet(D1Statement statement, D1QueryResult result) {
        this.statement = statement;
        this.columns = result.columns();
        this.rows = result.rows();
    }

    private void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("ResultSet is closed", "HY010");
        }
    }

    private void checkRow() throws SQLException {
        checkOpen();
        if (cursor < 0 || cursor >= rows.size()) {
            throw new SQLException("No current row (call next() first)", "24000");
        }
    }

    private Object cell(int columnIndex) throws SQLException {
        checkRow();
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw new SQLException("Column index out of range: " + columnIndex
                    + " (1.." + columns.size() + ")", "22023");
        }
        Object value = rows.get(cursor).get(columnIndex - 1);
        wasNull = (value == null);
        return value;
    }

    // ------------------------------------------------------------ navigation

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        if (cursor >= rows.size()) {
            return false;
        }
        cursor++;
        return cursor < rows.size();
    }

    @Override
    public void close() throws SQLException {
        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return wasNull;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return cursor < 0 && !rows.isEmpty();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return cursor >= rows.size() && !rows.isEmpty();
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return cursor == 0 && !rows.isEmpty();
    }

    @Override
    public boolean isLast() throws SQLException {
        checkOpen();
        return !rows.isEmpty() && cursor == rows.size() - 1;
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        return (cursor < 0 || cursor >= rows.size()) ? 0 : cursor + 1;
    }

    // ------------------------------------------------------------- by index

    @Override
    public String getString(int columnIndex) throws SQLException {
        Object v = cell(columnIndex);
        if (v == null) {
            return null;
        }
        if (v instanceof byte[]) {
            return new String((byte[]) v, StandardCharsets.UTF_8);
        }
        return String.valueOf(v);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        Object v = cell(columnIndex);
        if (v == null) {
            return false;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() != 0.0;
        }
        String s = String.valueOf(v).trim();
        return s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("t");
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return (byte) getLong(columnIndex);
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return (short) getLong(columnIndex);
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return (int) getLong(columnIndex);
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        Object v = cell(columnIndex);
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Cannot convert '" + v + "' to long", "22018", e);
        }
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return (float) getDouble(columnIndex);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        Object v = cell(columnIndex);
        if (v == null) {
            return 0.0;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Cannot convert '" + v + "' to double", "22018", e);
        }
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        BigDecimal bd = getBigDecimal(columnIndex);
        return bd == null ? null : bd.setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        Object v = cell(columnIndex);
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Cannot convert '" + v + "' to BigDecimal", "22018", e);
        }
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        Object v = cell(columnIndex);
        if (v == null) {
            return null;
        }
        if (v instanceof byte[]) {
            return (byte[]) v;
        }
        return String.valueOf(v).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        Object v = cell(columnIndex);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return new Date(((Number) v).longValue());
        }
        String s = String.valueOf(v).trim();
        try {
            // Accept "yyyy-MM-dd" or a leading date in a datetime string.
            String datePart = s.length() >= 10 ? s.substring(0, 10) : s;
            return Date.valueOf(LocalDate.parse(datePart));
        } catch (RuntimeException e) {
            throw new SQLException("Cannot convert '" + s + "' to Date", "22007", e);
        }
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        return getDate(columnIndex);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        Object v = cell(columnIndex);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return new Time(((Number) v).longValue());
        }
        String s = String.valueOf(v).trim();
        try {
            return Time.valueOf(LocalTime.parse(s));
        } catch (RuntimeException e) {
            throw new SQLException("Cannot convert '" + s + "' to Time", "22007", e);
        }
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        return getTime(columnIndex);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        Object v = cell(columnIndex);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return new Timestamp(((Number) v).longValue());
        }
        String s = String.valueOf(v).trim();
        try {
            String normalized = s.contains("T") ? s.replace('T', ' ') : s;
            return Timestamp.valueOf(LocalDateTime.parse(
                    normalized.replace(' ', 'T')));
        } catch (RuntimeException e) {
            // Fall back to java.sql.Timestamp's own SQLite-style parser.
            try {
                return Timestamp.valueOf(s);
            } catch (RuntimeException e2) {
                throw new SQLException("Cannot convert '" + s + "' to Timestamp", "22007", e2);
            }
        }
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        return getTimestamp(columnIndex);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return cell(columnIndex);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        if (type == null) {
            throw new SQLException("Target type must not be null");
        }
        if (type == String.class) {
            return (T) getString(columnIndex);
        }
        if (type == Boolean.class) {
            Object v = cell(columnIndex);
            return v == null ? null : (T) Boolean.valueOf(getBoolean(columnIndex));
        }
        if (type == Integer.class) {
            Object v = cell(columnIndex);
            return v == null ? null : (T) Integer.valueOf(getInt(columnIndex));
        }
        if (type == Long.class) {
            Object v = cell(columnIndex);
            return v == null ? null : (T) Long.valueOf(getLong(columnIndex));
        }
        if (type == Double.class) {
            Object v = cell(columnIndex);
            return v == null ? null : (T) Double.valueOf(getDouble(columnIndex));
        }
        if (type == Float.class) {
            Object v = cell(columnIndex);
            return v == null ? null : (T) Float.valueOf(getFloat(columnIndex));
        }
        if (type == Short.class) {
            Object v = cell(columnIndex);
            return v == null ? null : (T) Short.valueOf(getShort(columnIndex));
        }
        if (type == Byte.class) {
            Object v = cell(columnIndex);
            return v == null ? null : (T) Byte.valueOf(getByte(columnIndex));
        }
        if (type == BigDecimal.class) {
            return (T) getBigDecimal(columnIndex);
        }
        if (type == byte[].class) {
            return (T) getBytes(columnIndex);
        }
        if (type == Date.class) {
            return (T) getDate(columnIndex);
        }
        if (type == Time.class) {
            return (T) getTime(columnIndex);
        }
        if (type == Timestamp.class) {
            return (T) getTimestamp(columnIndex);
        }
        Object v = cell(columnIndex);
        if (v == null) {
            return null;
        }
        if (type.isInstance(v)) {
            return type.cast(v);
        }
        throw new SQLException("Cannot convert column " + columnIndex + " to " + type.getName());
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return cell(columnIndex);
    }

    // ------------------------------------------------------------- by label

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkOpen();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i) != null && columns.get(i).equalsIgnoreCase(columnLabel)) {
                return i + 1;
            }
        }
        throw new SQLException("No such column: " + columnLabel, "42S22");
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return getBigDecimal(findColumn(columnLabel), scale);
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    // --------------------------------------------------------------- metadata

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return new D1ResultSetMetaData(columns, rows);
    }

    @Override
    public Statement getStatement() throws SQLException {
        return statement;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
    }

    @Override
    public String getCursorName() throws SQLException {
        throw new SQLFeatureNotSupportedException("Named cursors are not supported");
    }

    @Override
    public int getType() throws SQLException {
        checkOpen();
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        checkOpen();
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return FETCH_FORWARD;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != FETCH_FORWARD) {
            throw new SQLFeatureNotSupportedException("Only FETCH_FORWARD is supported");
        }
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkOpen();
        // No streaming cursor; fetch size is advisory (DESIGN 4-3).
        this.fetchSizeHint = rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        // Return the advisory hint (consistent with D1Statement.getFetchSize()),
        // not the materialized row count.
        return fetchSizeHint;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return CLOSE_CURSORS_AT_COMMIT;
    }

    // ------------------------------------- streams (materialized -> derive)

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] b = getBytes(columnIndex);
        return b == null ? null : new java.io.ByteArrayInputStream(b);
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return getBinaryStream(findColumn(columnLabel));
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null
                : new java.io.ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return getAsciiStream(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null
                : new java.io.ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return getUnicodeStream(findColumn(columnLabel));
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new java.io.StringReader(s);
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return getCharacterStream(columnIndex);
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    // --------------------------------------------- unsupported navigation

    @Override
    public void beforeFirst() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public void afterLast() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean first() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean last() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean previous() throws SQLException {
        throw forwardOnly();
    }

    private static SQLException forwardOnly() {
        return new SQLFeatureNotSupportedException("ResultSet is TYPE_FORWARD_ONLY");
    }

    // --------------------------------------------- unsupported LOB / advanced

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        throw notSupported("getRef");
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        throw notSupported("getRef");
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        throw notSupported("getBlob (use getBytes)");
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        throw notSupported("getBlob (use getBytes)");
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        throw notSupported("getClob");
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        throw notSupported("getClob");
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        throw notSupported("getNClob");
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        throw notSupported("getNClob");
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        throw notSupported("getArray");
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        throw notSupported("getArray");
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        if (s == null) {
            return null;
        }
        try {
            return new java.net.URI(s).toURL();
        } catch (Exception e) {
            throw new SQLException("Cannot convert '" + s + "' to URL", "22018", e);
        }
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return getURL(findColumn(columnLabel));
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        throw notSupported("getRowId");
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        throw notSupported("getRowId");
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw notSupported("getSQLXML");
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        throw notSupported("getSQLXML");
    }

    private static SQLFeatureNotSupportedException notSupported(String name) {
        return new SQLFeatureNotSupportedException(name + " is not supported by the D1 driver");
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        return false;
    }

    @Override
    public boolean rowInserted() throws SQLException {
        return false;
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        return false;
    }

    // ---------------------------------- update ops are unsupported (read-only)

    private static SQLException readOnly() {
        return new SQLFeatureNotSupportedException("ResultSet is CONCUR_READ_ONLY");
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void insertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void deleteRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void refreshRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length)
            throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        throw readOnly();
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
