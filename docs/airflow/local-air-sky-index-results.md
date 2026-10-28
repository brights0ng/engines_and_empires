<!-- Written by ChatGPT. -->
# Shared local-air sky index

Updated 2026-10-27. Bright requested shared column coverage and cheaper first-time sky validation after the [coverage recovery follow-up](local-air-coverage-recovery-results.md).

## Behavior

Each Overworld heat runtime now owns a bounded, transient sky index shared by manual and automatic capture sources. An entry records a contiguous clear suffix downward from the world ceiling. Every higher query can use that suffix. Scanning farther down extends it until the first obstruction is found; that obstruction rejects every lower query. The index survives domain retirement and replacement, so reuse does not require retaining a live donor field. It is not saved to disk.

Unclassified chunk sections receive one non-loading classification. A section is bulk-empty only when it contains exclusively supported vanilla air states with empty fluids and empty, non-dynamic collision shapes. Generic `isAir`, lighting and heightmaps are not used as proof. Occupied/uncertain sections use the existing scalar collision/fluid extraction, including special snow occupancy. Once known, empty section summaries are shared across neighboring columns. Each charged index operation performs at most one section classification or scalar sample, plus at most 32 cached section skips. Coverage retains the existing shared allowance/deadline and full logical evidence admission limit.

Live coverage consumes the index and registers the proof's primitive read bounds in the receiving owner's existing dependency tracker. A validity lease is checked again after registration, closing the race where an off-thread edit arrives while a new owner imports evidence. Historical capture sources return no live index; their chronological scalar capture and guarded previous-certificate reuse remain available.

Every block write invalidates cached epochs for its chunk, including when no current domain covers that chunk. Load/unload hooks invalidate the same epochs before off-thread work is marshalled to the server. Scalar shape-context chunks are included in each entry's stamps. This deliberately uses conservative chunk granularity for the cache; published fields retain their existing, finer read-footprint guards. Material/build-height changes clear the index. World lifetimes have separate caches and server shutdown releases the registry. LRU limits bound column and section records to 16,384 each; a 4,096-chunk reset guard and 64 context chunks per entry bound additional bookkeeping. Entries retain no world/chunk/solver references.

`air auto` includes a `Shared sky index` report with record counts, queries/hits, actual scalar samples, section classifications and bulk-proven cells. The internal local-air report includes the same `skyIndex` record and `coverageBulkCells`. `coverageReadCells` continues to count fresh logical evidence positions, including bulk evidence; it should not be interpreted as the number of block getter calls. Reused complete column proofs remain in `coverageReusedCells`. Existing coverage progress reports verified positions against the complete required total.

## Verification and measured work

The real tall-column fixture captures a 3-by-3-by-3 domain near the world floor and checks 21 required sky columns through the build ceiling. Its admitted proof represents 7,979 sky positions. The following compares the work required by the old scalar traversal with the measured new proof work; it is not a comparison of measured player startup latency.

| Evidence work | Scalar traversal | Cold shared index | New owner, warm index |
| --- | ---: | ---: | ---: |
| Block samples | 7,979 | 251 | 0 |
| Section classifications | 0 | 24 | 0 |
| Charged coverage operations | 7,979 | 275 | 21 cached lookups |
| Bulk-proven positions | 0 | 7,728 | Reused complete columns |

The fixture logs `SKY_INDEX_COLD` and `SKY_INDEX_WARM`. Replacing the capture owner preserves the shared result without new block or section world reads. Stone, slab, water and one-layer snow placed above the domain each withdraw the publication immediately, reject with the actual obstruction height, and permit fresh admission after removal. Chunk lifecycle and material reload force new section evidence. The fixture also verifies that historical world views cannot access the live index.

Pure tests cover higher/lower height queries, the highest obstruction, neighboring-column section reuse, shared owners, roof placement/removal, shape-context and unrelated chunk edits, reloads/build heights/material revisions, separate world lifetimes, missing geometry/evidence fallback, scalar fluid/partial/thin occupancy, bounded LRU storage, off-thread invalidation, edits during sampling and dependency registration, deadline/allowance enforcement and unchanged total evidence admission.

The focused local-air unit and standalone Meteodyne runs passed. The final required `compileJava compileTestJava test runGameTestServer` run passed in **4m 59s**, exit 0 (`build/air-sky-full.log`). It included **1,606 unit cases**, zero failures/errors and the same two optional benchmark skips, including six architecture checks. All **409 aggregate**, **359 coreless** and **111 standalone Meteodyne** GameTests passed. Other standalone runs also passed: Empire 124, Frontier 17, lib 3, Sable Fixes 2, Seismic 20, Spoilage 34, and Sparse Ores 13 both bare and with Create. All three environments reproduced the cold 251-sample/24-section proof and warm zero-world-read reuse. IDE compilation and tracked/new-file whitespace checks also passed.

No actual client arrival-time claim is made. Geometry capture, natural thermal refinement and coupled stepping still have their own cost. The change preserves conservative coverage admission, automatic recovery limits, physics, production consumers and default activation.
