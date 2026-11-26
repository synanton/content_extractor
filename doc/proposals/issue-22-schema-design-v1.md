# Issue #22 Schema Design v1

**Status:** Proposed schema design. Non-normative. No `.proto` files are changed here.  
**Baseline:** #22 conceptual contract approved.  
**Scope:** Translate approved semantics into concrete protobuf field allocation, validation rules, compatibility behavior, and verification cases.  
**Guardrail:** Schema approval must be separate from implementation approval. Adapters, migration, benchmarks, and baselines remain frozen.

## 1. Design rules

- Preserve `StructuredPayload` as the common multimodal envelope.
- Evolve `DocumentPayload` and `DocumentElement` additively; no `PdfPayload`, `DocxPayload`, or `MarkdownPayload`.
- Preserve existing field numbers and meanings.
- New fields are optional; old producers remain readable.
- Provider-specific models remain inside adapters.
- Validation failures must be explicit; never silently reinterpret unknown or unavailable semantics.

## 2. DocumentPayload

### 2.1 Field allocation

Existing fields remain:

```proto
message DocumentPayload {
  string media_type = 1;
  map<string, string> metadata = 2;
  repeated DocumentElement elements = 3;
  string flattened_text = 4;

  DocumentIdentity identity = 5;
  SourceDescriptor source = 6;
  ExtractionProvenance provenance = 7;
  repeated DocumentProjection projections = 8;
}
```

### 2.2 Identity

```proto
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
```

`DOCUMENT` is the logical root and is not a `DocumentElement`. Source format remains in `SourceDescriptor`; it is not a document class.

### 2.3 Source descriptor

```proto
message SourceDescriptor {
  string media_type = 1;
  string source_format = 2;       // extensible: pdf, docx, markdown, html, ...
  string source_digest = 3;       // immutable, e.g. sha256:...
  string source_version = 4;
  string source_uri = 5;          // optional, access-controlled
  string filename = 6;            // optional
}
```

Rules:

- `source.media_type` is authoritative when populated.
- Legacy `DocumentPayload.media_type` must match `source.media_type` when both are non-empty.
- `source_digest` must identify immutable source bytes/content.
- Missing source identity maps to explicit unknown/unavailable, never synthesized.

## 3. DocumentElement and typed locations

### 3.1 Field allocation

```proto
message DocumentElement {
  string id = 1;
  DocumentElementType type = 2;
  ElementLocation location = 3;
  string text = 4;
  ContentProvenance provenance = 5;
  int32 level = 6;
  repeated string child_ids = 7;
  map<string, string> attributes = 8;
  string alternate_representation = 9;

  repeated SourceLocation source_locations = 10;
  CanonicalTextRange canonical_text_range = 11;
  repeated string execution_ids = 12;
  ExecutionAttribution execution_attribution = 13;
}
```

`ElementLocation` remains unchanged for backward compatibility. New producers may populate both `ElementLocation` and `source_locations` when compatible.

### 3.2 Element types

Existing values 0–15 remain. Proposed additions:

```proto
enum DocumentElementType {
  ELEMENT_TYPE_UNSPECIFIED = 0;
  ELEMENT_PARAGRAPH  = 1;
  ELEMENT_HEADING    = 2;
  ELEMENT_LIST       = 3;
  ELEMENT_LIST_ITEM  = 4;
  ELEMENT_TABLE      = 5;
  ELEMENT_TABLE_ROW  = 6;
  ELEMENT_TABLE_CELL = 7;
  ELEMENT_IMAGE      = 8;
  ELEMENT_FORMULA    = 9;
  ELEMENT_CAPTION    = 10;
  ELEMENT_FOOTNOTE   = 11;
  ELEMENT_HEADER     = 12;
  ELEMENT_FOOTER     = 13;
  ELEMENT_CODE_BLOCK = 14;
  ELEMENT_PAGE_BREAK = 15;

  ELEMENT_SECTION = 16;
  ELEMENT_FIGURE  = 17;
  ELEMENT_UNKNOWN = 18;
}
```

