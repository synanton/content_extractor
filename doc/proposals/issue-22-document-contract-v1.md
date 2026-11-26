# Issue #22 — Document Contract Evolution Proposal

**Status:** Review proposal. Non-normative. No protobuf or production behavior is changed here.  
**Scope:** Content Extractor contract evolution for format-neutral documents and typed source locations.  
**Revision:** Addresses the #23 second review: origin/derived identity, deterministic hashing, explicit unknown execution attribution, projection identity, and remaining ambiguity cleanup.  
**Related:** #20 (PDF parity), #21 (Markdown parity), #950 (production ownership transition), #947 (evidence compatibility).  
**Guardrails:** No parser migration, benchmark execution, semantic model execution, frozen artifact changes, or issue restructuring in this proposal.

## 1. Executive summary

The existing SCEP contract should be evolved, not replaced.

`StructuredPayload` remains the common multimodal result envelope. `DocumentPayload` remains the document payload. The change is additive:

- Add document identity/class and source descriptor.
- Add document-level extraction provenance.
- Add typed source locations without overloading `ElementLocation`.
- Add separate canonical-text ranges.
- Add explicit precision and reproducibility states.
- Retain and define compatibility behavior for existing fields.
- Add loss-aware projection metadata.
- Add durable multi-provider/multi-stage execution provenance without putting dispatcher routing into the document contract.

No `PdfPayload`, `DocxPayload`, or `MarkdownPayload` is introduced. PDF, DOCX, PPTX, Markdown, HTML, and plain text are source representations of a text document, not separate document roots.

## 2. Current contract inventory

### 2.1 Service result envelope

`StructuredPayload` currently has:

- `PayloadDescriptor payload_descriptor`
- `oneof content { bytes inline_content; PayloadReference reference; }`

`PayloadDescriptor` currently carries:

- `schema_id`
- `schema_version`
- `processor_id`
- `processor_version`
- `SerializationFormat format`
- `schema_digest`
- `payload_digest`

This separates schema identity from processor identity and already supports replacing one extraction engine with another.

### 2.2 Document payload

`DocumentPayload` currently has:

| Field | Number | Meaning |
|---|---:|---|
| `media_type` | 1 | Source media type |
| `metadata` | 2 | Opaque document metadata |
| `elements` | 3 | Reading-order structural elements |
| `flattened_text` | 4 | Derived compatibility projection |

### 2.3 Document element

`DocumentElement` currently has:

| Field | Number | Meaning |
|---|---:|---|
| `id` | 1 | Stable-within-payload identity |
| `type` | 2 | Structural type |
| `location` | 3 | `ElementLocation` |
| `text` | 4 | Element text |
| `provenance` | 5 | Content provenance |
| `level` | 6 | Heading/list depth |
| `child_ids` | 7 | Child element IDs |
| `attributes` | 8 | Processor-specific opaque metadata |
| `alternate_representation` | 9 | LaTeX or image description |

`ElementLocation` currently has only:

- `page` (1-based; 0 means no page)
- `BoundingBox` (`x0`, `y0`, `x1`, `y1`)

`ContentProvenance` already distinguishes embedded text, OCR, tagged structure, layout inference, generated content, and transcription.

### 2.4 SPI model

The internal adapter model has:

- `NormalizedDocument(mediaType, metadata, elements, flattenedText)`
- `NormalizedElement(id, type, bounds, text, contentOrigin, level, childIds, attributes, alternateRepresentation)`
- `ElementBounds(page, x0, y0, x1, y1)` — PDF user-space points, origin bottom-left

The SPI currently has no document class, source descriptor, source text range, canonical text range, or precision/reproducibility state.

### 2.5 Compatibility constraints

- Existing protobuf field numbers and meanings must remain valid.
- New fields must be optional and additive.
- Old consumers must ignore unknown fields.
- Old producers must remain readable by new consumers; missing new data maps to explicit unknown/unavailable values.
- `StructuredPayload` must remain the common multimodal envelope.
- `flattened_text` remains derived from elements and must not require reparsing the source.
- Provider-specific types must not enter the shared contract.
- The Platform and Content Extractor copies of `synanton.extraction.v1` must stay mirrored.

## 3. Target conceptual model

```text
Document (logical root)
  identity
  document class
  source descriptor
  metadata
  structure
  locations
  provenance
  projections

Source representation (input)
  PDF / DOCX / PPTX / XLSX / HTML / Markdown / TXT / audio transcript

Normalized document (contract)
  typed elements, hierarchy, content, metadata, typed locations, provenance
```

### 3.1 Document class versus source format

Document class is a logical processing class. Source format is an input serialization.

| Document class | Source formats | Notes |
|---|---|---|
| `TEXT` | PDF, DOCX, PPTX, HTML, Markdown, TXT | Common text-document structure |
| `SPREADSHEET` | XLSX, CSV, ODS | Needs sheet/cell coordinates and table relationships |
| `TRANSCRIPT` | Audio/video transcription | Text plus temporal provenance; may coexist with a conversation payload |
| `STRUCTURED_DATA` | JSON, XML, other serializations | Structure-first representation |
| `UNKNOWN` | Unknown/undetermined | Explicit fallback, never silently assumed |

Image and video may remain modality-specific payloads where a document abstraction is inappropriate. A transcript is a derived text document with temporal source references; it does not force audio concepts into every text document.

## 4. Proposed additive contract shape

The following is illustrative, not a request to merge protobuf changes now.

### 4.1 DocumentPayload extension

