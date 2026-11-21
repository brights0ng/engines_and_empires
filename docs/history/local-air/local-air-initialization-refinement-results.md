<!-- Written by ChatGPT. -->
# Refined natural-source initialization

The opt-in automatic runtime now refines fresh natural-source temperatures with the **new model's own thermal and airflow operators** before publishing its initial field. This implements Bright's requested follow-up to the [4.6 C initialization gap](local-air-thermal-persistence-results.md). Legacy temperature estimates remain useful starting guesses; they are no longer the final initialization for fresh natural coverage.

## Accuracy evidence

The original small-house regression retains its independent 6,000-second coupled control, half-second physical steps, unchanged source routing/limits, and the final 100-second thermal-drift check. It now additionally requires **every room cell** in the initial publication to match that reference within **0.01 C**.

| Measurement | Temperature |
| --- | ---: |
| Previous legacy-estimated room mean | 21.195399 C |
| Refined initial room mean | 25.782902 C |
| Independently time-stepped reference mean | 25.782901 C |
| Previous maximum room-cell difference | 5.619503 C |
| Refined maximum room-cell difference | about 0.000001 C |

Sixteen additional fluid-bearing cases cover heating/cooling and all eight horizontal compass wind directions. Their largest tested difference, over **every air, solid and fluid state**, is **0.005827094 C** against independent 6,000-second coupled controls. The assertion remains below 0.01 C for every state/case. These are numerical agreement measurements with this model, not real-world temperature accuracy claims or universal error bounds.

Some wind/fluid controls retain a small repeating thermal cycle. In one case, the reference itself varies by 0.009127835 C during its final 100 seconds and its endpoints differ by 0.006539698 C. An endpoint comparison therefore cannot establish steady thermal statistics. Those independent controls are qualified using consecutive complete 100-second window means (maximum change below 0.001 C) **and** a maximum local window range below 0.01 C. The initializer is still checked against the reference's actual final per-state temperatures within 0.01 C. The original house separately retains its stricter stationary-reference check.

## Preparation method

`AirThermalInitializer` is a pure single-owner numerical component. It uses the existing `ThermalStencil`, capacities/material interfaces, thermostat power/limit aggregation, `FlowGrid`, and coupled step acceptance. Its private stationary relaxation removes the transient storage term to estimate heat balance; it does not change the running transport equation or any physical coefficients.

Each outer iteration samples eight projected half-second flow updates at its current temperature estimate. Averaging their shared face fluxes preserves mass balance and fixed exterior conditions. Stationary heat relaxation uses that conservative mean field. A damped fixed-point update stabilizes the estimate; damping is confined to preparation and does not change physical flow damping, response scale, source power or gameplay time.

Publication requires one of two explicit numerical qualifications:

- **STATIONARY:** six successive private iterations with small temperature/mean-velocity corrections and converged stationary solves, followed by four ordinary coupled half-second verification steps with at most 0.001 C drift.
- **THERMAL_WINDOW:** when a repeating flow cycle prevents stationary qualification, ordinary coupled verification must produce three successive stable 64-second thermal windows. Consecutive all-state mean changes must be at most 0.001 C and local temperature ranges at most 0.01 C. Every verification step retains the normal pressure, divergence, thermal residual and energy checks.

There are at most 128 outer iterations, 256 stationary sweeps per iteration and 516 ordinary coupled verification steps. Private flow samples and verification are bounded preparation, not a replay of 1,500 seconds of coupled simulation. An unqualified, invalid or work-capped attempt fails explicitly; it cannot publish a claimed equilibrium or bypass the ordinary solver checks. The two qualification names distinguish stationary preparation from thermally bounded cycles.

`relaxationResidual` records the latest stationary linear relaxation's residual before the damped outer update. It is not advertised as the running field's transient thermal residual. `verificationDrift`, qualification, window range and validation-step counts are separate immutable initialization metadata. The actual initialized field receives a fresh pressure projection and the existing normal acceptance contract.

## Scheduling and continuity

