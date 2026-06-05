# [plan-05] DocumentRepository Data-Integrity & Cache Semantics — atomic tag-count on every path + documented cache rules

## Defect

The decomposed DocumentRepository façade (split across 9 specialized repositories) has two structural integrity gaps:

1. **Tag-count transaction atomicity incomplete:** The online path (DocumentMetadataRepository ~L118) wraps tag-count deltas inside `db.withTransaction{}`, but the offline path (SyncManager.pushDocumentChange ~L459) does not. When offline-queued updates are pushed later, tag deltas execute outside a transaction. A partial failure (e.g., one tag's count updates, another throws) leaves counts diverged and inconsistent with the cached document.

2. **Cache/TTL/soft-delete/pending-change semantics undocumented:** The decomposed repositories (TagRepository, CorrespondentRepository, DocumentTypeRepository, TrashRepository, DocumentSyncRepository, DocumentMetadataRepository, DocumentListRepository, DocumentCountRepository, CustomFieldRepository) either lack class-level kdoc or have minimal method-level documentation on cache TTL, refresh triggers, soft-delete inclusion rules, and pending-change interaction. New contributors cannot reliably answer: Does this Flow filter soft-deletes? When does it refresh from server? What happens to a pending-change if the network comes back? Does cache have an expiry?

**Current reality:** The online path is already fixed — DocumentMetadataRepository.updateDocument (L102–142) wraps the tag-count mutation inside `db.withTransaction{}` (L114–126) and getOldTagIds now logs instead of silently returning emptyList on parse failure (L203–210). The #51 God-class decomposition already happened (DocumentRepository is a ~261-line façade; 9 specialized repos exist, some with rich offline-first kdoc like TagRepository L28–68). DocumentSyncRepository (introduced in Phase 3.3) centralizes the offline-queue + serverHealth pattern for DocumentMetadataRepository.updateDocument, TrashRepository.deleteDocument, TrashRepository.restoreDocuments, and TrashRepository.permanentlyDeleteDocuments — but does not yet apply tag-count atomicity to offline pushes.

## Children

- #65 — Tag-count sync not transactional + getOldTagIds swallowed parse failures (**partially-done:** online path fixed in DocumentMetadataRepository; offline path in SyncManager.pushDocumentChange still needs tag deltas)
- #66 — Kdoc absent for cache/TTL/soft-delete/pending-change semantics (**partially-done:** TagRepository, CorrespondentRepository have class + method kdoc; others like DocumentTypeRepository, TrashRepository, DocumentSyncRepository lack class headers or method-level cache rules)

## Fix sequence

1. **Mirror tag-delta atomicity to offline push path:** Add identical `db.withTransaction{}` block to SyncManager.pushDocumentChange (around L485–489 where cache insert happens). Compute `oldTagIds` from the PendingChange's deserialized `tags` field (mirroring DocumentMetadataRepository.getOldTagIds). Wrap the cache insert + per-tag count deltas in a single transaction. If any DAO call throws, the transaction rolls back so the cached document is never persisted with stale counts alongside it. Add a test case in SyncManagerTest that verifies rollback on mocked tag DAO failure.

2. **Audit all decomposed repositories for cache/TTL/soft-delete/pending-change kdoc:** Scan each of the 9 decomposed repos (TagRepository.kt, CorrespondentRepository.kt, DocumentTypeRepository.kt, TrashRepository.kt, DocumentSyncRepository.kt, DocumentMetadataRepository.kt, DocumentListRepository.kt, DocumentCountRepository.kt, CustomFieldRepository.kt) and verify:
   - **Class-level kdoc** exists and documents: cache hierarchy (where is cached data stored?), TTL/refresh policy (does cache expire? when does a Flow refresh from server?), soft-delete inclusion semantics (does observeDocuments filter out soft-deleted rows?), pending-change behavior (how do pending updates interact with Flows?).
   - **Each public Flow method** documents: when does it refresh from server (on subscription, on forceRefresh, never)? Does it filter soft-deleted data? Is the Flow reactive (re-emits on cache mutation)?
   - **Each public suspend method** (getX, updateX, deleteX) documents: does it queue a pending change on offline? Does it insert cache immediately (optimistic) or wait for server response? Does it trigger cache invalidation?
   - **Mark methods with @Deprecated or @Experimental** if they're excluded from the cache contract (e.g., audit-only methods in AuditRepository).

3. **Apply consistent kdoc template across all decomposed repos:** Use a standardized structure:
   ```
   /**
    * <Phase X.Y of #51 — [what was extracted]>
    *
    * **CACHE & REFRESH POLICY:**
    * <Cache TTL, invalidation triggers, soft-delete inclusion, pending-change interaction>
    *
    * **EXAMPLE USAGE:**
    * <Show reactive + one-shot fetch patterns>
    *
    * @property ... <document each collaborator + its role>
    * @see ... <link to DAO, API, domain model>
    */
   ```
   This ensures every contributor opening a repo can immediately understand its cache contract without reading the entire file.

4. **Add offline→online divergence test:** Create a test in SyncManagerTest that verifies tag counts are consistent between the online path (DocumentMetadataRepository.updateDocument) and the offline push path (SyncManager.pushDocumentChange) when they share the same tag delta. Seed tags [1, 2], queue an offline update to tags [2, 3], push it, and verify tag 1's count dropped and tag 3's count rose in the same transaction.

## Test matrix

| Axis | Case | Required behavior |
|---|---|---|
| **Transaction atomicity (offline push)** | Tag DAO throws inside transaction | Cache insert rolls back; document never persisted with stale tag counts |
| **Transaction atomicity (offline push)** | All DAO calls succeed | Cache insert + tag count deltas all visible in single view |
| **Divergence test** | Online path vs. offline push path | Same tag delta produces identical count mutations; no off-by-one due to missed transaction |
| **Kdoc coverage** | TagRepository class + public methods | Explains cache TTL, refresh triggers, soft-delete filtering, pending-change queuing |
| **Kdoc coverage** | CorrespondentRepository class + public methods | Explains cache TTL, refresh triggers, soft-delete filtering, pending-change queuing |
| **Kdoc coverage** | DocumentTypeRepository class + public methods | Explains cache TTL, refresh triggers, soft-delete filtering, pending-change queuing |
| **Kdoc coverage** | TrashRepository class + public methods | Explains soft-delete semantics, restore/permanent-delete cache invalidation, pending-change interaction |
| **Kdoc coverage** | DocumentSyncRepository class + public methods | Explains serverHealth snapshot, IOException fallback, transaction wrapping, payload serialization |
| **Kdoc coverage** | DocumentMetadataRepository class + public methods | Explains cache TTL, tag-count atomicity, offline-queue delegated to DocumentSyncRepository |
| **Kdoc coverage** | DocumentListRepository class + public methods | Explains cache TTL, soft-delete inclusion, pending-change interaction, pagination |
| **Kdoc coverage** | DocumentCountRepository class + public methods | Explains cache TTL, soft-delete exclusion rules, reactive updates on tag/correspondent changes |
| **Kdoc coverage** | CustomFieldRepository class + public methods | Explains cache TTL, refresh triggers, serialization/deserialization contract |
| **Old kdoc removal** | Remove pre-Phase-3.3 stale comments | Any kdoc referring to "DocumentRepository.updateDocument" instead of "DocumentMetadataRepository" is outdated |

## Reusable seams

- `DocumentMetadataRepository.kt` L102–142 — withTransaction pattern to mirror in SyncManager.pushDocumentChange
- `DocumentSerializer.kt` L35–40 — deserializeCachedTagIds, idempotent + logs on failure; reuse in SyncManager for offline push tag parsing
- `DocumentMetadataRepository.kt` L199–210 — getOldTagIds implementation with logging; mirror in SyncManager
- `DocumentMetadataRepositoryTest.kt` "updateDocument rolls back cache insert when a tag-delta DAO call throws" — test template for transaction rollback; adapt for offline path
- `TagRepository.kt` L28–68 — exemplary class + method kdoc structure for cache/offline-first semantics; use as template for other decomposed repos
- `data/repository/*.kt` — 9 decomposed repositories to audit (all inherit from same pattern; use single template for consistency)
- `data/sync/SyncManager.kt` L459–503 — pushDocumentChange currently skips tag deltas entirely; must mirror DocumentMetadataRepository's withTransaction + delta logic

## Out of scope

- **Soft-delete filter rules per repository:** Each repository's soft-delete inclusion semantics (e.g., does observeDocuments filter out isDeleted=true rows?) is documented separately per-repository; this plan only ensures kdoc *exists* and is discoverable. The actual filter logic (already implemented) is not changed.
- **TTL enforcement mechanism:** This plan documents cache TTL policy (e.g., "one-shot getDocuments caches for 5 min unless forceRefresh") but does not implement TTL-based expiry. That is handled by each DAO's @Query or by explicit invalidation calls.
- **Pending-change propagation to Flows:** Some Flows may not yet invalidate cache on pending-change insertion. This plan documents the expected behavior; implementation (cache invalidation on PendingChange insert) is a separate defect (#73+).
- **#61 DocumentSerializer tag-id serialization audit:** The centralizedtag-id (de)serialization contract (#61) is already in DocumentSerializer.kt L35–40; this plan reuses it in SyncManager but does not change the serializer itself.
- **Other plan masters:** See [plan-03] for decomposed repository error handling, [plan-04] for offline-queue UX + retry semantics, [plan-06] for DAO-level transaction rollback behavior.

---

**Plan Summary:** Fix tag-count atomicity in the offline push path by wrapping SyncManager.pushDocumentChange's cache insert + per-tag count deltas in a single transaction (mirroring DocumentMetadataRepository.updateDocument), add an offline→online divergence test, and apply a standardized cache/TTL/soft-delete/pending-change kdoc template across all 9 decomposed repositories so contributors understand the cache contract at a glance.
