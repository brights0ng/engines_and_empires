<!-- Written by Gemini (2026-11-22) from Bright's design decisions in this session. -->
# Empire: Civilian Traits (Big Five + Orthogonality)

**Date:** 2026-11-22 · **Status:** built on branch `empire/civilian-traits`. Client display of `/empire debug civilian traits`, `/empire debug civilian info`, and sneak + right-click stats remains for Bright to verify in-game.
**Scope:** replaces independently rolled 6-score abilities with an 11-axis hidden character model (**5 Big Five personality traits** + **6 Orthogonality physical traits**), deriving **7 player-facing ability scores**, **22 plain-English folk quirks**, and **direct mechanical stat profiles** wired into live Minecraft 1.21.1 entity attributes, hunger/healing/starvation/hunger-strength, sleep, thermal comfort, fulfilment targets/grievance, preferences, and speech cadence, plus future-ready job/Create/Encumbered/combat multipliers.

---

## 1. Decisions (Bright, 2026-11-22)

- **D1 Two-layer model (direct wiring + honest summary):** Gameplay mechanics read directly from the 11 hidden axes (`CivilianTraits` → `CivilianDerivedStats`). Players never see clinical psychology or anthropometry scores; instead they see **7 derived ability scores** (`CivilianAbilities`) plus plain-English **folk quirks** (`CivilianQuirk`).
- **D2 Seven player-facing abilities (3 Body + 4 Mind, Option A):**
  - Body: **Brawn** (`BRA`), **Endurance** (`END`), **Precision** (`PRE`)
  - Mind / Social: **Resolve** (`RES`, replacing vague `Willpower`), **Wits** (`WIT`), **Organization** (`ORG`), **Teamwork** (`TEA`)
- **D3 Monotonic score derivation:** Displayed 3–18 ability scores use simple monotonic L2-normalized linear projections (`score = clamp(round(10.5 + 7.0 * Z), 3, 18)`); dual-pole downsides and inverted-U drag (e.g., perfectionist slowdown, chatterbox distraction, daydreamer focus drag, over-yielding) appear in gameplay mechanics and quirks rather than dipping the displayed number.
- **D4 Fitts's-Law trade-off on Motor-Sensory Integration:** High `Calibrated` improves fine work speed, precision, fumble avoidance, ranged aim, and motor learning — at the cost of higher sleep need (cerebellar consolidation), sharper cold/fatigue precision loss (`coldFatiguePrecisionSensitivity`), and slightly slower crude/ballistic swings (`heavyLaborSpeedMultiplier`). Low `Coarse` swings crude tools slightly faster and shrugs off cold/fatigue.
- **D5 Twenty-two folk quirks:** One plain-English quirk for each low and high extreme of each of the 11 base traits (triggered at `|raw| >= 60`, ordered strongest-first).
- **D6 `sustainedLaborStamina`:** General sustained-labor stamina attribute (covering hand cranks, mining, farming, hauling shifts) rather than crank-only.
- **D7 Debug command & lang keys:** `/empire debug civilian abilities <code>` replaced by `/empire debug civilian traits [set <trait> <-100..100> | neutral | reroll]`; `en_us.json` updated with 7 ability keys and 22 quirk keys (attribution recorded here because JSON does not allow comments).

---

## 2. The 11 Base Traits & 22 Folk Quirks

Each axis is stored as an integer in `[-100, +100]` (`norm = raw / 100.0` in `[-1.0, +1.0]`, exact neutral `0`). Rolled via `Gaussian(0, 40)` clamped to `[-100, +100]`, rerolling the whole set (up to `MAX_REROLLS = 1000`) when the derived 7-ability mean is outside `[8.5, 12.5]` or quirk count exceeds `4`.

### Big Five (Personality)
| Axis (`id`, short) | Low Extreme (`<= -60`) | High Extreme (`>= +60`) |
|---|---|---|
| **Openness** (`openness`, `O`) | `Creature of Habit` | `Daydreamer` |
| **Conscientiousness** (`conscientiousness`, `C`) | `Freewheeling` | `Perfectionist` |
| **Extraversion** (`extraversion`, `E`) | `Solitary` | `Chatterbox` |
| **Agreeableness** (`agreeableness`, `A`) | `Cantankerous` | `Softhearted` |
| **Neuroticism** (`neuroticism`, `N`) | `Unflappable` | `High-Strung` |

