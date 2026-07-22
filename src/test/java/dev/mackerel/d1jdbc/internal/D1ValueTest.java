package dev.mackerel.d1jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;

import org.junit.jupiter.api.Test;

/** Parameter serialization rules from DESIGN 4-2 ({@link D1Value#serialize}). */
class D1ValueTest {

    @Test
    void booleanBecomesOneOrZeroLong() throws SQLException {
        assertEquals(1L, D1Value.serialize(Boolean.TRUE));
        assertEquals(0L, D1Value.serialize(Boolean.FALSE));
    }

    @Test
    void integralTypesBecomeLong() throws SQLException {
        assertEquals(5L, D1Value.serialize((byte) 5));
        assertEquals(5L, D1Value.serialize((short) 5));
        assertEquals(5L, D1Value.serialize(5));
        assertEquals(5L, D1Value.serialize(5L));
        assertInstanceOf(Long.class, D1Value.serialize(5));
    }

    @Test
    void floatingTypesBecomeDouble() throws SQLException {
        assertEquals(1.5, D1Value.serialize(1.5f));
        assertEquals(1.5, D1Value.serialize(1.5d));
        assertInstanceOf(Double.class, D1Value.serialize(1.5f));
    }

    @Test
    void byteArrayPassesThroughUnchangedThenJsonEmitsUnsignedIntArray() throws SQLException {
        byte[] in = new byte[] {0, (byte) 200, (byte) 255};
        Object out = D1Value.serialize(in);
        assertSame(in, out, "byte[] is preserved as byte[] for the codec");
        // The unsigned 0..255 emission happens when the codec writes JSON.
        assertEquals("[0,200,255]", Json.write((byte[]) out));
    }

    @Test
    void nullSerializesToNull() throws SQLException {
        assertNull(D1Value.serialize(null));
    }

    @Test
    void bigDecimalBecomesPlainString() throws SQLException {
        // toPlainString preserves scale/precision (no exponent, trailing zeros kept).
        assertEquals("123.4500", D1Value.serialize(new BigDecimal("123.4500")));
        assertEquals("1000", D1Value.serialize(new BigDecimal("1E3")));
    }

    @Test
    void bigIntegerBecomesString() throws SQLException {
        assertEquals("99999999999999999999",
                D1Value.serialize(new BigInteger("99999999999999999999")));
    }

    @Test
    void stringPassesThrough() throws SQLException {
        assertEquals("hello", D1Value.serialize("hello"));
    }

    @Test
    void sqlDateBecomesIsoDateString() throws SQLException {
        assertEquals("2026-07-22", D1Value.serialize(Date.valueOf("2026-07-22")));
    }

    @Test
    void sqlTimeBecomesHmsString() throws SQLException {
        assertEquals("13:45:30", D1Value.serialize(Time.valueOf("13:45:30")));
    }

    @Test
    void sqlTimestampBecomesSqliteDatetimeString() throws SQLException {
        assertEquals("2026-07-22 13:45:30.123",
                D1Value.serialize(Timestamp.valueOf("2026-07-22 13:45:30.123")));
    }

    @Test
    void unsupportedTypeRaisesSqlException() {
        SQLException ex = assertThrows(SQLException.class,
                () -> D1Value.serialize(new Object()));
        assertEquals("22023", ex.getSQLState(), "unsupported type maps to a data-error SQLState");
    }

    @Test
    void plainUtilDateIsUnsupported() {
        // A non-JDBC java.util.Date has no fixed wire convention -> rejected.
        assertThrows(SQLException.class,
                () -> D1Value.serialize(new java.util.Date(0L)));
    }
}
