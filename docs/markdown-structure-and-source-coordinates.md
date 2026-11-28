# Markdown Structure and Source Coordinates

`TextModalityAdapter` parses Markdown directly into `HEADING`, `PARAGRAPH`,
`LIST`, `LIST_ITEM`, and `CODE_BLOCK` elements. Heading levels and list nesting
levels are preserved, and list parent/child relationships use element IDs.
HTML comments, YAML front matter, and a bold `**Key:** value` metadata
preamble before the first level-2 heading are excluded from structural
elements; bold preamble fields are retained in document metadata.
Other text-like formats continue to use the existing Tika paragraph adapter.

## Coordinate spaces

Markdown now exposes two separate coordinate spaces:

- **Original-source spans** — verified half-open UTF-8 byte offsets into the immutable source bytes,
  emitted as `SourceSpan`/`TextRange` evidence with no normalization.
- **Canonical-text ranges** — half-open UTF-16 code-unit ranges into the normalized structural
  projection, with NFC and LF semantics. See
  [`../doc/canonical-text-evidence.md`](../doc/canonical-text-evidence.md).

Offsets are never reconstructed by searching normalized or flattened text. Consumers must not
translate original-source spans into canonical offsets.

Canonical text includes the Markdown metadata preamble and comments as non-semantic content, so the
book container can start at canonical offset zero without injecting metadata. Those preamble
surfaces are not structural elements and must not become mentions.
