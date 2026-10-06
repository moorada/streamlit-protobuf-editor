package streamlitpwn;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

/** Burp Suite entry point for the Streamlit Protobuf message editor. */
public final class StreamlitBurpExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Streamlit Protobuf Editor");
        api.userInterface().registerWebSocketMessageEditorProvider(
                new StreamlitMessageEditorProvider(api));
        api.logging().logToOutput("Streamlit Protobuf Editor loaded.");
    }
}
