# Markdown Adapter Parity with the Platform Parser

**Status:** Implementation note for content_extractor issue #21.

## Structural comparison

| Structure | Platform Markdown parser | Content Extractor Markdown adapter | Resolution |
|---|---|---|---|
| Headings | Heading nodes with level | `HEADING` with level | Parity |
| Paragraphs | Paragraph blocks | `PARAGRAPH` | Parity |
| Lists | Flat list block | `LIST` + nested `LIST_ITEM` with child IDs | Content Extractor richer |
| Code | Code block | `CODE_BLOCK` with fenced code text | Parity |
| Tables | One `TABLE` element with joined text | `TABLE` + `TABLE_ROW` + `TABLE_CELL` | Intentional richer structure |
| Section paths | Stored on Platform structural chunks | Not a content-contract field; derived downstream from heading hierarchy | Intentional difference |
| Metadata | Parser-specific | Frontmatter, HTML comments, and bold preamble metadata excluded from elements | Intentional isolation |
| Source offsets | Canonical text offsets | Original UTF-8 source spans from PR #29 | Separate coordinate spaces |

## Table behavior

Pipe tables with a valid separator row emit:

- one `TABLE` element;
- one `TABLE_ROW` per header/data row;
- one `TABLE_CELL` per cell;
- parent/child IDs connecting the table hierarchy;
- `header=true` attributes on header cells;
- exact raw source spans where the cell content is non-empty;
- table text joined with ` | ` per row and `\n` between rows.

Tables without a valid `| ... |` separator are not claimed as tables.

Compatibility limits:

- Tables require leading and trailing pipes. The existing Platform parser also
  requires this form; outer-pipe-less tables are not claimed.
- Escaped pipes (`\|`) and pipes inside backtick code spans remain within one
  cell.
- Empty cells are preserved structurally without a synthetic source span.
- `tables=APPLIED` means a table element was actually produced. If tables are
  requested for a Markdown document without one, the state is `NOT_APPLICABLE`.

## Normalization and evidence

- Element text is parser text, not the original source slice.
- Original UTF-8 source ranges remain separate from any future canonical-text projection.
- No canonical-text identity, execution provenance, or projection is emitted by this adapter change.
- Inline Markdown interpretation and HTML entity decoding are not claimed.

## Compatibility

Existing Platform P1/A2/A4 artifacts remain unchanged. Consumers migrating to the canonical adapter must account for the intentional table expansion and must derive section paths from heading hierarchy rather than expecting a new contract field.
