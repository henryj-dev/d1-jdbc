package dev.mackerel.d1jdbc.testutil;

import dev.mackerel.d1jdbc.transport.Capabilities;
import dev.mackerel.d1jdbc.transport.D1Meta;
import dev.mackerel.d1jdbc.transport.D1QueryResult;
import dev.mackerel.d1jdbc.transport.D1Request;
import dev.mackerel.d1jdbc.transport.D1Transport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * An offline {@link D1Transport} for tests. It never touches the network: it
 * <em>records</em> every {@link #query} and {@link #batch} it is handed and
 * returns caller-supplied canned {@link D1QueryResult}s, so Connection /
 * Statement / ResultSet behavior can be driven and asserted without D1.
 *
 * <p>Results are served from a FIFO queue ({@link #enqueue}); when the queue is
 * empty an empty default result (or {@link #setDefaultResult a configured one})
 * is returned. All recorded calls are exposed for assertions.
 */
public final class MockTransport implements D1Transport {

    /** One recorded {@link #query} invocation. */
    public record QueryCall(String sql, List<Object> params, String bookmark) {
    }

    /** One recorded {@link #batch} invocation. */
    public record BatchCall(List<D1Request> stmts, String bookmark) {
    }

    public final List<QueryCall> queries = new ArrayList<>();
    public final List<BatchCall> batches = new ArrayList<>();
    public boolean closed = false;

    private Capabilities capabilities = new Capabilities(true, true, "mock");
    private final Deque<D1QueryResult> queued = new ArrayDeque<>();
    private D1QueryResult defaultResult =
            new D1QueryResult(List.of(), List.of(), D1Meta.EMPTY, null);

    // ------------------------------------------------------------- configure

    public MockTransport withCapabilities(Capabilities capabilities) {
        this.capabilities = capabilities;
        return this;
    }

    /** Queue a result to be returned by the next {@link #query}/{@link #batch} element. */
    public MockTransport enqueue(D1QueryResult result) {
        queued.add(result);
        return this;
    }

    /** Set the fallback result returned when the queue is empty. */
    public MockTransport setDefaultResult(D1QueryResult result) {
        this.defaultResult = result;
        return this;
    }

    private D1QueryResult nextResult() {
        return queued.isEmpty() ? defaultResult : queued.poll();
    }

    // ------------------------------------------------------------- transport

    @Override
    public D1QueryResult query(String sql, List<Object> params, String bookmark) {
        queries.add(new QueryCall(sql, params == null ? null : new ArrayList<>(params), bookmark));
        return nextResult();
    }

    @Override
    public List<D1QueryResult> batch(List<D1Request> stmts, String bookmark) {
        batches.add(new BatchCall(new ArrayList<>(stmts), bookmark));
        List<D1QueryResult> out = new ArrayList<>(stmts.size());
        for (int i = 0; i < stmts.size(); i++) {
            out.add(nextResult());
        }
        return out;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public void close() {
        closed = true;
    }
}
