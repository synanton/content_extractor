# Adapter Readiness Assessment v1

**Status:** Assessment only. No adapter implementation or schema change.  
**Scope:** Determine whether current extraction providers can populate the approved #22 evidence contract with defensible source correspondence.  
**Frozen:** #20/#21 implementation, #950, #947, migrations, benchmarks, and baselines.

## 1. Assessment principle

Distinguish three evidence levels:

| Level | Meaning |
|---|---|
| Provider capability | The underlying engine can expose the information |
| Adapter availability | The current adapter emits it in `DocumentPayload` |
| Verified correspondence | Tests prove the emitted value maps correctly to the original source |

A provider capability is not an adapter guarantee. An adapter field is not verified evidence until correspondence is tested.

Absent optional data remains `UNAVAILABLE`/unknown. It is not evidence that the information does not exist.

## 2. Current provider and adapter inventory

### 2.1 PDF — OpenDataLoader

| Field family | Provider capability | Current adapter | Verified correspondence |
|---|---|---|---|
| Document identity/source descriptor | Caller/source metadata | Not populated | No |
| Page/bbox geometry | Yes, OpenDataLoader elements include page and bbox | Mapped to `ElementBounds` | Partial normalizer tests; not new `SourceLocation` |
| Source text ranges | Not exposed as text-layer ranges | Not populated | No |
| Text-layer identity/digest | Not produced | Not populated | No |
| Canonical text identity | Not produced | Not populated | No |
| Execution provenance | Not produced | Not populated | No |
| Projections/loss report | Not produced | Only legacy `flattenedText` | No |
| Structural elements | Headings, paragraphs, tables, lists, images, captions | Mapped; table/list text flattened | Unit/integration tests exist |

Evidence found:

- `OpenDataLoaderNormalizerTest`
- `OpenDataLoaderIntegrationTest`
- `PdfModalityAdapterTest`
- Fixtures: `quarterly-report.pdf`, `einsteins-patents.pdf`

Limitations:

- Raw PDF is binary; no UTF-8 source-character range exists without an identified text layer.
- Current PDF output lacks `TextLayerIdentity`, so PDF source-range parity is not established.
- Table/list structure is flattened in normalized text, not cell-level evidence.
- Bbox coordinate system is currently represented through `ElementBounds`; new typed geometry/provenance fields are not populated.

### 2.2 Markdown — native parser in `TextModalityAdapter`

| Field family | Provider capability | Current adapter | Verified correspondence |
|---|---|---|---|
| Document identity/source descriptor | Caller/source metadata | Not populated in new fields | No |
| Markdown structure | Headings, paragraphs, nested lists, code | Emitted as normalized elements | Adapter tests cover structure |
| Source text ranges | UTF-8 source can be indexed | Not populated | No |
| Canonical identity | Could be computed from normalized text | Not populated | No |
| Execution provenance | Caller can assign execution | Not populated | No |
| Projections/loss report | Not produced | Only legacy `flattenedText` | No |
| Table structure | Not supported in Markdown branch | No | No |

Evidence found: `TextModalityAdapterTest` covers headings, paragraphs, nested lists, fenced code, metadata isolation, and canonical flattening.

Limitations:

- No source offsets are currently emitted.
- No canonical identity/digest, execution record, or projection record is emitted.
- Markdown tables are not parsed as table elements.
- Structure tests do not verify source-byte correspondence.

### 2.3 Other text-like formats — Tika

| Field family | Current adapter | Verified correspondence |
|---|---|---|
| Text | Paragraph elements | Adapter tests |
| Structure | Mostly paragraphs; no layout | No |
| Source ranges | Not populated | No |
| Canonical identity/provenance/projections | Not populated | No |

Tika text is useful for legacy text extraction but is not a sufficient first evidence-contract slice for source correspondence.

### 2.4 Audio/image/video

Capability stubs report unsupported. No evidence population is possible in the current milestone.

### 2.5 Platform legacy PDFBox parser

