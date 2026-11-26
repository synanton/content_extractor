# Issue #22 — Document Contract Evolution Proposal

**Status:** Review proposal. Non-normative. No protobuf or production behavior is changed here.  
**Scope:** Content Extractor contract evolution for format-neutral documents and typed source locations.  
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
  repeated string parent_execution_ids = 9; // fallback/merge lineage
  QualityReport quality = 10;
  map<string, string> attributes = 11;
}

message QualityReport {
  QualityStatus status = 1;
  double completeness = 2;          // 0.0..1.0, optional
  double confidence = 3;            // 0.0..1.0, optional
  repeated string warnings = 4;
}

enum QualityStatus {
  QUALITY_STATUS_UNSPECIFIED = 0;
  QUALITY_STATUS_COMPLETE = 1;
  QUALITY_STATUS_DEGRADED = 2;
  QUALITY_STATUS_PARTIAL = 3;
  QUALITY_STATUS_FAILED = 4;
}
```

`PayloadDescriptor` remains the payload-level schema/processor descriptor. `ExtractionProvenance` is document-level and must not duplicate schema identity.

### 4.2 Element source/canonical locations

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
  SourceLocation source_location = 10;
  CanonicalTextRange canonical_text_range = 11;

  // Execution(s) that produced this element. Empty inherits the document's
  // primary execution. Repeated permits merged/multi-stage elements.
  repeated string execution_ids = 12;
}
```

`ElementLocation` remains valid for compatibility and continues to mean page/PDF bbox when present. New code should prefer `SourceLocation` for explicit semantics.

### 4.3 Typed SourceLocation

`SourceLocation` is a typed union, not one universal offset object.

