<!-- Written by ChatGPT. -->
# Automatic thermal initialization and persistence

This checkpoint adds thermal initialization and versioned chunk storage to the **opt-in automatic runtime**. Existing `air plan` / `air auto` trial commands select it without another switch. Fixed/manual diagnostic domains keep their uniform cold-start control. Production instruments, default activation, the old heat computation and its stored offsets remain unchanged.

## Initialization contract

The automatic preparation pipeline reads initial values on the server thread, one represented cell per existing capture work unit, under the same soft deadline. Workers receive immutable arrays and never read chunks, block entities or mutable legacy grids. Missing geometry and revision changes retain existing rejection and stale-completion guards.

Priority is:

1. Live donor overlap during resize/merge/split, preserving each independent air pocket, solid and fluid temperature. Incoming cells use the remaining policies. Incoming thermal energy is explicitly included in the transaction ledger.
2. Compatible coupled chunk state, checked against exact solid/fluid occupancy boxes and conductance/capacity values, plus the number and order of thermal states.
3. An old-format saved scalar temperature offset, as a migration estimate for all current phases in that block. The old format cannot reconstruct distinctions it never recorded.
4. A captured immutable legacy thermal publication, including the existing thermal-only natural-source settling result.
5. Ambient, when no estimate or saved value exists and there is no uncovered active natural source requiring an estimate.

A missing estimate at an active static natural source withholds initialization. The sliced scan retries against a fresh immutable set of legacy publications. During automatic operation, loaded natural-source discovery also includes represented committed and replacement areas, so a target extending beyond player reach can obtain its thermal estimate before committing. This does not activate natural heat throughout the world or load chunks. The legacy settling job continues to use its existing workers and budget. It is **not** 1,500 seconds of coupled momentum.

Initialization starts a new accepted thermal-energy baseline and performs a fresh pressure projection. Historical source energy, elapsed thermal time, pressure scratch and face velocities are not loaded from disk. Subsequent reconciliation uses ordinary bounded physical intervals and the existing response-scale history. Live layout/topology transfers keep their existing source-history contracts; disk restoration does not duplicate those ledgers.

Automatic status rows expose `persistentThermal`, `restoredCells` and `estimatedCells`. `estimatedCells` counts nonzero scalar estimates; `restoredCells` counts compatible exact phase restores. These are initialization provenance counts, not a claim that an estimated field is at coupled equilibrium.

## Chunk format and retention

The existing `meteodyne:heat` attachment now has an optional `local_air` compound with explicit **version 1**. Each represented cell stores its position, accepted game tick, exact phase/material signature, and separate double-precision offsets for every air pocket followed by solid and fluid states. Offsets use the field's exterior ambient reference, retaining the established room-offset save convention. Explicit zero records overwrite old warmth and prevent an older estimate from reheating a cooled cell. An older checkpoint cannot replace a newer saved cell.

Legacy `boxes`, `pos` and `offset` tags retain their format and meaning. Legacy cooling/box cleanup cannot erase the independent coupled field. Old saves load without a version conversion that discards their wall/room offsets. Unknown versions, malformed phase counts and non-finite offsets are preserved and explicitly rejected; automatic initialization cannot silently overwrite them.

Natural-only state remains transient. A domain with placed heat or driven candidates is persistent; a domain inheriting saved persistent warmth or a persistent live donor retains that policy through cooling and transfers. Sensor/player demand alone does not persist otherwise natural-only warmth.

Accepted immutable checkpoints are saved on the existing 600-tick persistence cadence, explicit automatic shutdown/server shutdown, ordinary server-thread chunk unloading, and donor retirement at a committed layout handoff. The last accepted checkpoint can survive later invalidation for storage, but cannot become a current airflow query. Exact cell identity protects re-entry into changed geometry. Unsupported general topology edits can restore the last checkpoint rather than provide exact edit-time continuity; that remains a separate gate.

