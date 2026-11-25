<!-- Written by Gemini (2026-11-25) from Bright's design decisions in this session. -->
# Empire: Civilian AI (4-Level Engine)

**Date:** 2026-11-25 · **Status:** built on branch `empire/civilian-ai` (worktree `../EnginesAndEmpires-empire`). In-game visual and behavioral checks (daily movement rhythm, bonfire mealtime gathering, fishing/reading/walking look-and-feel, and `/empire debug civilian schedule` output) remain for Bright to verify in a client.
**Scope:** replaces temporary vanilla goals (`CivilianSleepGoal`, `MoveTowardsRestrictionGoal`, `WaterAvoidingRandomStrollGoal`) for `CITIZEN`s with the 4-level Civilian AI pipeline (**Schedule → Slot → Task → Action**), including live implementations of `SLEEP`, `MEAL`, `SOCIALIZE`, `HOBBY` (`FISHING`, `READING`, `RELAX_AT_HOME`, `WALK`), `RELAX`, an extensible `WORK` interface (with debug work assignment), live `Enjoyment` hobby-time tracking, 5 live interrupts, and D12 off-empire move-back. Real workplace/communal regions (`FARM`, `PUB`, `LARDER`, `WORKSHOP`) come in later branches.

---

## 1. Decisions (Bright, 2026-11-24)

| # | Decision |
|---|---|
| **D1** | **4-Level Hierarchy:** **Schedule** (Anchor Skeleton + live Gap-Filler) → **Slot** (utility-scored Task selection via `TaskUtility`: `Gate × (BaseWeight × Urgency + Momentum)`, evaluated only at decision boundaries) → **Task** (condition-driven Action generator) → **Action** (atomic Minecraft primitives). |
| **D2** | **Midnight-Anchored `mm:ss` Clock:** `00:00` = midnight (`vanilla 18,000`), `20:00` = next midnight (`24,000` ticks/day, stored internally in ticks for exact alignment with `CivilianSleep` and formatted as `mm:ss`). `04:00` = `4,800t`, `05:00` (sunrise) = `6,000t`, `10:00` (noon) = `12,000t`, `15:00` (sunset) = `18,000t`, `16:00` = `19,200t`. |
| **D3** | **Sparse Anchor Skeleton + Live Gap-Filler:** Built lazily whenever `scheduleDay` rolls over (handling midnight, player night-skips, and unloaded days). Hard anchors: `CONTINUE_SLEEP` (`00:00` → remaining sleep, capped at `04:33` / vanilla `23,460`), `LUNCH`, `DINNER`, and `TONIGHT_SLEEP` (`20:00 - sleepNeed/2` → `20:00`). Gaps are filled live at decision points using learned travel times (`RouteMemory`) and minimum useful durations (`MIN_*_TICKS`). Late arrivals at shiftable anchors (`LUNCH`/`DINNER`) shift the meal window forward without colliding with the next anchor. |
| **D4** | **Variable Meal Times:** Base `LUNCH` (`09:00`) and `DINNER` (`14:00`) vary per citizen via a deterministic personal habit offset (biased earlier by `basalExhaustionMultiplier`), an **Extraversion crowd pull** toward observed hub peaks (and drift away for `Solitary`), plus small daily jitter. |
| **D5** | **Assigned Jobs Only:** Citizens never stampede to unassigned workplaces; jobs are assigned by a player (or future Town Clerk). This branch provides `WorkAssignment` + `WorkSlotProvider` and a debug assignment command. |
| **D6** | **Fulfilment & Traits Shaping Work vs. Leisure:** Discontent (`Fulfilment < threshold` or `Grievance > 0`) and traits (`Conscientiousness`, `Agreeableness`, `sustainedLaborStamina`, `Neuroticism`, `Openness`) act in both places: (a) scaling daily `workQuota`, and (b) probabilistically inserting a **bounded** Social or Hobby break (`0.5–2.0` MC hours = `0:30–2:00`, scaling with discontent) before Work. |
| **D7** | **Clean Action/Task Finish:** When `workQuota` completes or a slot's `departAt` deadline arrives, the current atomic Action finishes cleanly (within a safety grace cap) before switching slots. |
| **D8** | **Outdoor Window (`04:00–16:00`):** Outdoor work and outdoor hobbies (`FISHING`, `WALK`) are gated to `04:00–16:00` (`4,800..19,200` ticks); early risers spend pre-dawn indoors (`READING`, `RELAX_AT_HOME`, indoor work/relaxing). |
| **D9** | **Scheduled Meals & Temporary Gathering Hub:** Citizens eat during `MEAL` slots (preferring liked foods first; ending early once full or out of fitting food). Outside mealtime, citizens only eat from pockets as a low-hunger emergency interrupt (`foodLevel <= max(10, hungerPenaltyThreshold)`). `FREE` and `TRAVELLER` keep passive pocket eating. Until `PUB`/`LARDER` exist, the Main Bonfire (fallback: empire home) serves as `FOOD_SITE` and `SOCIAL_HUB`. |
| **D10** | **Hobbies & Live Enjoyment:** Replaced `CivilianPreferences.Activity.PUB_CHAT` with `READING` (v1 set: `FISHING`, `READING`, `RELAX_AT_HOME`, `WALK`; legacy `"pub_chat"` NBT migrates to `READING`). Replaced Enjoyment's `0.25` placeholder with live 3-day liked-hobby time (`FulfilmentModel.enjoyment` is now `SourceKind.LIVE`). |
| **D11** | **5 Live Interrupts:** (1) Player dialogue pause, (2) Hostile damage flee to `HOME`/Bonfire, (3) Low-hunger emergency snack, (4) Extreme Meteodyne temperature shelter (`Comfort <= 0.0` via `TemperatureProvider.get()`), (5) D12 off-empire move-back (level-33 anchor ticket + teleport to nearest staked chunk when no non-spectator player is within 128 blocks) and last-resort stuck-teleport home. |
| **D12** | **Citizens Only:** The 4-level AI drives `Status.CITIZEN` only; `FREE`, `TRAVELLER`, and `LEAVING` stay on their existing lightweight goals. |

