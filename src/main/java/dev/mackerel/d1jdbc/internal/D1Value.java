package dev.mackerel.d1jdbc.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;

/**
 * Serializes a bound JDBC parameter value into the JSON-allowed set that D1
 * accepts: {@code number | string | null | byte[]} (bytes become an array of
 * unsigned 0..255 integers at JSON-write time). See DESIGN 4-2.
 *
 * <p>Conventions fixed by this driver (DESIGN section 7 open item):
 * <ul>
 *   <li>{@code boolean} -> {@code 1} / {@code 0} (the JSON wire has no
 *       boolean->INTEGER coercion; this matches D1's own native Boolean->INTEGER
 *       binding and 0/1 read-back — DESIGN 9-4)</li>
 *   <li>{@link BigDecimal} / {@link BigInteger} -> canonical string (preserves precision)</li>
 *   <li>{@link Date} -> ISO {@code yyyy-MM-dd} string</li>
 *   <li>{@link Time} -> {@code HH:mm:ss} string</li>
 *   <li>{@link Timestamp} -> SQLite datetime string {@code yyyy-MM-dd HH:mm:ss.SSS}</li>
 * </ul>
 */
public final class D1Value {

    /** {@code yyyy-MM-dd HH:mm:ss.SSS} — the SQLite-canonical datetime text form. */
    public static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private D1Value() {
    }

    /**
     * Convert a JDBC-bound value to a D1 wire value.
     *
     * @return {@code null}, a {@link Long}/{@link Double}, a {@link String}, or a {@code byte[]}
     * @throws SQLException if the value's type is not representable in D1
     */
    public static Object serialize(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof byte[]) {
            return value;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1L : 0L;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float || value instanceof Double) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof BigInteger) {
            return value.toString();
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime().format(TIMESTAMP_FORMAT);
        }
        if (value instanceof Date) {
            // java.sql.Date -> yyyy-MM-dd (via LocalDate to avoid TZ surprises).
            return ((Date) value).toLocalDate().toString();
        }
        if (value instanceof Time) {
            return ((Time) value).toLocalTime().toString();
        }
        if (value instanceof Character) {
            return value.toString();
        }
        throw new SQLException(
                "Unsupported parameter type for D1: " + value.getClass().getName()
                        + ". Allowed: number, string, null, byte[] (see DESIGN 4-2).",
                "22023");
    }
}
