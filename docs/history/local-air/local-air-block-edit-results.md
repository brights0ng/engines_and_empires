<!-- Written by ChatGPT. -->
# Local air: retained block-edit handoffs

Status: implemented and verified 2026-10-27.

## Behavior

Known server-thread static block edits now use the chronological topology handoff in both the manual diagnostic and automatic local-air owners. This extends the earlier vanilla door OPEN support to trapdoors, partial-block state changes, ordinary placement/removal, material replacement and fluid occupancy changes. Relevant edits withdraw the incompatible reading immediately. The replacement must pass geometry capture, exterior coverage admission and a fresh pressure projection before it becomes readable.

Unchanged air transfers by geometric overlap. It never inherits the temperature of a removed wall or fluid. Existing solid and fluid storage retain their own temperatures when their ownership and material coefficients remain compatible. A changed block type retires the old solid phase; a changed fluid block type retires the old fluid phase. The two decisions are independent, including for waterlogged/partial cells. Same-block rotations and fluid-level changes can retain compatible storage up to the smaller old/new capacity.

Replacement and newly represented capacity enter at the current ambient temperature. Removed capacity leaves with its old temperature. Both terms enter the existing geometry-energy ledger; they are not source heat or absent-time evolution. Unaffected room warmth, the original energy baseline, cumulative source/exterior energy, elapsed thermal time and accepted steps survive. Introducing a placed source also enables the existing persistent thermal policy for the retained owner.

## History and ownership

The existing single local-air worker lane and capture/worker allowances are unchanged. Workers receive immutable snapshots and copied replacement-cell sets, with no world access. Intermediate topologies are frozen and replayed at their actual edit ticks. Source, weather and response-scale events keep their chronological ordering.

Same-tick edits coalesce into one topology while accumulating every lost phase ownership. Removing a hot block and putting an equivalent block back in the same tick cannot resurrect the removed block's heat, even when the final geometry and coefficients match the original. In-flight work from a superseded stage is collected and charged but cannot publish.

Snapshot reuse follows the existing recorded read dependencies. Coverage-proof reuse is conditional on the same evidence; a new boundary opening is not automatically outdoor air. A roofed cut or unsupported shape remains rejected. External dependency edits can rebuild affected geometry/coverage without resetting unrelated thermal state when admission succeeds.

History/event/lag limits still fail explicitly rather than dropping edits. Chunk changes, unknown/off-thread writes, material-table/context reloads, edits received before an owner has usable retained state, and edits involving registered block-entity driver candidates keep their conservative restart path. Driver membership changes require a separate chronological candidate handoff; this checkpoint does not claim that work.

Automatic layout transactions still cancel on relevant block edits before the committed owners process their own edit histories. There is no partial layout commit and no additional solver owner.

Completed historical topologies can seal as layout donors and resume afterward. The previous preparation checks excluded every owner with a historical-stage object, including owners whose capture and thermal handoff had already finished. The extended automatic integration test exposed that restriction during growth after a block edit. Layout readiness now depends on a single prepared stage and a ready run; uncompleted histories still cannot act as donors.

## Verification

Focused pure/runtime tests passed. Tests cover independent solid/fluid replacement, exact unaffected temperatures at the edit tick, coefficient incompatibility, same-tick removal/replacement, a worker outstanding at an edit, and roofed-boundary rejection. Serial and delayed histories containing block/source/fluid/weather/response changes match across worker caps of 1, 2 and 64, including every final thermal state and face flux.

The real-world checks exercise trapdoor OPEN and full material replacement through the manual world hook, and slab placement/material replacement through an automatic owner. They require retained lifetime accounting, immediate field withdrawal, fresh projection and the existing energy tolerances.

The final required command `gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain` passed in **5m 1s** (`build/air-block-edit-verified.log`). Results: **407 aggregate, 357 coreless, 109 standalone Meteodyne GameTests**, with every other standalone run passing. Unit reports contain **1,569 tests**, zero failures/errors and the same two optional benchmark skips; all six architecture checks passed. IDE compilation passed. The initial full run exposed the completed-history/layout exclusion described above; the final run includes its fix and regression. No existing test or production numerical tolerance was weakened.

## Remaining work

Qualify driven-candidate membership changes, checkpoint/chunk churn, representative natural-arrival latency, larger-scene accuracy, shared-budget overload and multiplayer demand. Production consumer integration, instrument decisions and default activation remain separate gates. This checkpoint makes no new client-visual or performance claim; the automatic trial remains opt-in.
