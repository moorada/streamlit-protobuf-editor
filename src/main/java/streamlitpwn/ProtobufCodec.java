package streamlitpwn;

import java.io.ByteArrayOutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-contained generic Protobuf wire-format codec.
 * No .proto definitions required: it decodes the raw wire format into an
 * editable tree, re-encodes it (recomputing every length prefix), and
 * converts to/from a human-editable text form. Used to inspect and tamper
 * with Streamlit BackMsg / ForwardMsg WebSocket frames.
 *
 * Algorithm validated for byte-identical round-trips before porting to Java.
 */
public final class ProtobufCodec {

    public enum Kind { VARINT, I64, I32, STR, BYTES, MSG }

    public static final class Node {
        public int field;
        public Kind kind;
        public long intVal;      // VARINT / I64 / I32
        public String strVal;    // STR
        public byte[] bytesVal;  // BYTES
        public List<Node> children; // MSG

        Node(int field, Kind kind) { this.field = field; this.kind = kind; }
    }

    private ProtobufCodec() {}

    // ---------- varint ----------
    private static long readVarint(byte[] b, int[] pos) {
        long result = 0; int shift = 0;
        while (true) {
            if (pos[0] >= b.length) throw new IndexOutOfBoundsException("varint truncated");
            int x = b[pos[0]++] & 0xff;
            result |= ((long) (x & 0x7f)) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
            if (shift > 63) throw new IllegalArgumentException("varint too long");
        }
        return result;
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        long v = value;
        while (true) {
            int b = (int) (v & 0x7f);
            v >>>= 7;
            if (v != 0) out.write(b | 0x80); else { out.write(b); break; }
        }
    }

    private static void writeTag(ByteArrayOutputStream out, int field, int wire) {
        writeVarint(out, ((long) field << 3) | wire);
    }

