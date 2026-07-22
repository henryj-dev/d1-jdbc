package dev.mackerel.d1jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mackerel.d1jdbc.internal.D1Limits;
import dev.mackerel.d1jdbc.testutil.MockTransport;
import dev.mackerel.d1jdbc.transport.D1Meta;
import dev.mackerel.d1jdbc.transport.D1QueryResult;

/**
 * Client-side enforcement of the confirmed D1 platform limits (DESIGN 9-1):
 * per-value size, per-statement SQL length and parameter count, batch
 * chunking, and the commit atomicity ceiling.
 */
class D1LimitsGuardTest {

    private static D1Connection conn(MockTransport t) throws SQLException {
        return new D1Connection(
                D1JdbcUrl.parse("jdbc:cloudflare-d1:proxy://host/base?token=t", null), t);
    }

    private static D1QueryResult resultWithChanges(long changes, String bookmark) {
        return new D1QueryResult(List.of(), List.of(),
                new D1Meta(changes, 0, 0, changes, 0.0, 0, true, null, false, null, 0.0),
                bookmark);
    }

    // ------------------------------------------------ per-value size (2 MB)

    @Test
    void setStringOverTwoMegabytesThrowsDataTooLong() throws SQLException {
        try (var c = conn(new MockTransport())) {
            PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?)");
            String big = "a".repeat(D1Limits.MAX_VALUE_BYTES + 1);
            SQLException e = assertThrows(SQLException.class, () -> ps.setString(1, big));
            assertEquals("22001", e.getSQLState(), "data too long");
            assertTrue(e.getMessage().contains(String.valueOf(D1Limits.MAX_VALUE_BYTES)),
                    "message names the limit");
            assertTrue(e.getMessage().contains(String.valueOf(D1Limits.MAX_VALUE_BYTES + 1)),
                    "message names the actual size");
        }
    }

    @Test
    void setStringExactlyAtLimitIsAccepted() throws SQLException {
        try (var c = conn(new MockTransport())) {
            PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?)");
            assertDoesNotThrow(() -> ps.setString(1, "a".repeat(D1Limits.MAX_VALUE_BYTES)));
        }
    }

    @Test
    void setStringMeasuresUtf8BytesNotChars() throws SQLException {
        try (var c = conn(new MockTransport())) {
            PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?)");
            // "한" is 3 UTF-8 bytes: 700,000 chars = 2,100,000 bytes > 2,000,000.
            String big = "한".repeat(700_000);
            SQLException e = assertThrows(SQLException.class, () -> ps.setString(1, big));
            assertEquals("22001", e.getSQLState());
            assertTrue(e.getMessage().contains("2100000"), "size measured in UTF-8 bytes");
        }
    }

    @Test
    void setNStringOverLimitThrowsDataTooLong() throws SQLException {
        try (var c = conn(new MockTransport())) {
            PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?)");
            SQLException e = assertThrows(SQLException.class,
                    () -> ps.setNString(1, "a".repeat(D1Limits.MAX_VALUE_BYTES + 1)));
            assertEquals("22001", e.getSQLState());
        }
    }

    @Test
    void setBytesOverTwoMegabytesThrowsDataTooLong() throws SQLException {
        try (var c = conn(new MockTransport())) {
            PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?)");
            byte[] big = new byte[D1Limits.MAX_VALUE_BYTES + 1];
            SQLException e = assertThrows(SQLException.class, () -> ps.setBytes(1, big));
            assertEquals("22001", e.getSQLState());
            assertDoesNotThrow(() -> ps.setBytes(1, new byte[D1Limits.MAX_VALUE_BYTES]),
                    "exactly at the limit is fine");
        }
    }

    // ---------------------------------------- per-statement guards (execution)

    @Test
    void oneHundredOneBoundParamsThrowsAtExecution() throws SQLException {
        MockTransport t = new MockTransport();
        try (var c = conn(t)) {
            PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (1)");
            for (int i = 1; i <= D1Limits.MAX_BOUND_PARAMS + 1; i++) {
                ps.setInt(i, i);
            }
            SQLException e = assertThrows(SQLException.class, ps::executeUpdate);
            assertEquals("54000", e.getSQLState(), "program limit exceeded");
            assertTrue(e.getMessage().contains("101"), "message names the actual count");
            assertTrue(t.queries.isEmpty(), "nothing was sent to the transport");
        }
    }

    @Test
    void oneHundredBoundParamsIsFine() throws SQLException {
        MockTransport t = new MockTransport();
        try (var c = conn(t)) {
            PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (1)");
            for (int i = 1; i <= D1Limits.MAX_BOUND_PARAMS; i++) {
                ps.setInt(i, i);
            }
            assertDoesNotThrow(() -> {
                ps.executeUpdate();
            });
            assertEquals(1, t.queries.size());
            assertEquals(D1Limits.MAX_BOUND_PARAMS, t.queries.get(0).params().size());
        }
    }

    @Test
    void sqlOverOneHundredKilobytesThrows() throws SQLException {
        MockTransport t = new MockTransport();
        try (var c = conn(t)) {
            Statement s = c.createStatement();
            String big = "SELECT 1 -- " + "x".repeat(D1Limits.MAX_SQL_BYTES);
            SQLException e = assertThrows(SQLException.class, () -> s.execute(big));
            assertEquals("54000", e.getSQLState());
            assertTrue(e.getMessage().contains(String.valueOf(D1Limits.MAX_SQL_BYTES)),
                    "message names the limit");
            assertTrue(t.queries.isEmpty(), "nothing was sent to the transport");
        }
    }

    @Test
    void sqlExactlyAtLimitIsFine() throws SQLException {
        MockTransport t = new MockTransport();
        try (var c = conn(t)) {
            Statement s = c.createStatement();
            String prefix = "SELECT 1 -- ";
            String sql = prefix + "x".repeat(D1Limits.MAX_SQL_BYTES - prefix.length());
            assertDoesNotThrow(() -> s.execute(sql));
            assertEquals(1, t.queries.size());
        }
    }

    @Test
    void oversizedSqlIsRejectedFromEveryBatchElement() throws SQLException {
        MockTransport t = new MockTransport();
        try (var c = conn(t)) {
            Statement s = c.createStatement();
            s.addBatch("INSERT INTO t VALUES (1)");
            s.addBatch("SELECT 1 -- " + "x".repeat(D1Limits.MAX_SQL_BYTES));
            SQLException e = assertThrows(SQLException.class, s::executeBatch);
            assertEquals("54000", e.getSQLState());
            assertTrue(t.batches.isEmpty(), "no partial batch was sent");
        }
    }

    // ----------------------------------------------- executeBatch chunking

    @Test
    void executeBatchOfFifteenHundredSplitsIntoTwoChunks() throws SQLException {
        MockTransport t = new MockTransport()
                .setDefaultResult(resultWithChanges(1, "bk"));
        try (var c = conn(t)) {
            Statement s = c.createStatement();
            for (int i = 0; i < 1500; i++) {
                s.addBatch("INSERT INTO t VALUES (" + i + ")");
            }
            int[] counts = s.executeBatch();

            assertEquals(1500, counts.length, "one count per batched statement");
            for (int count : counts) {
                assertEquals(1, count, "per-statement count comes from meta.changes");
            }
            assertEquals(2, t.batches.size(), "1500 statements -> two transport batches");
            assertEquals(D1Limits.MAX_ATOMIC_BATCH_STATEMENTS, t.batches.get(0).stmts().size());
            assertEquals(500, t.batches.get(1).stmts().size());
            assertEquals("bk", t.batches.get(1).bookmark(),
                    "bookmark from chunk 1 is threaded into chunk 2");
        }
    }

    @Test
    void preparedExecuteLargeBatchChunksTheSameWay() throws SQLException {
        MockTransport t = new MockTransport()
                .setDefaultResult(resultWithChanges(1, null));
        try (var c = conn(t)) {
            PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?)");
            for (int i = 0; i < 1500; i++) {
                ps.setInt(1, i);
                ps.addBatch();
            }
            long[] counts = ps.executeLargeBatch();

            assertEquals(1500, counts.length);
            assertEquals(2, t.batches.size());
            assertEquals(D1Limits.MAX_ATOMIC_BATCH_STATEMENTS, t.batches.get(0).stmts().size());
            assertEquals(500, t.batches.get(1).stmts().size());
        }
    }

    @Test
    void batchAtOrBelowChunkLimitSendsSingleBatch() throws SQLException {
        MockTransport t = new MockTransport();
        try (var c = conn(t)) {
            Statement s = c.createStatement();
            for (int i = 0; i < D1Limits.MAX_ATOMIC_BATCH_STATEMENTS; i++) {
                s.addBatch("INSERT INTO t VALUES (" + i + ")");
            }
            int[] counts = s.executeBatch();
            assertEquals(D1Limits.MAX_ATOMIC_BATCH_STATEMENTS, counts.length);
            assertEquals(1, t.batches.size(), "at the limit: exactly one transport batch");
        }
    }

    // ------------------------------------------- commit() atomicity ceiling

    @Test
    void commitOverAThousandBufferedStatementsThrows() throws SQLException {
        MockTransport t = new MockTransport();
        try (var c = conn(t)) {
            c.setAutoCommit(false);
            Statement s = c.createStatement();
            for (int i = 0; i < D1Limits.MAX_ATOMIC_BATCH_STATEMENTS + 1; i++) {
                s.executeUpdate("INSERT INTO t VALUES (" + i + ")");
            }
            SQLException e = assertThrows(SQLException.class, c::commit);
            assertEquals("54000", e.getSQLState());
            assertTrue(e.getMessage().contains("1001"), "message names the buffer size");
            assertTrue(t.batches.isEmpty(), "nothing was sent; atomicity was not faked");
            c.rollback(); // buffer is preserved for the caller to discard
        }
    }

    @Test
    void commitOfExactlyAThousandBufferedStatementsIsFine() throws SQLException {
        MockTransport t = new MockTransport();
        try (var c = conn(t)) {
            c.setAutoCommit(false);
            Statement s = c.createStatement();
            for (int i = 0; i < D1Limits.MAX_ATOMIC_BATCH_STATEMENTS; i++) {
                s.executeUpdate("INSERT INTO t VALUES (" + i + ")");
            }
            assertDoesNotThrow(c::commit);
            assertEquals(1, t.batches.size(), "flushed as one atomic batch");
            assertEquals(D1Limits.MAX_ATOMIC_BATCH_STATEMENTS,
                    t.batches.get(0).stmts().size());
        }
    }

    @Test
    void bufferedStatementIsValidatedWhenBuffered() throws SQLException {
        MockTransport t = new MockTransport();
        try (var c = conn(t)) {
            c.setAutoCommit(false);
            Statement s = c.createStatement();
            SQLException e = assertThrows(SQLException.class,
                    () -> s.executeUpdate("SELECT 1 -- " + "x".repeat(D1Limits.MAX_SQL_BYTES)));
            assertEquals("54000", e.getSQLState());
        }
    }

    // ------------------------------------------------ DatabaseMetaData table

    @Test
    void databaseMetaDataReportsConfirmedLimits() throws SQLException {
        try (var c = conn(new MockTransport())) {
            DatabaseMetaData md = c.getMetaData();
            assertEquals(100_000, md.getMaxStatementLength());
            assertEquals(100, md.getMaxColumnsInTable());
            assertEquals(100, md.getMaxColumnsInSelect());
            assertEquals(100, md.getMaxColumnsInGroupBy());
            assertEquals(100, md.getMaxColumnsInOrderBy());
            assertEquals(100, md.getMaxColumnsInIndex());
            assertEquals(2_000_000, md.getMaxRowSize());
            assertTrue(md.doesMaxRowSizeIncludeBlobs());
            assertEquals(2_000_000, md.getMaxBinaryLiteralLength());
            assertEquals(2_000_000, md.getMaxCharLiteralLength());
            assertTrue(md.supportsBatchUpdates());
            assertFalse(md.supportsStoredProcedures());
            assertEquals(0, md.getMaxConnections(), "0 = unknown per JDBC convention");
            assertEquals(0, md.getMaxStatements(), "0 = unknown per JDBC convention");
        }
    }
}
