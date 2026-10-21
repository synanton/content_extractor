# Content Extractor documentation inventory

**Reviewed:** 2026-10-21. Scope covers the repository entry point, SCEP
implementation state, design proposals, deployment prerequisites, and
platform integration references.
**Parent tracker:** [platform #368](https://github.com/synanton/platform/issues/368); **component workstream:** [content_extractor #17](https://github.com/synanton/content_extractor/issues/17).

| Document | Classification | Disposition / action |
|---|---|---|
| [`../README.md`](../README.md) | Current / repository entry point | Added supported media types and explicit unsupported modality status; distinguished component capabilities from the accepted Phase 1 end-to-end path. |
| [`README.md`](README.md) | Current / navigation | Added as the documentation entry point and linked current architecture, code status, and historical proposals. |
| [`Synanton_v1.21_structured_content_extraction.md`](Synanton_v1.21_structured_content_extraction.md) | Useful historical proposal / superseded | Preserved design rationale; added superseded notice pointing to Platform Architecture 1.0 and Design 1.22 Part IX. Its generic requirements are not current implementation claims. |
| [`Synanton_v1.21_multimodal_extraction_model.md`](Synanton_v1.21_multimodal_extraction_model.md) | Useful historical proposal / superseded | Preserved multimodal design history; explicitly states audio/image/video remain unsupported adapter stubs. |
| [`k8s-test-hardware.md`](k8s-test-hardware.md) | Operational reference / time-sensitive | Retain as personal PoC hardware, not project-owned infrastructure; its source-of-truth link is identified in the document. Recheck before deployment. |
| `java/extraction-contract`, `java/extraction-gateway`, `java/extraction-spi` | Current implementation and contract | Code/tests are authoritative for supported runtime behavior; README summarizes media types but explicitly limits its claims. |
| `java/adapter-document-pdf`, `java/adapter-document-text` | Implemented adapters | PDF uses OpenDataLoader; text-like formats use Tika and mostly paragraph output; feature support is result-specific. |
| `java/adapter-stubs` | Implemented capability stubs | Audio/image/video modules report unsupported; they are not extraction implementations. |
| Platform [acceptance evidence](https://github.com/synanton/platform/blob/main/docs/acceptance/p0-ingestion-search-2026-10-21.md), [component issue #17](https://github.com/synanton/content_extractor/issues/17) | Cross-repo validation / workstream | End-to-end evidence applies to the demonstrated BM25 path only; it does not validate all formats or modalities. |

No design history was deleted. Use Proposed, Approved (design), Contract
frozen, In progress, Implemented, Validated (with scope/evidence/date), and
Superseded; a component-level integration test is not by itself end-to-end
validation.
