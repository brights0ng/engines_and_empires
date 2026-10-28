<!-- Written by ChatGPT. -->
# Local-air viewer publication timing

Updated 2026-10-27. Bright requested this fix after a live automatic viewer remained on `PENDING | Updating coupled field` despite successful sky admission.

## Evidence and cause

The live status snapshot reported `PREPARED / READY`, zero restarts and a published field at tick 61780. First publication had already completed after 7,717.5 ms: capture 3,245.9 ms, coverage 1,002.9 ms, initial thermal input 3,154.3 ms, bake 94.1 ms, plan 73.4 ms and coupled initialization 146.9 ms. The simulation had advanced well beyond that first field.

Weather refreshes and viewer sends both used ten-tick clocks. When aligned, the viewer sampled immediately after a weather change had correctly withdrawn the previous publication. A worker could publish the updated field between sends, but the next viewer send again landed on the next weather-refresh gap. The generic pending caption therefore made a running field look stuck.

## Change

`LocalAirView` now queries the existing guarded presentation once per viewer per server tick. A per-session schedule independently paces complete point packets at least ten ticks apart. Missing-field notices do not consume the next full-field slot: the first valid eligible publication is sampled and sent when it becomes available between weather refreshes. This is an owner-thread availability query, without block capture, chunk loading, solver dispatch or full-lattice sampling on every tick.

Status captions update on transitions and retain a ten-tick heartbeat for progress. Losing a renderable field, changing owners or losing compatible history sends empty withdrawal metadata immediately, independently of the full-packet limit. A valid surrounding field can still render when the body thermometer point is occupied. Source selection, caller age limits, client age accounting, grey historical cubes, point limits, protocol version 4 and held-item query behavior remain intact. Re-enabling/changing scale creates a fresh presentation schedule and clears the previous client data as before.

The solver publication contract is unchanged: pending weather/source revisions, invalid geometry, missing owners and expired fields are not promoted into current readings. Automatic planning, sky caching, physics, thermal initialization and production activation are unchanged.

## Verification

A deterministic unit regression uses the real coupled preparation pipeline with weather changes every ten ticks and worker completions delayed two ticks. The former fixed clock has **zero** usable publication opportunities; the new schedule sends all **seven** updated fields. Every refresh still returns a pending query and clears current client arrows. Complete packets remain at least ten ticks apart. Additional schedule checks cover pending heartbeats, invalidation, ownership/history changes, occupied body points, caption-only transitions and invalid time ordering.

A new in-world GameTest starts the actual live-weather diagnostic on an aligned ten-tick boundary, changes its wind provider before each refresh, and polls the production view-update method in the server post-tick phase. It requires at least six actual current packets outside the former fixed send phase and six unavailable weather boundaries, validates packet pacing/captions and thermometer values/timestamps, then edits geometry and requires immediate withdrawal followed by resumed publications. Its temporary event listener, view session, wind override and block changes are cleaned up on every test exit.

The focused local-air unit and all 112 standalone Meteodyne GameTests passed. The final required `compileJava compileTestJava test runGameTestServer` run passed in **4m 58s**, exit 0 (`build/air-view-cadence-full.log`): **1,610 unit cases**, zero failures/errors and the same two optional benchmark skips, including six architecture checks; **410 aggregate**, **360 coreless** and **112 standalone Meteodyne** GameTests; and every other standalone run (Empire 124, Frontier 17, lib 3, Sable Fixes 2, Seismic 20, Spoilage 34, and Sparse Ores 13 both bare and with Create). The live viewer regression passed in all three environments. IDE compilation and tracked/new-file whitespace checks passed. The actual client appearance and arrival-time experience still need Bright's live trial; this fixes publication timing without claiming faster numerical initialization.
