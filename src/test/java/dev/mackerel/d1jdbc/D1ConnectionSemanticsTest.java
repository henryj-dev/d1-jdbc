package dev.mackerel.d1jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mackerel.d1jdbc.testutil.MockTransport;
import dev.mackerel.d1jdbc.transport.Capabilities;
import dev.mackerel.d1jdbc.transport.D1Meta;
import dev.mackerel.d1jdbc.transport.D1QueryResult;

/** Transaction-buffering and generated-key semantics (DESIGN 5). */
class D1ConnectionSemanticsTest {

    private static D1JdbcUrl url() throws SQLException {
        return D1JdbcUrl.parse("jdbc:cloudflare-d1:proxy://host/base?token=t", null);
    }

    private static D1QueryResult insertMeta(long changes, long lastRowId) {
        return new D1QueryResult(List.of(), List.of(),
                new D1Meta(changes, lastRowId, 0, changes, 0.0, 0, true, null), null);
    }

    // -------------------------------------------------- autoCommit buffering

    @Test
    void manualCommitBuffersThenSendsOneBatchInOrder() throws SQLException {
        MockTransport t = new MockTransport();
        D1Connection c = new D1Connection(url(), t);
        c.setAutoCommit(false);

        Statement s = c.createStatement();
        s.executeUpdate("INSERT INTO t VALUES (1)");
        s.executeUpdate("INSERT INTO t VALUES (2)");

        assertTrue(t.queries.isEmpty(), "buffered writes are not sent immediately");
        assertTrue(t.batches.isEmpty(), "nothing sent before commit");

        c.commit();

        assertEquals(1, t.batches.size(), "commit sends exactly one batch");
        MockTransport.BatchCall bc = t.batches.get(0);
        assertEquals(2, bc.stmts().size());
        assertEquals("INSERT INTO t VALUES (1)", bc.stmts().get(0).sql(), "order preserved");
        assertEquals("INSERT INTO t VALUES (2)", bc.stmts().get(1).sql());
        c.close();
    }

    @Test
    void bufferedPreparedParamsAreCarriedIntoTheBatch() throws SQLException {
        MockTransport t = new MockTransport();
        D1Connection c = new D1Connection(url(), t);
        c.setAutoCommit(false);

        var ps = c.prepareStatement("INSERT INTO t(a) VALUES (?)");
        ps.setInt(1, 7);
        ps.executeUpdate();

        c.commit();

        MockTransport.BatchCall bc = t.batches.get(0);
        assertEquals(List.of(7L), bc.stmts().get(0).params(),
                "int param serialized to Long and buffered in order");
        c.close();
    }

    @Test
    void rollbackDiscardsBufferAndNeverBatches() throws SQLException {
        MockTransport t = new MockTransport();
        D1Connection c = new D1Connection(url(), t);
        c.setAutoCommit(false);

        c.createStatement().executeUpdate("INSERT INTO t VALUES (1)");
        c.rollback();

        assertTrue(t.batches.isEmpty(), "rollback discards -> batch never called");

        c.commit(); // empty buffer: still no batch
        assertTrue(t.batches.isEmpty());
        assertTrue(t.queries.isEmpty());
        c.close();
    }

    @Test
    void autoCommitSendsEachWriteImmediatelyAsAQuery() throws SQLException {
        MockTransport t = new MockTransport().setDefaultResult(insertMeta(1, 5));
        D1Connection c = new D1Connection(url(), t);
        // default autoCommit == true

        int n = c.createStatement().executeUpdate("INSERT INTO t VALUES (1)");
        assertEquals(1, n, "change count comes from meta.changes");
        assertEquals(1, t.queries.size(), "sent immediately as a single query");
        assertTrue(t.batches.isEmpty());
        c.close();
    }

    // ---------------------------------------------------- generated keys

    @Test
    void generatedKeysEmptyAfterNonInsert() throws SQLException {
        MockTransport t = new MockTransport().setDefaultResult(insertMeta(0, 0));
        D1Connection c = new D1Connection(url(), t);

        Statement s = c.createStatement();
        s.executeUpdate("UPDATE t SET a = a WHERE 1 = 0"); // changes=0
        ResultSet keys = s.getGeneratedKeys();
        assertFalse(keys.next(), "no generated key when nothing was inserted");
        c.close();
    }

    @Test
    void generatedKeysPopulatedAfterInsert() throws SQLException {
        MockTransport t = new MockTransport().setDefaultResult(insertMeta(1, 42));
        D1Connection c = new D1Connection(url(), t);

        Statement s = c.createStatement();
        s.executeUpdate("INSERT INTO t(a) VALUES (1)"); // changes=1, lastRowId=42
        ResultSet keys = s.getGeneratedKeys();
        assertTrue(keys.next(), "one generated key row");
        assertEquals(42L, keys.getLong(1));
        assertFalse(keys.next());
        c.close();
    }

    // -------------------------------------------- non-atomic-batch warning

    @Test
    void nonAtomicCommitOfMultipleStatementsRaisesWarning() throws SQLException {
        MockTransport t = new MockTransport()
                .withCapabilities(new Capabilities(false, false, "rest"));
        D1Connection c = new D1Connection(url(), t);
        c.setAutoCommit(false);

        Statement s = c.createStatement();
        s.executeUpdate("INSERT INTO t VALUES (1)");
        s.executeUpdate("INSERT INTO t VALUES (2)");
        c.commit();

        SQLWarning w = c.getWarnings();
        assertNotNull(w, "non-atomic multi-statement commit must warn");
        assertTrue(w.getMessage().toLowerCase().contains("atomic"),
                "warning explains the lack of atomicity: " + w.getMessage());
        c.close();
    }

    @Test
    void singleStatementCommitDoesNotWarnEvenWhenNonAtomic() throws SQLException {
        MockTransport t = new MockTransport()
                .withCapabilities(new Capabilities(false, false, "rest"));
        D1Connection c = new D1Connection(url(), t);
        c.setAutoCommit(false);

        c.createStatement().executeUpdate("INSERT INTO t VALUES (1)");
        c.commit();

        assertNull(c.getWarnings(), "a lone buffered statement is atomic by itself");
        c.close();
    }

    @Test
    void atomicTransportDoesNotWarnForMultipleStatements() throws SQLException {
        MockTransport t = new MockTransport()
                .withCapabilities(new Capabilities(true, true, "proxy"));
        D1Connection c = new D1Connection(url(), t);
        c.setAutoCommit(false);

        Statement s = c.createStatement();
        s.executeUpdate("INSERT INTO t VALUES (1)");
        s.executeUpdate("INSERT INTO t VALUES (2)");
        c.commit();

        assertNull(c.getWarnings(), "atomic batch transport needs no warning");
        c.close();
    }
}
