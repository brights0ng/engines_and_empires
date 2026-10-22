<!-- Written by ChatGPT. -->
# Retained-source refresh evidence

See the [source-refresh report](../../airflow/local-air-source-refresh-results.md) for the contract, measurements, and limits.

- `focused-unit-results.json`: 56 focused local-air tests, including six new chronological source/state tests.
- `interfered-scene-summary.log`: the first four-phase attempt timed out during wind coverage after unrelated fixture edits repeatedly invalidated preparation. Its source-off phase had already passed.
- `interfered-wind.json`: that wind phase's partial capture, including repeated preparation attempts and block-state change evidence from other fixtures.
- `phase-0.json` through `phase-3.json`: direct copies of isolated closed-door, open-door, retained source-off, and open-wind captures.
- `phases.csv`: first-field delay, maximum sampled field age, cumulative steps and energy, and numerical quality.
- `isolated-scene-summary.log`: isolated four-phase diagnostic passed 1/1.
- `initial-full-summary.log`: initial full invocation, with the ordinary Meteodyne source-refresh timeout; aggregate/coreless passed.
- `bare-retry-summary.log`: targeted ordinary Meteodyne rerun passed 95/95, before adding a cooperative worker yield to the asynchronous fixture.
- `cross-generation-full-summary.log`: subsequent full invocation exposing the ordinary fixture's comparison across geometry generations, before bounded coherent-generation recovery was added.
- `recovery-limit-full-summary.log`: bounded reheat attempt exhausted its limit under unrelated edits; the final fixture instead checks the appropriate source-refresh or rebuild contract, with long cooling qualification isolated.
- `full-build-summary.log`: final successful compile/unit/all-game-test invocation after correcting the ordinary fixture's scope.

The isolated paced run uses `./gradlew :meteodyne:runGameTestServer -Pmeteodyne.airScene=true --console=plain`; quote the `-P...` argument in PowerShell. Ordinary full verification uses no scene property. The opt-in scene is separate from the regular game tests.

Captures, CSVs, and log extracts are generated measurements collected by ChatGPT. JSON preserves its embedded attribution. Older scene captures remain unchanged in the preceding checkpoint directory.