Preparation jobs use the existing single local-air worker lane, wall-budget accounting, generation tickets and collection path. A job has a strict allowance of 256 numerical work units (flow updates or stationary sweeps) and an **8 ms soft deadline**. Deadlines are checked between flow/verification steps and every eight stationary sweeps. An individual pressure/coupled solve, setup allocation or stencil assembly remains atomic and can overrun that soft target; this is not a hard server-latency guarantee.

Worker timing is reported separately as `ESTIMATE`, including `local_air_estimate_wall_ms`, queue/CPU timing and job counters in existing benchmark captures. Automatic status rows include `equilibriumIterations` from the last completed job. Workers receive immutable geometry/material/source/temperature inputs and do not access world objects or block entities.

The first refinement job establishes the cold owner's real start tick. Verified static source changes during that preparation now enter the same chronological history as driven updates. Weather/source changes received during work are replayed after initialization, preserving **only real game time** and the selected response-scale history. The bounded weather/source-history lag guard still applies. Replacement targets prepare before their common layout barrier, and existing transaction/revision guards remain in force.

Old work cannot publish after geometry/material/context invalidation or shutdown. While refinement is unfinished, the query remains pending and target layout ownership cannot commit. Partial/private iterates are never a current reading.

## Retention policy

Refinement is restricted to fresh, nonpersistent initial states around active static natural heat. Compatible saved air pockets, solids and fluids remain exact, including explicit zero offsets. Old-format persistent scalar offsets retain their migration meaning. Live donor overlap continues to take precedence during layout handoffs. Initial guesses and compatible saved phases are not overwritten as a shortcut to convergence.

Only natural source terms are used for this ancient-environment estimate. A newly placed campfire or a newly observed block-entity heater is not retroactively heated for centuries. The actual running source set, including driven observations and chronological edits, is installed by the existing run path.

Prepared warmth becomes the initial thermal-energy baseline. Private relaxation and verification do not increment gameplay accepted steps, thermal seconds, source energy or exterior energy. Disk restore and live topology/layout transfers retain their existing contracts. The chunk format/version remains unchanged, and natural-only warmth remains transient. Manual/fixed diagnostic cold starts, production consumers and activation defaults are unchanged.

## Validation and cost

New tests cover strict work/deadline guards, uniform ambient preservation, immutable input/solution geometry identity, heating/cooling across eight winds with separate fluid storage, the original arrival bias, saved/legacy/zero preservation, single worker ownership, stale estimate completions, and source/weather changes received while preparation is physically outstanding. The real lava-room GameTest now requires qualified refinement and a warmer initial reading than its old legacy estimate, then repeats its mixed-room chunk round trip.

One focused in-process run of the original house used 120 outer iterations and 25,184 stationary sweeps across 103 worker jobs: about **0.34 seconds total worker wall time**, with a **7.9 ms maximum job** in that run. This test drains jobs at one fixed game tick; it is not a live startup benchmark. Shared dispatch cadence, capture, other owners and workload budgets add elapsed preparation time. Representative in-world initialization latency and overload costs still need measurement before default activation.

Verification on 2026-10-27: focused tests and IDE builds passed. The final command `gradlew compileJava compileTestJava test runGameTestServer --continue --no-daemon --no-parallel --console=plain` passed in **5m 1s** (`build/air-initializer-full.log`): **406 aggregate, 356 coreless, 108 standalone Meteodyne GameTests**, plus every other standalone run. Unit reports contain **1,562 tests**, zero failures/errors and the same two optional benchmark skips. All six architecture checks passed. No existing test was removed or newly skipped, and no production convergence or energy tolerance was changed. The new wind reference test qualifies complete thermal windows because its independently measured endpoint differences include real cycles; its per-state initializer agreement bound remains 0.01 C.

## Remaining work

Measure live natural-arrival latency and larger/irregular domains, weather changes and overload; qualify the estimator across representative natural scenes and response scales; continue general topology/state continuity and production consumer integration. Numerical agreement on the controlled fixtures does not remove those rollout gates. No client visual or multiplayer performance verification is claimed.
