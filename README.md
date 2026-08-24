# Synanton Content Extractor

[![Status](https://img.shields.io/badge/Status-SCEP--1%20Contract-blue)](https://github.com/Synanton/content_extractor)
[![Java](https://img.shields.io/badge/Java-21-red)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)
[![gRPC](https://img.shields.io/badge/gRPC-Protobuf-purple)](https://grpc.io/)

## Overview

The **Content Extractor** implements the Synanton **Structured Content Extraction Plane**: the
boundary between raw content and structured content that Synanton knowledge processing can consume.

It answers one question:

> **What is present in this artifact, and what structure can be reliably extracted from it?**

What the extracted content *means* in a business domain is a separate concern, handled downstream by
the platform.

### Architectural Invariant

- **Synanton Platform** determines **WHAT** must be extracted (content selection, requested
  features, priority intent, deadlines).
- **Content Extractor** determines **HOW** extraction is performed (processor selection, routing,
  scheduling, admission).
- **Deployment topology** determines **WHERE** it runs — and is a scaling concern that MUST NOT
  change the contract.

> **Critical constraint:** the platform MUST NOT be able to discover which parser, library,
> accelerator, worker, or queue performed the work. The `synanton.extraction.v1` gRPC contract is
> the sole surface visible to it.

The plane is deliberately a **black box**. It may route a request to a PDF extractor, Tika,
OpenDataLoader, an OCR service, a transcription service, an image or video analyzer, or another
extraction cluster entirely. None of that is visible through the contract.

---

## Current Status

**SCEP-1 (Contract) — complete.** This repository currently contains the contract module only. The
service, adapters, and processors land in later phases.

| Phase | Name | Status |
|-------|------|--------|
| SCEP-1 | Contract | ✅ Done |
| SCEP-2 | Extraction plane skeleton + sync path | Planned |
| SCEP-3 | PDF PoC (OpenDataLoader) | Planned |
| SCEP-4 | Async operation model | Planned |
| SCEP-5 | Platform integration | Planned |
| SCEP-6 | Topology equivalence + hardening | Planned |
| SCEP-7 | Multimodal expansion (audio/image/video) | Post-v1.21 |

The full plan lives in the platform repository at
`docs/implementation/content-extraction-plane/INDEX.md`.

---

## Architecture

### Layering

The implementation is split into three layers. This separation is the key design decision: it is
what lets the processor change without the contract changing.

```text
┌──────────────────────────────────────────────────────────┐
│  Synanton Extraction Contract                            │
│  Request / Operation / Result / Tags / Errors            │
│  (synanton.extraction.v1 — this repo + platform mirror)  │
└─────────────────────────┬────────────────────────────────┘
                          │
┌─────────────────────────▼────────────────────────────────┐
│  Modality Adapters                                       │
│  PDF │ Text │ EPUB │ HTML │ Audio │ Image │ Video        │
└─────────────────────────┬────────────────────────────────┘
                          │
┌─────────────────────────▼────────────────────────────────┐
│  Processor Implementations                               │
│  OpenDataLoader │ OCR │ ASR │ diarization │ VLM │ ...    │
└──────────────────────────────────────────────────────────┘
```

### Position in the platform

```text
        Source Systems (FileNet, SharePoint, local FS)
                          │
                          ▼
                     Lucentrix              ← how to retrieve content
                          │
                          ▼
                  Content Objects (S3)
                          │
                          ▼
        Structured Content Extraction Plane  ← how to structure it
                          │
                          ▼
                  StructuredPayload
                          │
                          ▼
                 Knowledge Platform          ← what it means
```

---

## Modules

| Module | Purpose |
|--------|---------|
| `java/extraction-contract` | Owns the `synanton.extraction.v1` protobuf contract, the request validator, and the error catalogue. Depends on nothing in this repo or the platform. |

Planned:

| Module | Phase | Purpose |
|--------|-------|---------|
| `java/extraction-gateway` | SCEP-2 | gRPC server, operation store, router, admission |
| `java/extraction-spi` | SCEP-2 | `ModalityAdapter` SPI and normalized payload model |
| `java/adapter-document-text` | SCEP-2 | plain text, EPUB, HTML |
| `java/adapter-document-pdf` | SCEP-3 | OpenDataLoader-backed PDF adapter |
| `java/adapter-stubs` | SCEP-2 | audio/image/video capability-declining stubs |

---

## The Contract

`synanton.extraction.v1` consists of two proto files:

| File | Contents |
|------|----------|
| `extraction_service.proto` | `ExtractionService` (9 RPCs), request/operation/result messages, 6 enums |
| `extraction_payload.proto` | `StructuredPayload` envelope, `DocumentPayload`, reserved audio/image/video payloads |

### RPCs

| RPC | Semantics |
|-----|-----------|
| `SubmitExtraction` | Async submit; returns an operation handle immediately |
| `SubmitExtractionBatch` | Several artifacts as one operation |
| `ExtractSync` | Small content, inline; same result model, same domain path |
| `GetOperations` | Authoritative status poll by id; safe after any timeout |
| `ListCompletedOperations` | Cursor-based completion feed for high-throughput consumers |
| `GetResult` | Structured result for one completed item |
| `CancelOperation` | Best-effort |
| `GetCapacity` | Advisory; does **not** reserve |
| `EstimateExtraction` | Advisory pre-flight estimate |
| `GetCapabilities` | Supported media types and features |

### Key contract properties

**Content is referenced, never transported.** Requests carry an `ObjectReference`
(bucket/key/version/sha256/size); the plane reads bytes from object storage directly.

**Options are tri-state.** Every field in `ExtractionOptions` is `optional`, so the contract
distinguishes *unset — plane decides* from *explicitly false — do not do this*. A scanned page with
`ocr=false` must not be OCR'd; the same page with `ocr` unset leaves the decision to the plane.

**Feature state is explicit.** `ocr = true` on a request says nothing about what happened. Results
report `FEATURE_APPLIED`, `FEATURE_NOT_APPLICABLE`, `FEATURE_UNSUPPORTED`, `FEATURE_FAILED`, or
`FEATURE_PARTIAL` per feature. A requested feature that was not applied is never reported as
success by omission.

**Priority is intent, not topology.** `PRIORITY_HIGH` does not name a queue, a pool, or an
algorithm. A numeric priority is deliberately absent.

**Expiry is a lifecycle outcome.** An operation past `expires_at` reports `STATUS_EXPIRED`, never
`STATUS_FAILED`.

**Errors are 13 fixed codes.** Consumers branch on `ExtractionErrorCode` and on
`ExtractionErrorCatalogue.isRetryable(...)` — never on the `diagnostic` string, which is unstable
operator detail by design.

**No webhooks in v1.21.** Notification is operation id plus status and cursor polling.

---

## Contract Mirroring

The proto files are a **byte-identical mirror** of
`platform/java/extraction-contract/src/main/proto/`. Both repositories generate their own stubs;
neither depends on the other's build.

```bash
./scripts/verify-contract-mirror.sh                 # sibling checkout
EXTRACTION_PEER_REPO=/path/to/platform ./scripts/verify-contract-mirror.sh
```

The check is wired into `check` and fails the build on divergence. Never edit one copy alone.

> This exists because the older `synanton.gpu.v1` "mirror" silently diverged — the platform holds
> one file under `org.synanton.gpu.v1` with a `GetStatus` RPC, while `gpu-runtime` holds four files
> under `com.synanton.gpu.v1` with a `StatusRequest` RPC. Nothing failed, because nothing checked.

---

## Building

Requires JDK 21. Gradle runs via the wrapper.

```bash
./gradlew build                                  # compile + test + verify mirror
./gradlew :java:extraction-contract:generateProto  # regenerate stubs after a .proto change
./gradlew test                                   # all tests
./gradlew verifyContractMirror                   # mirror check only
```

### Tests

43 contract tests currently run in this module:

| Suite | Covers |
|-------|--------|
| `ExtractionRequestValidatorTest` | every documented field rule, including boundary values |
| `ExtractionErrorCatalogueTest` | all 13 codes documented and classified; unknown codes are not retryable |
| `ContractOpacityTest` | walks compiled descriptors; fails if the contract names a processor, library, or topology element |
| `ExtractionServiceContractTest` | round-trip against an in-process mock plane |

### A note on validation

Request validation is hand-written (`ExtractionRequestValidator`) rather than generated by
protoc-gen-validate. The PGV plugin is not wired into either repository's build, so PGV annotations
in the `.proto` would compile without complaint and validate **nothing** — a silent gap on requests
that admit expensive work. The rules are documented per-field in the proto and enforced in Java at
the service boundary, which is the same approach the platform's `PgvRuleCatalogue` takes.

---

## Architectural Rules

`.cursor/rules/extraction-rules.mdc` carries 18 non-negotiable invariants, enforced by review and
by tests. The load-bearing ones:

1. Contract over topology — identical embedded or clustered
2. Black-box extraction — no processor or topology names in the contract
3. Raw source authority — the plane never modifies the source
4. Domain isolation — `domain/` imports no protobuf, JDBC, Spring, or adapter
5. PostgreSQL is the authoritative operation store; no Redis, Kafka, or Cassandra
6. Idempotency required, fail-closed
7. Feature state computed from what was produced, not what was requested
8. Extraction is not knowledge processing

---

## License

Apache 2.0 — see [LICENSE](LICENSE).

---

## References

- [Synanton v1.21 Structured Content Extraction Plane proposal](https://github.com/Synanton/platform/blob/main/docs/proposals/v1.21/Synanton_v1.21_Structured_content_extraction_plane.md) — the contract
- [Multimodal extraction design draft](https://github.com/Synanton/platform/blob/main/docs/proposals/v1.21/Synanton_v1.21_Structured_content_extraction_plane_draft.md) — modality models and PDF PoC
- [Implementation plan](https://github.com/Synanton/platform/blob/main/docs/implementation/content-extraction-plane/INDEX.md) — phased delivery
- [Synanton GPU Runtime](https://github.com/Synanton/gpu-runtime) — the contract-bounded plane this repository is patterned after
- [OpenDataLoader PDF](https://github.com/opendataloader-project/opendataloader-pdf) — SCEP-3 PoC processor
