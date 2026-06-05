# [plan-10] Upload At-Most-Once / Idempotency — BLOCKED on Paperless-ngx API

## Defect

**Current behavior:** UploadWorker implements **at-least-once semantics** by design. When a cancellation signal arrives after the request body is sent to the server but before the response is received, the upload commit state becomes unknown. To avoid losing uploads, the worker conservatively resets the row back to PENDING (line 322 in `UploadWorker.kt`), allowing the next run to retry the same file. This works correctly for preventing data loss, but creates a duplicate-upload risk if Paperless-ngx accepts and persists the document before returning the response.

**Root cause:** The Paperless-ngx `POST /api/documents/post_document/` endpoint returns a plain-text Task ID (see `KNOWN_ISSUES.md` section 2) and has no built-in support for idempotency keys or deduplication tokens. The server cannot distinguish between a legitimate retry (client crash + restart) and an accidental re-POST of an already-ingested document. The current design acknowledges this (line 317: "Paperless de-dups identical content by checksum"), relying on server-side content deduplication rather than request-level idempotency.

**Current reality:** Issue #128 (resolved on `fix/worker-reliability-cluster`, merged to main) hardened the at-least-once behavior by:
1. Guarding upload commitment with the `uploadCommitted` flag (set only after successful response)
2. Using `NonCancellable` context to prevent post-commit finalization interruption
3. Resetting in-flight uploads to PENDING on cancellation

This eliminated stranded UPLOADING rows and improved data integrity. However, it does not solve the idempotency gap: if cancellation occurs during the request/response window, a retry will re-POST the same document.

## Children

- #287 — UploadWorker may re-POST an already-accepted document if cancellation lands during request/response window (open, blocked on investigation)

## Fix sequence

1. **INVESTIGATION (blocking gate — must complete before proceeding):**
   - Inspect Paperless-ngx API documentation (https://docs.paperless-ngx.com/api/) for any existing idempotency/dedup mechanism:
     - Does `POST /api/documents/post_document/` accept request headers like `Idempotency-Key` or `X-Idempotency-ID`?
     - Does it support any query parameter (e.g., `?idempotency_key=...`)?
     - Are there any multi-request sequences (e.g., POST task then PUT to finalize) that offer better commit semantics?
   - If Paperless-ngx source is accessible, search for request header inspection in the `post_document` view.
   - Document findings (yes/no) in a GitHub comment on #287 with links to source or API docs.

2. **IF investigation returns YES (Paperless-ngx supports idempotency):**
   - Add an idempotency key to each PendingUpload row in the database (UUID, deterministically seeded from document content + metadata).
   - Modify `DocumentRepository.uploadDocument / uploadMultiPageDocument` to attach the idempotency key as a header/query param before calling the API.
   - Update `UploadWorker` comments to reference the idempotency mechanism.
   - Document the new contract in `docs/QUEUE_ONLY_ARCHITECTURE.md`.
   - Test: cancel mid-upload twice on the same row; verify only one document appears on the server.

3. **IF investigation returns NO (Paperless-ngx has no built-in idempotency):**
   - Document the limitation in `KNOWN_ISSUES.md` section 10 (after section 7):
     - Title: "Upload Duplicate Risk: Cancellation During Request/Response Window"
     - Explanation: at-least-once semantics + no server-side idempotency key → potential duplicates if worker stops during flight.
     - Mitigation: Paperless-ngx deduplicates by content checksum (observed on server); users can clean up duplicates via Paperless UI.
     - Recommendation: File a feature request on the Paperless-ngx GitHub discussing idempotency-header support.
   - Update `UploadWorker.kt` comment (line 317) to link to the known-issue doc.
   - Close #287 with a `wontfix` label and explanation.

## Test matrix

| Axis | Case | Required behavior |
|---|---|---|
| **Investigation success** | Paperless-ngx docs show idempotency-key support | Proceed to step 2 (implement idempotency header) |
| **Investigation success** | Paperless-ngx docs have no idempotency mechanism | Proceed to step 3 (document limitation, close #287) |
| **Source-level discovery** | GitHub search finds header-inspection code in `post_document` view | Idempotency is supported; implement it |
| **Source-level discovery** | No header-inspection code found | Idempotency not supported; document and close |
| **Double-cancel retry (if YES route)** | Queue same doc, cancel twice mid-upload, check server | Only 1 document persisted on server (idempotency key rejected re-POST) |
| **Duplicate recovery (if NO route)** | Queue doc, cancel during flight, restart, verify upload succeeds | Duplicate appears on server; user can delete via UI |

## Reusable seams

- `UploadWorker.kt` (line 304–325) — CancellationException handler with `uploadCommitted` guard; reuse as reference for any idempotency-key insertion point
- `DocumentRepository.uploadDocument / uploadMultiPageDocument` (lines 300–400) — Retrofit `@Multipart @POST` calls; if idempotency-key support exists, inject here
- `PendingUpload` entity (database schema) — may need an `idempotencyKey: String` field if step 2 proceeds
- `KNOWN_ISSUES.md` section 2 — existing explanation of Paperless-ngx response format; extend with idempotency discussion in section 10

## Out of scope

- Changes to Paperless-ngx server itself (feature request only, if step 3 applies)
- Retry-logic redesign; the at-least-once semantics remain the baseline
- Content-based deduplication on the client side (that is a server responsibility)
- Addressing #128 follow-ups (worker cancellation handling is complete and merged)