```proto
message DocumentPayload {
  // Existing fields 1-4 remain unchanged.
  string media_type = 1;
  map<string, string> metadata = 2;
  repeated DocumentElement elements = 3;
  string flattened_text = 4;

  // Proposed additive fields.
  DocumentIdentity identity = 5;
  SourceDescriptor source = 6;
  ExtractionProvenance provenance = 7;
  repeated DocumentProjection projections = 8;
}

message DocumentIdentity {
  string document_id = 1;
  DocumentClass document_class = 2;
  string document_version = 3;
}

enum DocumentClass {
  DOCUMENT_CLASS_UNSPECIFIED = 0;
  DOCUMENT_CLASS_TEXT = 1;
  DOCUMENT_CLASS_SPREADSHEET = 2;
  DOCUMENT_CLASS_TRANSCRIPT = 3;
  DOCUMENT_CLASS_STRUCTURED_DATA = 4;
  DOCUMENT_CLASS_UNKNOWN = 5;
}

message SourceDescriptor {
  string media_type = 1;
  string source_format = 2;       // extensible: "pdf", "docx", "markdown", ...
  string source_digest = 3;       // e.g. "sha256:..."
  string source_version = 4;
  string source_uri = 5;          // optional, access-controlled
  string filename = 6;            // optional
}

message ExtractionProvenance {
  // Summary for a single-provider/legacy payload.
  string primary_execution_id = 1;

  // Durable execution history for multi-provider or multi-stage extraction.
  repeated ExtractionExecution executions = 2;
}

message ExtractionExecution {
  string execution_id = 1;
  string extractor_id = 2;          // opaque, e.g. "pdf-extractor"
  string extractor_version = 3;
  string engine_id = 4;             // e.g. "opendataloader"
  string engine_version = 5;
  string stage_id = 6;              // e.g. "text", "ocr", "table-recovery"
  string policy_id = 7;             // routing policy, not a routing table
  string policy_version = 8;
  ExecutionOutcome outcome = 9;
  repeated ExecutionLink parents = 10;
  QualityReport quality = 11;
  map<string, string> attributes = 12;
}

enum ExecutionOutcome {
  EXECUTION_OUTCOME_UNSPECIFIED = 0;
  EXECUTION_OUTCOME_SUCCEEDED = 1;  // produced usable output
  EXECUTION_OUTCOME_PARTIAL = 2;    // produced some usable output
  EXECUTION_OUTCOME_FAILED = 3;     // produced no usable output
}

message ExecutionLink {
  string execution_id = 1;
  ExecutionLinkKind kind = 2;
}

enum ExecutionLinkKind {
  EXECUTION_LINK_UNSPECIFIED = 0;
  EXECUTION_LINK_RETRY = 1;
  EXECUTION_LINK_FALLBACK = 2;
  EXECUTION_LINK_INPUT_DEPENDENCY = 3;
  EXECUTION_LINK_MERGE = 4;
}

message QualityReport {
  QualityStatus status = 1;
  double completeness = 2;          // 0.0..1.0, optional
  double confidence = 3;            // 0.0..1.0, optional
  repeated string warnings = 4;
}

// Quality describes the output; it is not the execution outcome.
enum QualityStatus {
  QUALITY_STATUS_UNSPECIFIED = 0;
  QUALITY_STATUS_COMPLETE = 1;
  QUALITY_STATUS_DEGRADED = 2;
  QUALITY_STATUS_PARTIAL = 3;
  QUALITY_STATUS_UNKNOWN = 4;
}
```

`PayloadDescriptor` remains the payload-level schema/processor descriptor. `ExtractionProvenance` is document-level and must not duplicate schema identity.

### 4.2 Element source/canonical locations

An element may carry multiple source locations simultaneously. The model therefore uses a repeated collection of independently typed location records, not an exclusive oneof.

```proto
message DocumentElement {
  // Existing fields 1-9 remain unchanged.
  string id = 1;
  DocumentElementType type = 2;
  ElementLocation location = 3;
  string text = 4;
  ContentProvenance provenance = 5;
  int32 level = 6;
  repeated string child_ids = 7;
  map<string, string> attributes = 8;
  string alternate_representation = 9;

  // Proposed additive fields.
  repeated SourceLocation source_locations = 10;
  CanonicalTextRange canonical_text_range = 11;

  // Execution(s) that produced this element. Repeated permits merged/multi-stage elements.
  repeated string execution_ids = 12;
  ExecutionAttribution execution_attribution = 13;
}

enum ExecutionAttribution {
  EXECUTION_ATTRIBUTION_UNSPECIFIED = 0; // invalid
  EXECUTION_ATTRIBUTION_INHERITED = 1;   // inherit document primary execution
  EXECUTION_ATTRIBUTION_EXPLICIT = 2;    // execution_ids are authoritative
  EXECUTION_ATTRIBUTION_UNKNOWN = 3;     // known absence of provenance
}
```

`ElementLocation` remains valid for compatibility and continues to mean page/PDF bbox when present. New code should prefer `source_locations` for explicit semantics.

Source locations are independently typed records. A PDF paragraph may therefore carry both a `PdfGeometry` record and a `TextRange` record, each with its own precision, reproducibility, and source binding.

Tables retain `TABLE`, `TABLE_ROW`, and `TABLE_CELL` elements. Each cell may carry its own source locations; table-level geometry must not be the only evidence for cell content.

### 4.3 SourceLocation record

```proto
message SourceLocation {
  string location_id = 1;           // stable within element
  SourceBinding source_binding = 2; // inheritance or explicit binding
  LocationPrecision precision = 3;
  Reproducibility reproducibility = 4;

  oneof location {
    TextRange text_range = 10;
    PdfGeometry pdf_geometry = 11;
    FormatReference format_reference = 12;
  }
}

message SourceBinding {
  BindingKind kind = 1;
  // Origin identity is always the original document, never a derived layer.
  string origin_document_id = 2;
  string origin_source_digest = 3;
  string origin_source_version = 4;
}

enum BindingKind {
  BINDING_KIND_UNSPECIFIED = 0;              // invalid for a usable location
  BINDING_KIND_INHERIT_DOCUMENT = 1;         // origin = enclosing document
  BINDING_KIND_EXPLICIT_ORIGIN = 2;          // origin must match enclosing document
  BINDING_KIND_DERIVED_REPRESENTATION = 3;   // origin + derived representation
}

enum LocationPrecision {
  LOCATION_PRECISION_UNSPECIFIED = 0; // unusable; never EXACT
  LOCATION_PRECISION_EXACT = 1;
  LOCATION_PRECISION_APPROXIMATE = 2;
  LOCATION_PRECISION_UNAVAILABLE = 3;
}

enum Reproducibility {
  REPRODUCIBILITY_UNSPECIFIED = 0;   // not proven reproducible
  REPRODUCIBILITY_EXACT_FROM_SOURCE = 1;
  REPRODUCIBILITY_DEPENDS_ON_PARSER = 2;
  REPRODUCIBILITY_DEPENDS_ON_NORMALIZATION = 3;
  REPRODUCIBILITY_NOT_REPRODUCIBLE = 4;
}
```

The oneof is now safe because it is per record; multiple records are allowed on one element.

