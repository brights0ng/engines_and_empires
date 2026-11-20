<!-- Written by ChatGPT. -->
# Automatic local-air viewer and held thermometer trial

Status: implemented and verified 2026-10-27.

The [coverage recovery follow-up](local-air-coverage-recovery-results.md) adds explicit preparation progress, bounded rejected-cut expansion and automatic-aware `air status` routing. It addresses the subsequent live obstruction report without changing this view payload protocol.

Bright selected this functional milestone after deferring further performance work. The existing operator viewer and held thermometer trial can now follow automatic domains. Manual bounded diagnostics remain independently selectable. Production player climate, placed sensors, wind gauges and redstone behavior are unchanged.

## Use

Restart both dev client/server code for the version 4 optional view payload. Configure the existing automatic trial, then select its view explicitly. For example, using the earlier experimental limits:

```text
/meteodyne heat air plan 4 4 32 8
/meteodyne heat air auto 2 65536 1000000 4
/meteodyne heat air view auto 40
```

These remain experimental admission limits, not selected production defaults. Automatic domains require loaded geometry, valid coverage and available cell/worker capacity. `air auto` reports admission/owner reasons when an area cannot become current. The viewer does not turn incomplete coverage into outdoor weather.

Hold a thermometer in the hotbar or offhand. Its reading uses automatic air at player position plus 0.9 Y, with the actual simulation-through timestamp. The action bar identifies **Automatic** or **Manual** mode and shows current temperature/velocity or the pending/stale/unavailable reason. Only a current reading populates the trial instrument component.

The automatic view samples the committed domain containing that body point. Walking into another owner switches the displayed field. A replacement target remains hidden until atomic layout commit. Leaving all admitted coverage clears previous-area markers, including when a replacement is still preparing. An occupied body point reports no thermometer reading; a valid surrounding field can still be visualized.

`/meteodyne heat air view scale <1..32>` changes display spacing only. Automatic lattice positions are aligned to world coordinates, including negative coordinates and sparse domains, so resizing does not shift the sample grid. The view still shows all eligible centers of the selected domain, not all domains in the world, and remains bounded to 32,768 points per packet.

The existing `/meteodyne heat air view 40` selects the manual diagnostic source, with its original domain-relative lattice. There is no silent fallback between sources. Re-enabling/switching sources retains the player's chosen spacing and clears old view/item data. `view off` clears the trial and restores ordinary thermometer sampling.

Enabling a view alone does not enable automatic runtime or allocate a domain. Held instruments reuse existing player demand. `air auto off` leaves an active automatic view explicitly waiting with no reading; restarting runtime can make that same session current again. View sessions remain transient and clear on logout, dimension change, respawn/death, permission loss and server stop.

## Consistent readings and graphics

A shared owner-thread presentation selection pairs the point reading with its matching immutable field under one revision check. Queries do not read blocks, load chunks, dispatch work or wait for workers. Automatic selection exposes committed wanted owners, not preparation targets or retained inactive coverage. Each packet samples one selected field directly.

The payload now carries capture/domain identity, a history-retention flag, the field's age at send and the caller-selected age limit. The existing payload ID is preserved; protocol version 4 requires matching code. Codec count bounds and the less-than-1-MiB maximum packet check remain.

The client counts age already spent on the server plus elapsed client ticks. Receiving an old field cannot refresh its freshness. Expired or unavailable data has no arrows. During a temporary refresh of the same owner, prior cubes may remain grey as historical positions. A new owner, uncovered area, explicit clear, source change or level change removes incompatible cached markers. Full marker packets remain at least ten ticks apart. The [publication timing follow-up](local-air-view-publication-results.md) checks guarded availability each tick, retries between aligned weather refreshes and sends empty withdrawal metadata immediately when invalid coverage is observed. The item query withdraws invalid data on its next inventory tick.

Rendering retains the existing relative-temperature palette, cube style, downstream three-dimensional arrows and length cap. Thermometer materials, dial, no-reequip behavior, item IDs and normal sampling are unchanged. Radiant warmth and wind chill are not added to the air temperature. This trial does not settle the separate wind-gauge/redstone decisions.

## Lifecycle and validation

Pure tests cover shared reading/frame ownership, hidden replacement targets, stale-frame exclusion, crossing between owners, sparse holes, stable world-aligned sampling, historical cache clearing and client freshness. A chunk-loss/reload fixture withdraws the field, then restores saved warmth without replaying old source energy; creating a new runtime changes the visual owner identity.

The real automatic world fixture moves an operator viewer between separately sourced owners, outside coverage and into pending growth. It checks held item values/timestamps against the public automatic query, samples only the selected owner, preserves manual source isolation, withdraws readings on block edits and coverage shrinkage, and restores a readable warm field after automatic shutdown and a real chunk-attachment NBT round trip. It also verifies explicit opt-out clears trial item state. The codec GameTest checks the added ownership/age fields and retains maximum packet and oversize rejection assertions.

The fixture's viewer moves between source-demanded areas; this is a consumer/lifecycle check, not a sustained running-player admission or multiplayer performance qualification. Asynchronous current-reading assertions wait for a current publication; separate pending/unavailable checks require empty item readings and no current marker data.

The final required command `gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain` passed in **5m 10s** (`build/air-auto-view-final.log`): **407 aggregate, 357 coreless and 109 standalone Meteodyne GameTests**, with every other standalone run passing. Unit reports contain **1,584 tests**, zero failures/errors and the same two optional benchmark skips; all six architecture checks passed. An earlier run hit the existing underground-humidity fixture's outside-versus-covered assertion; it passed unchanged in the final full rerun. No existing assertion or numerical tolerance was weakened. Source compilation and data/cache/codec tests cannot verify actual client rendering, visual readability or real-time movement feel. Those require the updated client trial.

## Next boundaries

Player climate and placed temperature consumers remain on their established paths. Ship ventilation/forces and wind-gauge/redstone integration remain separate milestones. Automatic coverage admission, driven-candidate membership changes and the previously recorded performance/overload gates are unchanged.
