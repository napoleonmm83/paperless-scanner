# Server capabilities

The app reads `X-Version` and `X-Api-Version` from successful authenticated network responses. Server versions use semantic version ordering; the API protocol version remains separate. Cached responses, failed requests, unknown versions and development builds cannot confirm compatibility.

Capability evidence is held in memory and belongs to the current server and credential generation. Saving credentials, including an account change on the same server, or logging out clears it. Responses from earlier generations cannot restore it. Settings observes this shared state and probes the existing document-list endpoint with `page_size=1` when no version is known.

## Adding a feature later

1. Implement the feature and verify its minimum Paperless-ngx version against the upstream release and API contract.
2. Set its catalog entry in `ServerFeatureCatalog` to `implemented = true` with the verified minimum version. Keep an unknown minimum unavailable.
3. Show controls only when `feature.evaluate(state.serverVersion) == FeatureStatus.AVAILABLE`.
4. Call `ServerCapabilityRepository.requireFeature(id)` before each feature request. Hiding controls alone does not protect alternate entry points.
5. Test older, minimum, newer, unknown and prerelease versions, plus account/server changes and late responses.

Settings → Server lists only implemented features whose status is `UPDATE_REQUIRED`. Features with an unknown version or minimum are not presented as benefits of an update.

## Planned integrations

These entries are reserved and are all currently `NOT_IMPLEMENTED`, without an invented minimum version:

| Catalog ID | Planned feature |
| --- | --- |
| `share_links` | Document share links |
| `saved_views` | Saved views |
| `bulk_edit` | Bulk document edits |
| `similar_documents` | Similar documents |
| `custom_fields_search` | Search by custom fields |
| `search_assistance` | Search autocomplete and highlighting |
| `pdf_edit` | Archived PDF edits |
| `storage_paths` | Storage path management |
| `file_versions` | Document file versions |

The implementation tasks are recorded in the project task list and deferred at the owner's request. The capability foundation does not enable these integrations.
