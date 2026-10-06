# Streamlit Protobuf Editor

A Burp Suite Montoya extension for viewing and editing Streamlit WebSocket
messages (`BackMsg` and `ForwardMsg`) as human-readable Protobuf fields. It
uses a schema-free codec, so no `.proto` files are required.

> Use this extension only on systems you own or are explicitly authorized to
> test.

## Features

- Decodes binary Protobuf frames into editable text.
- Re-encodes edited fields and recalculates nested message lengths.
- Supports varints, 32-bit and 64-bit values, UTF-8 strings, raw bytes, and
  nested messages.
- Falls back to the original frame when an edit cannot be encoded.
- Works with Burp Suite Professional and Community Edition through the
  Montoya API.

## Install

1. Download `streamlit-protobuf-editor.jar` from the repository or a release.
2. In Burp Suite, open **Extensions > Installed > Add**.
3. Select **Java** as the extension type and choose the JAR.
4. Open a binary Streamlit message in the WebSockets history and select the
   **Streamlit Protobuf** tab.

## Editable format

```text
16: {
  1: {
    1: "$$ID-textinput-0"
    11: "user input"
  }
}
3: 150
```

| Syntax | Protobuf wire type |
| --- | --- |
| `N: 123` | Varint (0) |
| `N: i64:-5` | 64-bit (1) |
| `N: "text"` | Length-delimited UTF-8 string (2) |
| `N: hex:deadbeef` | Length-delimited bytes (2) |
| `N: { ... }` | Length-delimited nested message (2) |
| `N: i32:1234` | 32-bit (5) |

## Build

JDK 17 or newer is required.

```bash
./gradlew clean test jar
```

On Windows, use `gradlew.bat`. The resulting extension is written to
`build/libs/streamlit-protobuf-editor.jar`.

## Limitations

- Non-canonical Protobuf encodings may not round-trip byte for byte.
- A UTF-8 string that is also valid Protobuf may be displayed as a nested
  message. Use the `hex:` representation when necessary.
- The editor is enabled for any binary WebSocket payload that parses as
  Protobuf, not only Streamlit traffic.

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).