```proto
message SourceLocation {
  string document_id = 1;          // optional; inherits document identity if empty
  string source_digest = 2;        // optional explicit binding
  string source_version = 3;       // optional explicit binding
  LocationPrecision precision = 4;
  Reproducibility reproducibility = 5;

  oneof location {
    TextRange text_range = 10;
    PdfGeometry pdf_geometry = 11;
    FormatReference format_reference = 12;
  }
}

enum LocationPrecision {
  LOCATION_PRECISION_UNSPECIFIED = 0; // treat as UNAVAILABLE, never EXACT
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

Invariant: a present `SourceLocation` is not automatically exact. `precision` and `reproducibility` are mandatory contract semantics; `UNSPECIFIED` must be treated as unavailable/unknown by consumers.

### 4.4 Text ranges

```proto
message TextRange {
  OffsetUnit unit = 1;
  CoordinateSpace coordinate_space = 2;
  int64 start = 3;                 // inclusive
  int64 end = 4;                   // exclusive
  TextNormalization normalization = 5;
  LineEndingPolicy line_endings = 6;
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
  COORDINATE_SPACE_CANONICAL_TEXT = 2;
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
```

Recommended defaults:

- Original UTF-8 text source: `UTF8_BYTE`, `ORIGINAL_SOURCE`, `start/end` half-open, `NONE` normalization, `ORIGINAL` line endings.
- Canonical text: explicit unit (Platform currently uses UTF-16 code units), `CANONICAL_TEXT`, documented normalization and LF policy.
- No canonical offset is valid as an `ORIGINAL_SOURCE` location.

### 4.5 Canonical text range

```proto
message CanonicalTextRange {
  OffsetUnit unit = 1;             // Platform compatibility: UTF16_CODE_UNIT
  int64 start = 2;                 // inclusive
  int64 end = 3;                   // exclusive
  TextNormalization normalization = 4;
  LineEndingPolicy line_endings = 5;
  string canonical_text_digest = 6;
}
```

This is separate from `TextRange` and may coexist with it. An element may have source geometry, source text range, canonical range, both, or none.

### 4.6 PDF geometry

```proto
message PdfGeometry {
  int32 page_number = 1;           // 1-based; 0 when unavailable
  BoundingBox bbox = 2;            // x0,y0,x1,y1
  string coordinate_system = 3;    // e.g. PDF_USER_SPACE_BOTTOM_LEFT
  string unit = 4;                 // e.g. POINT
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

Dispatcher routing is outside this contract. The contract only records durable facts about which execution produced which element, so mixed or fallback extraction remains traceable.

Requirements:

- Document-level provenance contains an ordered registry of `ExtractionExecution` records.
- Every element may reference one or more `execution_id` values.
- A fallback or retry records the previous execution in `parent_execution_ids`; fallback is never silent.
- Per-page or per-region routing is represented by element-level execution references, not by a PDF-specific field.
- Extractor/model/policy versions are recorded as opaque identifiers. The contract does not encode routing decisions.
- `QualityReport` records complete/degraded/partial/failed status and optional completeness/confidence, separately from extraction failure.
- Old payloads inherit a single primary execution or remain explicitly unknown.

Example: a mixed PDF where page 1 text comes from a PDF parser and page 2 comes from OCR:

```json
{
  "provenance": {
    "primary_execution_id": "exec-pdf-1",
    "executions": [
      {"execution_id": "exec-pdf-1", "extractor_id": "pdf-extractor",
       "stage_id": "text", "quality": {"status": "COMPLETE"}},
      {"execution_id": "exec-ocr-2", "extractor_id": "ocr-extractor",
       "stage_id": "ocr", "parent_execution_ids": ["exec-pdf-1"],
       "quality": {"status": "DEGRADED"}}
    ]
  },
  "elements": [
    {"id": "p1-e1", "execution_ids": ["exec-pdf-1"]},
    {"id": "p2-e1", "execution_ids": ["exec-ocr-2"]}
  ]
}
```

A dispatcher may decide how to produce this structure. The contract does not contain a routing table, confidence threshold, model endpoint, or execution plan.

### 4.9 Projections

`flattened_text` remains the existing compatibility projection. Add optional projection metadata:

```proto
message DocumentProjection {
  string format = 1;               // "text", "markdown", "html", "json"
  string digest = 2;
  oneof content {
    bytes inline_content = 3;
    PayloadReference reference = 4;
  }
  LossReport loss = 5;
}

message LossReport {
  bool lossless = 1;
  repeated string dropped = 2;     // "table_cell_span", "pdf_geometry", ...
  repeated string approximated = 3;
  repeated string unsupported = 4;
}
```

Existing `flattened_text` remains authoritative for compatibility but cannot claim losslessness without a report.

### 4.10 Dispatcher boundary

The Content Extractor Dispatcher is a separate orchestration and routing layer. It may classify inputs, select extractors, compose page-level or region-level pipelines, manage retries, and reconcile outputs.

The contract must not encode:

- routing tables or preferred implementation names;
- extension/MIME routing rules;
- model endpoints or GPU scheduling policy;
- a second execution request/response model;
- another canonical document model.

The only dispatcher-related facts in the contract are durable extraction provenance: execution identity, extractor/version, stage, policy version, parent/fallback lineage, and quality status. This keeps #22 focused on the document model while allowing a future dispatcher to use it.

## 5. Compatibility mapping from the current contract

| Existing field | Target mapping | Notes |
|---|---|---|
| `StructuredPayload.payload_descriptor` | unchanged | Schema/processor descriptor remains |
| `StructuredPayload.inline_content` / `reference` | unchanged | Envelope remains common multimodal carrier |
| `DocumentPayload.media_type` | `source.media_type` plus existing field | Retain field 1 for old consumers |
| `DocumentPayload.metadata` | unchanged | Document metadata remains opaque |
| `DocumentPayload.elements` | unchanged | Same reading order |
| `DocumentPayload.flattened_text` | unchanged | Compatibility projection; not source evidence |
| `DocumentElement.location.page` | `SourceLocation.pdf_geometry.page_number` | Old field retained; old payload maps to geometry |
| `DocumentElement.location.bbox` | `SourceLocation.pdf_geometry.bbox` | Coordinate system must be declared |
| `DocumentElement.id` | unchanged | Stable within payload; document identity added separately |
| `DocumentElement.child_ids` | unchanged | Hierarchy remains |
| `ContentProvenance` | unchanged | Already distinguishes embedded/OCR/generated/transcribed |
| `PayloadDescriptor.processor_id/version` | remains payload-level | Do not duplicate as element provenance |
| `DocumentElement` without execution IDs | inherits primary execution or `UNKNOWN` | No execution identity is invented |
| Single-provider payload | one execution record or primary vector | Backward compatible |

Old payload to new consumer:

- `ElementLocation` with a non-zero page/bbox maps to `PdfGeometry` with `precision = APPROXIMATE` unless the producer explicitly declared exact semantics. Do not infer EXACT.
- Missing page/bbox maps to `UNAVAILABLE`.
- No `TextRange` or canonical range is inferred.
- Missing document class/source descriptor maps to `UNKNOWN`/empty, never guessed.

New payload to old consumer:

- Existing fields remain populated.
- Unknown fields are ignored by protobuf.
- Old consumers may use `ElementLocation` only when the new producer also populates it for compatibility.

## 6. Schema evolution rules

1. `StructuredPayload` and `DocumentPayload` are evolved additively; no parallel document hierarchy.
2. Existing field numbers and meanings are immutable.
3. New fields are optional; no adapter is forced to emit unsupported capabilities.
4. `PayloadDescriptor.schema_version` changes for semantic schema changes.
5. Unknown fields are ignored; consumers must not require unknown extensions.
6. `UNSPECIFIED` precision/reproducibility maps to unavailable/unknown, never exact.
7. Source format strings are extensible; document class is a controlled enum with `UNKNOWN`.
8. Provider-specific details live in `attributes`/`FormatReference`, not shared types.
9. Contract mirror updates are atomic across Platform and Content Extractor.
10. A capability declaration must describe which location types and precisions an adapter can produce.
11. Execution IDs are stable within a payload; an element with no execution ID inherits the document's primary execution or is explicitly unknown.
12. Multi-stage/fallback lineage uses `parent_execution_ids`; it is provenance, not a routing policy.

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
      "source_location": {
        "precision": "EXACT",
        "reproducibility": "EXACT_FROM_SOURCE",
        "pdf_geometry": {
          "page_number": 1,
          "bbox": {"x0": 72, "y0": 120, "x1": 420, "y1": 148},
          "coordinate_system": "PDF_USER_SPACE_BOTTOM_LEFT",
          "unit": "POINT",
          "rotation": 0
        }
      }
    }]
  }
}
```

No text range is claimed. A bbox is not converted to a character offset.

### 7.2 PDF approximate location

```json
{
  "source_location": {
    "precision": "APPROXIMATE",
    "reproducibility": "DEPENDS_ON_PARSER",
    "pdf_geometry": {
      "page_number": 2,
      "coordinate_system": "PDF_USER_SPACE_BOTTOM_LEFT",
      "unit": "POINT",
      "rotation": 90
    }
  }
}
```

The missing bbox remains absent; the consumer must not invent one.

### 7.3 Markdown exact source range

Source bytes are UTF-8; range is half-open and refers to the original Markdown bytes.

```json
{
  "source_location": {
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
  },
  "canonical_text_range": {
    "unit": "UTF16_CODE_UNIT",
    "start": 118,
    "end": 241,
    "normalization": "NFC",
    "line_endings": "LF",
    "canonical_text_digest": "sha256:def..."
  }
}
```

The source range and canonical range are deliberately different. They must not be substituted for each other.

### 7.4 Markdown unavailable source range

```json
{
  "source_location": {
    "precision": "UNAVAILABLE",
    "reproducibility": "NOT_REPRODUCIBLE",
    "text_range": {
      "unit": "UTF8_BYTE",
      "coordinate_space": "ORIGINAL_SOURCE",
      "normalization": "NONE",
      "line_endings": "ORIGINAL"
    }
  }
}
```

Start/end are absent. A consumer must not search `flattened_text` to recover them.

### 7.5 Legacy payload conversion

An old payload with `ElementLocation.page=1` and a bbox maps to:

- `PdfGeometry` with page/bbox.
- `precision=APPROXIMATE` unless the producer provided explicit precision.
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

Every lossy export must carry or reference a `LossReport`. A projection that cannot represent required information is `UNSUPPORTED`, not a silent approximation.

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

### 9.3 Projection loss

1. Table with row/column spans projected to Markdown reports lost geometry/spans.
2. Nested lists projected to flat text report lost hierarchy.
3. PDF geometry projected to Markdown reports lost geometry.
4. JSON projection reports no silent loss.
5. Unsupported projection returns `UNSUPPORTED` with reason, not partial success.

### 9.4 Multi-provider provenance

1. Mixed PDF pages record distinct execution IDs for text and OCR elements.
2. Fallback execution records the prior execution in `parent_execution_ids`.
3. An element without execution IDs inherits the primary execution, or is reported unknown for legacy payloads.
4. A merged element can reference multiple execution IDs without losing lineage.
5. Quality degradation is distinct from extraction failure.
6. No dispatcher routing table, model endpoint, or policy decision is serialized into the document contract.

### 9.5 Evidence compatibility

1. Historical P1/A2/A4 evidence maps to canonical elements with stable identity where possible.
2. Changed or missing coordinates produce explicit `APPROXIMATE`/`UNAVAILABLE` outcomes.
3. Frozen reference artifacts, gold labels, denominators, and metrics remain unchanged.
4. A compatibility report distinguishes mapped, approximate, unavailable, and unsupported evidence.

## 10. Gate assessment

### #20 / #21 — adapter capabilities

**Conditionally ready for design, not implementation.**

- PDF geometry semantics are sufficiently specified (page number, bbox, origin, units, rotation, precision).
- Markdown original/canonical range distinction is specified.
- PDF/Markdown adapters still need concrete source-range extraction support.
- The existing `PdfPayload`/`MarkdownPayload` alternatives are rejected: adapters target the common `DocumentPayload`.
- Readiness to implement is blocked until schema fields are approved and mirrored.
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

## 11. Blockers and unresolved questions

1. **Canonical unit decision:** Platform currently uses UTF-16 code units for canonical structural offsets. Should the contract make `UTF16_CODE_UNIT` the required canonical unit for compatibility, or allow multiple units with explicit metadata?
2. **PDF precision default:** Should legacy `ElementLocation` page/bbox map to `APPROXIMATE` by default or to an explicit `UNKNOWN` state not currently available?
3. **Document class scope:** Confirm that `TEXT`, `SPREADSHEET`, `TRANSCRIPT`, `STRUCTURED_DATA`, and `UNKNOWN` are the initial controlled classes; image/video remain modality payloads.
4. **Section/document element types:** Current `DocumentElementType` lacks `DOCUMENT`, `SECTION`, `FIGURE`, and `UNKNOWN`. Decide whether these are added or represented via hierarchy/attributes.
5. **Table/source spans:** Define cell-level source locations and spans before claiming table evidence parity.
6. **Source descriptor duplication:** Confirm whether `SourceLocation` repeats source digest/version or inherits them from `DocumentPayload.source`.
7. **Schema version threshold:** Decide which additions require a schema version bump versus an additive minor revision.
8. **Exporter ownership:** Confirm that projection/export code is owned by Content Extractor or a separate exporter layer; it must not become a Platform parser.
9. **Execution provenance cardinality:** Confirm whether an element may reference multiple executions and whether merged elements are represented by repeated IDs or a separate merge record.
10. **Quality ownership:** Confirm which quality dimensions are durable contract facts versus dispatcher-local diagnostics.

## 12. Readiness conclusion

- **Contract design:** Proposal is sufficient for review and adapter design discussion, including multi-provider provenance.
- **#20/#21:** Not ready for implementation until schema approval and mirroring; design can proceed.
- **#950:** Blocked until a production mapping from the approved payload exists.
- **#947:** Blocked until contract, adapters, and frozen compatibility reference are available.

No production implementation, contract modification, migration, benchmark execution, or baseline change is authorized by this proposal.