---

## 2. Provisional Defaults (One Named Constant Each)

| Constant | Value | Where |
|---|---:|---|
| `OUTDOOR_START_TICK` / `OUTDOOR_END_TICK` | `4_800` (`04:00`) / `19_200` (`16:00`) | `rules.ScheduleClock` |
| `BASE_LUNCH_TICK` / `BASE_DINNER_TICK` | `10_800` (`09:00`) / `16_800` (`14:00`) | `rules.CivilianSchedule` |
| `MEAL_DURATION_TICKS` | `300` (`0:15`) | `rules.CivilianSchedule` |
| `MEAL_PERSONAL_OFFSET_MAX_TICKS` | `900` (`±0:45`) | `rules.CivilianSchedule` |
| `MEAL_DAILY_JITTER_TICKS` | `100` (`±0:05`) | `rules.CivilianSchedule` |
| `DEFAULT_WORK_ASK_TICKS` | `8_400` (`7:00`) | `rules.CivilianSchedule` |
| `MIN_WORK_TICKS` / `MIN_HOBBY_TICKS` | `600` (`0:30`) | `rules.CivilianSchedule` |
| `MIN_SOCIAL_TICKS` | `300` (`0:15`) | `rules.CivilianSchedule` |
| `PREEMPT_MIN_TICKS` / `PREEMPT_MAX_TICKS` | `600` (`0:30`) / `2_400` (`2:00`) | `rules.CivilianSchedule` |
| `ENJOYMENT_HOBBY_FULL_TICKS` | `7_200` (`6:00` / 3 days) | `rules.FulfilmentModel` |
| `DETOUR_FACTOR` / `EMA_ALPHA` | `1.25` / `0.50` | `rules.RouteMemory` |
| `DEFAULT_MOMENTUM_BONUS` | `15.0` | `rules.TaskUtility` |
| `CLEAN_FINISH_GRACE_TICKS` | `200` (`0:10`) | `civilian.ai.CivilianSlotRunner` |
| `EMERGENCY_FOOD_FLOOR` | `10` | `civilian.ai.CivilianAiController` |
| `FLEE_DURATION_TICKS` | `400` (`0:20`) | `civilian.ai.CivilianAiController` |
| `THERMAL_RECOVER_COMFORT` | `0.35` | `civilian.ai.CivilianAiController` |
| `STUCK_TELEPORT_TICKS` | `1_200` (`1:00`) | `civilian.ai.MoveToAction` |

---

## 3. Architecture & Persistence

- **Pure rules (`dev.brights0ng.empire.rules`, zero Minecraft imports):**
  - `ScheduleClock`, `LocationKey`, `RouteMemory`, `CrowdMemory`, `CivilianSchedule`, `TaskUtility`, plus `LoadPlan.nearestStakedPoint`, `CivilianPreferences.Activity.READING`, `CivilianFulfilment` 3-day `likedHobbyTicksDays`, and live `FulfilmentModel.enjoyment(int, long)`.
