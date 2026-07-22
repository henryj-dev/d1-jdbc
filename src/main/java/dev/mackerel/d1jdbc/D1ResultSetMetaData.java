package dev.mackerel.d1jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * {@link ResultSetMetaData} derived from the {@code columns[]} labels. SQLite is
 * dynamically typed, so the JDBC type of each column is inferred from the first
 * non-null cell value in that column (Long / Double / String / byte[]) per
 * DESIGN 4-3. Columns that are entirely null report {@link Types#NULL}.
 */
public final class D1ResultSetMetaData implements ResultSetMetaData {

    private final List<String> columns;
    private final List<List<Object>> rows;

    D1ResultSetMetaData(List<String> columns, List<List<Object>> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    private void check(int column) throws SQLException {
        if (column < 1 || column > columns.size()) {
            throw new SQLException("Column index out of range: " + column
                    + " (1.." + columns.size() + ")", "22023");
        }
    }

    /** First non-null value in the column, or {@code null} if all null/empty. */
    private Object sample(int column) {
        for (List<Object> row : rows) {
            Object v = row.get(column - 1);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        check(column);
        return columns.get(column - 1);
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        return getColumnLabel(column);
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        check(column);
        Object v = sample(column);
        if (v == null) {
            return Types.NULL;
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return Types.BIGINT;
        }
        if (v instanceof Double || v instanceof Float) {
            return Types.DOUBLE;
        }
        if (v instanceof byte[]) {
            return Types.BLOB;
        }
        return Types.VARCHAR;
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        switch (getColumnType(column)) {
            case Types.BIGINT:
                return "INTEGER";
            case Types.DOUBLE:
                return "REAL";
            case Types.BLOB:
                return "BLOB";
            case Types.NULL:
                return "NULL";
            default:
                return "TEXT";
        }
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        switch (getColumnType(column)) {
            case Types.BIGINT:
                return Long.class.getName();
            case Types.DOUBLE:
                return Double.class.getName();
            case Types.BLOB:
                return "[B";
            case Types.NULL:
                return Object.class.getName();
            default:
                return String.class.getName();
        }
    }

    @Override
    public int isNullable(int column) throws SQLException {
        check(column);
        // SQLite column nullability is not carried in the wire result.
        return columnNullableUnknown;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        int t = getColumnType(column);
        return t == Types.BIGINT || t == Types.DOUBLE;
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        check(column);
        return 0;
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        check(column);
        return 0;
    }

    @Override
    public int getScale(int column) throws SQLException {
        check(column);
        return 0;
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        check(column);
        return true;
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        check(column);
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        check(column);
        return true;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        check(column);
        return "";
    }

    @Override
    public String getTableName(int column) throws SQLException {
        check(column);
        return "";
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        check(column);
        return "";
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
