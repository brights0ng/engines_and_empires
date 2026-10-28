<!-- Written by Claude (2026-10-26, revised 2026-10-27) from Bright's decisions in this session. -->
# Empire: Civilian sleep

**Date:** 2026-10-27 · **Status:** planned; implementation by Claude Code (prompts S1–S5), branch `civilian-sleep` off `main`.
**Scope:** civilians sleep in beds like players and villagers, sleep rough by the bonfire when they have no bed, and record how well they slept. This is the data source for the fulfilment **Sleep** meter (`claude/civilian-fulfilment.md`); the meter itself is wired with fulfilment, not here.

## Decisions (Bright, 2026-10-26/27)

- **D1 Beds are auto-claimed** until proper housing exists. A citizen claims the nearest unclaimed bed on its empire's land and keeps it. Any bed on the empire's own land counts, including beds members placed. Players get vanilla's "This bed is occupied"; there is no kick-out.
- **D2 No bed = 50% credit.** Civilians cannot sleep longer to make up for it: a rough-only night tops out at 0.5.
- **D3 Sleep need varies with Endurance.** Average 6 min (7,200 ticks). Raw Endurance 3 → 8 min, 10 → 6 min, 18 → 5 min, linear between those points.
- **D4 Sleep is centred on the night.** Each civilian goes to bed so that the midpoint of its sleep falls on the midpoint of the night. Short sleepers go to bed later and wake earlier; nobody wakes minutes before sunrise just because of high Endurance.
- **D5 No bed = sleep lying down next to the bonfire.**
- **D6 Night skipped** (players sleeping) while a civilian isn't lying down yet, during the night and not rested:
  1. It teleports to its own bed, if valid.
  2. Otherwise it claims a free bed nearby and teleports to it.
  3. Otherwise it teleports next to the bonfire and gets rough sleep.

  It is then credited for the skipped time at that rate. Civilians already lying down stay where they are.
- **D7 Unloaded nights.** A night the civilian was loaded for but never slept counts as 0. A night spent entirely unloaded counts as slept: 1.0 with a claimed bed, 0.5 without.
- Previously confirmed: only citizens claim beds, within 48 blocks of home. An occupied, unreachable (600 ticks) or missing bed means rough sleep for the rest of that night. Damage wakes a sleeper, who may lie down again after 200 ticks. A right-click wakes it with a grumble line. Forward time jumps that stay within the night are credited without a teleport. Fulfilment uses one provider per meter with a default of 0.5.

## Claude's readings (Bright to check)

| # | Reading |
|---|---|
| R1 | The night = vanilla's clear-weather bed times, day time **12542 → 23460** (10,918 ticks ≈ 9.1 min; Bright said ~8.5). Midpoint 18001. Named constants. |
| R3 | D7 extends to *partly* unloaded nights: unloaded time inside the civilian's own sleep period counts as sleep at the claimed-bed / rough rate. |
| R4 | The D6 teleport fires when a forward jump carries a loaded civilian past the end of the night, even if its own bedtime hadn't come yet; the skipped part of its sleep period is credited. `/time add` within the night credits without moving anyone. |
| R5 | The rough spot is a standable block 1–3 blocks outside the bonfire's 3x3 footprint, spread around it by a stable per-civilian angle. If the civilian can't get there within 600 ticks, it lies down where it stands. Without a main bonfire, the spot is near the empire home (`CivilianRoster.homeFor`). |
| R6 | A civilian that gets to bed late still sleeps until rested, but always wakes at the end of the night. |

## Rules

**Need** (pure, `rules.CivilianSleep.neededTicks(enduranceRaw)`), rounded to the nearest tick:

| Endurance | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 | 17 | 18 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Ticks | 9600 | 9257 | 8914 | 8571 | 8229 | 7886 | 7543 | 7200 | 7050 | 6900 | 6750 | 6600 | 6450 | 6300 | 6150 | 6000 |

`raw ≤ 10: 1200 × (8 − 2(raw − 3)/7)`; `raw ≥ 10: 1200 × (6 − (raw − 10)/8)`.

**Night:** `t = floorMod(dayTime, 24000)`, `12542 ≤ t < 23460`. **Night index:** `floorDiv(dayTime − 12542, 24000)`.

**Own sleep period:** bedtime `= 18001 − need/2` (integer division), wake time `= bedtime + need`.

| Need | 9600 | 7200 | 6000 |
|---|---|---|---|
| Bed → wake | 13201 → 22801 | 14401 → 21601 | 15001 → 21001 |

**Per night:**
- A session opens when the night starts.
- `sleptTicks`: raw ticks lying down. `creditTicks`: +1.0 per bed tick, +0.5 per rough tick.
- **Rested** when `sleptTicks ≥ need`.
- **Finalise** when the night ends, the night changes, or the civilian is rested: `lastNight = min(1, creditTicks / need)`, then reset. NaN before the first night (the fulfilment provider then uses 0.5).
- A loaded civilian that never slept finalises at 0.

**Catch-up** `catchUp(from, to, bed, need)`: treat every tick of `[from, to)` inside the civilian's own sleep period as slept at the given rate, with the finalise rules applied at night boundaries. Computed in O(1). A fully missed night finalises at 1.0 (bed) or 0.5 (rough). Used for time skips (D6, R4) and unloaded time (D7, R3).

## Architecture

- `rules.CivilianSleep` (new, no Minecraft imports): curve, night, own period, per-night state, `catchUp`, `Saved` / `restore` / `copy`. Mirrors `CivilianFood`.
- `data.CivilianSleepNbt` (new): shared body (`Sleep`) and record (`sleep`) codec, including `last_day_time`. The claimed bed is saved as `bed`. `CivilianRecord.VERSION` stays 2.
- `CivilianEntity`: state, write-back, immobile / silent / click-to-wake while asleep, time-jump handling in `aiStep`.
- `civilian.CivilianBeds` (new): vanilla `HOME` POI claims (`take` / `release`) within 48 blocks of home on the empire's staked chunks; validation; the rough spot by the bonfire; teleport targets.
- `civilian.CivilianSleepGoal` (new, priority 3, MOVE/LOOK/JUMP): from the civilian's own bedtime, go to the bed and `startSleeping`, or go to the rough spot and lie down. A temporary stand-in for the scheduler's sleep Slot/Task.
- **Debug:** a sleep line in `info` (including bedtime and wake time); `sleep claim | release | lastnight <0..1>`; `sleep` on the roster line.

## Tests

- **Unit** (`CivilianSleepTest`): curve, night edges, own period, credit, rested cap, finalise, `catchUp`.
- **Game tests:** persistence and claims in batch `civilians`. Anything that changes day time runs in batch `civilian_sleep` and restores the time in `finally`.

## Open (later)

- Housing / Shelter replaces auto-claim.
- The scheduler owns bedtime.
- The fulfilment Sleep meter reads `lastNight`.
- A "Zzz" bubble and other client polish.