- **Persistence (`dev.brights0ng.empire.data`):**
  - `WorkAssignment` (assigned anchor, indoor/outdoor, daily ask ticks, label).
  - `CivilianScheduleNbt` (tolerant shared codec for schedule anchors/quotas/hobby days, `RouteMemory`, `CrowdMemory`, and optional `WorkAssignment`).
  - `CivilianRecord` bumped `VERSION` `3 → 4`; legacy v1–v3 records without `"schedule"` load cleanly with empty defaults, and legacy `"pub_chat"` activities migrate cleanly to `READING`.
- **World-side 4-level AI runtime (`dev.brights0ng.empire.civilian.ai`):**
  - Level 4 Actions: `CivilianAction`, `MoveToAction`, `ConsumeMealAction`, `SocializeChatAction`, `HobbyAction`, `StubWorkAction`, `WaitIdleAction`.
  - Level 3 Tasks: `CivilianTask`, `BedSleepTask`, `RoughSleepTask`, `EatScheduledMealTask`, `ChatWithNearbyTask`, `GatherAtSocialSpotTask`, `PerformHobbyTask`, `RelaxTask`, `StubWorkTask`, `FleeToSafetyTask`, `EmergencyEatTask`, `ThermalShelterTask`.
  - Level 2 & 1: `WorkSlotProvider`, `LocationResolver`, `CivilianSlotRunner`, `CivilianAiController`, `CivilianAiGoal` (single priority-3 `Goal` replacing `CivilianSleepGoal` and citizen wandering).
- **Chunk loading (`CivilianChunkLoading`):**
  - Implements D12 off-empire move-back using a level-33 memory-only `OFF_EMPIRE_ANCHOR` `TicketType` while a non-spectator player is within 128 blocks, teleporting off-empire citizens to the nearest staked chunk's surface once no player is within 128 blocks, plus brief `RESTART_PULL_BACK` loading for unloaded off-empire records after restart.
- **Debug commands (`CivilianDebugCommands`):**
  - `/empire debug civilian schedule [rebuild]`
  - `/empire debug civilian work <here | x y z> [minutes] [indoor|outdoor]` and `/empire debug civilian work clear`
  - Compact `AI:` summary line added to `/empire debug civilian info` and sneak + right-click stats.

---

## 4. Verification (2026-11-25)

- **All-module unit tests (`compileJava compileTestJava test`):** **1454 passed, 6 skipped** (pre-existing), **0 failed, 0 errors** across **208 suites** — including **233** in Empire (+15 new unit tests across `ScheduleClockTest`, `RouteMemoryTest`, `TaskUtilityTest`, `CivilianScheduleTest`, `LoadPlanTest`, `FulfilmentModelTest`, `CivilianFulfilmentTest`) and `core`'s `ArchitectureTest`.
- **Empire standalone game test server (`:empire:runGameTestServer`):** **175/175 passed** (including all 8 new integration tests in `CivilianAiGameTests`, all 11 `CivilianSleepGameTests`, all 32 `CivilianNeedsGameTests`, all 7 `CivilianFulfilmentGameTests`, and all 5 `CivilianTraitsGameTests`).
- **Combined coreless run with Meteodyne (`:coreless:runGameTestServer -Pcoreless.namespaces=empire`):** **174/175 passed** — all **8/8 `CivilianAiGameTests`**, **7/7 `CivilianFulfilmentGameTests`**, **5/5 `CivilianTraitsGameTests`**, and **32/32 `CivilianNeedsGameTests`** passed under live Meteodyne weather & `TemperatureProvider`. The sole failure was the pre-existing documented timing flake `CivilianSleepGameTests.unreachableBedSkippedUntilRegionUpdated` (left untouched).
- **Aggregate `:runGameTestServer`:** ran prior to widening `assignedWorkAdvancesQuotaAndRecordsLearnedRoute`'s Meteodyne-wind check window (`140 → 210` ticks, subsequently verified green under live Meteodyne in `:coreless:runGameTestServer`), observing only the pre-existing failures outside this branch (`DietGameTests.loggingInDoesNotRefillEmptyGroups`, `RegionGameTests.claimPrefersExistingHome...`, and Meteodyne's `asavethatranawayhealswhenitstileisloaded`).
- **Not verified (left for Bright's in-game playtest):** visual/behavioral feel of daily schedule progression, bonfire mealtime gathering, fishing/reading/walking activities, hostile-flee & thermal-shelter reactions, and `/empire debug civilian schedule` / `work` / `info` formatting in a live client.