The Platform PDFBox path is a historical evaluation/evidence compatibility path, not a SCEP production provider. It can produce line-level bbox and canonical text offsets, but it is not an approved source for new adapter evidence and is outside this assessment.

## 3. Source-correspondence requirements

A field may be marked adapter-available only when the required evidence exists.

### 3.1 Source identity

- Source digest matches the exact bytes used for extraction.
- Source version/URI is immutable for that digest.
- Location bindings resolve to that source or an explicitly identified derived representation.

### 3.2 Page/bbox geometry

- Page number and bbox are present together or the geometry is explicitly unavailable.
- Coordinate system, units, and rotation are declared.
- Bbox coordinates are finite and ordered.
- A geometry record does not imply a text range.
- PDF geometry can be exact in its coordinate system while remaining `DEPENDS_ON_PARSER` for reproducibility.

### 3.3 Source text range

- Markdown/text: start/end are half-open UTF-8 offsets into the original bytes. Slicing returns the exact raw source span, including Markdown markers, delimiters, and original line endings. It does not necessarily equal the parser's normalized element text. Any transformation from raw slice to parser text, canonical text, or `flattenedText` must be explicit and separately tested.
- PDF: requires a `TextLayerIdentity` with text-layer digest, encoding, origin source digest/version, and derivation execution.
- No range is derived by searching flattened text.
- An unavailable range remains absent/unavailable.

### 3.4 Canonical text identity

- Canonical digest is reproducible from the declared source/execution/normalization/projection/encoding contract.
- Range normalization and line-ending policy match identity.
- Unicode version is explicit for NFC/NFKC.
- Mismatched identity makes the range invalid.

### 3.5 Execution provenance

- Every execution ID resolves.
- Direct producer is explicit.
- Merge uses a merge execution and `MERGE` links; contributors are not copied unless direct producers.
- Unknown provenance is not inferred as `NONE_KNOWN`.

### 3.6 Projections

- Lossless/lossy/unsupported status is contract-relative.
- Loss reports identify dropped/approximated information.
- Legacy `flattenedText` is not treated as lossless.

## 4. Representative adapter selection

### Markdown evidence layers

The Markdown vertical slice must keep four layers distinct:

1. **Original source bytes** — immutable bytes, digest, version, and identity.
2. **Parser-produced structure** — element text and hierarchy emitted by the Markdown parser.
3. **Canonical projection** — normalized text and its identity/digest, if produced.
4. **Source ranges** — half-open ranges into the original Markdown bytes.

The following transformations can move offsets:

- CRLF/CR to LF normalization
- Unicode NFC/NFKC normalization
- HTML entity decoding
- trimming, whitespace collapsing, or indentation stripping
- fenced-code or list marker removal

A successful parse or stable normalized text is not evidence of exact source correspondence.

Markdown evidence tests must:

- Slice original bytes using the emitted source range and compare the result against the parser's declared source text before normalization.
- Keep source ranges independent from canonical ranges.
- Recompute the canonical digest under the declared normalization/projection contract.
- Reject ranges that cannot be tied to the original bytes.
- Avoid deriving ranges by searching parsed text or `flattenedText`.

### Partial-data requirement

The Markdown slice must demonstrate useful partial population:

- Structural elements and exact original-source ranges may be emitted.
- Canonical identity, execution attribution, and projections may remain `UNAVAILABLE`/absent if not reproducible.
- Consumers must handle this partial payload without inferring missing evidence or treating absent fields as zero/exact.
- The implementation proposal must state which fields are emitted in the first slice and which remain unavailable.

### Minimum slice versus optional evidence

**Minimum evidence slice:**

- Immutable source identity/digest.
- Parser-produced structural elements.
- Verified original-byte ranges tied to the source.

**Optional evidence capabilities:**

- Canonical text identity.
- Execution attribution.
- Projection records and loss reports.

Optional capabilities are populated only when their contracts can be satisfied and verified. The minimum slice must not fabricate canonical/execution/projection completeness merely to satisfy a checklist.

### Recommendation: Markdown first

Use the existing Markdown adapter as the first end-to-end evidence vertical slice.

