package dev.mackerel.d1jdbc.transport;

/**
 * Result metadata returned by D1 alongside a query/execute response.
 *
 * <p>Maps the D1 {@code meta} object to JDBC concepts (see DESIGN 4-4, and the
 * confirmed field list in DESIGN 9-2):
 * {@code {changes, last_row_id, rows_read, rows_written, duration, size_after,
 * changed_db, served_by_colo, served_by_primary, served_by_region,
 * timings:{sql_duration_ms}}}.
 * <ul>
 *   <li>{@code changes} -> {@code executeUpdate()} / {@code getUpdateCount()}</li>
 *   <li>{@code lastRowId} -> {@code getGeneratedKeys()}</li>
 *   <li>{@code rowsRead/rowsWritten/duration/sqlDurationMs/servedBy*} ->
 *       diagnostics (vendor extension)</li>
 * </ul>
 *
 * <p>The single legacy {@code served_by} string was replaced by
 * {@code served_by_colo}/{@code served_by_primary}/{@code served_by_region} in
 * the current D1 REST API.
 */
public record D1Meta(
        long changes,
        long lastRowId,
        long rowsRead,
        long rowsWritten,
        double duration,
        long sizeAfter,
        boolean changedDb,
        String servedByColo,
        boolean servedByPrimary,
        String servedByRegion,
        double sqlDurationMs) {

    public static final D1Meta EMPTY =
            new D1Meta(0, 0, 0, 0, 0.0, 0, false, null, false, null, 0.0);
}