Normative rules:

1. `SourceBinding` identifies origin identity. `INHERIT_DOCUMENT` resolves to the enclosing document's immutable source identity/version/digest. `EXPLICIT_ORIGIN` must match it. `DERIVED_REPRESENTATION` binds to the origin but requires the location variant to identify the derived representation separately.
2. A derived representation (for example `TextLayerIdentity`) carries its own representation ID, digest, encoding, and derivation execution. It must not be conflated with the origin document digest.
3. `BINDING_KIND_UNSPECIFIED`, `LOCATION_PRECISION_UNSPECIFIED`, and `REPRODUCIBILITY_UNSPECIFIED` are invalid for a usable location.
4. Every variant declares its coordinate system and units. Missing required semantics make the record invalid or `UNAVAILABLE`, never exact.
5. A structurally present record is not automatically usable. Usable requires valid binding, explicit coordinate semantics, and `EXACT` or `APPROXIMATE` precision.
6. A stale digest/version mismatch invalidates the location; it must not be silently reused against a replaced source.

### 4.4 Text ranges

```proto
message TextRange {
  OffsetUnit unit = 1;
  CoordinateSpace coordinate_space = 2;
  int64 start = 3;                 // inclusive
  int64 end = 4;                   // exclusive
  TextNormalization normalization = 5;
  LineEndingPolicy line_endings = 6;
  TextLayerIdentity text_layer = 7; // required for SOURCE_TEXT_LAYER
}

message TextLayerIdentity {
  string text_layer_id = 1;
  string text_layer_digest = 2;       // SHA-256 of the declared extracted text bytes
  string encoding = 3;                // e.g. UTF-8, UTF-16LE
  string origin_document_id = 4;      // original document, not the text layer
  string origin_source_digest = 5;    // original source digest
  string origin_source_version = 6;
  string derivation_execution_id = 7; // execution that produced this layer
  string extraction_layer_version = 8;
}

enum OffsetUnit {
  OFFSET_UNIT_UNSPECIFIED = 0;     // invalid for a usable range
  OFFSET_UNIT_UTF8_BYTE = 1;
  OFFSET_UNIT_UTF16_CODE_UNIT = 2;
  OFFSET_UNIT_UNICODE_CODE_POINT = 3;
}

enum CoordinateSpace {
  COORDINATE_SPACE_UNSPECIFIED = 0; // invalid for a usable range
  COORDINATE_SPACE_ORIGINAL_SOURCE = 1; // original text source only
  COORDINATE_SPACE_SOURCE_TEXT_LAYER = 2; // extracted text layer (e.g. PDF)
  COORDINATE_SPACE_CANONICAL_TEXT = 3;
}

enum TextNormalization {
  TEXT_NORMALIZATION_UNSPECIFIED = 0; // invalid for a usable range
  TEXT_NORMALIZATION_NONE = 1;
  TEXT_NORMALIZATION_NFC = 2;
  TEXT_NORMALIZATION_NFKC = 3;
  TEXT_NORMALIZATION_CUSTOM = 4;
}

enum LineEndingPolicy {
  LINE_ENDING_UNSPECIFIED = 0;      // invalid for a usable range
  LINE_ENDING_ORIGINAL = 1;
  LINE_ENDING_LF = 2;
  LINE_ENDING_CRLF = 3;
}
```

Normative defaults:

- Original UTF-8 text source: `UTF8_BYTE`, `ORIGINAL_SOURCE`, half-open `[start, end)`, `NONE` normalization, `ORIGINAL` line endings.
- Canonical text: explicit unit; Platform legacy mapping defaults to `UTF16_CODE_UNIT`, but other units may be emitted only when declared.
- No canonical offset is valid as an `ORIGINAL_SOURCE` record.
- No unit may be silently converted. A producer that changes units must emit a new record with explicit semantics.
- `ORIGINAL_SOURCE` is valid only when the source itself is text (for example Markdown/TXT). Raw PDF bytes are not UTF-8 text; PDF text ranges require `SOURCE_TEXT_LAYER`.
- A `SOURCE_TEXT_LAYER` range requires `TextLayerIdentity` and must identify the extraction execution and source digest. The text layer is a derived source representation; use a `DERIVED_REPRESENTATION` source binding that resolves to the original document source. Without this identity, PDF text-range parity is unavailable.
- A PDF geometry-only result remains valid; the missing text layer must remain explicit, not approximated from bbox.

### 4.5 Canonical text range and identity

A canonical range is meaningful only relative to a specific canonical text revision. The following binds it to the source, extraction execution, and normalization policy.

```proto
message CanonicalTextRange {
  CanonicalTextIdentity identity = 1;
  OffsetUnit unit = 2;              // Platform compatibility default: UTF16_CODE_UNIT
  int64 start = 3;                  // inclusive
  int64 end = 4;                    // exclusive
  TextNormalization normalization = 5;
  LineEndingPolicy line_endings = 6;
}

message CanonicalTextIdentity {
  string canonical_text_id = 1;      // stable within payload
  string canonical_text_digest = 2;  // SHA-256 of canonical text bytes in `encoding`
  string encoding = 3;               // e.g. UTF-8, UTF-16LE
  string source_document_id = 4;
  string source_digest = 5;
  string source_version = 6;
  string extraction_execution_id = 7;
  string normalization_profile = 8;
  string normalization_version = 9;
  string projection_contract_id = 10;
  string projection_contract_version = 11;
}
```

Rules:

- `canonical_text_digest` hashes the exact encoded canonical text, before applying range offsets.
- `source_*` and `extraction_execution_id` must resolve to the enclosing document and a registered execution.
- `projection_contract_id`/`projection_contract_version` identify the canonical projection rules.
- A changed parser, normalization profile, projection contract, or encoding creates a new canonical-text identity. Old ranges are not silently reused.
- Canonical ranges are separate from source records and may coexist with them.
- An element may have several source records and at most one canonical range in this proposal; multiple canonical revisions require separate payloads or a future explicit collection.

### 4.5.1 Deterministic canonical hashing

For `canonical_text_digest`, define the byte sequence as:

1. Decode source or extracted text according to the declared source encoding.
2. Apply the declared Unicode normalization profile to the decoded code points.
3. Apply the declared line-ending policy.
4. Encode the resulting code-point sequence using the declared canonical encoding.
5. Hash the complete encoded byte sequence with SHA-256.