Removed cells remain in their chunk attachment. On later coverage growth or cold re-entry they are eligible for restoration; live donor overlap still takes precedence. Inactive isolated owners save and release their solver allocation after the demand registry's retention expires. No background simulation or elapsed-time cooling is invented for absent coverage: saved offsets return as retained offsets, consistent with the existing room-save policy.

Read work shares the capture allowance. Numerical buffers still obey the automatic cell/node/face limits, and chunk records have an explicit 131,072-cell format limit. Saves and serialization are synchronous bounded checkpoints, following the legacy save cadence; their worst-case main-thread cost and long-lived loaded-chunk storage still need representative overload measurements. `local_air_persist_ms` records periodic automatic checkpoint writing within the existing benchmark. No performance claim follows from the correctness tests.

## Natural initialization comparison and decision gate

**Follow-up:** Bright requested a fix for this gap. The [refined initialization checkpoint](local-air-initialization-refinement-results.md) now replaces the final legacy estimate for fresh natural coverage with a qualified estimate from the coupled model. The measurements below record the previous checkpoint.

The new deterministic small-house fixture uses the same full-block geometry, total power, limit, and five adjacent source targets in both solvers. Legacy runs its existing **1,500-second thermal-only settle**; the offline coupled control runs at **0.5-second intervals to 6,000 seconds**, accepting every projection and thermal step. Its final 100-second maximum thermal drift is below the existing 0.001 C criterion.

| Measurement | Temperature |
| --- | ---: |
| Initial room mean from the legacy estimate | 21.195399 C |
| Offline coupled final room mean | 25.782901 C |
| Coupled minus estimate, room mean | 4.587502 C |
| Maximum room-cell difference | 5.619503 C |

This is one characterization fixture, not a new calibration target or a bound for all caves. It establishes a real initialization gap. The plan requires bringing this back to Bright before treating the estimator as production-qualified. The concrete choices are to retain this approximate hot arrival and accept bounded reconciliation, add prepared/cached coupled thermal state for closer arrival temperatures (with measured preparation/memory costs), or develop a different thermal initialization approximation. No coefficients, source limits, convergence tolerances or response scales were changed to hide the difference. The experimental runtime remains opt-in.

The real-world integration fixture separately captures lava, observes a missing estimate as pending, invokes the actual legacy natural settling path, and verifies the initial coupled probe matches that legacy offset (77.761856 C above its reference in this sealed fixture) without elapsed momentum. It then adds a placed campfire, saves a mixed room through the actual attachment/NBT format, clears the legacy regions, and restores every represented thermal phase with a fresh projection. This small sealed fixture is a lifecycle check, not the steady-house accuracy control above.

## Verification

The focused tests cover independent partial-cell air pockets/solid/fluid restoration, material/phase mismatch, new ambient offset references, owner-thread sliced reads, pending natural estimates, stale in-flight completion rejection, saved outgoing coverage and re-entry, retained live overlap, energy balance, source-history reset and inactive-owner release. GameTests cover legacy migration, exact NBT round trips, zeros/newer checkpoints, invalid/future format retention, real lava initialization, transient natural policy, mixed policy, and actual automatic shutdown storage. Persistent fixture attachments are restored during cleanup.

Verification on 2026-10-27: IDE builds and focused checks passed. The final full-family command `gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain` passed in **5 minutes** (`build/air-thermal-full.log`): **406 aggregate, 356 coreless, 108 standalone Meteodyne GameTests**, plus every other standalone run. Unit reports contain **1,556 tests**, zero failures/errors and the two existing optional benchmark skips; all six architecture checks passed. No existing tests were removed, weakened or newly skipped. The final run includes independent legacy/coupled cleanup, replacement-area natural discovery, exact phase-count validation, and isolated persistent fixture attachments.

## Remaining gates

Refined initializer latency/scene qualification (see the follow-up above); general topology/state continuity; persistence and checkpoint-cost qualification under chunk churn and multiplayer load; production consumer migration and wind-instrument conventions; workload/default sizing and eventual default activation. No client visual or multiplayer-performance validation was performed here.
