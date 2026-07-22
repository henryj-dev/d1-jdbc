package dev.mackerel.d1jdbc;

import dev.mackerel.d1jdbc.internal.D1Limits;
import dev.mackerel.d1jdbc.internal.Json;
import dev.mackerel.d1jdbc.transport.D1Meta;
import dev.mackerel.d1jdbc.transport.D1QueryResult;
import dev.mackerel.d1jdbc.transport.D1Request;
import dev.mackerel.d1jdbc.transport.TransportException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transport-agnostic protocol codec, shared by both {@code RestTransport} and
 * {@code ProxyTransport}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Build request bodies: {@code {sql, params}} for single statements and
 *       {@code [{sql, params}, ...]} arrays for batches.</li>
 *   <li>Parse a {@code ROWS_AND_COLUMNS} result plus {@code meta} into the
 *       internal {@link D1QueryResult} model. Accepts both the REST {@code /raw}
 *       shape ({@code {results:{columns,rows}, meta}}), the normalized proxy
 *       shape ({@code {columns, rows, meta}}), and an array-of-objects
 *       {@code results} fallback.</li>
 *   <li>Map transport/D1 errors to {@link SQLException}.</li>
 * </ul>
 */
public final class D1Codec {

    private D1Codec() {
    }

    // ------------------------------------------------------------ limit guards

    /**
     * Guard one statement against the confirmed per-statement D1 limits
     * (DESIGN 9-1) before it is sent, buffered, or batched.
     *
     * @throws SQLException SQLState {@code 54000} (program limit exceeded) when
     *         the SQL exceeds {@link D1Limits#MAX_SQL_BYTES} UTF-8 bytes or more
     *         than {@link D1Limits#MAX_BOUND_PARAMS} parameters are bound
     */
    public static void validateStatement(String sql, List<Object> params) throws SQLException {
        long sqlBytes = utf8Length(sql == null ? "" : sql);
        if (sqlBytes > D1Limits.MAX_SQL_BYTES) {
            throw new SQLException(
                    "SQL statement is " + sqlBytes + " UTF-8 bytes; D1 allows at most "
                            + D1Limits.MAX_SQL_BYTES + " bytes per statement (DESIGN 9-1)",
                    "54000");
        }
        int paramCount = params == null ? 0 : params.size();
        if (paramCount > D1Limits.MAX_BOUND_PARAMS) {
            throw new SQLException(
                    "Statement binds " + paramCount + " parameters; D1 allows at most "
                            + D1Limits.MAX_BOUND_PARAMS + " bound parameters per statement"
                            + " (DESIGN 9-1)",
                    "54000");
        }
    }

    /** UTF-8 encoded byte length of {@code s}, computed without allocating the bytes. */
    public static long utf8Length(String s) {
        long bytes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                bytes += 4; // supplementary code point (surrogate pair)
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    // --------------------------------------------------------- request bodies

    /** Build the {@code {"sql":..., "params":[...]}} body for a single statement. */
    public static String buildQueryBody(String sql, List<Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", sql);
        body.put("params", params == null ? List.of() : params);
        return Json.write(body);
    }

    /** Build the {@code [{sql, params}, ...]} array body for a proxy batch. */
    public static String buildBatchBody(List<D1Request> stmts) {
        return Json.write(batchArray(stmts));
    }

    /**
     * Build the {@code {"batch":[{sql, params}, ...]}} body for the REST
     * {@code /raw} first-class batch form, which D1 executes atomically (verified
     * live, DESIGN 9-2).
     */
    public static String buildRestBatchBody(List<D1Request> stmts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batch", batchArray(stmts));
        return Json.write(body);
    }

    private static List<Object> batchArray(List<D1Request> stmts) {
        List<Object> arr = new ArrayList<>(stmts.size());
        for (D1Request r : stmts) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("sql", r.sql());
            obj.put("params", r.params() == null ? List.of() : r.params());
            arr.add(obj);
        }
        return arr;
    }

    // ----------------------------------------------------------- response parse

    /**
     * Parse one result object (columns/rows/meta, in any of the accepted shapes)
     * into a {@link D1QueryResult}.
     */
    @SuppressWarnings("unchecked")
    public static D1QueryResult parseResult(Map<String, Object> resultObj, String bookmark) {
        List<String> columns;
        List<List<Object>> rows;

        Object resultsNode = resultObj.get("results");
        if (resultObj.containsKey("columns")) {
            // Normalized shape: {columns, rows, meta}
            columns = toStringList(resultObj.get("columns"));
            rows = toRows(resultObj.get("rows"));
        } else if (resultsNode instanceof Map) {
            // REST /raw shape: {results: {columns, rows}, meta}
            Map<String, Object> r = (Map<String, Object>) resultsNode;
            columns = toStringList(r.get("columns"));
            rows = toRows(r.get("rows"));
        } else if (resultsNode instanceof List) {
            // Array-of-objects fallback: {results: [{col: val}, ...]}
            List<Object> objs = (List<Object>) resultsNode;
            columns = deriveColumns(objs);
            rows = reshape(objs, columns);
        } else {
            columns = List.of();
            rows = List.of();
        }

        D1Meta meta = parseMeta(resultObj.get("meta"));
        return new D1QueryResult(columns, rows, meta, bookmark);
    }

