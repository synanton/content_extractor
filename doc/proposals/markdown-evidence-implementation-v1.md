# Markdown Evidence Implementation Proposal v1

**Status:** Proposal only. No adapter implementation, schema change, migration, benchmark, or baseline change.  
**Predecessor:** Adapter Readiness Assessment (#27).  
**Scope:** Define the minimum Markdown evidence vertical slice and its test contract.

## 1. Goal

Populate the approved #22 schema for Markdown with the minimum defensible evidence:

- Immutable source identity.
- Parser-produced structural elements.
- Exact original-byte ranges.

Canonical text identity, execution attribution, and projection records are optional and must not be fabricated.

## 2. Non-goals

- PDF or other format evidence.
- Adapter migration.
- Benchmark execution.
- Changes to platform consumers.
- #20/#21, #950, #947.
- Changing the approved schema.

## 3. Evidence layers

Keep separate:

1. **Original source bytes** — exact Markdown bytes, digest, version, encoding.
2. **Parser structure** — elements and hierarchy produced by the Markdown parser.
3. **Parser text** — text after explicit Markdown transformations.
4. **Canonical text** — optional normalized projection with its own identity.
5. **Source ranges** — half-open byte ranges into original source.

A parser element does not prove source correspondence. A canonical range does not prove original-source correspondence.

## 4. Source-range contract

### 4.1 Coordinates

- Unit: `UTF8_BYTE`.
- Coordinate space: `ORIGINAL_SOURCE`.
- Half-open: `[start, end)`.
- Range is exact for the original bytes; no trimming, Unicode normalization, or line-ending conversion changes the range.
- Range must slice the exact raw source span, including Markdown markers, delimiters, indentation, and original line endings.
- A source range is never reconstructed by searching parser text or `flattenedText`.

### 4.2 Range boundaries by element type

| Element | Raw slice includes | Parser text transformation |
|---|---|---|
| HEADING | Markdown heading marker and text through line end | Remove heading marker and trim the heading text |
| PARAGRAPH | Original lines, including line endings and Markdown punctuation | Current parser text is stripped/normalized per declared rule; inline Markdown is not interpreted |
| LIST | Full list block including markers/indentation | List item text removes the marker; list element text is generated |
| LIST_ITEM | Marker/indentation and item text | Remove marker/indentation; nested item ranges remain separate |
| CODE_BLOCK | Fence lines and code lines | Remove fence delimiters; preserve code lines |
| TABLE | N/A in minimum slice | Markdown table support is not claimed |
| Metadata/comments | No element | No source range; excluded from structural content |

Line-ending rules:

- Raw source range uses original `\n`, `\r\n`, or `\r` bytes.
- Parser text may convert line endings only through an explicit, tested transformation.
- Parser text target is normalized LF (`\n`) for every element in the minimum slice, regardless of original CRLF/CR/LF source.
- Source ranges always retain the original bytes; only parser/canonical text is normalized.
- CRLF/CR fixtures are mandatory.

Unicode rules:

- Raw source range is unnormalized.
- Current Markdown parser does not claim NFC/NFKC normalization.
- If canonical identity is later added, its Unicode version/profile must be explicit and tested.

### 4.3 Java UTF-16 versus source UTF-8

Java `String` indexes UTF-16 code units, while source ranges are UTF-8 byte offsets. The implementation must not mix them.

Required strategy:

- Read the original source as bytes once.
- Build line start/end byte offsets by scanning the original UTF-8 bytes.
- If the Markdown parser works on a decoded `String`, maintain an explicit mapping from parser string positions to original byte offsets.
- The mapping may resolve only valid source boundaries: UTF-8 code-point boundaries and UTF-16 surrogate-pair boundaries. Positions inside a UTF-8 sequence or surrogate pair are invalid.
- Source decoding is explicit and strict UTF-8 for Markdown. Malformed UTF-8 must fail closed or mark the range unavailable; U+FFFD replacement must never be treated as exact source correspondence.
- BOM handling is explicit; BOM bytes are not silently dropped from source offsets. If omitted from parser text, that transformation is documented and tested.
- Do not use `String.length()`, `String.indexOf()`, or regex match indices directly as byte offsets.
- Reject any range whose conversion cannot be proven against the original bytes.

Tests must include:

- BMP multibyte characters;
- supplementary-plane characters (outside U+FFFF, requiring surrogate pairs);
- combining sequences;
- mixed CRLF/CR/LF with multibyte characters;
- inline Markdown around supplementary characters.

### 4.4 Multi-line ranges

For a multi-line element:

- Start is the first byte of the first source line.
- End is the byte after the last source line's content, before its trailing line terminator.
- Internal line terminators are included; the final line terminator is excluded.
- Element-type exceptions are listed in the ownership table above; none applies in the minimum slice.
- Empty ranges are invalid for evidence elements.
- Ranges must not cross document boundaries.

## 5. Transformation model

Define `raw_slice -> parser_text` as a pure, documented transformation.

Minimum transformation rules:

| Element | Transformation |
|---|---|
| HEADING | Remove one `#{1,6}` marker and following spaces; trim outer whitespace |
| PARAGRAPH | Trim line-leading/trailing whitespace; join wrapped lines with normalized LF (`\n`); do not decode entities or remove inline markup |
| LIST_ITEM | Remove leading indentation and one list marker; trim outer whitespace |
| LIST | Generated from item text/order; not a source slice |
| CODE_BLOCK | Remove opening/closing fence lines; preserve code line content; join internal lines with normalized LF (`\n`); source range retains original bytes |

Rules:

- No inline Markdown interpretation is claimed in the minimum slice.
- No HTML entity decoding is claimed.
- No Unicode normalization is claimed.
- No transformation may be used to reverse-engineer source ranges.
- Tests must compare the raw slice to the expected raw source, then separately compare parser text.

## 6. Required SPI changes

The current `NormalizedElement` has no typed source span. The schema has `DocumentElement.source_locations`, but the internal adapter model cannot carry it.

The implementation proposal requires a separate SPI design step:

1. Add an internal typed source-span/location model.
2. Carry document source identity from `ExtractionRequest`/source metadata.
3. Map internal locations to `DocumentElement.SourceLocation` in `ResultMapper`.
4. Keep optional canonical/execution/projection data absent unless produced.

Do not use opaque `attributes` to smuggle source offsets.

## 7. Fixtures

Required fixtures:

1. Single heading and paragraph.
2. Nested unordered and ordered lists.
3. Fenced code block with language and internal blank lines.
4. Inline emphasis/strong/code markers.
5. Markdown table (assert no table claim; behavior recorded).
6. HTML comments and YAML front matter (metadata isolation).
7. CRLF source.
8. CR-only source.
9. Unicode precomposed/decomposed text.
10. HTML entity text (`&amp;`) to assert no decoding claim.
11. Supplementary Unicode characters outside the BMP (for example emoji) before/after Markdown markers.
12. Combining marks and decomposed sequences around range boundaries.
13. Repeated headings and list items to prevent text-search range resolution.
14. Mixed line endings and trailing newline/no trailing newline.

Each fixture must include expected raw byte slices, not only expected parser text.

## 8. Acceptance tests

### Source correspondence

- For every emitted range, `sourceBytes[start..end)` equals the expected raw slice.
- Ranges are within source length and non-empty.
- Start/end are byte offsets, not Java char offsets.
- Supplementary-plane characters and combining sequences do not shift expected ranges.
- UTF-16 string indexes are never used directly as source offsets.
- CRLF/CR ranges preserve original bytes.
- Final line terminators are excluded consistently per the ownership table.
- Malformed UTF-8 is rejected/unavailable, never replaced and claimed exact.
- No test passes by searching parser output.

### Structure

- Heading levels, paragraph grouping, list nesting, and code blocks remain correct.
- Metadata/comments do not appear as structural elements.
- Tables are explicitly unsupported or unclaimed in the minimum slice.

### Transformations

- `raw_slice -> parser_text` matches the documented rule for each element type.
- Parser text uses normalized LF regardless of original CRLF/CR/LF; source ranges retain original bytes.
- Inline Markdown/entity handling matches the declared non-interpretation/decoding policy.
- No canonical identity is emitted when normalization/Unicode version is not reproducible.

### Partial data

- Source identity and ranges may be populated while canonical/execution/projection remain absent.
- Consumers do not interpret absence as zero or exact.
- Unavailable geometry/canonical fields remain absent, not fabricated.

### Consumer compatibility

- Existing platform consumers handle payloads with new optional fields absent and present.
- No consumer treats parser text or `flattenedText` as source offsets.

## 9. Minimum slice versus optional capabilities

**Minimum slice:**

- Document identity/source descriptor.
- Parser structure.
- Verified original-byte source ranges.

**Optional, only when verified:**

- Canonical text identity/digest.
- Execution attribution.
- Projection/loss records.

The minimum slice must be independently useful and must not invent optional completeness.

## 10. Implementation phases after approval

1. SPI typed source-span model.
2. Markdown byte-offset parser integration.
3. ResultMapper population of source descriptor and source locations.
4. Evidence tests for raw-byte correspondence.
5. Optional canonical/execution/projection only after separate verification.

## 11. Go/no-go criteria

Go when:

- Original source digest/identity is available.
- Exact UTF-8 ranges can be computed without searching parser output.
- Raw-slice fixtures pass for CRLF/CR/Unicode/entity/inline-markup cases.
- Partial payloads preserve absence semantics.
- Platform consumers tolerate the new optional fields.

No-go when:

- Ranges are reconstructed from parser text or `flattenedText`.
- Offsets mix UTF-16/Java chars with UTF-8 bytes.
- Canonical identity is emitted without reproducible normalization.
- Optional provenance is inferred from missing metadata.
- Tables are described as cell-level evidence without support.

## 12. Approval boundary

Approval authorizes an implementation plan for the Markdown minimum slice only.

It does not authorize:

- PDF evidence work.
- #20/#21.
- #950/#947.
- Schema changes.
- Migrations, benchmarks, or baseline updates.

No code or schema is changed by this proposal.