All proposed enum values are package-scoped under `synanton.extraction.v1` and must be globally unique. Existing values 0–15 are shown exactly; additions 16–18 use the existing `ELEMENT_` prefix.

`DOCUMENT` is not added as an element type. `SECTION` and `FIGURE` are structural distinctions; `UNKNOWN` is explicit, never silently mapped to `PARAGRAPH`.

### 3.3 SourceLocation records

An element may have multiple source records. The oneof is per record.

```proto
message SourceLocation {
  string location_id = 1;
  SourceBinding source_binding = 2;
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
  string origin_document_id = 2;
  string origin_source_digest = 3;
  string origin_source_version = 4;
}

message FormatReference {
  string format = 1;               // docx, xlsx, html, audio, ...
  string reference = 2;            // documented format-specific reference
  map<string, string> attributes = 3;
  string representation_id = 4;    // required for derived representations
  string representation_digest = 5;
}

enum BindingKind {
  BINDING_KIND_UNSPECIFIED = 0;
  BINDING_KIND_INHERIT_DOCUMENT = 1;
  BINDING_KIND_EXPLICIT_ORIGIN = 2;
  BINDING_KIND_DERIVED_REPRESENTATION = 3;
}

enum LocationPrecision {
  LOCATION_PRECISION_UNSPECIFIED = 0;
  LOCATION_PRECISION_EXACT = 1;
  LOCATION_PRECISION_APPROXIMATE = 2;
  LOCATION_PRECISION_UNAVAILABLE = 3;
}

enum Reproducibility {
  REPRODUCIBILITY_UNSPECIFIED = 0;
  REPRODUCIBILITY_EXACT_FROM_SOURCE = 1;
  REPRODUCIBILITY_DEPENDS_ON_PARSER = 2;
  REPRODUCIBILITY_DEPENDS_ON_NORMALIZATION = 3;
  REPRODUCIBILITY_NOT_REPRODUCIBLE = 4;
}
```

Validation:

- `UNSPECIFIED` binding/precision/reproducibility is invalid for a usable location.
- `INHERIT_DOCUMENT` resolves to `DocumentPayload.source`; explicit origin must match.
- `DERIVED_REPRESENTATION` requires a representation identity in the location variant.
- Invalid or stale source binding makes the location unusable.
- `DERIVED_REPRESENTATION` with `FormatReference` requires `representation_id` and `representation_digest`.
- `TextRange.coordinate_space` permits only `ORIGINAL_SOURCE` or `SOURCE_TEXT_LAYER`; canonical coordinates use `CanonicalTextRange`.

### 3.4 Text ranges and text layers

```proto
message TextRange {
  OffsetUnit unit = 1;
  CoordinateSpace coordinate_space = 2;
  int64 start = 3;                 // inclusive
  int64 end = 4;                   // exclusive
  TextNormalization normalization = 5;
  LineEndingPolicy line_endings = 6;
  TextLayerIdentity text_layer = 7;
}

enum OffsetUnit {
  OFFSET_UNIT_UNSPECIFIED = 0;
  OFFSET_UNIT_UTF8_BYTE = 1;
  OFFSET_UNIT_UTF16_CODE_UNIT = 2;
  OFFSET_UNIT_UNICODE_CODE_POINT = 3;
}

enum CoordinateSpace {
  COORDINATE_SPACE_UNSPECIFIED = 0;
  COORDINATE_SPACE_ORIGINAL_SOURCE = 1;
  COORDINATE_SPACE_SOURCE_TEXT_LAYER = 2;
  // Canonical coordinates use CanonicalTextRange, not TextRange.
}

enum TextNormalization {
  TEXT_NORMALIZATION_UNSPECIFIED = 0;
  TEXT_NORMALIZATION_NONE = 1;
  TEXT_NORMALIZATION_NFC = 2;
  TEXT_NORMALIZATION_NFKC = 3;
  TEXT_NORMALIZATION_CUSTOM = 4;
}

enum LineEndingPolicy {
  LINE_ENDING_UNSPECIFIED = 0;
  LINE_ENDING_ORIGINAL = 1;
  LINE_ENDING_LF = 2;
  LINE_ENDING_CRLF = 3;
}

message TextLayerIdentity {
  string text_layer_id = 1;
  string text_layer_digest = 2;
  string encoding = 3;
  string origin_document_id = 4;
  string origin_source_digest = 5;
  string origin_source_version = 6;
  string derivation_execution_id = 7;
  string extraction_layer_version = 8;
}
```

