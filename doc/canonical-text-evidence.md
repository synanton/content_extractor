# Canonical Text Evidence

**Status:** Implemented for Markdown; issues #33 and #34.<br>
**Related:** Platform A1–A3 approval #971 and implementation #972.<br>
**Not included:** `schema_digest` (#32), PDF canonical ranges, or source-byte translation.

## Contract

The text adapter emits one `synanton.canonical-text` projection when canonical text can be built:

- `projection_id`: `canonical-text`
- `format`: `text/plain; charset=utf-8`
- `contract_id`: `synanton.canonical-text`
- `contract_version`: `1`
- `outcome`: `PROJECTION_OUTCOME_SUCCESS_LOSSLESS`
- `digest`: `sha256:<64 lowercase hex>` over the UTF-8 projection bytes

Every ranged element carries the same `CanonicalTextIdentity`:

- `canonical_text_id = ct-v1:<canonical_text_digest>`
- `canonical_text_digest = sha256:<digest>`
- `encoding = UTF-8`
- source document/digest/version from the extraction source identity
- `extraction_execution_id` from the extraction operation
- normalization profile/version
- projection contract ID/version
- NFC normalization and LF line endings

Ranges are half-open UTF-16 code-unit offsets into the projection.

## Markdown canonical text

Canonical text is the adapter's normalized structural text, not a source-byte slice and not a
reparse of flattened text:

- heading, paragraph, code-block, list-item, and table text appears once in reading order;
- table row/cell ranges are nested inside the TABLE range;
- list container ranges are the union of their item ranges;
- element text is NFC-normalized and segments are joined with LF.

### Metadata preamble

Per the approved A2 decision (Platform #971 / #972), the Markdown metadata preamble is included in
canonical text while remaining non-semantic:

- bold `**Key:** value` metadata lines, HTML comments, and YAML front matter are included in the
  projection before the first level-2 heading;
- they do not become `DocumentElement`s and cannot produce mentions;
- no metadata is reconstructed from the original Markdown by Platform;
- the book container range starts at canonical offset zero and includes this preamble slice.

The adapter records these as non-element canonical segments. Document metadata extraction remains
unchanged.

## Missing evidence

No canonical projection is emitted when canonical text cannot be produced:

- PDF and other adapters that do not yet build canonical structural text;
- Markdown extraction without an immutable source identity.

Consumers must treat the absence as `-1/-1`/unavailable, not as an approximate range. Original-source
UTF-8 spans remain a separate coordinate space and are never translated into canonical offsets.

## Table and list boundaries

- TABLE uses its own canonical range; nested TABLE_ROW/TABLE_CELL ranges are located inside it.
- LIST uses the union of its item ranges.
- Empty cells do not receive a synthetic range.
- Table text retains the adapter's documented ` | ` representation; this is an intentional
  structural difference from the Platform parser, not a source-coordinate claim.

## Verification

- `TextModalityAdapterTest.shouldEmitCanonicalTextWithPreambleAndElementRanges`
- `TextModalityAdapterTest.shouldNestTableDescendantCanonicalRangesInsideTable`
- `ResultMapperTest.mapsCanonicalProjectionIdentityAndRanges`

Full `./gradlew test` passes.
