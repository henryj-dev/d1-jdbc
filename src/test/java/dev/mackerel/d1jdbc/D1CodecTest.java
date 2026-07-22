package dev.mackerel.d1jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.mackerel.d1jdbc.internal.Json;
import dev.mackerel.d1jdbc.transport.D1Meta;
import dev.mackerel.d1jdbc.transport.D1QueryResult;
import dev.mackerel.d1jdbc.transport.D1Request;
import dev.mackerel.d1jdbc.transport.TransportException;

/** Request-body building and response-envelope parsing (DESIGN 4-1..4-4). */
class D1CodecTest {

    // --------------------------------------------------------- request bodies

    @Test
    void buildsSingleQueryBodyWithSqlThenParams() {
        String body = D1Codec.buildQueryBody("SELECT ?", List.of(1L, "x"));
        assertEquals("{\"sql\":\"SELECT ?\",\"params\":[1,\"x\"]}", body);
    }

    @Test
    void buildsQueryBodyWithEmptyParamsWhenNull() {
        assertEquals("{\"sql\":\"SELECT 1\",\"params\":[]}",
                D1Codec.buildQueryBody("SELECT 1", null));
    }

    @Test
    void buildsQueryBodyEmittingBlobParamAsUnsignedIntArray() {
        String body = D1Codec.buildQueryBody(
                "INSERT INTO t VALUES (?)", List.of(new byte[] {0, (byte) 255}));
        assertEquals("{\"sql\":\"INSERT INTO t VALUES (?)\",\"params\":[[0,255]]}", body);
    }

    @Test
    void buildsBatchArrayBodyPreservingOrder() {
        String body = D1Codec.buildBatchBody(List.of(
                new D1Request("A", List.of(1L)),
                new D1Request("B", List.of("y"))));
        assertEquals("[{\"sql\":\"A\",\"params\":[1]},{\"sql\":\"B\",\"params\":[\"y\"]}]", body);
    }

    // --------------------------------------------------------- response parse

    @Test
    void parsesRestRawEnvelopeShape() {
        // REST /raw: {results:{columns, rows}, meta}
        Map<String, Object> obj = Json.parseObject(
                "{\"results\":{\"columns\":[\"id\",\"name\"],"
                        + "\"rows\":[[1,\"alice\"],[2,\"bob\"]]},"
                        + "\"meta\":{\"changes\":0,\"last_row_id\":0}}");
        D1QueryResult r = D1Codec.parseResult(obj, "bm-1");
        assertEquals(List.of("id", "name"), r.columns());
        assertEquals(2, r.rows().size());
        assertEquals(1L, r.rows().get(0).get(0));
        assertEquals("alice", r.rows().get(0).get(1));
        assertEquals("bob", r.rows().get(1).get(1));
        assertEquals("bm-1", r.bookmark());
    }

    @Test
    void parsesNormalizedProxyShape() {
        // Proxy: {columns, rows, meta}
        Map<String, Object> obj = Json.parseObject(
                "{\"columns\":[\"n\"],\"rows\":[[3.5]],"
                        + "\"meta\":{\"changes\":0,\"last_row_id\":0}}");
        D1QueryResult r = D1Codec.parseResult(obj, null);
        assertEquals(List.of("n"), r.columns());
        assertEquals(3.5, r.rows().get(0).get(0));
    }

    @Test
    void parsesBlobCellIntArrayIntoByteArray() {
        Map<String, Object> obj = Json.parseObject(
                "{\"columns\":[\"data\"],\"rows\":[[[104,105,255]]]}");
        D1QueryResult r = D1Codec.parseResult(obj, null);
        Object cell = r.rows().get(0).get(0);
        assertInstanceOf(byte[].class, cell, "a JSON int array cell becomes a BLOB byte[]");
        assertArrayEquals(new byte[] {104, 105, (byte) 255}, (byte[]) cell);
    }

    @Test
    void malformedBlobCellRaisesTransportExceptionNotClassCast() {
        // A non-numeric element inside a BLOB array must be a clean, typed failure
        // (not a leaked ClassCastException).
        Map<String, Object> obj = Json.parseObject(
                "{\"columns\":[\"data\"],\"rows\":[[[104,\"oops\",106]]]}");
        RuntimeException ex = assertThrows(TransportException.class,
                () -> D1Codec.parseResult(obj, null));
        assertTrue(ex.getMessage().contains("BLOB"), "message names the malformed BLOB");
        // At the JDBC surface this typed failure becomes a SQLException.
        assertInstanceOf(java.sql.SQLException.class,
                D1Codec.toSQLException((TransportException) ex));
    }

    @Test
    void parsesMetaChangesAndLastRowId() {
        D1Meta meta = D1Codec.parseMeta(Json.parseObject(
                "{\"changes\":3,\"last_row_id\":99,\"rows_written\":3,"
                        + "\"duration\":1.25,\"served_by\":\"miniflare\"}"));
        assertEquals(3L, meta.changes());
        assertEquals(99L, meta.lastRowId());
        assertEquals(3L, meta.rowsWritten());
        assertEquals(1.25, meta.duration());
        assertEquals("miniflare", meta.servedBy());
    }

    @Test
    void missingMetaYieldsEmpty() {
        D1QueryResult r = D1Codec.parseResult(
                Json.parseObject("{\"columns\":[],\"rows\":[]}"), null);
        assertEquals(0L, r.meta().changes());
        assertEquals(0L, r.meta().lastRowId());
    }

    // -------------------------------------------------------------- error map

    @Test
    void constraintErrorTextMapsToIntegrityViolationSqlState() {
        assertEquals("23000", D1Codec.sqlStateFor("UNIQUE constraint failed: t.id"));
    }

    @Test
    void syntaxErrorTextMapsToSyntaxSqlState() {
        assertEquals("42000", D1Codec.sqlStateFor("near \"SELCT\": syntax error"));
    }
}