Rules:

- Encoding names are canonical and case-insensitive (`UTF-8`, `UTF-16LE`, `UTF-16BE`); the emitted value uses the canonical spelling.
- Normalization occurs before encoding and before line-ending conversion unless the profile explicitly says otherwise.
- The digest input excludes BOM bytes. A future BOM-including encoding profile requires a new versioned profile and a new canonical identity; it must not be silently mixed with the current rule.
- Offsets are into the normalized code-point sequence as represented in the declared `unit`; they are not byte offsets into the digest encoding unless `unit=UTF8_BYTE`.
- A change to source digest/version, extraction execution, normalization profile/version, line-ending policy, encoding, or projection rules creates a new canonical text identity.

### 4.6 PDF geometry

```proto
message PdfGeometry {
  int32 page_number = 1;           // 1-based; 0 when unavailable
  BoundingBox bbox = 2;            // x0,y0,x1,y1
  string coordinate_system = 3;    // required; e.g. PDF_USER_SPACE_BOTTOM_LEFT
  string unit = 4;                 // required; e.g. POINT
  int32 rotation = 5;              // 0, 90, 180, 270
  string transform = 6;            // optional documented page transform
}
```

Rules:

- `page_number` is 1-based for compatibility with existing `ElementLocation.page`.
- `bbox` is expressed in the declared coordinate system; no implicit conversion.
- Existing contract states PDF user-space points, origin bottom-left.
- Platform PDFBox currently supplies adjusted top-left coordinates; migration must transform or label explicitly, never silently reinterpret.
- Missing geometry is `UNAVAILABLE`; do not fabricate a zero bbox.
- Rotation must be retained; consumers must not assume zero.
- Geometry precision belongs to the location record. Geometry can be exact in its coordinate system while no text range is available.

### 4.6.1 Precision validation criteria

`EXACT` is operationally defined per location type. Adapters may not choose it subjectively.

| Location type | EXACT requires | APPROXIMATE | UNAVAILABLE |
|---|---|---|---|
| `PdfGeometry` | page_number > 0; finite valid bbox; coordinate system; unit; rotation; valid source binding; producer declares exact geometry | Inferred/layout-derived geometry, or incomplete provenance | Missing bbox/page/coordinate metadata |
| `TextRange` in `ORIGINAL_SOURCE` | text source; valid UTF-8/UTF-16/code-point bounds; declared unit/space/normalization/line endings; source digest matches | Source text transformed or position inferred | No range; binary PDF raw bytes |
| `TextRange` in `SOURCE_TEXT_LAYER` | `TextLayerIdentity` matches a registered extraction execution and source digest; valid bounds | Text layer exists but position/execution is approximate | No text layer identity or no range |
| `CanonicalTextRange` | canonical identity matches source, execution, normalization, encoding, and digest; valid bounds | Canonical text exists but identity/provenance is incomplete | No canonical range or identity mismatch |
| `FormatReference` | Adapter-declared exact format reference and binding | Adapter-declared approximate reference | No reference |

Precision and reproducibility are independent:

- Geometry can be exact in its coordinate system while its historical extraction provenance is `DEPENDS_ON_PARSER`.
- A canonical range can be exact within a canonical text revision while reproducibility depends on normalization policy.
- A location with invalid binding or mismatched digest is unusable regardless of its stated precision.

### 4.7 Format-specific references

`FormatReference` carries provider/format-specific identity only when geometry or text ranges are insufficient:

```proto
message FormatReference {
  string format = 1;               // "docx", "xlsx", "html", "audio", ...
  string reference = 2;            // opaque, documented per format
  map<string, string> attributes = 3;
}
```

Provider-specific references must not become required for generic consumers. A DOCX paragraph ID, Markdown AST node ID, spreadsheet sheet/cell, or audio utterance ID can be represented here without forcing every consumer to understand it.

### 4.8 Multi-provider and multi-stage execution provenance

Dispatcher routing is outside this contract. The contract records durable facts about which execution produced which element.

Requirements:

- Document-level provenance contains an ordered registry of `ExtractionExecution` records.
- Every non-empty element `execution_id` resolves to a record in that registry.
- An element with no execution ID inherits the document's `primary_execution_id`; if no primary exists, its execution is explicitly unknown.
- Parent links use `ExecutionLink` with kind `RETRY`, `FALLBACK`, `INPUT_DEPENDENCY`, or `MERGE`.
- Per-page or per-region routing is represented by element-level execution references, not by a PDF-specific field.
- `ExecutionOutcome` is separate from `QualityReport`: an execution can fail while the document succeeds through another execution, and a successful execution can produce degraded-quality output.
- `EXECUTION_OUTCOME_FAILED` means that execution produced no usable output; it may still appear in history and parent links for failed/retried attempts.
- Extractor/model/policy versions are opaque identifiers. The contract does not encode routing decisions.

Graph integrity rules:

- Execution IDs are unique within a payload.
- Every parent `execution_id` resolves to a record in the same payload.
- Parent edges are directed from child execution to its predecessor/input; cycles are invalid.
- `RETRY` and `FALLBACK` describe replacement/attempt lineage; `INPUT_DEPENDENCY` and `MERGE` describe composition lineage.
- `execution_attribution=INHERITED` requires empty `execution_ids` and a document primary execution.
- `execution_attribution=EXPLICIT` requires non-empty `execution_ids`; every ID must resolve.
- `execution_attribution=UNKNOWN` requires empty `execution_ids` and explicitly records known absence of provenance; it does not inherit.
- An unresolved non-empty execution ID is a contract-integrity failure, never unknown.
- Unknown provenance (legacy/no execution record) is distinct from an unresolved reference.
- `ExecutionOutcome` describes execution result; `QualityReport` describes output quality. A failed execution may appear as history but contributes no usable elements.

Example: mixed PDF where page 1 text comes from a PDF parser and page 2 comes from OCR:

```json
{
  "provenance": {
    "primary_execution_id": "exec-pdf-1",
    "executions": [
      {"execution_id": "exec-pdf-1", "extractor_id": "pdf-extractor",
       "stage_id": "text", "outcome": "SUCCEEDED",
       "quality": {"status": "COMPLETE"}},
      {"execution_id": "exec-ocr-2", "extractor_id": "ocr-extractor",
       "stage_id": "ocr", "outcome": "PARTIAL",
       "parents": [{"execution_id": "exec-pdf-1", "kind": "FALLBACK"}],
       "quality": {"status": "DEGRADED"}}
    ]
  },
  "elements": [
    {"id": "p1-e1", "execution_ids": ["exec-pdf-1"]},
    {"id": "p2-e1", "execution_ids": ["exec-ocr-2"]}
  ]
}
```