Why:

- Source is already text; exact UTF-8 source ranges are feasible without a PDF text-layer intermediary.
- PR #19 provides canonical Markdown structure (headings, paragraphs, nested lists, code).
- Source digest, source range, canonical identity, execution provenance, and projection loss can all be exercised with a small fixture.
- It avoids conflating PDF text-layer uncertainty with evidence-contract plumbing.
- Failures are attributable to adapter logic rather than PDF coordinate systems.

Minimum vertical-slice scope:

1. Assign document identity/source descriptor.
2. Emit exact original-source UTF-8 ranges for headings, paragraphs, list items, and code blocks.
3. Validate old consumers still handle absent/new optional fields safely.

Optional additions, only if independently reproducible and tested:

4. Canonical text identity.
5. Execution attribution.
6. Loss-aware projection records.

### PDF second

PDF is the higher-value but higher-risk provider. It should follow Markdown because:

- It requires `TextLayerIdentity` and a reproducible text layer.
- Bbox geometry can be validated independently, but source text ranges need extraction-layer semantics.
- Tables/lists currently flatten; table evidence parity is not ready.
- PDF coordinate provenance needs explicit rotation/unit/coordinate-system tests.

PDF should not be the first slice unless the milestone explicitly prioritizes geometry over source text ranges.

## 5. Consumer compatibility checklist

Before adapter evidence is claimed, verify:

- [ ] Old payloads with fields 1-4 parse and behave unchanged.
- [ ] New optional fields absent do not become default/zero evidence.
- [ ] `QualityReport` unset values are not treated as 0.0.
- [ ] `UNKNOWN` vs `NONE_KNOWN` are not collapsed.
- [ ] Unresolved execution IDs fail; unknown attribution is accepted.
- [ ] Multiple location records are preserved independently.
- [ ] Canonical ranges without identity are rejected/unavailable.
- [ ] Legacy `ElementLocation` is not converted to PDF geometry for non-PDF formats.
- [ ] `flattenedText` is not used to reconstruct source offsets.
- [ ] Projection unsupported/lossy outcomes are surfaced, not flattened into success.

Current status: schema-level tests exist; production consumer tests for these behaviors are **not yet evidenced**.

## 6. Vertical-slice go/no-go criteria

### Go for Markdown implementation milestone

- Source fingerprint and original bytes are available to the adapter.
- Exact UTF-8 source ranges can be computed from parser line/offset tracking.
- An evidence test can compare every emitted original-byte range to the raw source slice.
- Platform consumer tests prove absent optional fields are handled safely.
- Any canonical identity is reproducible from a declared normalization/profile contract.
- Any execution provenance resolves at the gateway/adapter boundary.

### No-go conditions

- Offsets are derived by searching `flattenedText`.
- Canonical identity depends on undocumented normalization or Unicode version.
- Execution provenance is inferred from missing metadata.
- PDF text ranges are emitted without `TextLayerIdentity`.
- Tables are flattened and then described as cell-level evidence.
- Consumers treat absent geometry/range as exact or zero.

## 7. Recommended next milestone

**Markdown evidence vertical slice** after this assessment is accepted.

Deliverables for that milestone:

1. Markdown adapter populates source descriptor, structure, and exact original-source ranges.
2. Evidence tests validate raw-byte correspondence.
3. Consumer compatibility tests validate absent optional data.
4. Canonical identity, execution attribution, and projections are added only as separately verified optional capabilities.
5. No PDF, migration, benchmark, or baseline changes.

PDF parity remains frozen until the Markdown slice establishes the evidence pipeline.

## 8. Assessment conclusion

- Contract schema: implemented.
- Provider capability inventory: partially available, uneven.
- Adapter population: not implemented.
- Verified source correspondence: not established.
- Best first slice: Markdown.
- PDF source-range parity: blocked on `TextLayerIdentity` and evidence tests.
- #20/#21/#950/#947: remain frozen.

No adapter implementation, schema change, migration, benchmark, or baseline change is included in this assessment.
