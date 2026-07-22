package dev.mackerel.d1jdbc.transport;

import java.util.List;

/**
 * A fully-materialized D1 result in {@code ROWS_AND_COLUMNS} form.
 *
 * <p>{@code columns} are the column labels (in order); {@code rows} is the row
 * data where each cell is one of the D1 runtime types
 * (Long / Double / String / byte[] / null). {@code meta} carries change counts
 * and the generated rowid; {@code bookmark} is the session commit token
 * ({@code x-d1-bookmark}) returned by this response, if any.
 */
public record D1QueryResult(
        List<String> columns,
        List<List<Object>> rows,
        D1Meta meta,
        String bookmark) {
}