### 4.9 Projections

`flattened_text` remains the legacy compatibility field. It is not automatically lossless and has no embedded loss report. A producer may emit a projection record with `projection_id = "flattened_text"` to describe it; consumers must treat absence of that record as legacy, limited guarantees.

```proto
message DocumentProjection {
  string projection_id = 1;        // e.g. "flattened_text", "markdown"
  string format = 2;               // "text", "markdown", "html", "json"
  string contract_id = 3;          // declared projection semantics
  string contract_version = 4;
  ProjectionOutcome outcome = 5;
  string unsupported_reason = 6;
  string digest = 7;
  oneof content {
    bytes inline_content = 8;
    PayloadReference reference = 9;
  }
  LossReport loss = 10;
}

enum ProjectionOutcome {
  PROJECTION_OUTCOME_UNSPECIFIED = 0;  // invalid; not a success claim
  PROJECTION_OUTCOME_SUCCESS_LOSSLESS = 1;
  PROJECTION_OUTCOME_SUCCESS_WITH_LOSS = 2;
  PROJECTION_OUTCOME_UNSUPPORTED = 3;
}

message LossReport {
  repeated string dropped = 1;     // "table_cell_span", "pdf_geometry", ...
  repeated string approximated = 2;
  repeated string unsupported = 3;
  repeated string reasons = 4;
}
```

Rules:

- `SUCCESS_WITH_LOSS` requires at least one loss entry or reason.
- `UNSUPPORTED` requires `unsupported_reason` and has no content.
- `SUCCESS_LOSSLESS` is evaluated against the declared `contract_id`/`contract_version` and its required preserved fields, not a subjective judgment.
- A projection contract must define required preserved fields for its target; omitted required fields make the outcome `SUCCESS_WITH_LOSS` or `UNSUPPORTED`.
- A projection must not silently flatten hierarchy, geometry, or source references.

### 4.10 Dispatcher boundary

The Content Extractor Dispatcher is a separate orchestration and routing layer. It may classify inputs, select extractors, compose page-level or region-level pipelines, manage retries, and reconcile outputs.

The contract must not encode:

- routing tables or preferred implementation names;
- extension/MIME routing rules;
- model endpoints or GPU scheduling policy;
- a second execution request/response model;
- another canonical document model.

The only dispatcher-related facts in the contract are durable extraction provenance: execution identity, extractor/version, stage, policy version, parent/fallback lineage, outcome, and quality status. This keeps #22 focused on the document model while allowing a future dispatcher to use it.

## 5. Compatibility mapping from the current contract

| Existing field | Target mapping | Notes |
|---|---|---|
| `StructuredPayload.payload_descriptor` | unchanged | Schema/processor descriptor remains |
| `StructuredPayload.inline_content` / `reference` | unchanged | Envelope remains common multimodal carrier |
| `DocumentPayload.media_type` | legacy field plus `source.media_type` | If both populated, they must match |
| `DocumentPayload.metadata` | unchanged | Document metadata remains opaque |
| `DocumentPayload.elements` | unchanged | Same reading order |
| `DocumentPayload.flattened_text` | unchanged | Legacy compatibility field; not source evidence |
| `DocumentElement.location.page` | `source_locations[].pdf_geometry.page_number` | Old field retained; map to a geometry record |
| `DocumentElement.location.bbox` | `source_locations[].pdf_geometry.bbox` | Coordinate system must be declared |
| `DocumentElement.id` | unchanged | Stable within payload; document identity added separately |
| `DocumentElement.child_ids` | unchanged | Hierarchy remains |
| `ContentProvenance` | unchanged | Already distinguishes embedded/OCR/generated/transcribed |
| `PayloadDescriptor.processor_id/version` | remains payload-level | Do not duplicate as element provenance |
| `DocumentElement` without execution IDs | `ExecutionAttribution=INHERITED` or explicit `UNKNOWN` | No execution identity is invented |
| Single-provider payload | one execution record or primary reference | Backward compatible |

### 5.1 Source identity consistency

- `DocumentPayload.source.media_type` is authoritative when populated.
- Legacy `DocumentPayload.media_type` must equal `source.media_type` when both are non-empty; mismatch invalidates the document for structured use.
- `SourceBinding.INHERIT_DOCUMENT` resolves to `DocumentPayload.source`; it does not repeat digest/version.
- An explicit or derived binding must identify a source that resolves to an immutable digest. A binding that cannot resolve has `REPRODUCIBILITY_NOT_REPRODUCIBLE` or is unusable.
- A location that repeats a digest/version must match the enclosing source unless its binding kind is `DERIVED_REPRESENTATION`.
- A stale location retained after source replacement is invalid; consumers must revalidate digest/version before use.

### 5.2 Legacy payload conversion

An old payload with `ElementLocation.page=1` and a bbox maps to one `PdfGeometry` source record:

- Geometry coordinates are exact in their declared coordinate system if the producer supplied them; historical extraction provenance remains approximate for evidence purposes.
- `precision=APPROXIMATE` unless the producer explicitly supplied exact semantics.
- `reproducibility` is `DEPENDS_ON_PARSER` or `NOT_REPRODUCIBLE`, never inferred as exact-from-source.
- No `TextRange` and no canonical range are inferred.
- Missing page/bbox maps to `UNAVAILABLE`; no zero geometry is created.
- `document_class=UNKNOWN` and `source` fields only when available; never guessed.

New payload to old consumer:

- Existing fields remain populated.
- Unknown fields are ignored by protobuf.
- Old consumers may use `ElementLocation` only when the new producer also populates it for compatibility.
- Old consumers cannot infer source/canonical ranges from geometry.

## 6. Schema evolution rules

