# Synanton Content Extractor

[![Status](https://img.shields.io/badge/Status-SCEP--5%20Integration-blue)](https://github.com/Synanton/content_extractor)
[![Java](https://img.shields.io/badge/Java-21-red)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)
[![gRPC](https://img.shields.io/badge/gRPC-Protobuf-purple)](https://grpc.io/)

**Structured content extraction infrastructure for the Synanton Knowledge Platform.**

The Synanton Content Extractor implements the **Structured Content Extraction Plane (SCEP)**: the boundary between raw enterprise content and structured content that downstream Synanton knowledge processing can consume.

It answers one fundamental question:

> **What is present in this artifact and what structure can be reliably extracted from it?**

The extraction plane is deliberately separated from knowledge processing. It extracts and structures observable content; downstream Synanton components determine what that content means in a business domain.

------

## Overview

Enterprise content arrives in many forms:

- Documents - PDF, TXT, EPUB, HTML
- Audio - recordings, meetings, conversations
- Images - scans, screenshots, photographs, diagrams
- Video - recordings, presentations, demonstrations, short clips

Each modality contains structure that can be lost when content is reduced to plain text.

The Structured Content Extraction Plane preserves that structure and exposes it through a stable, deployment-neutral contract.

```text
                           SYNANTON
                              │
                              │ extraction contract
                              ▼
                 ┌─────────────────────────────┐
                 │ Structured Content          │
                 │ Extraction Plane            │
                 │                             │
                 │          BLACK BOX          │
                 └──────────────┬──────────────┘
                                │
              ┌─────────────────┼──────────────────┐
              │                 │                  │
              ▼                 ▼                  ▼
          Documents           Audio            Images / Video
              │                 │                  │
        ┌─────┼─────┐       ┌───┼────┐       ┌────┼─────┐
        │     │     │       │   │    │       │    │     │
       text layout tables  ASR diarization  OCR  VLM  scenes
        │     │     │       │   │    │       │    │     │
        └─────┴─────┘       └───┴────┘       └────┴─────┘
              │                 │                  │
              └─────────────────┼──────────────────┘
                                ▼
                     StructuredPayload
                                │
                                ▼
                       Knowledge Platform
```

------

## Architectural Position

The extraction plane sits between raw content storage and knowledge processing.

```text
      Source Systems
    (FileNet, SharePoint, Box, Emails, Audio,
       ImageServices, OpenText,
        COLD, FS, ...)
               │
               ▼
            Lucentrix
        source retrieval
               │
               ▼
         Content Objects
         Object Storage
               │
               ▼
 ┌──────────────────────────────┐
 │     Structured Content       │
 │      Extraction Plane        │
 │                              │
 │          BLACK BOX           │
 └──────────────┬───────────────┘
                │
                ▼
        StructuredPayload
                │
        ┌───────┴────────┐
        ▼                ▼
  flattenedText     structured data
        │                │
        └────────┬───────┘
                 ▼
        Knowledge Processing
```

The separation is intentional:

**Extraction asks:**

> What can be extracted from the content?

**Knowledge processing asks:**

> What does the extracted content mean?

The extraction plane therefore does **not** become an ontology, entity-resolution, ranking, business-rule, or knowledge-graph service.

------

## Core Architectural Principle

### Contract over topology

The extraction contract is the architectural boundary.

The implementation may be:

```text
Mode A - Embedded

Synanton
   │
   └──► extractor
Mode B - Co-located

Synanton
   │
   └──► extraction component
Mode C - Clustered

Synanton
   │
   └──► extraction API
            │
            └──► workers
Mode D - Distributed / delegated

Synanton
   │
   └──► extraction API
            │
            ├──► extractor A
            │       │
            │       └──► extractor B
            │
            └──► extractor C
```

These deployments are **contractually equivalent**.

> Deployment topology is a scaling and implementation concern, not an API concern.

Synanton must not need to know which parser, library, worker, accelerator, queue, scheduler, or downstream extractor performed the work.

------

## Structure Before Meaning

The extraction plane preserves observable structure before applying interpretation.

A PDF should not simply become one large string:

```text
document
 ├── page
 │    ├── heading
 │    ├── paragraph
 │    ├── table
 │    ├── image
 │    ├── caption
 │    └── formula
 └── page
```

A conversation should preserve temporal and speaker structure:

```text
conversation
 ├── utterance
 │    ├── speaker
 │    ├── start
 │    ├── end
 │    ├── text
 │    ├── pause-before
 │    └── pause-after
 │
 └── relationships
      └── overlap
```

A video can preserve:

```text
video
 ├── metadata
 ├── audio
 ├── transcription
 ├── OCR
 ├── key frames
 ├── scenes
 └── short clips
```

This structure becomes evidence that downstream processing can reason over rather than reconstruct later.

------

# Content Domains

The initial SCEP model covers five major domains.

| Domain         | Typical inputs               | Primary extraction                | Optional enrichment                                       |
| -------------- | ---------------------------- | --------------------------------- | --------------------------------------------------------- |
| **Documents**  | PDF, TXT, EPUB, HTML         | text, structure, layout, metadata | OCR, tables, formulas, image descriptions, summaries      |
| **Audio**      | WAV, MP3, M4A, meetings      | transcription, timestamps         | diarization, pauses, overlap, conversation summaries      |
| **Images**     | PNG, JPEG, TIFF, screenshots | metadata, dimensions, OCR         | image/scene description, chart interpretation             |
| **Video**      | MP4, WebM, MOV               | metadata, streams, key frames     | transcription, OCR, scene detection, short-clip summaries |
| **Multimodal** | mixed artifacts              | cross-modal structure             | LLM/VLM enrichment and derived representations            |

The contract is designed so that new modalities can be introduced without changing the core extraction boundary.

------

# Document Extraction

Document extraction covers:

- PDF
- plain text
- EPUB
- HTML

The objective is to preserve document structure rather than flattening everything immediately.

Typical extracted elements include:

```text
Document
 ├── metadata
 ├── page
 │    ├── heading
 │    ├── paragraph
 │    ├── list
 │    ├── table
 │    ├── image
 │    ├── caption
 │    └── formula
 └── relationships
```

Optional features include:

- OCR
- layout preservation
- reading order
- table extraction
- embedded image extraction
- formula extraction
- image descriptions
- document summaries

------

## PDF Extraction PoC

The first concrete processor integration is the **OpenDataLoader PDF** path, backed by the
real `org.opendataloader:opendataloader-pdf-core` Java library (Maven Central), called
in-process - not an HTTP service. The library is file-in/file-out: it reads a PDF from a
path and writes JSON (and, if configured, PDF/Markdown/HTML) to a configured output folder;
`adapter-document-pdf`'s `OpenDataLoaderClient` handles the temp-file lifecycle around that.

The real element `type` values (verified against the library's own `schema.json`) are:

```text
heading
paragraph
caption
table
table row
table cell
text block
list
list item
image
header
footer
```

A representative real output element (verified against the library's actual JSON, not
hand-written):

```json
{
  "type": "heading",
  "id": 1,
  "level": "Doctitle",
  "page number": 1,
  "bounding box": [72.0, 715.44, 230.92, 739.24],
  "heading level": 1,
  "font": "Helvetica-Bold",
  "font size": 20.0,
  "text color": "[0.0]",
  "content": "Quarterly Report"
}
```

Tables nest as `table` → `rows: [tableRow]` → `cells: [tableCell]` rather than a flat
`{headers, rows}` object; `adapter-document-pdf`'s normalizer walks that nested shape.

Note: earlier drafts of this section (and of `adapter-document-pdf`'s own code) assumed
`picture`/`formula` element types and camelCase field names (`pageNumber`, `boundingBox`) -
neither is accurate. `picture`-with-`description` output only appears when the library's
optional hybrid/VLM image-description mode is enabled (`Config.HYBRID_*`, off by default);
base extraction uses `image` with `source`/`data`/`format`, and field names use spaces
(`"page number"`, not `"pageNumber"`), per the real `schema.json`.

The processor-specific representation is normalized into a Synanton document payload.

```json
{
  "schema": {
    "id": "synanton.document",
    "version": "1.0"
  },
  "source": {
    "contentRefId": "01J-PDF-0001",
    "mediaType": "application/pdf",
    "sha256": "abc123..."
  },
  "features": {
    "text": "applied",
    "layout": "applied",
    "tables": "applied",
    "images": "applied",
    "ocr": "partial",
    "formulas": "applied",
    "image-description": "applied"
  }
}
```

The OpenDataLoader output is therefore an **implementation detail**. The Synanton normalized representation is the contract.

------

# Audio Extraction

Audio extraction preserves the temporal structure of conversations.

Primary extraction:

- transcription
- timestamps
- utterances

Optional enrichment:

- speaker diarization
- pauses
- simultaneous speech / overlap
- language identification
- conversation summaries

A conversation can be represented as:

```json
{
  "conversation": {
    "utterances": [
      {
        "id": "u021",
        "speakerId": "speaker-1",
        "startMs": 10200,
        "endMs": 13800,
        "text": "The important part is-"
      },
      {
        "id": "u022",
        "speakerId": "speaker-2",
        "startMs": 12900,
        "endMs": 15200,
        "text": "Yes, but the contract-"
      }
    ],
    "relationships": [
      {
        "type": "overlap",
        "source": "u021",
        "target": "u022",
        "startMs": 12900,
        "endMs": 13800
      }
    ]
  }
}
```

This preserves evidence that two participants were speaking simultaneously.

### Conversation summaries

Summarization is an **enrichment stage**, not a replacement for the transcript.

```text
audio
 │
 ├── acoustic analysis
 ├── transcription
 ├── diarization
 ├── pause / overlap detection
 │
 └── structured conversation
          │
          └── LLM summary
```

Generated summaries should retain references to the extracted utterances that support them.

------

# Image Extraction

Image processing separates deterministic extraction from LLM/VLM interpretation.

```text
image
 │
 ├── metadata
 ├── dimensions
 ├── EXIF where permitted
 ├── OCR
 │
 └── VLM enrichment
       ├── image description
       ├── object interpretation
       ├── scene interpretation
       └── chart interpretation
```

Example:

```json
{
  "type": "image",
  "mediaType": "image/jpeg",
  "width": 1920,
  "height": 1080,
  "ocr": {
    "text": "Q3 Revenue: €4.2M",
    "regions": [
      {
        "text": "Q3 Revenue: €4.2M",
        "bbox": [220, 90, 780, 170],
        "confidence": 0.97
      }
    ]
  },
  "description": {
    "text": "A presentation slide containing a Q3 revenue headline and a bar chart."
  }
}
```

LLM/VLM-generated descriptions are typed extraction artifacts with provenance rather than silently becoming canonical business knowledge.

------

# Video Extraction

Video extraction combines temporal, visual, audio, and textual structure.

```text
video
 │
 ├── metadata
 ├── audio stream
 │     └── transcription
 │
 ├── key frames
 │     └── OCR / image analysis
 │
 ├── scene detection
 │     └── scene descriptions
 │
 └── short clips
       └── clip-level summaries
```

Typical capabilities include:

- video metadata
- audio extraction
- transcription
- OCR
- key-frame extraction
- scene detection
- speaker information
- short-clip generation
- scene/clip summaries

The result can therefore represent both **what happened over time** and **what was visible or spoken within each interval**.

------

# LLM and VLM Enrichment

LLMs and VLMs may be used where deterministic extraction is insufficient.

Examples:

- describe an image
- describe a chart
- summarize a conversation
- identify a video scene
- interpret visual content
- generate a short clip description

The rule is:

> **Generated interpretation is an extraction artifact, not canonical business knowledge.**

Generated content should retain provenance and, where applicable, confidence and source references.

```text
    Raw Content
        │
        ▼
    Deterministic Extraction
        │
        ▼
   Structured Evidence
        │
        ├──► LLM/VLM enrichment
        │         │
        │         ▼
        │    Derived Artifact
        │
        ▼
   Knowledge Processing
```

------

# Feature Tags

Extraction requests can express requested capabilities using typed options and/or feature tags.

Examples:

```text
document=layout
document=tables
document=ocr
document=formulas
document=image-description

audio=transcription
audio=diarization
audio=pauses
audio=overlap
audio=summary

image=ocr
image=description
image=chart-analysis

video=transcription
video=ocr
video=scenes
video=clips
video=summary
```

Business metadata can travel with the request:

```text
department=legal
document-type=contract
ticket=T-100
classification=internal
```

Business tags are preserved for downstream consumers but are not interpreted as business semantics by the extraction implementation.

------

# Feature State

A requested feature must never be considered successful merely because it was requested.

The result explicitly reports what happened.

```text
   REQUESTED
       │
       ▼
   SUPPORTED?
       │
    ┌──┴───────────────┐
    │                  │
   yes                no
    │                  │
    ▼                  ▼
   execution       UNSUPPORTED
    │
    ├── APPLIED
    ├── PARTIAL
    ├── NOT_APPLICABLE
    └── FAILED
```

For example:

```json
{
  "features": {
    "ocr": "applied",
    "layout": "applied",
    "tables": "partial",
    "formulas": "not_applicable",
    "image-description": "applied"
  }
}
```

This prevents silent feature loss and allows consumers to distinguish unsupported capabilities from extraction failures.

------

# Source Authority and Provenance

The raw source artifact remains authoritative.

The extraction plane:

- reads source content;
- never modifies the source artifact;
- preserves the source reference;
- records source checksum;
- identifies media type;
- records extraction time;
- identifies schema/version;
- records processor information;
- records payload digest.

A structured result can therefore be traced back to the source artifact and the representation that produced it.

```text
   Source Object
        │
        ├── contentRefId
        ├── object reference
        ├── media type
        └── SHA-256
              │
              ▼
        Extraction Operation
              │
              ▼
        Structured Payload
              │
              ├── schema
              ├── processor
              ├── payload digest
              └── extraction timestamp
```

------

# Extraction Contract

The external API is exposed through:

```text
synanton.extraction.v1
```

The contract defines requests, operations, results, capabilities, feature states, and errors without exposing implementation topology.

## Object References

Large content is referenced through object storage rather than transported through the extraction API.

Conceptually:

```text
   ObjectReference
    ├── bucket
    ├── key
    ├── version
    ├── sha256
    └── size
```

This keeps the API independent of content size and transport implementation.

------

## Extraction Options

Options use explicit semantics.

Typical options include:

```text
ocr
transcription
layout
tables
embeddedImages
sceneAnalysis
language
preflight
```

Options are intentionally independent of the processor implementation.

For example:

```text
ocr = true
```

means:

> OCR is requested.

It does **not** mean:

> a particular OCR engine must be used.

The result reports whether OCR was actually applied.

------

# Asynchronous Operations

Asynchronous extraction is a first-class contract.

An extraction operation has an externally stable lifecycle:

```text
   ACCEPTED
      │
      ▼
   QUEUED
      │
      ▼
   RUNNING
      │
      ├──► COMPLETED
      ├──► PARTIAL
      ├──► FAILED
      ├──► CANCELLED
      └──► EXPIRED
```

Progress is normalized:

```text
0.0 <= progress <= 1.0
```

Progress is advisory and may be based on pages, bytes, audio duration, video duration, or processing stages.

------

# Idempotency

Asynchronous extraction requires idempotent submission.

A client must be able to retry after a network failure without unintentionally creating duplicate expensive extraction work.

```text
same idempotency key
        +
same request
        │
        ▼
same operation
```

This is particularly important for:

- OCR
- transcription
- image analysis
- video processing

Reusing an idempotency key with materially different request parameters should be rejected.

------

# Expiration and Cancellation

Expiration is a lifecycle outcome, not a technical failure.

An operation that reaches its expiration boundary may become:

```text
STATUS_EXPIRED
```

rather than:

```text
STATUS_FAILED
```

Cancellation is best-effort:

- before execution, work can be prevented;
- while queued, admission/scheduling can be interrupted where possible;
- while running, the plane may allow work to finish when cancellation is unsafe or more expensive than completion.

------

# Priority and Capacity

Priority expresses **intent**, not topology.

Supported classes may include:

```text
LOW
NORMAL
HIGH
CRITICAL
```

The extraction plane determines how those priorities map to internal scheduling.

Consumers must not infer:

- a queue;
- a worker pool;
- CPU allocation;
- GPU allocation;
- scheduling algorithm.

Capacity information is advisory and may be used for admission decisions or pre-flight estimation.

------

# Structured Payload

Successful extraction produces a `StructuredPayload`.

The payload may represent:

- PDF structure
- HTML structure
- document structure
- audio timelines
- transcription
- image/OCR structure
- video scene structure
- modality-specific derived artifacts

Conceptually:

```text
   StructuredPayload
    ├── descriptor
    │    ├── schemaId
    │    ├── schemaVersion
    │    ├── processorId
    │    ├── processorVersion
    │    ├── format
    │    ├── schemaDigest
    │    └── payloadDigest
    │
    └── content
         └── modality-specific representation
```

Processor version and schema version remain independent.

------

# Flattened Text

For modalities where textual extraction is meaningful, the result may expose:

```text
flattenedText
```

This provides a compatibility projection for generic text consumers.

The structured representation remains the authoritative extraction result.

Consumers that require structured content should not force the source artifact to be reprocessed merely to obtain text.

------

# Internal Processor Routing

The extraction plane owns processor selection and internal routing.

Examples:

```text
PDF
 │
 └──► OpenDataLoader
```

or:

```text
   PDF
    │
    └──► detector
          ├──► OCR extractor
          ├──► PDF parser
          └──► external extraction service
```

Audio may be routed to transcription and diarization infrastructure.

Video may be routed to scene detection, vision, speech, and OCR processors.

These implementation details are deliberately hidden from the Synanton platform.

------

# Architecture

The implementation is organized around three layers.

```text
   ┌──────────────────────────────────────────────────────────┐
   │ Synanton Extraction Contract                             │
   │ Request / Operation / Result / Tags / Errors             │
   │ synanton.extraction.v1                                   │
   └─────────────────────────┬────────────────────────────────┘
                             │
   ┌─────────────────────────▼────────────────────────────────┐
   │ Modality Adapters                                        │
   │ PDF │ Text │ EPUB │ HTML │ Audio │ Image │ Video         │
   └─────────────────────────┬────────────────────────────────┘
                             │
   ┌─────────────────────────▼────────────────────────────────┐
   │ Processor Implementations                                │
   │ OpenDataLoader │ OCR │ ASR │ Diarization │ VLM │ ...     │
   └──────────────────────────────────────────────────────────┘
```

The contract remains stable while modality adapters and processor implementations evolve independently.

------

# Repository Structure

Current and planned modules:

| Module                       | Status        | Purpose                                                |
| ---------------------------- | ------------- | ------------------------------------------------------ |
| `java/extraction-contract`   | **Active**    | Protobuf contract, request validation, error catalogue |
| `java/extraction-gateway`    | **Active**    | gRPC server, Postgres-backed operation store, router, admission, async worker |
| `java/extraction-spi`        | **Active**    | `ModalityAdapter` SPI and normalized payload model     |
| `java/adapter-document-text` | **Active**    | TXT, EPUB, HTML extraction                             |
| `java/adapter-document-pdf`  | **Active**    | Real `opendataloader-pdf-core`-backed PDF extraction   |
| `java/adapter-stubs`         | **Active**    | Audio/image/video capability stubs (declare `UNSUPPORTED`, not real processing) |

------

# Contract API

The `synanton.extraction.v1` service provides the following operations:

| RPC                       | Purpose                                    |
| ------------------------- | ------------------------------------------ |
| `SubmitExtraction`        | Submit asynchronous extraction             |
| `SubmitExtractionBatch`   | Submit multiple artifacts as one operation |
| `ExtractSync`             | Extract small content synchronously        |
| `GetOperations`           | Retrieve authoritative operation status    |
| `ListCompletedOperations` | Cursor-based completion feed               |
| `GetResult`               | Retrieve a completed structured result     |
| `CancelOperation`         | Best-effort cancellation                   |
| `GetCapacity`             | Advisory capacity information              |
| `EstimateExtraction`      | Advisory pre-flight estimate               |
| `GetCapabilities`         | Supported media types and features         |

There are no webhook dependencies in v1.21. Completion can be observed through operation status and cursor-based polling.

------

# Contract Mirroring

The protobuf contract is mirrored between this repository and the Synanton platform.

```text
   content_extractor
       │
       └── java/extraction-contract/src/main/proto/

   platform
       │
       └── java/extraction-contract/src/main/proto/
```

The copies must remain byte-identical.

Verify the mirror with:

```bash
./scripts/verify-contract-mirror.sh
```

For a sibling platform checkout:

```bash
EXTRACTION_PEER_REPO=/path/to/platform \
  ./scripts/verify-contract-mirror.sh
```

Never modify one copy independently.

------

# Architectural Invariants

The following rules are non-negotiable:

1. **Contract over topology** - embedded, co-located, clustered, and distributed deployments expose the same contract.
2. **Black-box extraction** - the contract does not depend on processor or topology names.
3. **Raw source authority** - extraction never modifies the source artifact.
4. **Structure before meaning** - observable structure is preserved before semantic interpretation.
5. **Structured payload extensibility** - modality-specific representations do not require expanding a universal document model.
6. **Domain isolation** - extraction does not become knowledge processing.
7. **Idempotency** - asynchronous submission is safely retryable.
8. **Expiration** - asynchronous operations have explicit expiration semantics.
9. **Capacity awareness** - the plane can reject or defer work when safe admission is impossible.
10. **External priority** - priority expresses intent without exposing scheduling topology.
11. **Explicit feature state** - requested, applied, partial, unsupported, failed, and not-applicable states remain distinguishable.
12. **Opaque business metadata** - business tags are carried through without being interpreted as extraction semantics.
13. **Async first-class** - asynchronous extraction is not a secondary implementation path.
14. **Batch operations** - multiple content references can belong to one operation.
15. **No webhook dependency** - v1.21 does not require callbacks for completion.
16. **No topology leakage** - queues, workers, hardware, and routing remain implementation details.
17. **Provenance** - structured and generated artifacts retain traceability to source evidence.
18. **Extraction is not knowledge processing** - ontology, entity resolution, ranking, business semantics, and knowledge graphs remain downstream concerns.

------

# Current Status

The repository is implementing the Structured Content Extraction Plane incrementally.

| Phase      | Name                                         | Status                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| ---------- | -------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **SCEP-1** | Extraction contract                          | ✅ Complete                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| **SCEP-2** | Extraction plane skeleton + synchronous path | ✅ Complete                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| **SCEP-3** | PDF extraction PoC with OpenDataLoader       | ✅ Complete <br/> now backed by the real `org.opendataloader:opendataloader-pdf-core` library                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| **SCEP-4** | Asynchronous operation model                 | ✅ Complete <br/> gRPC gateway, Postgres-backed operation store, lease-based worker, idempotency, capacity/cancel/estimate; verified via `AsyncExtractionIntegrationTest`<br/> (Testcontainers - currently `@Disabled` on environments where the local Docker Engine's API version trips a `testcontainers`/`docker-java` connectivity-probe bug; passes the PDF path added alongside the pre-existing text path once Docker is compatible)                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| **SCEP-5** | Synanton platform integration                | Contract-ready - `synanton.extraction.v1` and the `ObjectReference{bucket,key,version,sha256,size}` shape are the real integration surface (deliberately matches the platform's Synvault object model). The platform-side consumer (`java/extraction-client`'s `ExtractionPlaneClient`, with `ExtractionFallbackPolicy` and reconcile-after-timeout on submit) is implemented and tested in the sibling `platform` repo, not here. `scripts/verify-contract-mirror.sh` enforces byte-identical `.proto`s on both sides and currently passes. Open: this plane's async Operation lifecycle (`SubmitExtraction`/`GetOperations`) is not yet reconciled with the platform's common eventing/workflow contract (Design 1.27) or its public `Operation` resource (Design 1.32) - both are themselves "Approved, not started" in the platform's own architecture, so this is correctly sequenced, not overdue. |
| **SCEP-6** | Topology equivalence + hardening             | In progress                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| **SCEP-7** | Multimodal expansion: audio, image, video    | Post-v1.21                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |

The architecture is intentionally being established before committing the platform to a particular extraction implementation.

------

# Development

Requires **JDK 21**.

Gradle is executed through the included wrapper.

```bash
./gradlew build
```

Generate protobuf stubs after contract changes:

```bash
./gradlew :java:extraction-contract:generateProto
```

Run tests:

```bash
./gradlew test
```

Verify contract mirroring:

```bash
./gradlew verifyContractMirror
```

------

# Testing

The contract layer includes tests for:

- request validation;
- documented field boundaries;
- error codes and retryability;
- contract opacity;
- service contract behavior;
- contract mirror integrity.

A key architectural test verifies that the compiled contract does not expose processor, library, worker, queue, or topology implementation details.

Validation is intentionally implemented at the service boundary rather than relying on annotations that are not actually enforced by the build.

------

# Design Philosophy

The Structured Content Extraction Plane follows a few simple principles:

### Preserve evidence

Raw content remains authoritative.

### Preserve structure

Do not throw away pages, layout, tables, speakers, timestamps, overlaps, scenes, images, or other observable relationships merely because a flattened representation is easier to consume.

### Make enrichment explicit

LLM/VLM-derived descriptions, summaries, and interpretations are derived artifacts with provenance.

### Hide implementation

Processors are replaceable. Deployment topology is replaceable. Hardware is replaceable. Internal routing is replaceable.

The contract is the stable boundary.

### Separate extraction from knowledge

The extractor determines **what is present**.

The Knowledge Platform determines **what it means**.

------

# References

- [Synanton v1.21 Structured Content Extraction Plane](doc/Synanton_v1.21_structured_content_extraction.md)
- [Synanton v1.21 Structured Content Extraction Plane - Draft / Multimodal Design](doc/Synanton_v1.21_multimodal_extraction_model.md)
- [Test Kubernetes Cluster (PoC hardware)](doc/k8s-test-hardware.md)
- [Synanton Platform](https://github.com/synanton/platform)
- [Synanton Platform Architecture 1.0 (capstone)](https://github.com/synanton/platform/blob/main/docs/architecture/synanton-platform-architecture-1.0.md) - integrates Designs 1.22–1.34; current entry point for the platform architecture
- [Design 1.21 - Structured Content Extraction Plane](https://github.com/synanton/platform/blob/main/docs/architecture/archive/synanton-design-1.21.md) - archived; folded into Design 1.22 Part IX as the historical baseline
- [Design 1.22 - Platform base architecture](https://github.com/synanton/platform/blob/main/docs/architecture/synanton-design-1.22.md)
- [Design 1.26 - Content Cache Plane](https://github.com/synanton/platform/blob/main/docs/architecture/synanton-design-1.26.md) - owns storage/retrieval/lifecycle of the `StructuredPayload`/`flattenedText` this plane emits, downstream of extraction
- [Design 1.27 - Eventing and Workflow Plane](https://github.com/synanton/platform/blob/main/docs/architecture/synanton-design-1.27.md) - the platform's common async/event/retry contract; this plane's own Operation lifecycle (SubmitExtraction/GetOperations) predates it and is not yet reconciled with it (tracked under SCEP-5)
- [Design 1.32 - Platform API & Contract Architecture](https://github.com/synanton/platform/blob/main/docs/architecture/synanton-design-1.32.md) - owns the public `Operation` resource semantics that this plane's async model should eventually align with
- [Design 1.33 - Kubernetes Operator Readiness](https://github.com/synanton/platform/blob/main/docs/architecture/synanton-design-1.33.md) - canonical `content-extractor-operator` naming for a future Kubernetes lifecycle, not required for the PoC deployment in [`doc/k8s-test-hardware.md`](doc/k8s-test-hardware.md)
- [OpenDataLoader PDF](https://github.com/opendataloader-project/opendataloader-pdf)

------

# License

Apache 2.0 - see [LICENSE](LICENSE).
 
