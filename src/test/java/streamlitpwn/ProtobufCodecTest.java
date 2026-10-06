package streamlitpwn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProtobufCodecTest {

    @Test
    void roundTripsAllSupportedWireTypes() {
        String text = "1: 150\n"
                + "2: i64:-5\n"
                + "3: i32:1234\n"
                + "4: \"hello\\nworld\"\n"
                + "5: hex:00ff10\n"
                + "6: {\n  1: \"nested\"\n}\n";

        List<ProtobufCodec.Node> nodes = ProtobufCodec.parseText(text);
        byte[] encoded = ProtobufCodec.encode(nodes);
        List<ProtobufCodec.Node> decoded = ProtobufCodec.tryParseMessage(encoded);

        assertEquals(text, ProtobufCodec.dump(decoded));
        assertArrayEquals(encoded, ProtobufCodec.encode(decoded));
    }

    @Test
    void recalculatesLengthAfterEditingAString() {
        byte[] shortValue = ProtobufCodec.encode(ProtobufCodec.parseText("1: \"a\"\n"));
        byte[] longValue = ProtobufCodec.encode(ProtobufCodec.parseText("1: \"longer value\"\n"));

        assertEquals(3, shortValue.length);
        assertEquals(14, longValue.length);
        assertEquals("longer value",
                ProtobufCodec.tryParseMessage(longValue).get(0).strVal);
    }

    @Test
    void rejectsInvalidProtobufAndMalformedText() {
        assertNull(ProtobufCodec.tryParseMessage(new byte[] {(byte) 0x80}));
        assertNull(ProtobufCodec.tryParseMessage(new byte[] {0x0f}));
        assertThrows(IllegalArgumentException.class,
                () -> ProtobufCodec.parseText("not a field\n"));
    }
}