1. `StructuredPayload` and `DocumentPayload` are evolved additively; no parallel document hierarchy.
2. Existing field numbers and meanings are immutable.
3. New fields are optional; no adapter is forced to emit unsupported capabilities.
4. `PayloadDescriptor.schema_version` changes for semantic schema changes; wire-compatible additive fields do not automatically change semantic capability versioning.
5. Unknown fields are ignored; consumers must not require unknown extensions.
6. `UNSPECIFIED` binding, coordinate, precision, or reproducibility values make a location invalid or unavailable, never exact.
7. Source format strings are extensible; document class is a controlled enum with `UNKNOWN`.
8. Provider-specific details live in `attributes`/`FormatReference`, not shared types.
9. Contract mirror updates are atomic across Platform and Content Extractor.
10. A capability declaration must describe which location types and precisions an adapter can produce.
11. Execution IDs are stable within a payload; every non-empty element execution ID must resolve to a registered execution.
12. `ExecutionAttribution` explicitly distinguishes inherited, explicit, and unknown provenance; unresolved IDs are invalid.
13. Parent relationships use typed `ExecutionLink` entries (`RETRY`, `FALLBACK`, `INPUT_DEPENDENCY`, `MERGE`); they are provenance, not routing decisions.
14. `ExecutionOutcome` and `QualityStatus` are distinct; failed executions may remain in history but contribute no usable output.
15. `DOCUMENT`, `SECTION`, `FIGURE`, and `UNKNOWN` are proposed structural types; table cells retain cell-level structure and optional locations.
16. Canonical offset units are explicit. `UTF16_CODE_UNIT` is the legacy Platform default, not a universal source unit.
17. Canonical text identity includes source, execution, normalization profile/version, projection contract/version, encoding, and digest; no canonical range is valid across a changed identity.

## 7. Concrete examples

### 7.1 PDF exact geometry

Source: a PDF with source digest `sha256:abc...`. Element is a heading on page 1; bbox is in PDF user space.

```json
{
  "document": {
    "identity": {"document_id": "doc-1", "document_class": "TEXT"},
    "source": {"media_type": "application/pdf", "source_format": "pdf",
                "source_digest": "sha256:abc..."},
    "elements": [{
      "id": "p1-e1",
      "type": "HEADING",
      "text": "Distributed Systems",
      "source_locations": [{
        "location_id": "loc-1",
        "source_binding": {"kind": "INHERIT_DOCUMENT"},
        "precision": "EXACT",
        "reproducibility": "EXACT_FROM_SOURCE",
        "pdf_geometry": {
          "page_number": 1,
          "bbox": {"x0": 72, "y0": 120, "x1": 420, "y1": 148},
          "coordinate_system": "PDF_USER_SPACE_BOTTOM_LEFT",
          "unit": "POINT",
          "rotation": 0
        }
      }]
    }]
  }
}
```

No text range is claimed. A bbox is not converted to a character offset.

### 7.2 PDF approximate geometry

```json
{
  "source_locations": [{
    "location_id": "loc-2",
    "source_binding": {"kind": "INHERIT_DOCUMENT"},
    "precision": "APPROXIMATE",
    "reproducibility": "DEPENDS_ON_PARSER",
    "pdf_geometry": {
      "page_number": 2,
      "coordinate_system": "PDF_USER_SPACE_BOTTOM_LEFT",
      "unit": "POINT",
      "rotation": 90
    }
  }]
}
```

The missing bbox remains absent; the consumer must not invent one.

### 7.3 Markdown exact source range and canonical identity

Source bytes are UTF-8; range is half-open and refers to the original Markdown bytes.

```json
{
  "source_locations": [{
    "location_id": "loc-src-1",
    "source_binding": {"kind": "INHERIT_DOCUMENT"},
    "precision": "EXACT",
    "reproducibility": "EXACT_FROM_SOURCE",
    "text_range": {
      "unit": "UTF8_BYTE",
      "coordinate_space": "ORIGINAL_SOURCE",
      "start": 120,
      "end": 245,
      "normalization": "NONE",
      "line_endings": "ORIGINAL"
    }
  }],
  "canonical_text_range": {
    "identity": {
      "canonical_text_id": "canon-1",
      "canonical_text_digest": "sha256:def...",
      "encoding": "UTF-16LE",
      "source_document_id": "doc-1",
      "source_digest": "sha256:abc...",
      "source_version": "v1",
      "extraction_execution_id": "exec-md-1",
      "normalization_profile": "platform-markdown",
      "normalization_version": "1",
      "projection_contract_id": "canonical-markdown-text",
      "projection_contract_version": "1"
    },
    "unit": "UTF16_CODE_UNIT",
    "start": 118,
    "end": 241,
    "normalization": "NFC",
    "line_endings": "LF"
  }
}
```

The source range and canonical range are deliberately different. They must not be substituted for each other.

### 7.4 Markdown unavailable source range

```json
{
  "source_locations": [{
    "location_id": "loc-unavail-1",
    "source_binding": {"kind": "INHERIT_DOCUMENT"},
    "precision": "UNAVAILABLE",
    "reproducibility": "NOT_REPRODUCIBLE",
    "text_range": {
      "unit": "UTF8_BYTE",
      "coordinate_space": "ORIGINAL_SOURCE",
      "normalization": "NONE",
      "line_endings": "ORIGINAL"
    }
  }]
}
```

Start/end are absent. A consumer must not search `flattened_text` to recover them.

### 7.5 One PDF element with geometry and an extracted-text-layer range

The repeated collection is required here: a single oneof alternative would lose one of these records. Raw PDF bytes are not a UTF-8 text source; the text range refers to an identified extracted text layer.

```json
{
  "id": "p1-e7",
  "source_locations": [
    {
      "location_id": "loc-geom",
      "source_binding": {"kind": "INHERIT_DOCUMENT"},
      "precision": "EXACT",
      "reproducibility": "DEPENDS_ON_PARSER",
      "pdf_geometry": {"page_number": 1, "coordinate_system": "PDF_USER_SPACE_BOTTOM_LEFT", "unit": "POINT", "rotation": 0}
    },
    {
      "location_id": "loc-text",
      "source_binding": {"kind": "DERIVED_REPRESENTATION", "origin_document_id": "doc-1", "origin_source_digest": "sha256:abc...", "origin_source_version": "v1"},
      "precision": "EXACT",
      "reproducibility": "DEPENDS_ON_PARSER",
      "text_range": {
        "unit": "UTF8_BYTE",
        "coordinate_space": "SOURCE_TEXT_LAYER",
        "start": 300,
        "end": 420,
        "normalization": "NONE",
        "line_endings": "ORIGINAL",
        "text_layer": {
          "text_layer_id": "pdf-text-layer-1",
          "text_layer_digest": "sha256:layer...",
          "encoding": "UTF-8",
          "origin_document_id": "doc-1",
          "origin_source_digest": "sha256:abc...",
          "origin_source_version": "v1",
          "derivation_execution_id": "exec-pdf-1",
          "extraction_layer_version": "1"
        }
      }
    }
  ]
}
```

