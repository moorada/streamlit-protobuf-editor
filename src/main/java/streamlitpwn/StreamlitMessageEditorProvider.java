package streamlitpwn;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedWebSocketMessageEditor;
import burp.api.montoya.ui.editor.extension.WebSocketMessageEditorProvider;

/** Creates an independent editor instance for each Burp message editor. */
public final class StreamlitMessageEditorProvider implements WebSocketMessageEditorProvider {

    private final MontoyaApi api;

    public StreamlitMessageEditorProvider(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ExtensionProvidedWebSocketMessageEditor provideMessageEditor(
            EditorCreationContext creationContext) {
        return new StreamlitMessageEditor(api, creationContext);
    }
}
