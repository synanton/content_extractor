#!/usr/bin/env bash
#
# Builds deployment/docker/extraction-gateway.Dockerfile, runs it against a throwaway
# Postgres+MinIO stack, and makes a real ExtractSync gRPC call for a text fixture and a
# valid PDF fixture - proving the image actually works, not just that `./gradlew build`
# compiled it. Catches runtime-only classpath bugs (e.g. a dependency-management BOM
# silently downgrading a transitive library) that no unit/integration test run under
# Gradle's own classpath resolution can see.
#
# Usage: ./scripts/docker-smoke-test.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$REPO_ROOT/deployment/docker/docker-compose.smoke-test.yml"
VENV_DIR="$REPO_ROOT/.smoke-test-venv"
PROTO_STUB_DIR="$REPO_ROOT/.smoke-test-venv/proto-stubs"
BUCKET="smoke-test"

TEXT_FIXTURE="$REPO_ROOT/java/extraction-gateway/src/test/resources/fixtures/supply-chain-overview.txt"
PDF_FIXTURE="$REPO_ROOT/java/adapter-document-pdf/src/test/resources/fixtures/quarterly-report.pdf"

cleanup() {
  echo "[cleanup] tearing down smoke-test stack..."
  docker compose -f "$COMPOSE_FILE" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "=== content_extractor Docker smoke test ==="

echo "[1/5] Building and starting throwaway stack (postgres, minio, extraction-gateway)..."
docker compose -f "$COMPOSE_FILE" up -d --build

echo "[2/5] Waiting for extraction-gateway to become healthy..."
for i in $(seq 1 30); do
  status=$(docker compose -f "$COMPOSE_FILE" ps extraction-gateway --format json 2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('Health',''))" 2>/dev/null || echo "")
  if [[ "$status" == "healthy" ]]; then
    echo "  extraction-gateway is healthy"
    break
  fi
  if [[ $i -eq 30 ]]; then
    echo "Error: extraction-gateway did not become healthy in time"
    docker compose -f "$COMPOSE_FILE" logs extraction-gateway --tail=60
    exit 1
  fi
  sleep 3
done

echo "[3/5] Uploading fixtures to MinIO..."
docker compose -f "$COMPOSE_FILE" cp "$TEXT_FIXTURE" minio:/tmp/supply-chain-overview.txt
docker compose -f "$COMPOSE_FILE" cp "$PDF_FIXTURE" minio:/tmp/quarterly-report.pdf
docker compose -f "$COMPOSE_FILE" exec -T minio sh -c "
  mc alias set local http://localhost:9000 minioadmin minioadmin >/dev/null &&
  mc cp /tmp/supply-chain-overview.txt local/$BUCKET/supply-chain-overview.txt >/dev/null &&
  mc cp /tmp/quarterly-report.pdf local/$BUCKET/quarterly-report.pdf >/dev/null
"

echo "[4/5] Setting up Python gRPC probe environment..."
if [[ ! -d "$VENV_DIR" ]]; then
  python3 -m venv "$VENV_DIR"
fi
# shellcheck disable=SC1091
source "$VENV_DIR/bin/activate"
pip install --quiet --upgrade pip grpcio grpcio-tools

mkdir -p "$PROTO_STUB_DIR/synanton/extraction/v1"
cp "$REPO_ROOT/java/extraction-contract/src/main/proto/synanton/extraction/v1/"*.proto \
   "$PROTO_STUB_DIR/synanton/extraction/v1/"
python3 -m grpc_tools.protoc \
  -I"$PROTO_STUB_DIR" \
  --python_out="$PROTO_STUB_DIR" \
  --grpc_python_out="$PROTO_STUB_DIR" \
  "$PROTO_STUB_DIR/synanton/extraction/v1/extraction_service.proto" \
  "$PROTO_STUB_DIR/synanton/extraction/v1/extraction_payload.proto"

TEXT_SHA=$(sha256sum "$TEXT_FIXTURE" | cut -d' ' -f1)
TEXT_SIZE=$(wc -c < "$TEXT_FIXTURE")
PDF_SHA=$(sha256sum "$PDF_FIXTURE" | cut -d' ' -f1)
PDF_SIZE=$(wc -c < "$PDF_FIXTURE")

echo "[5/5] Running real ExtractSync calls against the built image..."
PYTHONPATH="$PROTO_STUB_DIR" python3 - "$TEXT_SHA" "$TEXT_SIZE" "$PDF_SHA" "$PDF_SIZE" "$BUCKET" <<'PYEOF'
import sys
import grpc
from synanton.extraction.v1 import extraction_service_pb2 as svc
from synanton.extraction.v1 import extraction_service_pb2_grpc as svc_grpc

text_sha, text_size, pdf_sha, pdf_size, bucket = sys.argv[1:6]

channel = grpc.insecure_channel("localhost:9091")
stub = svc_grpc.ExtractionServiceStub(channel)


def extract_sync(key, sha256, size_bytes, media_type, label):
    item = svc.ExtractionRequestItem(
        content_ref_id=f"smoke-{label}",
        source=svc.ObjectReference(
            bucket=bucket, key=key, sha256=sha256, size_bytes=int(size_bytes),
        ),
        media_type=media_type,
        options=svc.ExtractionOptions(layout=True, tables=True, embedded_images=True),
    )
    req = svc.SubmitExtractionRequest(
        tenant_id="smoke-test",
        idempotency_key=f"smoke-test-{label}",
        item=item,
        priority_class=svc.PriorityClass.PRIORITY_NORMAL,
    )
    result = stub.ExtractSync(req, timeout=30)
    status = svc.ExtractionStatus.Name(result.status)
    if result.status != svc.ExtractionStatus.STATUS_COMPLETED:
        diag = result.error.diagnostic if result.HasField("error") else "(no error detail)"
        print(f"FAIL [{label}]: status={status} diagnostic={diag!r}")
        return False
    print(f"OK   [{label}]: status={status} flattened_text_len={len(result.flattened_text)}")
    return True


ok_text = extract_sync("supply-chain-overview.txt", text_sha, text_size, "text/plain", "text")
ok_pdf = extract_sync("quarterly-report.pdf", pdf_sha, pdf_size, "application/pdf", "pdf")

sys.exit(0 if (ok_text and ok_pdf) else 1)
PYEOF

echo ""
echo "Smoke test PASSED - extraction-gateway built from the real Dockerfile can parse both a real text file and a real PDF."
