<!-- Written by ChatGPT. -->
# Live driven heat sources

The experimental local-air pipeline now accepts heat sources controlled by registered block-entity drivers. It uses the existing `HeatDrivers` registry and heat-power table. Geometry capture records candidate cells without freezing block-entity power into a historical geometry snapshot. Live power is sampled separately on the server thread, then delivered as immutable chronological source updates to the coupled worker.

This extends [sparse automatic domains](local-air-sparse-results.md). Existing `air auto` and manual coupled diagnostic commands use the new path; no extra activation command is required. Production thermometer/player/instrument routing and activation defaults remain unchanged.

## Source contract

Like the legacy planner, matching driven variants take precedence over the ordinary static variant. `HeatTables.driven` selects the first matching variant for each driver. Each running variant becomes a separate immutable term with its scaled air power, absolute limit, ambient-offset limit and per-face flag. Radiant power remains excluded. Heating/cooling terms are routed independently through the actual cell geometry before the existing solver combines terms per thermal state. Equal block-level powers are not discarded when their face routing differs.

Unknown drivers, missing block entities, invalid driver answers and caught driver failures retain `HeatDrivers`' existing off/clamping policy. The adapter checks that the source chunk is already loaded before asking for its block entity. Driver callbacks and world references never enter worker snapshots. Historical geometry views explicitly reject live block-entity sampling.

The existing thermostat law is preserved: powers are summed per thermal state, then the strictest limit in the net direction is chosen from all terms. **Opposing limits can suppress net heating or cooling**, even when the term imposing that limit has the opposite sign. For example, +20 limited to 100 C and -5 limited to 0 C produce net heating capped at 0 C; at 20 C, delivered source energy is zero. This matches both `AirGrid` and the coupled transport, and now has an explicit regression fixture. Changing that law requires a separate calibration/gameplay decision; this checkpoint does not change it.

## Scheduling, readiness and continuity

Driver sampling shares the existing capture cell allowance and soft deadline. Known candidates receive up to 16 reads before geometry work, with remaining allowance usable afterward. This keeps driver observations progressing during door reconstruction. Each source targets a read every ten game ticks, subject to the shared budget; this is a sample-and-hold observation contract, not continuous detection of every intermediate block-entity transition. An individual opaque driver callback cannot be interrupted by the deadline.

The trial admits at most 256 driven cells per domain and 64 terms per source cell. Driver candidates must be interior to represented coverage, even when currently off. Limits reject explicitly rather than silently dropping sources. Existing bounded source histories, lag limits, total cell reservations and worker accounting remain in force. No extra production executor is introduced. Reports expose driven candidate/read counts alongside geometry capture work.

Initial publication waits until every driven candidate has an observation. A cold start uses those primed values, rather than the power at geometry capture time. Once a run starts, changed observations carry their game tick and response scale. Old in-flight completions cannot publish over a newer source revision. Power-only changes retain geometry, flow and stored air/material warmth; they do not increment the geometry revision or require new exterior proof.

Door handoffs retain the driven source state at the edit barrier in the immutable frame, while later observations queue against the next topology. Layout targets retain their initial forcing at the layout barrier and replay observations received while initialization/projection is held. Donors and replacements still use the existing common-tick ownership/energy transaction. Unknown block-state changes, including changes to driver membership, retain conservative invalidation and recapture. Material/chunk invalidation clears source readiness and discards late work through the existing generation guards.

## Verification

Eight new pure lifecycle fixtures cover:

- A power change while initialization is physically in flight, owner-thread-only sampling, zero allowance/expired deadline, withheld old publication, retained warmth, no geometry recapture and balanced energy.
- Cold initialization from the latest observation rather than capture-time power.
- Opposing terms with different per-face routing, individual limit conversion and the 64-term guard.
- A door barrier retaining its driven value and replaying a later off observation without reading historical block entities.
- Target initialization at a layout barrier, a power change during held projection and source energy counted once across donor/target ownership.
- Explicit failure on unavailable live source data and retry after invalidation.
- The unchanged strictest-net-direction thermostat behavior for opposing limits.
- Rejection of excessive driven candidates before block-entity reads.

The live GameTest uses a real barrel block entity and registered fractional heating/cooling drivers. A source-adapter injection supplies fixture variants without mutating global heat tables. It verifies candidate-only geometry capture, separate radiant/limit conversion, room heating followed by cooling after inventory changes, retained geometry and energy counters, and unchanged pressure/energy acceptance. The fixture runs preparation jobs under its controlled test loop; the existing automatic-domain tests separately cover real shared-executor integration. No production driver is registered by Meteodyne, and release jars exclude the GameTest class.

Verification on 2026-10-27: IDE build and focused unit tests passed (`build/air-forcing-focused.log`). All Meteodyne unit tests and 106 standalone GameTests passed (`build/air-forcing-integration.log`). The complete `gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain` check passed in 4m 23s (`build/air-forcing-full.log`): 404 aggregate, 354 coreless, 106 standalone Meteodyne GameTests and every other standalone run. Unit results contain 1,550 tests, zero failures/errors and the two existing optional benchmark skips; all six architecture checks passed. No existing tests were removed or newly skipped.

## Remaining work

Natural-source initialization, thermal persistence outside represented coverage, production consumer migration, representative overload/multiplayer qualification and default activation remain outstanding. Existing geometry restrictions, including block-entity-dependent collision shapes and out-of-cell material ownership, still apply. The in-world driver fixture is a correctness test; no client visual or multiplayer performance claim is made.
