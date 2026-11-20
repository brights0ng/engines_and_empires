<!-- Written by ChatGPT. -->
# Automatic local-air coverage recovery

Updated 2026-10-27. Bright authorized this follow-up after a live automatic viewer remained pending for roughly a minute.

The later [shared sky index](local-air-sky-index-results.md) addresses first-time evidence cost and reuse across owner lifetimes. The measurements and scalar-scan limitations below describe the recovery checkpoint before that follow-up.

## Observed failure

The live log's final automatic snapshot reported a 16,384-cell area at `(64, -64, -80)`, zero restarts, and an unchanged four-tile demand plan. Preparation spent 3,197.8 ms in capture and 46,750.7 ms checking coverage, then rejected with `Open boundary column is obstructed`. No coupled field or thermal initialization had begun. Earlier repeated `air status` output described the closed manual diagnostic instead of the selected automatic trial.

The previous scan completed each proof column through the world ceiling before checking the next column. A nearby obstruction late in the column order could therefore be discovered only after many clear, tall columns. An allocated (`OWNED`) area was not necessarily an admitted or running field, and a rejected automatic proposal had no boundary recovery.

## Implemented behavior

- Coverage checks now interleave columns, taking one sample from each unfinished column per pass. They retain the complete proof, total evidence admission limit, shared capture allowance/deadline, revision checks and guarded reuse. This changes rejection order, not admission requirements. The tall-world regression finds a low obstruction within 21 coverage reads regardless of the 384-block ceiling.
- Insufficient bottom, partial-air and obstructed-column coverage carries a structured cut position/axis/side. Obstructed-column messages include the actual obstruction position as well as the originating cut and face. Pending view text reports geometry capture, checked/required coverage cells and completed columns, initial-temperature input, thermal preparation or layout handoff.
- At the next automatic planning refresh, a rejected cut can request one adjacent full 16-block tile. New boundaries undergo ordinary capture and complete coverage validation. Recovery may take multiple attempts, and every attempt adds an actual tile; it cannot invent outdoor air or skip an invalid boundary.
- Expansions remain associated with their original demand footprint. A changed footprint or explicit planner policy drops that association. Overlapping/face-adjacent expanded requests merge into one calculation group rather than inventing independent exterior seams. Sparse layouts remain sparse.
- Growth respects planner component/total tile limits, world height and coordinate limits, the 32,768-cell per-domain cap, runtime owner/total-cell limits and retained/replacement buffer headroom. A new tile requires a non-loading loaded-chunk lookup. Blocked growth retains a specific explanation and does not repeatedly allocate the same rejected replacement. This also guards unsupported/non-expandable target failures. World edits permit retry; a previously missing recovery chunk is checked again on planning refresh. Explicit `air auto off` followed by re-enabling the runtime clears retained failure state; toggling only the viewer does not restart preparation.
- A rejected initial owner cannot provide a valid transfer seed. It is released when a larger replacement fits; its latest valid thermal checkpoint, if any, is saved first. Healthy donors continue running while ordinary retained transfers prepare a recovered replacement. A failed/over-budget replacement does not withdraw a healthy donor's current reading.
- `air status` now reports automatic runtime when the operator selected the automatic view, or when no view source is selected, automatic runtime exists and no manual diagnostic is running. An explicit manual view continues to select manual status, including when its manual field is closed. `air auto` remains an explicit automatic status command. Allocation feedback clarifies that `OWNED` means allocated; preparation/run and progress establish readiness.

There are no new setup commands or payload protocol changes. Restart the code and use the same opt-in trial:

```text
/meteodyne heat air plan 4 4 32 8
/meteodyne heat air auto 2 65536 1000000 4
/meteodyne heat air view auto 40
```

## Verification and limits

Focused local-air unit checks and the standalone Meteodyne GameTests passed. A real loaded-world fixture places a slab across the initial tile cut, checks initial absence of trial readings, recovers to one 8,192-cell owner, then checks the same viewer and held thermometer against the public automatic query. Pure integration tests cover successful initial recovery, unloaded recovery chunks, rejected replacement recovery from a healthy donor, and runtime headroom exhaustion without repeated allocation. Coverage/planner tests retain rejection, proof, epoch and exact read-count assertions and add obstruction location/order, policy changes, tile/height/total/domain limits and merged requests.

The final required `compileJava compileTestJava test runGameTestServer` run passed in **4m 55s**, exit 0 (`build/air-coverage-final.log`). It ran **1,597 unit cases**, with zero failures/errors and the same two optional benchmark skips, including six architecture checks. All **408 aggregate**, **358 coreless** and **110 standalone Meteodyne** GameTests passed, along with every other standalone mod run (Empire 124, Frontier 17, lib 3, Sable Fixes 2, Seismic 20, Spoilage 34, and Sparse Ores 13 both bare and with Create). The real recovery fixture verifies both automatic and explicit manual status selection. An additional pure integration regression verifies a non-expandable rejected target stops allocating and can retry after a world edit. IDE compilation and whitespace checks also passed.

The original client scene has not been rerun with this code. This is bounded, rejection-driven expansion, not a guarantee that any building or terrain fits the experimental caps. Clear first-time sky evidence still requires all its samples; tall-world successful startup can remain slow. Sustained movement, multiplayer capacity and broader performance work remain unqualified. Physics, solver tolerances, production consumers and default activation are unchanged.