    @SuppressWarnings("unchecked")
    public static D1Meta parseMeta(Object metaNode) {
        if (!(metaNode instanceof Map)) {
            return D1Meta.EMPTY;
        }
        Map<String, Object> m = (Map<String, Object>) metaNode;
        double sqlDurationMs = 0.0;
        Object timings = m.get("timings");
        if (timings instanceof Map) {
            sqlDurationMs = asDouble(((Map<String, Object>) timings).get("sql_duration_ms"));
        }
        // served_by_colo is the current field; fall back to the legacy single
        // served_by string if an older endpoint (e.g. miniflare) returns it.
        Object colo = m.get("served_by_colo");
        if (colo == null) {
            colo = m.get("served_by");
        }
        return new D1Meta(
                asLong(m.get("changes")),
                asLong(m.get("last_row_id")),
                asLong(m.get("rows_read")),
                asLong(m.get("rows_written")),
                asDouble(m.get("duration")),
                asLong(m.get("size_after")),
                Boolean.TRUE.equals(m.get("changed_db")),
                colo == null ? null : String.valueOf(colo),
                Boolean.TRUE.equals(m.get("served_by_primary")),
                m.get("served_by_region") == null ? null : String.valueOf(m.get("served_by_region")),
                sqlDurationMs);
    }

    // ------------------------------------------------------------- error map

    /** Convert a transport failure into a JDBC {@link SQLException}. */
    public static SQLException toSQLException(TransportException e) {
        String state = e.sqlState();
        int code = e.vendorCode();
        return new SQLException(e.getMessage(), state, code, e);
    }

    /** Build a {@link SQLException} from a D1 error message, inferring SQLState. */
    public static SQLException errorToSQLException(String message, int vendorCode) {
        return new SQLException(message, sqlStateFor(message), vendorCode);
    }

    /**
     * Best-effort mapping of SQLite/D1 error text to a SQLState class.
     * (DESIGN 4-5: constraint violations and syntax errors get SQLite-family states.)
     */
    public static String sqlStateFor(String message) {
        if (message == null) {
            return "HY000";
        }
        String m = message.toLowerCase();
        if (m.contains("unique") || m.contains("constraint") || m.contains("foreign key")
                || m.contains("not null") || m.contains("check constraint")) {
            return "23000"; // integrity constraint violation
        }
        if (m.contains("syntax error") || m.contains("no such") || m.contains("malformed")) {
            return "42000"; // syntax error or access rule violation
        }
        return "HY000"; // general error
    }

    // ------------------------------------------------------------- coercions

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object node) {
        if (!(node instanceof List)) {
            return List.of();
        }
        List<Object> raw = (List<Object>) node;
        List<String> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            out.add(o == null ? null : String.valueOf(o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> toRows(Object node) {
        if (!(node instanceof List)) {
            return List.of();
        }
        List<Object> raw = (List<Object>) node;
        List<List<Object>> out = new ArrayList<>(raw.size());
        for (Object rowNode : raw) {
            if (rowNode instanceof List) {
                out.add(normalizeRow((List<Object>) rowNode));
            } else {
                List<Object> single = new ArrayList<>(1);
                single.add(normalizeCell(rowNode));
                out.add(single);
            }
        }
        return out;
    }

    private static List<Object> normalizeRow(List<Object> row) {
        List<Object> out = new ArrayList<>(row.size());
        for (Object cell : row) {
            out.add(normalizeCell(cell));
        }
        return out;
    }

    /**
     * A BLOB arrives as a JSON array of unsigned bytes; convert it to {@code byte[]}
     * so the ResultSet can return it via {@code getBytes()}. Other cell types
     * (Long/Double/String/null) pass through unchanged.
     */
    @SuppressWarnings("unchecked")
    private static Object normalizeCell(Object cell) {
        if (cell instanceof List) {
            List<Object> list = (List<Object>) cell;
            byte[] bytes = new byte[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object b = list.get(i);
                if (!(b instanceof Number)) {
                    throw new TransportException(
                            "Malformed BLOB cell: element " + i + " is not numeric ("
                                    + (b == null ? "null" : b.getClass().getSimpleName()) + ")",
                            "22000", 0, null);
                }
                bytes[i] = (byte) (((Number) b).intValue() & 0xFF);
            }
            return bytes;
        }
        return cell;
    }

    @SuppressWarnings("unchecked")
    private static List<String> deriveColumns(List<Object> objs) {
        List<String> cols = new ArrayList<>();
        for (Object o : objs) {
            if (o instanceof Map) {
                for (Object k : ((Map<String, Object>) o).keySet()) {
                    String key = String.valueOf(k);
                    if (!cols.contains(key)) {
                        cols.add(key);
                    }
                }
            }
        }
        return cols;
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> reshape(List<Object> objs, List<String> columns) {
        List<List<Object>> rows = new ArrayList<>(objs.size());
        for (Object o : objs) {
            List<Object> row = new ArrayList<>(columns.size());
            Map<String, Object> map = (o instanceof Map) ? (Map<String, Object>) o : Map.of();
            for (String c : columns) {
                row.add(normalizeCell(map.get(c)));
            }
            rows.add(row);
        }
        return rows;
    }

    private static long asLong(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        return 0L;
    }

    private static double asDouble(Object o) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        return 0.0;
    }
}