### Orthogonality (Physique)
| Axis (`id`, short) | Low Extreme (`<= -60`) | High Extreme (`>= +60`) |
|---|---|---|
| **Allometric Scale** (`allometric_scale`, `AS`) | `Stocky` | `Lanky` |
| **Metabolic Reserve** (`metabolic_reserve`, `MR`) | `Lean` | `Stout` |
| **Contractile Force** (`contractile_force`, `CF`) | `Slight` | `Brawny` |
| **Aerobic Stamina** (`aerobic_stamina`, `AE`) | `Explosive` | `Tireless` |
| **Elastic Compliance** (`elastic_compliance`, `EC`) | `Taut` | `Limber` |
| **Motor-Sensory** (`motor_sensory`, `MS`) | `Heavy-Handed` | `Deft` |

---

## 3. Seven Derived Ability Projections (`CivilianAbilities`)

Each score uses L2-normalized weights ($Z = \frac{\sum w_i x_i}{\sqrt{\sum w_i^2}}$) so derived scores retain a `~3d6` spread (`σ ≈ 2.8`):

| Ability | Formula (before L2 normalization) |
|---|---|
| **Brawn** (`BRA`) | `+0.65 CF − 0.20 AS − 0.15 EC` |
| **Endurance** (`END`) | `+0.55 AE + 0.25 MR + 0.20 C` |
| **Precision** (`PRE`) | `+0.65 MS + 0.15 EC + 0.10 C − 0.10 N` |
| **Resolve** (`RES`) | `−0.65 N + 0.20 C + 0.15 E` |
| **Wits** (`WIT`) | `+0.65 O + 0.20 MS + 0.15 C` |
| **Organization** (`ORG`) | `+0.75 C + 0.15 O + 0.10 MS` |
| **Teamwork** (`TEA`) | `+0.55 A + 0.35 E − 0.10 N` |

---

## 4. Direct Derived Mechanics (`CivilianDerivedStats`)

At `CivilianTraits.NEUTRAL` (all 11 axes at `0`), every live mechanic reproduces Empire's pre-traits defaults identically (`30 HP`, `0.50` speed, `3.0` safe fall, `32.0` follow range, `1.0×` basal/activity hunger, `7200t` sleep need, `0.50` rough credit, `200t` wake cooldown, `0 °C` comfort shift, `10` community / `5` socializing / `25` shelter floor targets, `1.0×` grievance rates, `0.25` talk chance).

### Live Now (wired into `CivilianEntity` & `CivilianFulfilmentMeters`)
- **Native 1.21.1 Entity Attributes:** `MAX_HEALTH` (`22–38`), `MOVEMENT_SPEED` (`0.86×–1.14×`), `JUMP_STRENGTH` (`0.88×–1.15×`), `SAFE_FALL_DISTANCE` (`2.0–5.0`), `FALL_DAMAGE_MULTIPLIER` (`0.60×–1.50×`), `KNOCKBACK_RESISTANCE` (`0.0–0.45`), `ATTACK_DAMAGE` (`0.65×–1.50×` of `2.0`), `ATTACK_KNOCKBACK` (`0.0–1.0`), `MOVEMENT_EFFICIENCY` (`0.0–0.40`), `WATER_MOVEMENT_EFFICIENCY` (`0.0–0.45`), `OXYGEN_BONUS` (`0.0–2.5`), `FOLLOW_RANGE` (`20–44`).
- **Hunger, Healing, Starvation & Hunger-Strength:**
  - `basalExhaustionMultiplier` (`0.65×–1.45×`) scales per-tick basal exhaustion.
  - `activityExhaustionMultiplier` (`0.60×–1.50×`) scales jump & water movement exhaustion (and future work actions).
  - `healingIntervalMultiplier` (`0.65×–1.45×`) & `starvationIntervalMultiplier` (`0.60×–1.65×`) scale `CivilianFood.tick(...)` intervals.
  - `hungerStrengthFactor(foodLevel)` starts weakening physical output below threshold `5–13` (`Lean` fades at `12–13`, `Fatty` holds to `5–6`) down to floor `0.55–0.75` at food `0`, applied live as a transient `empire:hunger_weakness` modifier on `ATTACK_DAMAGE`.
- **Sleep:** `sleepNeedTicks` (`5400–10200` = `4.5–8.5 min`), `roughSleepCreditRate` (`0.38–0.62`), `wakeCooldownTicks` (`120–320`).
- **Fulfilment & Thermal Comfort:**
  - `comfortTempShiftC` (`−5.5` to `+5.5 °C`) shifts the Meteodyne `[10–35 °C]` comfort band colder (`Stocky/Stout/Brawny`) or warmer (`Lanky/Lean/Slight`); `comfortDangerMarginMultiplier` (`0.75×–1.30×`) scales the danger margin.
  - `communityTargetPeople` (`4–16`), `socializingTargetInteractions` (`2–9`), `shelterFloorPerOccupant` (`19–32`), `grievanceBuildMultiplier` (`0.60×–1.60×`), `grievanceDrainMultiplier` (`0.65×–1.45×`).
