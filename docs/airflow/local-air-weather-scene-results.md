<!-- Written by ChatGPT. -->
# Paced live-weather building trial

Updated 2026-10-21. A 1,008-cell open building now has an isolated, opt-in weather trial using the real climate/wind adapter and an explicit 50 ms tick deadline. The trial checks retained heat, source/door history and projection while measuring field availability and worker cost. It leaves production readers and numerical settings unchanged.

## Full-check repair

The two missing aggregate recipe entries were `domum_ornamentum:beige_bricks` and `domum_ornamentum:beige_stone_bricks`. Current build dependencies, metadata expectations, compatibility removals and the Empires plan already intentionally remove MineColonies and its libraries, including Domum. Only those two stale recorded recipe lines were removed from `PackRecipesGameTests`; its exact set comparison and every remaining expectation are unchanged. No replacement recipes were introduced.

The subsequent full compile/unit/aggregate/standalone check exited 0. Aggregate passed 262/262, coreless 212/212 and Meteodyne 97/97. These current counts reflect the shared checkout after the colony compatibility removal; older reports retain their original historical counts.

## Fixture and measurement scope

Run the isolated fixture with:

```text
gradlew :meteodyne:runGameTestServer -Pmeteodyne.airWeatherScene=true --no-daemon --no-parallel --console=plain
```

The generated fixture uses `meteodyne_air_scene`'s copied empty structure. Ordinary fixtures do not run concurrently in that namespace, and the copied structure stays excluded from release jars. Both opt-in scene flags default off.

The 12 by 7 by 12 domain sits high in loaded air, with a floor, north/south walls, an interior partition and a two-block vanilla door. Its roof and east/west boundaries are open. A campfire warms one side. The coupled runtime uses the existing two-step worker cap and normal eight-unit capture allowance. Surface wind is controlled through the existing debug/test provider override. Day time is pinned to dawn or afternoon to make the existing climate provider deliver repeatable cold/warm inputs. Game time and weather sampling cadence remain chronological; these forced day-time jumps are a test stimulus rather than a simulation of a naturally advancing weather front. Wind/day time and block states are restored on cleanup.

The trace records every tick, including unavailable fields. Available rows retain their represented tick, weather/source revisions, topology transfers, sample temperature/velocity and cumulative energy ledger. Each phase snapshot includes capture counters and worker timings. The existing heat benchmark records game-thread heat-pipeline and worker wall/CPU/queue samples. Provider sample/admission time is recorded separately from geometry snapshot work. These measurements overlap where the benchmark documents it; they must not be added into a whole-server cost. Client FPS and production MSPT are not measured.

## Pilot finding

The first five-phase run passed in 1,461 game ticks, with 73.12 seconds in the heat capture. It retained all four topology transfers and all three source revisions. Warm afternoon exterior temperature rose by 12 C; the campfire probe reached 18.01 C, then cooled to -13.72 C after the cold reversal/source-off phase. Maximum cumulative absolute ledger error was below 2.49e-7 and divergence below 2.08e-8.

Coupled step packets averaged 2.35 ms wall time (p95 4.76 ms, maximum 15.48 ms). All 108 local-air worker packets together used about 345.66 ms of measured wall time across the capture. Windows CPU samples are coarse (15.625 ms increments), so these short jobs do not establish precise per-job CPU rates.

Opening and closing first-field delays were 161 and 162 ticks (8.05 and 8.10 s). Each transition resampled only two cells and reused 1,006, but still charged 1,240 sky-coverage samples plus the two changed domain cells. At eight units per tick, coverage alone requires 155 ticks. The observed delay is consistent with that quota plus planning/bake/publication overhead; aggregate coverage cost, rather than a slow coupled step, explains most of the waiting. Earlier closed-room trials had no open-sky evidence work.

The frequent-gust/door/source phase produced only 27 available samples in 365 ticks. That combined phase does not isolate gust cost: its first 29 ticks before either door edit had 26 available samples. A longer fixed-topology gust control is included in the final trial to separate weather publication from coverage reconstruction.

