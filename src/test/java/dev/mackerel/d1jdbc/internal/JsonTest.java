package dev.mackerel.d1jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Unit tests for the hand-rolled {@link Json} reader/writer. */
class JsonTest {

    // ------------------------------------------------------------- round-trip

    @Test
    void parsesNestedObjectPreservingTypesAndOrder() {
        Object v = Json.parse("{\"a\":1,\"b\":[2,3],\"c\":{\"d\":true}}");
        assertInstanceOf(Map.class, v);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        assertEquals(List.of("a", "b", "c"), List.copyOf(m.keySet()), "insertion order preserved");
        assertEquals(1L, m.get("a"));
        assertEquals(List.of(2L, 3L), m.get("b"));
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) m.get("c");
        assertEquals(Boolean.TRUE, c.get("d"));
    }

    @Test
    void parsesTopLevelArrayOfMixedTypes() {
        List<Object> arr = Json.parseArray("[1,\"two\",null,false,3.5]");
        assertEquals(1L, arr.get(0));
        assertEquals("two", arr.get(1));
        assertNull(arr.get(2));
        assertEquals(Boolean.FALSE, arr.get(3));
        assertEquals(3.5, arr.get(4));
    }

    @Test
    void writeThenParseIsIdentityForNestedStructure() {
        Map<String, Object> obj = new java.util.LinkedHashMap<>();
        obj.put("sql", "SELECT * FROM t WHERE x = ?");
        obj.put("params", List.of(42L, "hi", true));
        String json = Json.write(obj);
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) Json.parse(json);
        assertEquals("SELECT * FROM t WHERE x = ?", back.get("sql"));
        assertEquals(List.of(42L, "hi", Boolean.TRUE), back.get("params"));
    }

    @Test
    void parsesEmptyObjectAndArray() {
        assertTrue(((Map<?, ?>) Json.parse("{}")).isEmpty());
        assertTrue(((List<?>) Json.parse("[]")).isEmpty());
    }

    // --------------------------------------------------------------- escapes

    @Test
    void writesStringEscapes() {
        assertEquals("\"a\\\"b\"", Json.write("a\"b"), "double-quote escaped");
        assertEquals("\"a\\\\b\"", Json.write("a\\b"), "backslash escaped");
        assertEquals("\"line1\\nline2\"", Json.write("line1\nline2"), "newline escaped");
        assertEquals("\"\\t\\r\\b\\f\"", Json.write("\t\r\b\f"), "control chars escaped");
    }

    @Test
    void escapesSubUnicodeControlCharAsUEscape() {
        // A US (0x1F) control char must be emitted as \u001f, not raw.
        assertEquals("\"\\u001f\"", Json.write("\u001f"));
    }

    @Test
    void parsesStringEscapesIncludingUnicode() {
        assertEquals("a\"b", Json.parse("\"a\\\"b\""));
        assertEquals("a\\b", Json.parse("\"a\\\\b\""));
        assertEquals("tab\there", Json.parse("\"tab\\there\""));
        assertEquals("A", Json.parse("\"\\u0041\""), "unicode escape decoded");
        assertEquals("/", Json.parse("\"\\/\""), "escaped solidus allowed");
    }

    @Test
    void stringWithUnicodeRoundTrips() {
        String original = "quote\" back\\ nl\n tab\t uA";
        assertEquals(original, Json.parse(Json.write(original)));
    }

    // --------------------------------------------------------------- numbers

    @Test
    void parsesIntegralAsLong() {
        assertEquals(0L, Json.parse("0"));
        assertEquals(42L, Json.parse("42"));
        assertEquals(-7L, Json.parse("-7"), "negative integer");
    }

    @Test
    void parsesDecimalAndExponentAsDouble() {
        assertEquals(3.14, Json.parse("3.14"));
        assertEquals(-2.5, Json.parse("-2.5"), "negative decimal");
        assertEquals(1000.0, Json.parse("1e3"), "exponent");
        assertEquals(0.25, Json.parse("2.5e-1"), "negative exponent");
    }

    @Test
    void parsesOutOfLongRangeNumberAsDouble() {
        Object v = Json.parse("99999999999999999999");
        assertInstanceOf(Double.class, v, "overflowing integer falls back to double");
    }

    // ------------------------------------------------------- literals

    @Test
    void parsesNullTrueFalse() {
        assertNull(Json.parse("null"));
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertEquals(Boolean.FALSE, Json.parse("false"));
    }

    // ------------------------------------------ unsigned byte-array emission

    @Test
    void emitsByteArrayAsUnsignedIntArray() {
        // 0, 127, 128, 255 must serialize as unsigned 0..255, not signed -128..127.
        byte[] bytes = new byte[] {0, 127, (byte) 128, (byte) 255};
        assertEquals("[0,127,128,255]", Json.write(bytes));
    }

    @Test
    void emitsFullUnsignedByteRange() {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        StringBuilder expected = new StringBuilder("[");
        for (int i = 0; i < 256; i++) {
            if (i > 0) {
                expected.append(',');
            }
            expected.append(i);
        }
        expected.append(']');
        assertEquals(expected.toString(), Json.write(all));
    }

    // --------------------------------------------------- malformed input

    @Test
    void unterminatedObjectThrowsJsonException() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{"));
    }

    @Test
    void unterminatedArrayThrowsJsonException() {
        assertThrows(Json.JsonException.class, () -> Json.parse("[1,2"));
    }

    @Test
    void trailingContentThrowsJsonException() {
        assertThrows(Json.JsonException.class, () -> Json.parse("1 2"));
    }

    @Test
    void badLiteralThrowsJsonExceptionNotNpe() {
        RuntimeException ex = assertThrows(Json.JsonException.class, () -> Json.parse("nul"));
        assertFalse(ex instanceof NullPointerException, "must not leak a raw NPE");
    }

    @Test
    void unterminatedStringThrowsJsonException() {
        assertThrows(Json.JsonException.class, () -> Json.parse("\"abc"));
    }

    @Test
    void invalidUnicodeEscapeThrowsJsonException() {
        assertThrows(Json.JsonException.class, () -> Json.parse("\"\\uZZZZ\""));
    }
}
