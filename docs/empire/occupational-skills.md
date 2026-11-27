<!-- Written by Gemini (2026-11-27) from Bright's design decisions in this session. -->
# Empire: Occupational Skills (Cross-Job Action Domains)

**Date:** 2026-11-27 · **Status:** built on branch `empire/occupational-skills` (worktree `../EnginesAndEmpires-empire`). In-game feel (visible rookie fumbles, progression pace across a working farm, sneak + right-click `Skills:` line, and `/empire debug civilian skills` display) remains for Bright to verify in a client.
**Scope:** cross-job action-domain skill progression (`HARVESTING`, `CULTIVATION`, `LOGISTICS`, `EXCAVATION`, `CONSTRUCTION`), cubic XP progression curve (`0.0–1.0`, `24d` Competent / `192d` Mastered), speed/exhaustion/durability/produce-yield/rookie-fumble hooks wired into Level-4 atomic actions, trait-aligned starting skills for travellers and migrated v1–v4 saves, active-day decay with a 3-day grace window, sneak-click inspection, and `/empire debug civilian skills`.

---

## 1. Decisions (Bright, 2026-11-27)

| # | Decision |
|---|---|
| **D1** | **Cross-Job Action Domains (v1 Set):** Skills map to reusable Level-4 atomic actions rather than 1:1 occupations so experience transfers across jobs: **`Harvesting`** (crops, leaves, foraging), **`Cultivation`** (seeding + tilling soil, grouped so tilling stays trained and does not starve/decay), **`Logistics`** (container deposits/withdrawals across all jobs), **`Excavation`** (stone/ore/dirt/logs, ready for Mining/Treefelling), and **`Construction`** (placing structural blocks, ready for Building). |
| **D2** | **Scale, Tiers & Cubic Curve (`24d` → `192d`):** Skill levels span `[0.0, 1.0]` across five bands: `Unskilled` (`0.00..<0.25`), `Simple` (`0.25..<0.50`), `Competent` (`0.50..<0.75`), `Advanced` (`0.75..<1.00`), and `Mastered` (`1.00`). Backed by cumulative XP on an exact cubic curve (`XP(s) = MAX_XP * s^3`, `MAX_XP = 19,200` at `100 XP/day`): `0.25` in `3` active workdays (`1h`), `0.50` in `24` days (`8h`), `0.75` in `81` days (`27h`), and `1.00` in `192` days (`2` MC years at `96` days/yr = `64h`). |
| **D3** | **Per-Action XP with Time-Efficiency Scaling:** XP is earned strictly per completed action (no shift-length or wall-clock time-based XP). Per-action XP scales by `1 / speedMultiplier(level)` (`0.5x` XP/action at Advanced `2.0x` speed vs. Simple `1.0x`, while the cubic slope simultaneously demands `9x` more XP per unit of skill), multiplied by each skill's motor/cognitive trait learning blend (`motorLearningMultiplier` / `cognitiveLearningMultiplier`), Fulfilment `productivity` (`0.5x–1.0x`), and sleep consolidation (`0.5 + 0.5 * lastNight`, `1.0` before the first night). |
| **D4** | **Starting Skills & Legacy Save Migration:** Newly spawned civilians/travellers roll `1–3` trait-aligned starting skills (most commonly `2`) in `[0.05, 0.50]` biased by `CivilianSkill.startingAffinity(traits)`. Legacy v1–v4 `CivilianRecord`s (without `"skills"`) migrate deterministically via UUID + saved traits into trait-aligned starting skills. |
| **D5** | **Slow Active-Day Decay with Grace Period:** Unused citizen skills decay in XP-space at `20%` of the reference daily gain rate (`20 XP/day`) after `3` consecutive active civilian days (`DECAY_GRACE_DAYS = 3`) without using that skill. Tied to `CivilianFood.countActiveTick()` for `Status.CITIZEN` only (unloaded/frozen empires, travellers, and free civilians do not decay). |
| **D6** | **Linear Mechanical Scaling:** Action speed scales linearly from `0.5x` (`0.0`) → `1.0x` (`0.25`) → `1.5x` (`0.50`) → `2.0x` (`0.75`) → `2.5x` (`1.0`). Work exhaustion scales linearly from `1.0x` (`0.0`) down to `0.5x` (`1.0`). Tool durability consumption chance scales linearly from `100%` (`0.0`) down to `25%` (`1.0`, Unbreaking-style). Harvested crop produce yield scales linearly from `1.0x` (`0.0`) up to `1.5x` (`1.0`) for **produce only** (excluding separate non-edible seeds like `wheat_seeds`/`beetroot_seeds` and `poisonous_potato`). |
| **D7** | **Non-Destructive Rookie Fumbles (`0.0 <= s < 0.25`):** Rookie mistakes occur only below `0.25` (Simple), tapering linearly to `0%` at `0.25`, scaled by `fumbleRateMultiplier` and cold/fatigue precision stress through `coldFatiguePrecisionSensitivity`. Harvest fumbles drop items on the ground without triggering full-pocket overflow; seeding fumbles drop 1 seed on the ground (leaving the plot for the next pass); tilling fumbles whiff the swing (spending rolled durability, retried cleanly); Logistics fumbles spill `1–2` stackable non-tool items beside the container (swept up by `PickupDropsTask`, never spilling hoes/tools). |
| **D8** | **Limited Visibility & Separate Vocation:** `Vocation` stays separate for a later slice. Player visibility is limited to seeing fumbles in-world, the compact `Skills:` line on normal sneak + right-click / `/empire debug civilian info`, and `/empire debug civilian skills` (no Administrative Ledger inspection hook). |

