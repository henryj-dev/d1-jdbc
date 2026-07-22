package dev.mackerel.d1jdbc.transport;

/**
 * Result metadata returned by D1 alongside a query/execute response.
 *
 * <p>Maps the D1 {@code meta} object
 * ({@code {changes, last_row_id, rows_read, rows_written, duration, size_after,
 * changed_db, served_by}}) to JDBC concepts (see DESIGN 4-4):
 * <ul>
 *   <li>{@code changes} -> {@code executeUpdate()} / {@code getUpdateCount()}</li>
 *   <li>{@code lastRowId} -> {@code getGeneratedKeys()}</li>
 *   <li>{@code rowsRead/rowsWritten/duration} -> diagnostics (vendor extension)</li>
 * </ul>
 */
public record D1Meta(
        long changes,
        long lastRowId,
        long rowsRead,
        long rowsWritten,
        double duration,
        long sizeAfter,
        boolean changedDb,
        String servedBy) {

    public static final D1Meta EMPTY = new D1Meta(0, 0, 0, 0, 0.0, 0, false, null);
}
