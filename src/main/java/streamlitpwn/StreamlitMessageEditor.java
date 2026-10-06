package streamlitpwn;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.contextmenu.WebSocketMessage;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedWebSocketMessageEditor;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A custom message-editor tab ("Streamlit Protobuf") that renders a binary
 * WebSocket frame as editable protobuf text and re-encodes the user's edits
 * on the way out. Appears in Proxy intercept and in Repeater for any binary
 * WebSocket message that parses as protobuf.
 */
public class StreamlitMessageEditor implements ExtensionProvidedWebSocketMessageEditor {

    private final MontoyaApi api;
    private final JPanel panel;
    private final JTextArea textArea;
    private final JLabel status;

    private byte[] originalPayload = new byte[0];
    private String baseline = "";
    private boolean decodable = false;

    public StreamlitMessageEditor(MontoyaApi api, EditorCreationContext ctx) {
        this.api = api;
        this.textArea = new JTextArea();
        this.textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        this.status = new JLabel(" ");
        this.status.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));

        this.panel = new JPanel(new BorderLayout());
        this.panel.add(new JScrollPane(textArea), BorderLayout.CENTER);
        this.panel.add(status, BorderLayout.SOUTH);
    }

    @Override
    public boolean isEnabledFor(WebSocketMessage message) {
        try {
            byte[] p = message.payload().getBytes();
            List<ProtobufCodec.Node> tree = ProtobufCodec.tryParseMessage(p);
            return tree != null && !tree.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void setMessage(WebSocketMessage message) {
        originalPayload = message.payload().getBytes();
        try {
            List<ProtobufCodec.Node> tree = ProtobufCodec.tryParseMessage(originalPayload);
            if (tree != null) {
                baseline = ProtobufCodec.dump(tree);
                textArea.setText(baseline);
                textArea.setEditable(true);
                decodable = true;
                status.setText("Decoded " + originalPayload.length + " bytes \u2014 edit values and send.");
            } else {
                baseline = "hex:" + bytesToHex(originalPayload);
                textArea.setText(baseline);
                textArea.setEditable(false);
                decodable = false;
                status.setText("Not valid protobuf; showing raw hex (read-only).");
            }
        } catch (Exception e) {
            baseline = "hex:" + bytesToHex(originalPayload);
            textArea.setText(baseline);
            textArea.setEditable(false);
            decodable = false;
            status.setText("Decode error: " + e.getMessage());
        }
        textArea.setCaretPosition(0);
    }

    @Override
    public ByteArray getMessage() {
        if (!decodable) {
            return ByteArray.byteArray(originalPayload);
        }
        try {
            List<ProtobufCodec.Node> tree = ProtobufCodec.parseText(textArea.getText());
            byte[] encoded = ProtobufCodec.encode(tree);
            status.setText("Re-encoded " + encoded.length + " bytes.");
            return ByteArray.byteArray(encoded);
        } catch (Exception e) {
            // Never send garbage: fall back to the original frame and warn.
            status.setText("Edit not re-encodable (" + e.getMessage() + "); sending original frame.");
            api.logging().logToError("Streamlit editor re-encode failed: " + e.getMessage());
            return ByteArray.byteArray(originalPayload);
        }
    }

    @Override
    public boolean isModified() {
        return decodable && !textArea.getText().equals(baseline);
    }

    @Override
    public String caption() {
        return "Streamlit Protobuf";
    }

    @Override
    public Component uiComponent() {
        return panel;
    }

    @Override
    public Selection selectedData() {
        String sel = textArea.getSelectedText();
        if (sel == null || sel.isEmpty()) return null;
        return Selection.selection(ByteArray.byteArray(sel.getBytes(StandardCharsets.UTF_8)));
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }
}