- **Preferences & Speech:** `Openness` biases liked-food and liked-activity count distributions (`CivilianPreferences.roll`); `Extraversion` (and mild `Agreeableness`) scales idle speech cooldown (`0.65×–1.60×`) and `talkChance` (`0.08–0.50`).

### Future-Ready (computed, unit-tested, and shown in `/empire debug civilian traits`)
- Reach (`blockReach`, `meleeReach`), Create & Encumbered (`mechanicalTorqueMultiplier`, `mechanicalSpeedMultiplier`, `sustainedLaborStamina`, `carryCapacityMultiplier`), labor & Fitts's-Law (`heavyLaborSpeedMultiplier`, `fineWorkSpeedMultiplier`, `coldFatiguePrecisionSensitivity`, `fumbleRateMultiplier`, `rangedAccuracyMultiplier`), learning & routine (`cognitiveLearningMultiplier`, `motorLearningMultiplier`, `monotonyDrainRate`, `reassignmentPenaltyMultiplier`, `routingEfficiencyMultiplier`), social AI (`teamworkSynergyMultiplier`, `moraleAuraStrength`, `moraleContagionSensitivity`), and combat psychology (`braveryScore`, `bleedOutDurationMultiplier`).

---

## 5. Architecture & Persistence

- **Pure rules (`dev.brights0ng.empire.rules`, no MC imports):** `CivilianTraits`, `CivilianQuirk`, `CivilianAbilities` (7 scores, 28-bit pack), `CivilianDerivedStats`, plus backward-compatible parameterized overloads in `CivilianFood`, `CivilianSleep`, `FulfilmentModel`, and `CivilianPreferences`.
- **Persistence (`dev.brights0ng.empire.data`):**
  - `CivilianTraitsNbt`: shared tolerant codec (`Traits` on entity, `traits` on record).
  - `CivilianRecord` bumped `VERSION` from `2` to `3`; legacy v1/v2 records without `traits` migrate deterministically via `CivilianTraits.defaultFor(id)`.
  - `CivilianFoodNbt`: widened `tick_timer` clamp from `80` to `200` to accommodate trait-extended starvation/regen intervals.
- **Public lang key edits (`en_us.json`, edited by Gemini):** replaced `civilian.empire.ability.willpower` with `civilian.empire.ability.resolve`, added `civilian.empire.ability.brawn`, updated `message.empire.civilian.abilities` to 7 slots, and added all 22 `civilian.empire.quirk.*` keys.

---

## 6. Verification (2026-11-22)

- **All-module unit tests (`compileJava compileTestJava test`):** **1439 passed, 6 skipped** (pre-existing), **0 failed, 0 errors** across **204 suites** — including **218** in Empire (+16 new tests across `CivilianTraitsTest`, `CivilianDerivedStatsTest`, and overload tests in `CivilianFoodTest`, `CivilianSleepTest`, `FulfilmentModelTest`, `CivilianFulfilmentTest`) and `core`'s `ArchitectureTest`.
- **Empire standalone game test server (`:empire:runGameTestServer`):** **167/167 passed** (including all 5 new tests in `CivilianTraitsGameTests`, verifying live 1.21.1 entity attributes, basal/activity hunger, hunger-strength dynamic modifier, starvation timing, sleep need/rough-credit/wake-cooldown, individualized thermal comfort shift, Extraversion social targets, Neuroticism grievance scaling, quirks/debug output, and >30 HP respawn preservation).
- **Combined coreless run with Meteodyne (`:coreless:runGameTestServer -Pcoreless.namespaces=empire`):** **166/167 passed** — all 5/5 `CivilianTraitsGameTests` and all 7/7 `CivilianFulfilmentGameTests` (live Meteodyne `TemperatureProvider`) passed. The sole failure was the pre-existing documented timing flake `CivilianSleepGameTests.unreachableBedSkippedUntilRegionUpdated` under Meteodyne weather (left untouched).
- **Not verified:** in-game client display of `/empire debug civilian traits`, `/empire debug civilian info`, `/empire debug civilian roster`, and sneak + right-click stats output remain for Bright to inspect in-game.