---

## 2. Provisional Defaults (One Named Constant Each)

| Constant | Value | Where |
|---|---:|---|
| `REFERENCE_DAILY_XP` / `MAX_XP` | `100.0` / `19,200.0` | `rules.CivilianSkills` |
| `DAILY_DECAY_XP` / `DECAY_GRACE_DAYS` | `20.0` / `3` days | `rules.CivilianSkills` |
| `BASE_ROOKIE_FUMBLE_CHANCE` / `MAX_FUMBLE_CHANCE` | `0.25` / `0.50` | `rules.CivilianSkills` |
| `COLD_FATIGUE_FUMBLE_WEIGHT` | `0.50` | `rules.CivilianSkills` |
| `HARVEST_CROP_BASE_XP` / `PLANT_CROP_BASE_XP` / `TILL_SOIL_BASE_XP` | `1.0` / `1.0` / `2.0` | `rules.CivilianSkills` |
| `TRANSFER_BASE_XP` / `TRANSFER_XP_PER_STACK` / `TRANSFER_MAX_XP` | `4.0` / `2.0` / `20.0` | `rules.CivilianSkills` |
| `EXCAVATE_BLOCK_BASE_XP` / `CONSTRUCT_BLOCK_BASE_XP` | `1.0` / `1.0` | `rules.CivilianSkills` |
| `FUMBLE_ATTEMPT_XP_FACTOR` | `0.5` | `rules.CivilianSkills` |
| `START_SKILL_MIN_LEVEL` / `START_SKILL_MAX_LEVEL` | `0.05` / `0.50` | `rules.CivilianSkills` |

---

## 3. Architecture & Persistence

- **Pure rules (`dev.brights0ng.empire.rules`, zero Minecraft imports):**
  - `CivilianSkill`: 5 v1 domains with motor/cognitive learning weights, `startingAffinity(CivilianTraits)`, and lang keys.
  - `CivilianSkills`: `SkillTier` enum, cubic XP ↔ level curve, speed/exhaustion/durability/yield/fumble/cold-fatigue formulas, time-efficiency action XP awards, active-day decay with grace window, trait-aligned starting rolls (`rollStarting`, deterministic `defaultFor`), and immutable `Saved` / `Entry` snapshots.
  - `FarmerUtility.scaledWorkTicks`: 4-arg overload multiplying trait speed, Fulfilment productivity, and `CivilianSkills.speedMultiplier`.