    // ---------- decode: bytes -> tree (null if not valid protobuf) ----------
    public static List<Node> tryParseMessage(byte[] b) {
        if (b == null || b.length == 0) return null;
        List<Node> nodes = new ArrayList<>();
        int[] pos = {0};
        try {
            while (pos[0] < b.length) {
                long tag = readVarint(b, pos);
                int field = (int) (tag >>> 3);
                int wt = (int) (tag & 7);
                if (field == 0) return null;
                switch (wt) {
                    case 0: {
                        long v = readVarint(b, pos);
                        Node n = new Node(field, Kind.VARINT); n.intVal = v; nodes.add(n);
                        break;
                    }
                    case 1: {
                        if (pos[0] + 8 > b.length) return null;
                        long v = ByteBuffer.wrap(b, pos[0], 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
                        pos[0] += 8;
                        Node n = new Node(field, Kind.I64); n.intVal = v; nodes.add(n);
                        break;
                    }
                    case 5: {
                        if (pos[0] + 4 > b.length) return null;
                        int v = ByteBuffer.wrap(b, pos[0], 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                        pos[0] += 4;
                        Node n = new Node(field, Kind.I32); n.intVal = v; nodes.add(n);
                        break;
                    }
                    case 2: {
                        long lenL = readVarint(b, pos);
                        int len = (int) lenL;
                        if (len < 0 || pos[0] + len > b.length) return null;
                        byte[] payload = new byte[len];
                        System.arraycopy(b, pos[0], payload, 0, len);
                        pos[0] += len;
                        List<Node> sub = tryParseMessage(payload);
                        if (sub != null) {
                            Node n = new Node(field, Kind.MSG); n.children = sub; nodes.add(n);
                        } else {
                            String s = asPrintableUtf8(payload);
                            if (s != null) {
                                Node n = new Node(field, Kind.STR); n.strVal = s; nodes.add(n);
                            } else {
                                Node n = new Node(field, Kind.BYTES); n.bytesVal = payload; nodes.add(n);
                            }
                        }
                        break;
                    }
                    default:
                        return null; // groups (3,4) unsupported
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return nodes;
    }

    private static String asPrintableUtf8(byte[] payload) {
        if (payload.length == 0) return "";
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder();
        dec.onMalformedInput(CodingErrorAction.REPORT);
        dec.onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = dec.decode(ByteBuffer.wrap(payload));
            String s = cb.toString();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 32 && c != '\t' && c != '\n' && c != '\r') return null;
            }
            return s;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    // ---------- encode: tree -> bytes ----------
    public static byte[] encode(List<Node> nodes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Node nd : nodes) {
            switch (nd.kind) {
                case VARINT: writeTag(out, nd.field, 0); writeVarint(out, nd.intVal); break;
                case I64: {
                    writeTag(out, nd.field, 1);
                    ByteBuffer bb = ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    bb.putLong(nd.intVal); out.write(bb.array(), 0, 8); break;
                }
                case I32: {
                    writeTag(out, nd.field, 5);
                    ByteBuffer bb = ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    bb.putInt((int) nd.intVal); out.write(bb.array(), 0, 4); break;
                }
                case STR: {
                    byte[] s = nd.strVal.getBytes(StandardCharsets.UTF_8);
                    writeTag(out, nd.field, 2); writeVarint(out, s.length); out.write(s, 0, s.length); break;
                }
                case BYTES: {
                    writeTag(out, nd.field, 2); writeVarint(out, nd.bytesVal.length);
                    out.write(nd.bytesVal, 0, nd.bytesVal.length); break;
                }
                case MSG: {
                    byte[] inner = encode(nd.children);
                    writeTag(out, nd.field, 2); writeVarint(out, inner.length);
                    out.write(inner, 0, inner.length); break;
                }
            }
        }
        return out.toByteArray();
    }

    // ---------- tree -> editable text ----------
    public static String dump(List<Node> nodes) {
        StringBuilder sb = new StringBuilder();
        dump(nodes, 0, sb);
        return sb.toString();
    }

    private static void dump(List<Node> nodes, int indent, StringBuilder sb) {
        String pad = "  ".repeat(indent);
        for (Node nd : nodes) {
            switch (nd.kind) {
                case MSG:
                    sb.append(pad).append(nd.field).append(": {\n");
                    dump(nd.children, indent + 1, sb);
                    sb.append(pad).append("}\n");
                    break;
                case STR:
                    sb.append(pad).append(nd.field).append(": \"").append(escape(nd.strVal)).append("\"\n");
                    break;
                case BYTES:
                    sb.append(pad).append(nd.field).append(": hex:").append(toHex(nd.bytesVal)).append('\n');
                    break;
                case I64:
                    sb.append(pad).append(nd.field).append(": i64:").append(nd.intVal).append('\n');
                    break;
                case I32:
                    sb.append(pad).append(nd.field).append(": i32:").append(nd.intVal).append('\n');
                    break;
                case VARINT:
                    sb.append(pad).append(nd.field).append(": ").append(nd.intVal).append('\n');
                    break;
            }
        }
    }

    // ---------- editable text -> tree ----------
    public static List<Node> parseText(String text) {
        String[] lines = text.split("\n", -1);
        int[] idx = {0};
        return parseBlock(lines, idx, false);
    }

    private static List<Node> parseBlock(String[] lines, int[] idx, boolean nested) {
        List<Node> nodes = new ArrayList<>();
        while (idx[0] < lines.length) {
            String raw = lines[idx[0]];
            String line = raw.trim();
            if (line.isEmpty()) { idx[0]++; continue; }
            if (line.equals("}")) { idx[0]++; return nodes; }

            int colon = line.indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("Malformed line: " + raw);
            int field;
            try { field = Integer.parseInt(line.substring(0, colon).trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Bad field number: " + raw); }
            String rest = line.substring(colon + 1).trim();

            if (rest.equals("{")) {
                idx[0]++;
                List<Node> sub = parseBlock(lines, idx, true);
                Node n = new Node(field, Kind.MSG); n.children = sub; nodes.add(n);
                continue;
            }
            if (rest.length() >= 2 && rest.startsWith("\"") && rest.endsWith("\"")) {
                Node n = new Node(field, Kind.STR);
                n.strVal = unescape(rest.substring(1, rest.length() - 1)); nodes.add(n);
            } else if (rest.startsWith("hex:")) {
                Node n = new Node(field, Kind.BYTES); n.bytesVal = fromHex(rest.substring(4).trim()); nodes.add(n);
            } else if (rest.startsWith("i64:")) {
                Node n = new Node(field, Kind.I64); n.intVal = Long.parseLong(rest.substring(4).trim()); nodes.add(n);
            } else if (rest.startsWith("i32:")) {
                Node n = new Node(field, Kind.I32); n.intVal = Long.parseLong(rest.substring(4).trim()); nodes.add(n);
            } else {
                Node n = new Node(field, Kind.VARINT); n.intVal = Long.parseLong(rest.trim()); nodes.add(n);
            }
            idx[0]++;
        }
        return nodes;
    }

    // ---------- helpers ----------
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '\\': sb.append('\\'); break;
                    case '"': sb.append('"'); break;
                    default: sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }
    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }
    private static byte[] fromHex(String s) {
        s = s.replaceAll("\\s", "");
        if (s.length() % 2 != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
