package dev.mackerel.d1jdbc.transport;

import java.util.List;

/**
 * Transport-agnostic contract for reaching a D1 database.
 *
 * <p>Two implementations exist: {@link RestTransport} (public Cloudflare REST
 * API, no deployment) and {@link ProxyTransport} (self-deployed Worker, native
 * atomic batches). Both reuse {@link dev.mackerel.d1jdbc.D1Codec} to build
 * requests and parse the {@code ROWS_AND_COLUMNS} responses.
 *
 * <p>Parameter values passed here are already serialized to the D1-allowed set
 * (Long / Double / String / null / byte[]).
 */
public interface D1Transport {

    /**
     * Execute a single statement.
     *
     * @param sql      SQL text with positional {@code ?} placeholders
     * @param params   ordered, pre-serialized parameter values
     * @param bookmark the session commit token to send, or {@code null}
     * @return the materialized result plus meta and the returned bookmark
     */
    D1QueryResult query(String sql, List<Object> params, String bookmark);

    /**
     * Execute a list of statements as a batch. On the proxy transport this is a
     * native atomic {@code db.batch()}; on REST it is best-effort.
     *
     * @param stmts    the statements, in order
     * @param bookmark the session commit token to send, or {@code null}
     * @return one result per statement, in order
     */
    List<D1QueryResult> batch(List<D1Request> stmts, String bookmark);

    /** Capabilities this transport advertises. */
    Capabilities capabilities();

    /** Release any transport resources. */
    void close();
}
