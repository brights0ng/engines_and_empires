<!-- Written by ChatGPT. -->
# Automatic tile allocation planner

This follows the [demand registry](local-air-demand-registry-results.md), within stage 5 of the [model plan](local-air-model-plan.md). It converts live player/source/instrument requests into bounded, shared tile proposals. It does not yet execute tiled simulation domains. Existing manual coupled diagnostics and production consumers keep their existing paths.

## Operator trial

```text
/meteodyne heat air plan <radius> <margin> <totalTiles> <componentTiles>
/meteodyne heat air plan
/meteodyne heat air plan off
```

For example, `plan 4 4 128 64` requests a four-block radius around each demand cell, adds four blocks of calculation margin, and allows up to 128 proposed tiles in total with at most 64 in any connected group. These are explicit experimental inputs, not selected production defaults or a validated coverage radius. Radius accepts 0..32, margin 1..32, and each tile cap 1..512. All commands require operator permission and the Overworld.

The first command enables continuous planning using that policy; it immediately reconciles demand and subsequently refreshes once every 20 game ticks. Bare `plan` reports the last plan's tick, demand/layout revisions, proposed tile/cell counts, policy, work-limit rejection, and the first 16 groups with their states and missing-chunk counts. `plan off` releases this planner. Manual `/air stop` remains independent. Planning is off until explicitly enabled; its policy is transient and cleared at shutdown. Heat disable clears registry demand, so the next plan is empty; re-enable resumes observation/planning. Held instruments continue to reuse player demand.

## Allocation contract

- The provisional tile edge is 16 blocks at full block resolution. Floor division anchors tiles to world coordinates, including negative coordinates. Requested radius plus calculation margin rounds outward to these stable tiles. World height clips the vertical allocation; it does not prove a valid ground boundary.
- Overlapping requests reuse the same tiles. Face-touching tiles form one calculation group, including tiles requested by different owners. Edge/corner contact alone does not connect groups. Distant requests do not acquire a large rectangular bridge between them; irregular unions stay explicit tile sets.
- Player-containing active groups get first capacity consideration, then other active groups, then retained-only groups. Spatial ordering breaks ties deterministically. Whole groups reserve capacity; they are never cut into independent atmospheres to fit a limit. This is an initial admission order, not a starvation-prevention scheduler.
- Availability uses one non-loading lookup per distinct horizontal chunk column. Missing tiles remain in the connectivity graph. A missing column defers its entire connected group; it cannot create two apparently independent solves across unknown geometry. Chunk arrival/unload is reconsidered each refresh even without a demand revision change.
- Tile layout revision changes when tile membership or group eligibility changes. Owner movement inside an unchanged layout updates demand metadata without changing this revision. Registry grace retains previous allocations across cell/tile crossings, then expires normally. This is planning stability, not domain identity or thermal-state transfer.

`PROPOSED_UNVERIFIED` means spatial capacity and loaded-chunk checks passed. It is not a ready airflow field. Other group states are `MISSING_CHUNKS`, `COMPONENT_LIMIT`, and `TOTAL_LIMIT`. Geometry, bottom sealing, partial boundary cuts, sky/exterior evidence, and numerical/memory admission still require the subsequent coverage/execution pipeline. A source anchor can be solid; the calculation margin includes neighboring air without making the source block an air-reading location.

Requests outside build height or whose margins overflow integer coordinates are reported separately as unsupported, with their owner/cell and reason. They do not block unrelated valid groups. The command shows up to four unsupported requests and four missing-chunk coordinates per displayed group.

## Bounded work and evidence

Hard preview guards allow at most 4,096 input requests, 4,096 distinct candidate tiles, and 65,536 tile visits per refresh. Exceeding a work guard rejects the entire layout before chunk-availability lookups; no truncated request list becomes an apparently complete plan. Whole-group capacity decisions then apply the operator's smaller tile caps. Cell counts include all tile voxels, clipped to build height; they are not measured fluid-node counts or memory estimates. The hard guards bound work/storage, not a two-millisecond deadline. Tick planning is measured as `local_air_planning_ms` inside `thermal_tick_ms`, alongside the separately nested `local_air_demand_ms`.

Nine unit tests cover overlap, touching/distant/diagonal groups, negative coordinates, calculation margins, build height, immutable results, movement and grace expiry, missing-column deferral/arrival, whole-group limits and priorities, deterministic ordering, work guards, overflow and owner-thread access. A GameTest exercises the actual enable/inspect/off commands, automatic refresh, and absence of solver activation.

Verification on 2026-10-26: IDE build passed. The focused Meteodyne unit/GameTest run passed, followed by the full check. After the unsupported-request refinement, `gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain` passed again in 3m 14s (`build/air-planner-final.log`): 401 aggregate, 351 coreless, and 103 standalone Meteodyne GameTests, plus every other standalone run. All nine planner unit tests and six architecture checks passed. The final GameTest also verifies that an actual placed campfire reaches the plan through the live world registry. This checkpoint does not establish live server cost, multiplayer capacity, or visual behavior.

## Next gate

Follow-up: the [shared tile interface checkpoint](local-air-tile-interface-results.md) now proves joint pressure and heat transport for complete rectangular groups. Irregular/partial-height groups and live coverage admission are still unsupported by that assembler.

The [automatic runtime checkpoint](local-air-automatic-runtime-results.md) now drives compatible groups through live capture/coverage admission and shared-budget simulation. Unsupported unions and layout transfers remain explicitly deferred.

Prove two touching tiles use a single pressure operator and shared thermal flux, comparing the seam against one equivalent dense domain. Then wire these proposals through bounded geometry/coverage admission and retained domain ownership. Grow or reject coverage explicitly at interior cuts; preserve warmth through resize/merge/split and deferred admission. Select production radii, actual memory/work budgets and scheduling priorities from multi-demand measurements before enabling automatic solver execution or moving production consumers.
