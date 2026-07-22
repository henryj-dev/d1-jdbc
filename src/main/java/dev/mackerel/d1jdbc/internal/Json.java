package dev.mackerel.d1jdbc.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer.
 *
 * <p>The D1 wire contract uses only tiny, constrained JSON shapes
 * ({@code {sql, params:[...]}}, batch arrays, and
 * {@code {columns:[...], rows:[[...]]}} + meta), so a small hand-rolled
 * implementation keeps the driver classpath-clean (zero runtime deps).
 *
 * <p>Parse maps JSON values to: {@link Map} (object, insertion-ordered),
 * {@link List} (array), {@link String}, {@link Long} (integral number),
 * {@link Double} (fractional/exponent number), {@link Boolean}, or {@code null}.
 *
 * <p>Write accepts the same Java types plus arrays ({@code int[]}, {@code long[]},
 * {@code byte[]} -> array of unsigned 0..255, {@code Object[]}) and any other
 * {@link Number}.
 */
public final class Json {

    private Json() {
    }

    // ----------------------------------------------------------------- parse

    public static Object parse(String input) {
        Parser p = new Parser(input);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw new JsonException("Trailing content at index " + p.pos);
        }
        return value;
    }

    /** Parse and require a JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String input) {
        Object v = parse(input);
        if (!(v instanceof Map)) {
            throw new JsonException("Expected JSON object, got " + typeName(v));
        }
        return (Map<String, Object>) v;
    }

    /** Parse and require a JSON array. */
    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String input) {
        Object v = parse(input);
        if (!(v instanceof List)) {
            throw new JsonException("Expected JSON array, got " + typeName(v));
        }
        return (List<Object>) v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw new JsonException("Unexpected end of input");
            }
            return s.charAt(pos);
        }

        Object readValue() {
            skipWs();
            char c = peek();
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                case 'f':
                    return readBoolean();
                case 'n':
                    return readNull();
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw new JsonException("Unexpected character '" + c + "' at index " + pos);
            }
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw new JsonException("Expected string key at index " + pos);
                }
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw new JsonException("Expected ',' or '}' at index " + pos);
                }
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object value = readValue();
                list.add(value);
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new JsonException("Expected ',' or ']' at index " + pos);
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new JsonException("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new JsonException("Unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw new JsonException("Invalid unicode escape");
                            }
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException nfe) {
                                throw new JsonException("Invalid unicode escape \\u" + hex);
                            }
                            break;
                        default:
                            throw new JsonException("Invalid escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object readNumber() {
            int start = pos;
            boolean fractional = false;
            if (peek() == '-') {
                pos++;
            }
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fractional = true;
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (fractional) {
                return Double.parseDouble(num);
            }
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                // Out of long range: fall back to double so parsing still succeeds.
                return Double.parseDouble(num);
            }
        }

        Boolean readBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new JsonException("Invalid literal at index " + pos);
        }

        Object readNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new JsonException("Invalid literal at index " + pos);
        }

        void expect(char c) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw new JsonException("Expected '" + c + "' at index " + pos);
            }
            pos++;
        }
    }

    // ----------------------------------------------------------------- write

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("Cannot serialize non-finite number: " + d);
            }
            sb.append(value.toString());
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof Map) {
            writeObject(sb, (Map<?, ?>) value);
        } else if (value instanceof List) {
            writeArray(sb, (List<?>) value);
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            sb.append('[');
            for (int i = 0; i < bytes.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(bytes[i] & 0xFF);
            }
            sb.append(']');
        } else if (value instanceof int[]) {
            int[] arr = (int[]) value;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(arr[i]);
            }
            sb.append(']');
        } else if (value instanceof long[]) {
            long[] arr = (long[]) value;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(arr[i]);
            }
            sb.append(']');
        } else if (value instanceof Object[]) {
            Object[] arr = (Object[]) value;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, arr[i]);
            }
            sb.append(']');
        } else {
            throw new JsonException("Cannot serialize type " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        boolean first = true;
        for (Object o : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, o);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static String typeName(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    /** Unchecked exception thrown on malformed JSON. */
    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }
}