## Final controlled run

The six-phase follow-up also passed, including explicit west/east ventilation checks and a sustained gust-only control after the door/source sequence. It ran for 1,671 game ticks, with 83.60 seconds in the heat capture (about 20 observed ticks per second). The recorded origin and complete trace are in `control/`. Phase availability counts are observed callback samples; the initial callback adds one sample beyond phase zero's elapsed-tick count.

| Phase | First field, ticks | Available samples | Maximum published age, ticks | Exterior C | Campfire probe C |
| --- | ---: | ---: | ---: | ---: | ---: |
| Cold start, 0.5 wind | 283 | 201 / 484 | 10 | -13.50 | 12.70 |
| Warm 2.0 gust + open door | 160 | 51 / 210 | 143 | -1.50 | 19.23 |
| Cold -2.0 reversal + close/source off | 161 | 41 / 201 | 144 | -13.50 | -12.51 |
| Gusts + two door/source changes | 1 | 27 / 365 | 9 | -13.50 | -13.04 |
| Calm, fixed geometry | 2 | 201 / 202 | 10 | -13.50 | -13.02 |
| Gust every 10 ticks, fixed geometry | 1 | 189 / 210 | 9 | -13.50 | -13.20 |

The combined edit phase's first field arrives before its scheduled door edits; that number is not a door-rebuild delay. Its longest unavailable sequence was 335 samples (16.75 s). Published-age maxima exclude unavailable samples and must not be treated as uninterrupted freshness. The final gust-only phase held topology/source revisions fixed and did no additional geometry or coverage work: availability was 90%, versus 99.5% in the preceding calm phase. Its 21 missing samples matched 21 weather revisions; the immutable worker update/publication introduces approximately one absent tick per sampled gust in this fixture. This is an observation, not a latency guarantee under other loads.

The four door transitions finish with only nine additional domain samples and 4,023 reused cells, but the preparation count rises by 4,960 coverage units (1,240 per transition). This quantifies the open-sky proof bottleneck independently of the gust-only control.

West-opening velocity during the warm gust was +1.84 blocks/s; east-opening velocity during the cold reversal was -1.72 blocks/s. All four topology transfers and all three source revisions were retained. Maximum absolute cumulative ledger error across final phase snapshots was below 2.98e-7 and divergence below 2.09e-8.

Completed coupled step packets averaged 2.11 ms wall time (p95 5.16 ms, maximum 15.30 ms). All 129 completed local-air packets, including planning/baking/initialization, totalled 340.91 ms wall time during the 83.60 s capture. Heat-pipeline game-thread measurements averaged 0.054 ms/tick (p95 0.120 ms); local-air snapshot advancement averaged 0.0086 ms/tick. These nested measurements are not total server MSPT. The harness's largest inter-callback interval was 234 ms, so the explicit pacing does not imply every callback met a strict 50 ms interval. Recorded CPU values are too quantized for precise short-packet comparisons.
The full check passed again after the initial fixture was introduced. After the final trial-only control/probe extension, the IDE build, six-phase trial, 98 local-air/mapper unit tests and ordinary standalone Meteodyne 97/97 all passed. See the evidence for exact invocation scope.

## Next gate

The [coverage-proof reuse follow-up](local-air-coverage-reuse-results.md) implements guarded column evidence and reduces the repeated opening/closing field delay to six ticks (0.30 s). The measurements above remain the original before evidence. That follow-up retains proofs across supported interior edits and qualifies roof/context/chunk/material invalidation. Freshness and unavailable-state behavior still need an explicit consumer contract. The current diagnostic exposes age and availability; this trial does not select production reading semantics, radii, memory defaults or a new solver tolerance.

[Evidence and final verification](../benchmarks/air-weather-paced-2026-10-21/README.md) include the phase snapshots and full per-tick trace.
