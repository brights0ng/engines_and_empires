<!-- Written by ChatGPT. -->
# Live exterior weather diagnostic

Updated 2026-10-21. The opt-in local-air diagnostic now accepts chronological climate and wind snapshots from the existing weather providers. Fixed-weather controls remain available. This checkpoint does not activate airflow for production consumers.

## Controls and sampling contract

Operators can request:

```text
/meteodyne heat air live <min> <max> <cellLimit> <coverageLimit> <steps>
```

Bounds are inclusive block positions. Existing cell/coverage admission, operator permissions, loaded-only geometry reads, shared heat workers and bounded catch-up rules apply. `start` retains its explicit ambient/wind arguments; `status`, `sample` and `stop` work for both modes.

`LIVE_CENTER` samples `Climate.sample` at the domain center and `WindSources.column`'s unsheltered surface vector at that column. Missing wind means calm. It deliberately avoids underground-temperature and shelter feedback: room geometry already belongs to the coupled solver. This is a uniform exterior prototype, without spatial climate variation, altitude-dependent wind or a qualified vertical reference profile.

Sampling occurs on the server thread every ten observed game ticks, with the last sample held until the next one. It does not reconstruct gusts between samples or scale weather time with thermal response speed. Only immutable numeric weather records enter worker jobs. Initial sampling requires an already loaded anchor chunk; unavailable/unsupported weather fails explicitly rather than silently retaining a current-looking frame.

## Retained state and event ordering

Ambient changes replace background/exterior temperatures and rebuild ambient-relative source limits without rebasing stored air, solid or fluid temperatures. Wind changes build a fresh boundary/operator, map the same geometry's retained temperatures and compatible flux, and project again. They do not increment topology-transfer counts or recapture world geometry. Projection failure withholds the field.

Weather edges split physical intervals at their exact captured tick, alongside source edits and response-scale changes. Samples and topology barriers retain revision order even when several events share a tick. A topology initializes under its sealed seed's boundary before applying later weather edges. Each sealed stage records its last admitted weather revision, preventing an event arriving after a door edit from leaking backward across that barrier during delayed reconstruction.

A completion with old weather cannot publish a current field. Its valid physical progress is retained and replay continues through the missing events. Histories are bounded to 256 changed samples and the existing 2,400-tick catch-up window, with a retained pruning anchor. Overflow fails explicitly. Identical samples advance observation time without producing another revision. Existing unsupported geometry/chunk/material changes retain their conservative cold-start behavior.

## Diagnostics and limits

Reports include `forcingMode`, `weatherSampleTick`, cumulative `weatherSampleMs`, `sampledWeather`, and accepted frame `weatherRevision`/`weather`. The latest sampled input and accepted worker input can differ while work is pending; field availability remains revision guarded. Weather sample/admission wall time is separate from geometry snapshot-advance time and its soft deadline. Gust operator allocation/projection is charged to the worker packet. Frequent-gust cost and field availability under representative weather still need a paced measurement; this is not a performance qualification.

All 98 local-air/mapper unit tests pass without failures, errors or skips, including ten new weather tests. They cover open warming/cooling and gust reversal, instantaneous heat retention, ambient-relative source limits, stale completions, same-tick topology ordering, exact serial/delayed equivalence at worker caps 1/2/64, bounded overflow, long sampling histories and unavailable/invalid inputs. A real server fixture verifies the existing climate/wind adapter and a controlled wind-provider reversal without geometry recapture; command coverage exercises both fixed and live branches. Verification results and development failures are recorded with [the evidence](../benchmarks/air-weather-2026-10-21/README.md). The final focused Gradle invocation exited 0, standalone Meteodyne passed 97/97, and coreless passed 212/212. At this checkpoint, aggregate reported two missing recipe-baseline lines and a full invocation was interrupted by a daemon-stop request. The [paced-trial follow-up](local-air-weather-scene-results.md) resolves the stale Domum expectations after the intentional dependency removal, restores the full check to green and records live-weather cost/availability. Source-level and headless checks do not establish client visuals or gameplay calibration.

## Remaining work

Driven heat sources, general topology/resize continuity, spatial/vertical weather profiles, player/instrument consumers and production activation remain separate gates. The next useful measurement is a paced open-building trial under changing weather, recording freshness, ventilation, worker cost and retained room heat together.