Here the range is exact within the materialized text layer, but reproducing that layer depends on the extractor/parser. `DEPENDS_ON_PARSER` is therefore the correct reproducibility state unless the contract explicitly defines reproducibility as byte-for-byte stability within an already materialized layer.

### 7.6 Legacy payload conversion

An old payload with `ElementLocation.page=1` and a bbox maps to one `PdfGeometry` record:

- `precision=APPROXIMATE` and `reproducibility=DEPENDS_ON_PARSER` for historical evidence.
- No source text range.
- No canonical range.
- `document_class=UNKNOWN`.
- `source` fields only if available from existing metadata; otherwise absent.

## 8. Loss-aware projections

| Projection | Preserved | Lost / reported |
|---|---|---|
| `flattened_text` | Element text and reading order | Page/bbox, source ranges, hierarchy, table cell spans, code fences, metadata |
| Markdown | Headings, paragraphs, lists, code, some tables | PDF geometry, source digests, precise table spans, element IDs unless annotated |
| HTML | Headings, lists, tables, links | PDF geometry, source ranges, provider references |
| JSON | Full represented model | Only fields explicitly omitted by the exporter; no silent loss |
| Legacy `DocumentPayload` | Existing fields 1-4 | New identity/source/location/projection precision unless mapped |

Projection records use `ProjectionOutcome`:

- `SUCCESS_LOSSLESS` — all relevant represented information is preserved.
- `SUCCESS_WITH_LOSS` — output exists and a `LossReport` explains what was dropped or approximated.
- `UNSUPPORTED` — no output; `unsupported_reason` is required.

`flattened_text` is a legacy field with explicitly limited guarantees. A producer may add a projection record with `projection_id="flattened_text"`; absence of that record means old behavior and no lossless claim.

## 9. Verification test design

### 9.1 Serialization and compatibility

1. Old `DocumentPayload` with fields 1-4 round-trips byte-equivalently through new schema.
2. New payload ignored by an old consumer still yields old fields correctly.
3. Unknown fields are preserved or ignored without failure according to protobuf rules.
4. Schema-version mismatch is rejected or handled by documented compatibility policy.
5. Mirrored proto contract comparison passes.

### 9.2 Location semantics

1. PDF page/bbox exact location round-trips with coordinate system, units, rotation.
2. PDF missing bbox remains `UNAVAILABLE`; no zero bbox is fabricated.
3. PDF rotation 90/180/270 is retained, not normalized away.
4. Markdown UTF-8 original byte range round-trips at code-point boundaries.
5. Markdown range with CRLF source and LF canonical text retains both coordinate spaces separately.
6. Unicode NFC/NFKC cases do not silently reuse offsets from the other normalization.
7. Half-open range test: `[start,end)` selects exactly the expected bytes/code units.
8. Approximate location remains `APPROXIMATE`; unavailable remains `UNAVAILABLE`.
9. No negative test permits offset recovery by searching flattened/canonical text.
10. PDF `SOURCE_TEXT_LAYER` range without matching `TextLayerIdentity` is invalid.
11. PDF raw bytes are never accepted as an `ORIGINAL_SOURCE` UTF-8 range.
12. Precision validation follows the per-location criteria in 4.6.1; an invalid binding/digest overrides an `EXACT` claim.

### 9.3 Projection loss

1. Table with row/column spans projected to Markdown reports lost geometry/spans.
2. Nested lists projected to flat text report lost hierarchy.
3. PDF geometry projected to Markdown reports lost geometry.
4. JSON projection reports no silent loss.
5. Unsupported projection returns `UNSUPPORTED` with reason, not partial success.

### 9.4 Multiple locations and canonical identity

1. One element with both `PdfGeometry` and `TextRange` records retains both after round-trip.
2. Two source records with different location IDs, precision, and reproducibility remain independently addressable.
3. A canonical range with source/execution/normalization identity round-trips.
4. A canonical range whose source digest or execution ID does not match is rejected or reported unavailable.
5. A changed normalization profile/version invalidates old canonical ranges; no silent reuse.
6. Hash test confirms normalization occurs before line-ending conversion/encoding and digest is over the exact encoded bytes.
7. BOM, encoding-name casing, and line-ending differences produce the documented identity/digest result; BOM is excluded.
8. A projection contract/version change produces a new canonical identity.
9. `INHERIT_DOCUMENT` resolves to the document source; explicit matching binding succeeds; mismatched explicit binding fails validation.
10. Source replacement with stale digest/version produces an invalid/unavailable result, not exact evidence.
11. `UNSPECIFIED` binding, unit, coordinate space, precision, or reproducibility is not treated as exact.

### 9.5 Projection outcomes

1. Lossless projection reports `SUCCESS_LOSSLESS`.
2. Lossy projection reports `SUCCESS_WITH_LOSS` with non-empty loss/reason.
3. Unsupported projection reports `UNSUPPORTED` and no content.
4. `flattened_text` without a projection record is treated as legacy, not lossless.
5. A projection record with `projection_id="flattened_text"` describes its loss explicitly.
6. A projection is `SUCCESS_LOSSLESS` only when the declared projection contract/version is preserved.
7. A contract-required field that is omitted forces `SUCCESS_WITH_LOSS` or `UNSUPPORTED`.

### 9.6 Multi-provider provenance

1. Mixed PDF pages record distinct execution IDs for text and OCR elements.
2. Fallback execution records an `ExecutionLink` of kind `FALLBACK`.
3. Retry, merge, and input-dependency links remain distinguishable.
4. Every non-empty element execution ID resolves to an execution record; unresolved IDs fail validation.
5. An element without execution IDs inherits the primary execution, or is reported unknown for legacy payloads.
6. A merged element can reference multiple execution IDs without losing lineage.
7. `ExecutionOutcome.FAILED` can coexist with a successful document and contributes no usable output.
8. Duplicate execution IDs fail validation.
9. Parent references resolve; cycles are rejected; retry/fallback/merge directions are preserved.
10. Missing execution reference fails; explicit unknown provenance is accepted and distinct.
11. Quality degradation is distinct from execution failure.
12. No dispatcher routing table, model endpoint, or policy decision is serialized into the document contract.