- **Persistence (`dev.brights0ng.empire.data`):**
  - `CivilianSkillsNbt`: tolerant codec writing a format version marker `"v": 1` so intentionally reset all-zero skills reload empty while missing/legacy tags trigger trait-aligned migration.
  - `CivilianRecord`: bumped `VERSION` `4 → 5`; persists `"skills"` and migrates legacy v1–v4 saves deterministically from `id` + loaded `traits`.
- **Entity & AI Actions (`dev.brights0ng.empire.civilian` & `civilian.ai`):**
  - `CivilianEntity`: rolls trait-aligned starting skills at `finalizeSpawn`, restores & syncs skills to `CivilianRecord` (surviving body respawn via `CivilianSpawning.respawnBody` and recruitment via `CivilianRoster.recruit`), runs active-day decay on `Status.CITIZEN` rollovers, exposes `currentFumbleChance` & `awardSkillActionXp`, adds compact `Skills:` summary to `debugInfoLines`, and provides `skillsDebugLines`.
  - `BreakBlockAction`, `PlaceBlockAction`, `InteractBlockAction`, `TransferItemsAction`: apply skill speed, exhaustion, tool durability saving, produce-only crop yield (`FarmCrops.isCropProduceDrop`), non-destructive rookie fumbles, and per-action XP awards.
  - `HarvestFieldTask` (`HARVESTING`), `SeedFieldTask` (`CULTIVATION`, skips fumbled tile for the current pass), `RetillSoilTask` (`CULTIVATION`, retries up to 3 consecutive fumbles per tile), and `FarmWorkScanner` (extends `strayDrops` around `SHED` `storageBlocks` so shed spills get tidied by `PickupDropsTask`).
- **Debug & Lang:**
  - `/empire debug civilian skills [set <skill> <0..1> | addxp <skill> <xp> | decay [days] | reroll | reset]`.
  - Public lang keys added in `en_us.json` (edited by Gemini; JSON does not allow comments): `skill.empire.{harvesting,cultivation,logistics,excavation,construction}` and `skill.empire.tier.{unskilled,simple,competent,advanced,mastered}`.

---

## 4. Verification (2026-11-27)

- **Unit & architecture tests (`:empire:compileJava :empire:compileTestJava :empire:test :test`):**
  - `:empire:test`: **254 passed, 0 failed, 0 skipped** across **40 suites** (including all 7 new tests in `CivilianSkillsTest` covering the cubic curve, linear multipliers, time-efficiency/trait/productivity/sleep XP scaling, Fitts's-Law cold/fatigue fumble behavior, 3-day grace + 20% XP decay, trait-aligned starting rolls, and `en_us.json` key coverage, plus expanded `FarmerUtilityTest`).
  - `:test` (core): **33 passed, 0 failed, 0 skipped** across **5 suites** (including `ArchitectureTest`).
- **Empire standalone game test server (`:empire:runGameTestServer`):**
  - **207/207 required tests passed** (including all **5/5 new `CivilianSkillsGameTests`**, all **7/7 `FarmerJobGameTests`**, all `CivilianAiGameTests`, `CivilianTraitsGameTests`, `CivilianFulfilmentGameTests`, and `FarmOccupationGameTests`).
  - Note: on the first game-test run, an unrelated random-coordinate terrain flake occurred in `hungerbasalactivitystarvationandweaknessscalewithtraits` (`206/207`); re-running `:empire:runGameTestServer --rerun-tasks` passed cleanly at **207/207**.
- **Not verified (left for Bright's in-game playtest):** live client feel of rookie fumble drops & pickup cadence, skill speed progression across an active farm, sneak + right-click `Skills:` formatting, and `/empire debug civilian skills` output.