Rules:

- Half-open ranges `[start, end)`.
- `ORIGINAL_SOURCE` is valid only for text sources; PDF raw bytes are binary.
- PDF text ranges require `SOURCE_TEXT_LAYER` and `TextLayerIdentity`.
- `text_layer_digest` hashes the exact extracted text bytes in `encoding`.
- No unit conversion is implicit.
- Text layer is a derived representation; `SourceBinding.DERIVED_REPRESENTATION` binds to origin.

Text-layer integrity rules:

- `derivation_execution_id` must resolve in `ExtractionProvenance.executions`.
- `origin_document_id`, `origin_source_digest`, and `origin_source_version` must match the enclosing source and source binding.
- `text_layer_digest` must hash the exact extracted text bytes in `encoding`; changing bytes or encoding requires a new text-layer identity.
- A `SOURCE_TEXT_LAYER` range with missing, unresolved, or inconsistent `TextLayerIdentity` is invalid.
- A text layer cannot be used as origin identity.

### 3.5 PDF geometry

```proto
message PdfGeometry {
  int32 page_number = 1;           // 1-based; 0 unavailable
  BoundingBox bbox = 2;
  string coordinate_system = 3;    // required
  string unit = 4;                 // required
  int32 rotation = 5;              // 0/90/180/270
  string transform = 6;            // optional documented transform
}
```

Rules:

- Existing PDF contract: user-space points, origin bottom-left.
- Platform PDFBox top-left adjusted coordinates require transformation or explicit labeling.
- Missing geometry is `UNAVAILABLE`; no zero bbox fabrication.
- Rotation is retained.

Validation combinations:

| page_number | bbox | Result |
|---:|---|---|
| 0 | absent | Geometry unavailable; precision must be `UNAVAILABLE` |
| >0 | absent | Page known, geometry unavailable; no fabricated bbox |
| 0 | present | Invalid |
| >0 | present | Valid only with finite coordinates, `x0 <= x1`, `y0 <= y1`, non-empty coordinate system/unit |

- `rotation` is 0, 90, 180, or 270; no other values are valid in v1.
- Bbox coordinates are in the declared `coordinate_system`; rotation metadata describes the page orientation and must not be applied implicitly.
- A bbox without a page number or coordinate semantics is invalid, not approximate.

### 3.6 Canonical text

```proto
message CanonicalTextRange {
  CanonicalTextIdentity identity = 1;
  OffsetUnit unit = 2;
  int64 start = 3;                 // inclusive
  int64 end = 4;                   // exclusive
  TextNormalization normalization = 5;
  LineEndingPolicy line_endings = 6;
}

message CanonicalTextIdentity {
  string canonical_text_id = 1;
  string canonical_text_digest = 2;
  string encoding = 3;
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

Hash rule:

1. Decode source text using declared encoding.
2. Apply Unicode normalization profile.
3. Apply line-ending policy.
4. Encode using declared canonical encoding.
5. SHA-256 the complete bytes.

BOM bytes are excluded. Offsets are into the normalized text in `unit`; digest encoding is separate. Any source/execution/normalization/projection/encoding change creates a new canonical identity.

## 4. Execution provenance

### 4.1 Field allocation

```proto
message ExtractionProvenance {
  string primary_execution_id = 1;
  repeated ExtractionExecution executions = 2;
}

message ExtractionExecution {
  string execution_id = 1;
  string extractor_id = 2;
  string extractor_version = 3;
  string engine_id = 4;
  string engine_version = 5;
  string stage_id = 6;
  string policy_id = 7;
  string policy_version = 8;
  ExecutionOutcome outcome = 9;
  repeated ExecutionLink parents = 10;
  QualityReport quality = 11;
  map<string, string> attributes = 12;
}

