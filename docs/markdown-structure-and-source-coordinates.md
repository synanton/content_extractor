# Markdown Structure and Source Coordinates

`TextModalityAdapter` parses Markdown directly into `HEADING`, `PARAGRAPH`,
`LIST`, `LIST_ITEM`, and `CODE_BLOCK` elements. Heading levels and list nesting
levels are preserved, and list parent/child relationships use element IDs.
HTML comments, YAML front matter, and a bold `**Key:** value` metadata
preamble before the first level-2 heading are excluded from structural
elements; bold preamble fields are retained in document metadata.
Other text-like formats continue to use the existing Tika paragraph adapter.

## Source-coordinate decision

The current extraction contract does not expose source character offsets.
This change does not add offsets, and does not derive them by searching
normalized or flattened text.

If an optional extension is added later, it must define:

- coordinate unit (Unicode code points);
- inclusive start and exclusive end;
- coordinate space (original source bytes decoded as UTF-8);
- Unicode normalization behavior (none before indexing);
- line-ending transformation behavior (offsets in the original source, not
  normalized `\n` text);
- protobuf compatibility and contract-mirror synchronization.

Until then, callers must not claim exact P1 evidence spans from SCEP output.