### 9.7 Evidence compatibility

1. Historical P1/A2/A4 evidence maps to canonical elements with stable identity where possible.
2. Changed or missing coordinates produce explicit `APPROXIMATE`/`UNAVAILABLE` outcomes.
3. Frozen reference artifacts, gold labels, denominators, and metrics remain unchanged.
4. A compatibility report distinguishes mapped, approximate, unavailable, and unsupported evidence.

## 10. Gate assessment

### #22 — contract design

**Ready for final design approval, not schema implementation approval.**

- No architectural blocker remains in the proposal.
- The original exclusive-location blocker is resolved.
- PDF text layers are a defined prerequisite rather than an implicit range.
- Origin versus derived representation identity is explicit.
- Canonical hashing, execution-graph integrity, execution attribution, precision validation, and projection contracts are specified.
- Remaining field/enum approvals and narrow policy choices are recorded in section 11.

### #20 / #21 — adapter capabilities

**Conditionally ready for design, not implementation.**

- Multiple simultaneous location records are now representable; the exclusive-oneof blocker is resolved in the proposal.
- PDF geometry semantics are specified (page number, bbox, origin, units, rotation, per-record precision).
- PDF text ranges require approved `SOURCE_TEXT_LAYER`/`TextLayerIdentity`; raw PDF bytes are not an original text source.
- Markdown original and canonical ranges are specified separately, with canonical identity binding.
- PDF/Markdown adapters still need concrete source-range extraction support.
- The existing `PdfPayload`/`MarkdownPayload` alternatives are rejected: adapters target the common `DocumentPayload`.
- Readiness to implement is blocked until schema fields, validation rules, and mirroring are approved.
- Element-level execution provenance is sufficient for mixed PDF/OCR results; dispatcher routing remains out of scope.

### #950 — production ownership transition

**Blocked for implementation.**

- #950 requires SCEP payload to supply the structure Platform currently reconstructs locally.
- Proposal defines the target mapping, but no production mapping exists.
- Production `ExtractionStage` still calls local `DocumentStructureService` before SCEP.
- Required acceptance tests (instrumented zero local-parser invocation) can be designed now, but cannot pass until #950 implementation and #20/#21 data are available.
- #950 must not depend on exact source ranges for initial removal; it can use explicit `UNAVAILABLE` coordinates.

### #947 — historical evidence compatibility

**Blocked.**

- Needs approved #22 schema and #20/#21 adapter output.
- Needs a frozen compatibility reference and explicit mapping of historical evidence coordinates.
- Exact mapping is impossible for missing/approximate locations; those must be reported.
- No frozen artifact, denominator, gold annotation, or metric may change.

## 11. Proposed dispositions and remaining decisions

The following defaults are proposed for approval; they are not yet normative.

| Area | Proposed disposition | Remaining approval |
|---|---|---|
| Canonical unit | UTF-16 code units for legacy Platform canonical ranges; all units explicit; no silent conversion | Whether multiple canonical units may coexist |
| Legacy PDF precision | Geometry may be exact in its coordinate system; historical evidence precision is `APPROXIMATE`; no source text range inferred | Validation rule for old payloads |
| PDF text ranges | `SOURCE_TEXT_LAYER` + `TextLayerIdentity`; origin and derived representation identities are distinct | Approve extraction-layer identity semantics |
| Canonical hashing | Decode -> normalize -> line endings -> encode -> SHA-256; BOM exclusion is decided and applied universally | Approve supported encoding profile/versioning |
| Source binding | `INHERIT_DOCUMENT` / `EXPLICIT_ORIGIN` / `DERIVED_REPRESENTATION`; origin and derived representation identities are separate (resolved) | Approve exact linking/versioning policy for derived representations |
| Element types | Add `DOCUMENT`, `SECTION`, `FIGURE`, `UNKNOWN`; retain table row/cell types | Whether `DOCUMENT` is an element or root metadata |
| Table cells | Cell-level elements and optional source locations | Whether cell spans need dedicated fields |
| Schema version | Separate wire compatibility from semantic capability version | Exact versioning policy |
| Exporter ownership | Content Extractor owns canonical projections | Whether a separate exporter service is ever needed |
| Execution provenance | Explicit `INHERITED` / `EXPLICIT` / `UNKNOWN` attribution; typed links; unknown and unresolved are different | Approve attribution enum/field numbers |
| Execution graph | Unique IDs; resolvable parents; no cycles; unknown vs unresolved distinct | None beyond approval |
| Precision | Per-location validation criteria in 4.6.1 | Approval of criteria |
| Projection | Contract-relative lossless/lossy/unsupported with `contract_id/version` | Approval of projection contracts |
| Canonical identity | Includes source, execution, normalization, projection contract, encoding, and digest | Approve hash/profile versioning |
| Quality | Execution outcome is separate from quality status | Which quality metrics are durable |

Remaining approval items:

1. Approve `TextLayerIdentity` and extraction-layer versioning for PDF text ranges.
2. Approve the supported canonical encoding profile/versioning. BOM exclusion is already decided.
3. Approve `ExecutionAttribution` field/enum semantics.
4. Decide whether `DOCUMENT` is an element or only root metadata.
5. Approve the derived-representation linking/versioning policy; the origin/representation separation itself is resolved.
6. Define table cell-span/versioning requirements.
7. Decide whether audio/video transcripts use the document class or remain modality payloads.
8. Decide whether merged elements need a dedicated merge record in addition to repeated execution IDs.

## 12. Readiness conclusion

- **Contract design:** Ready for final design approval. This is not schema implementation approval.
- **#20:** Conditional; PDF text-range parity requires approval of `SOURCE_TEXT_LAYER`/`TextLayerIdentity`.
- **#21:** Conditional; Markdown structure and source/canonical range semantics are substantially specified.
- **#950:** Blocked until an approved production mapping from SCEP payload exists; unavailable-coordinate handling can proceed independently.
- **#947:** Blocked until contract approval, adapter output, and a frozen compatibility reference exist.

No production implementation, contract modification, migration, benchmark execution, or baseline change is authorized by this proposal.