enum ExecutionOutcome {
  EXECUTION_OUTCOME_UNSPECIFIED = 0;
  EXECUTION_OUTCOME_SUCCEEDED = 1;
  EXECUTION_OUTCOME_PARTIAL = 2;
  EXECUTION_OUTCOME_FAILED = 3;
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

enum ExecutionAttribution {
  EXECUTION_ATTRIBUTION_UNSPECIFIED = 0;
  EXECUTION_ATTRIBUTION_INHERITED = 1;
  EXECUTION_ATTRIBUTION_EXPLICIT = 2;
  EXECUTION_ATTRIBUTION_UNKNOWN = 3;
  EXECUTION_ATTRIBUTION_NONE_KNOWN = 4; // candidate; requires explicit schema decision
}

message QualityReport {
  QualityStatus status = 1;
  optional double completeness = 2; // unset = unknown; 0.0 = measured zero
  optional double confidence = 3;   // unset = unknown; 0.0 = measured zero
  repeated string warnings = 4;
}

enum QualityStatus {
  QUALITY_STATUS_UNSPECIFIED = 0;
  QUALITY_STATUS_COMPLETE = 1;
  QUALITY_STATUS_DEGRADED = 2;
  QUALITY_STATUS_PARTIAL = 3;
  QUALITY_STATUS_UNKNOWN = 4;
}

Presence rule: `completeness`/`confidence` use proto3 presence. Unset means unknown; a set value of `0.0` means a measured zero. Consumers must not treat unset as zero.
```

### 4.2 execution_ids membership rule

`execution_ids` is the set of executions **directly responsible for producing that element**.

- Empty + `INHERITED`: inherit `primary_execution_id`; under the inheritance convention, the primary execution is the direct producer.
- Empty + `UNKNOWN`: provenance unavailable/cannot be established.
- Empty + `NONE_KNOWN`: provenance is known not to exist.
- Non-empty + `EXPLICIT`: all IDs resolve; duplicates invalid.
- Order has no semantic meaning. Serializers must emit deterministic order (lexicographic).
- A merged element references the merge execution.
- The merge execution's `MERGE` links identify contributing executions.
- Contributors are not copied into the element's `execution_ids` unless they directly produced the element.
- `UNKNOWN` is not used for unresolved IDs; unresolved non-empty IDs are invalid.

### 4.3 NONE_KNOWN decision (required)

`NONE_KNOWN` is a candidate value, not an approved default. Choose one:

- **Approve:** reserve enum value 4 and define its validation semantics; empty IDs plus `NONE_KNOWN` is valid known absence.
- **Defer:** omit value 4 from the initial schema; keep only `INHERITED`, `EXPLICIT`, and `UNKNOWN`; do not use `UNKNOWN` for known absence.

Implementation must not begin with this decision implicit.

### 4.4 Graph integrity

- Execution IDs are unique within a payload.
- Parent references resolve.
- Parent edge direction: child execution → predecessor/input.
- Cycles are invalid.
- Retry/fallback/merge/input-dependency relationships remain typed.

## 5. Projections

```proto
message DocumentProjection {
  string projection_id = 1;
  string format = 2;
  string contract_id = 3;
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
  PROJECTION_OUTCOME_UNSPECIFIED = 0;
  PROJECTION_OUTCOME_SUCCESS_LOSSLESS = 1;
  PROJECTION_OUTCOME_SUCCESS_WITH_LOSS = 2;
  PROJECTION_OUTCOME_UNSUPPORTED = 3;
}

message LossReport {
  repeated string dropped = 1;
  repeated string approximated = 2;
  repeated string unsupported = 3;
  repeated string reasons = 4;
}
```

`flattened_text` remains legacy and is not lossless by default. A projection record with `projection_id="flattened_text"` may describe it. Losslessness is relative to `contract_id`/`contract_version`.

## 6. Compatibility mapping

| Current | Schema-design mapping |
|---|---|
| `DocumentPayload.media_type` | retain field 1; reconcile with `source.media_type` |
| `DocumentPayload.metadata/elements/flattened_text` | unchanged |
| `DocumentElement.location` | Retain legacy field; map to `PdfGeometry` only when source format is PDF and coordinate semantics are established; otherwise preserve legacy and report new source location unavailable/unresolved |
| `DocumentElement.id/child_ids/level` | unchanged |
| `ContentProvenance` | unchanged |
| No execution IDs | `INHERIT` only when primary exists; otherwise `UNKNOWN` |
| Old PDF geometry with established semantics | `APPROXIMATE`, `DEPENDS_ON_PARSER`; no text range |
| Old non-PDF/generic location | No `PdfGeometry` manufactured; preserve legacy; no source location inference |
| Old payload | `DocumentClass.UNKNOWN`; no inferred source identity |

Old consumers ignore new fields. New consumers must not infer exact source/canonical coordinates from old payloads.

## 7. Validation matrix

| Case | Expected |
|---|---|
| Existing fields 1-4 only | Valid legacy payload; unknown new fields absent |
| Old PDF `ElementLocation` with established PDF semantics | Geometry record approximate; no text range |
| Old non-PDF/generic `ElementLocation` | Preserve legacy; no `PdfGeometry`; source location unavailable/unresolved |
| Duplicate execution ID | Invalid |
| Unresolved execution ID | Invalid |
| Empty + `INHERITED` without primary | Invalid/unavailable |
| Empty + `UNKNOWN` | Valid unavailable provenance |
| Empty + `NONE_KNOWN` | Valid only if schema decision is Approve; invalid/unsupported if deferred |
| `TextRange.coordinate_space=CANONICAL_TEXT` | Invalid; use `CanonicalTextRange` |
| PDF `page_number=0` with bbox | Invalid |
| PDF bbox with non-finite/reversed coordinates | Invalid |
| PDF bbox without page/coordinate system/unit | Invalid |
| Text layer derivation execution unresolved | Invalid |
| Text layer origin/source digest mismatch | Invalid |
| `SOURCE_TEXT_LAYER` without `TextLayerIdentity` | Invalid |
| Quality completeness/confidence unset | Unknown; not 0.0 |
| Multiple source locations | All retained independently |
| PDF text range without text layer | Invalid/unavailable |
| PDF raw bytes as original text range | Invalid |
| Canonical digest/BOM/encoding mismatch | New identity or invalid |
| Projection lossless with omitted required field | Invalid; use lossy/unsupported |
| Source media type mismatch | Invalid |
| Stale source digest/version | Invalid/unavailable |
| Cycle in execution links | Invalid |

## 8. Test design

- Legacy payload round-trip and old-consumer compatibility.
- Old PDF geometry maps only with established PDF semantics; old Markdown/HTML locations do not become PDF geometry.
- `TextRange` rejects canonical coordinate space.
- Multiple location records on one element.
- PDF geometry and text-layer range coexistence.
- PDF page/bbox combination validation and rotation values.
- Text-layer derivation/origin/digest referential integrity.
- Inherited attribution treats primary execution as direct producer.
- Unset quality fields remain unknown; explicit 0.0 remains zero.
- Markdown original-source and canonical ranges.
- Unicode normalization, line endings, BOM exclusion, half-open ranges.
- Source binding inheritance/mismatch/stale source.
- Canonical identity changes on source, execution, normalization, projection, encoding.
- Execution ID uniqueness, parent resolution, cycles, attribution states, merge ancestry.
- Projection lossless/lossy/unsupported outcomes.
- Frozen evidence mapping produces explicit approximate/unavailable outcomes.

## 9. Approval and implementation boundary

This schema design is ready for review only if:

- Field numbers and enum values are accepted.
- `NONE_KNOWN` is explicitly marked **Approve** or **Defer** using the decision in 4.3.
- `execution_ids` direct-production/set semantics are accepted.
- Validation rules are accepted as normative.
- `FormatReference` and `QualityReport` definitions are accepted, including optional presence semantics.
- Exact enum names/values and package-level uniqueness are accepted.

Approval of this document would authorize a separate protobuf change PR. It would not authorize adapters, #20/#21, #950, or #947.

No protobuf, production source, adapter, migration, benchmark, fixture, or baseline changes are included in this document.
