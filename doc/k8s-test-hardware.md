# Test Kubernetes Cluster (PoC hardware)

**Status:** Reference — not project-owned infrastructure
**Date:** 2026-09-12
**Source of truth:** [`andreminin/homelab-k8s`](https://github.com/andreminin/homelab-k8s) — `src/cluster-as-built.md` (as-built 2026-09-09)

## Purpose

For SCEP-6 (topology equivalence + hardening) and related PoC work, prototyping happens in `docker compose` first — it's faster to iterate on and closer to the "Mode B — Co-located" deployment shape the extraction contract already treats as equivalent (see README "Core Architectural Principle").

A test Kubernetes cluster is available as a fallback **if `docker compose` prototyping turns out not to be sufficient** — for example, to validate "Mode C/D — Clustered / Distributed" topology equivalence under something closer to a real orchestrator, to exercise multi-node scheduling, or to test behavior under the registry/network constraints a real deployment would have. It is a personal homelab cluster lent for PoC purposes, not part of Synanton's own infrastructure, and using it does **not** imply or require the `content-extractor-operator` from [Design 1.33](https://github.com/synanton/platform/blob/main/docs/architecture/synanton-design-1.33.md) — per that design, domain contracts do not require Kubernetes, and this is exploratory PoC deployment, not an operator readiness exercise.

## Nodes

| Node | Role | K8s version | CPU | RAM | GPU | Internal IP |
|---|---|---|---|---|---|---|
| `node0` | control-plane, master | v1.37.0 | 4 | 16Gi | none | 192.168.10.30 |
| `node1` | worker | v1.37.0 | 16 | 64Gi | GTX 1650, 4GB | 192.168.10.31 |
| `node2` | worker | v1.37.0 | 20 | 64Gi | RTX 4060 Ti, 16GB | 192.168.10.32 |
| `node3` | worker | v1.37.0 | 20 | 64Gi | RTX 5060 Ti, 16GB | 192.168.10.33 |

`node0` also runs the container registry (see below). All four nodes have a second 1 Gbit interface on `192.168.18.x`; the addresses above are the 10 Gbit/s SFP+ mesh.

**No GPU is required for the current PDF extraction PoC** (OpenDataLoader is CPU-only) — any node, including `node0`, can run `extraction-gateway` and the PDF adapter. The three GPU workers (`node1`–`node3`, one physical GPU each, no MIG/time-slicing) matter only for future multimodal work (SCEP-7: audio/image/video, OCR/ASR/VLM), not for SCEP-6.

## Networking

Calico CNI, no cluster-level network policy beyond what individual namespaces define. Nothing content_extractor–specific needed here beyond whatever `NetworkPolicy` a new namespace chooses to add.

## Storage

Two real options today:

| StorageClass | Backing | Binding mode |
|---|---|---|
| `local-ssd` | per-node hostPath, no provisioner | `WaitForFirstConsumer` |
| `longhorn` / `longhorn-static` | Longhorn replicated block storage | `Immediate` |

The existing workload on this cluster (`speech-to-speech-k8s`) uses per-node `hostPath` directly rather than either `StorageClass` for latency-sensitive local caches. `extraction-gateway` has no obvious need for replicated block storage (extraction is stateless per-request; large content is referenced via object storage, not stored locally per the extraction contract), so `local-ssd`/hostPath is the more likely fit if any local scratch space is needed at all.

There is **no `nfs-datasets` StorageClass** — an NFS export exists on `node1` but isn't wired into Kubernetes as a `StorageClass`.

## Container registry

Plain `registry:2`, run via `docker compose` directly on `node0` (not in-cluster), at `local-registry:5000`, basic-auth protected. Worker nodes have no assumed direct internet egress, so images must be built, tagged, and pushed to `local-registry:5000` rather than pulled from a public registry in-cluster — the same pattern `speech-to-speech-k8s/scripts/mirror-images.sh` uses for third-party images.

For `extraction-gateway`, this means: build the image from `deployment/docker/extraction-gateway.Dockerfile`, tag it `local-registry:5000/extraction-gateway:<tag>`, push it, then reference it from a Deployment with an `imagePullSecrets` entry for a `local-registry-cred` Secret.

## Namespace

No namespace for content_extractor exists yet (`speech` is currently the only application namespace on this cluster). If the fallback to Kubernetes is needed, create a new namespace — e.g. `content-extractor` — and replicate the registry-secret pattern `speech-to-speech-k8s/scripts/create-registry-secret.sh` uses, rather than reusing `speech`'s namespace or secret.

## What's intentionally not duplicated here

Full networking/GPU/storage/troubleshooting detail lives in `andreminin/homelab-k8s` (`src/cluster-as-built.md`) and `speech-to-speech-k8s`'s own docs (`docs/longhorn-setup.md`, `docs/architecture.md`, `docs/troubleshooting.md`). This document only summarizes what's relevant to deciding whether/how to run a content_extractor PoC here; treat the source repo as authoritative if details drift.
