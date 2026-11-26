<!-- Written by Gemini (2026-11-25) from Bright's design decisions in this session. -->
# Empire: Occupations & Farmer (v1)

**Date:** 2026-11-25 (updated 2026-11-26) · **Status:** built on branch `empire/farmer-occupation` (worktree `../EnginesAndEmpires-empire`). In-game visual and UX feel (ledger hiring/unemploying feedback, speech bubbles, offhand ledger crop override, farmer harvesting/replanting/tilling/chest interactions, and end-of-shift tool stowage) remain for Bright to check in a client.
**Scope:** foundational Occupation system connecting Empire's 4-level Civilian AI (`WORK` slots) with workplace regions (`RegionType.FARM`), reusable Level-4 Atomic Actions, and full `Occupation.FARMER` v1 job mechanics (`CropBlock` harvesting/replanting, shed storage dumping/fetching, hoe-required re-tilling, pure seeding, majority crop + offhand ledger override, end-of-shift stowage, and emergency-only raw produce eating).

---

## 1. Decisions (Bright, 2026-11-25)

| # | Decision |
|---|---|
| **D1** | **Ledger Assignment & Unassignment:** Select a workplace region (`FARM`) with the Administrative Ledger and **right-click** a citizen of your empire to assign them to that region's occupation (`FARMER`). **Shift-right-click** a citizen while holding the ledger to unemploy them — only allowed when that citizen's current workplace region is selected. While holding an Administrative Ledger, sneak-right-clicking a civilian **never** triggers the debug stats info dump. |
| **D2** | **Invalidation Pauses (Does Not Unassign):** If a `FARM` becomes `INVALID` (e.g. its shed is temporarily broken while remodeling), assigned farmers keep their assignment and simply pause work (`LocationKey.WORK` resolves unavailable, so they take the day off) until the farm is repaired. Workers are only detached back to `UNEMPLOYED` when their workplace region is **deleted** (last room removed, 3-day auto-expiry, debug remove, or empire disband) or when the citizen dies / is re-recruited / cleared. |
| **D3** | **Worker Capacity & Shed Targeting:** Provisional cap of **1 farmer per `FARM`** (`RegionRules.FARM_MAX_WORKERS = 1`). When a farm has multiple `SHED` rooms, the farmer targets a standable spot adjacent to the farm's **seed shed** (the room containing `farm.seed()`, falling back to the first `SHED` room). |
| **D4** | **Schedule & Vocation:** `FARMER` uses the existing scheduler defaults — outdoor shift (`04:00–16:00` via `ScheduleClock.isOutdoorTime`), `7:00` (`8,400` ticks) daily work ask scaled by traits and fulfilment, respecting meals, sleep, hobbies, and interrupts. The `Vocation` fulfilment meter stays on its `0.5` placeholder for now. |
| **D5** | **Feedback & Dialogue:** Assigning or unemploying a civilian plays a ledger page-turn sound, shows an action-bar message, and triggers a speech bubble (`CivilianLines.Category.HIRED` / `UNEMPLOYED`). Selecting or creating a `FARM` with the ledger shows its current worker count (`X/1 workers`). |
| **D6** | **Tools & Crop Scope (2026-11-26):** Farmers require a hoe (`#minecraft:hoes` / `HOE_TILL`, taking 1 durability per till, fetched from shed storage if needed) to re-till dirt, and harvest basic `CropBlock` crops bare-handed. Harvesting, planting, and tilling all scale with `heavyLaborSpeedMultiplier × productivity` and `activityExhaustionMultiplier`; storage transfers scale with `routingEfficiencyMultiplier × productivity`; all interactions use `blockReach`. |
| **D7** | **Non-Shrinking Fields & Crop Selection (2026-11-26):** `FIELD` rooms never shrink on automatic block-edit rescans (preserving trampled dirt for re-tilling); explicit main-hand Ledger click on a selected farm's room rescans and shrinks to remaining live farmland/crops. By default each `FIELD` room plants its majority growing `CropBlock` (falling back to first available seed for fresh fields). Holding the Ledger in **offhand** while a farm is selected and right-clicking a `FIELD` room with a crop seed or produce overrides that room's crop (leaving immature existing crops to finish growing); right-clicking with an **empty main hand** resets to automatic majority. |
| **D8** | **Storage Dumping, End-of-Shift Stowage & Raw Produce Eating (2026-11-26):** During work, farmers dump farm produce and surplus seeds into `SHED` `storageBlocks` (via `Capabilities.ItemHandler.BLOCK`), retaining only enough seeds/plantables to cover currently missing farm spots (capped at 1 stack per seed type) and never dumping personal meals or hoes. At end of shift (quota reached or `END_SHIFT_BUFFER_TICKS` before departure), `EndShiftStoreWorkGearTask` stows all hoes, seeds, and raw produce into shed storage while leaving personal meals in pockets. Raw crop produce is only eaten if out of proper foods and at or below the emergency snack threshold (never filling above it). |

---

## 2. Provisional Defaults (One Named Constant Each)

| Constant | Value | Where |
|---|---:|---|
| `FARM_MAX_WORKERS` | `1` | `rules.RegionRules` |
| `FARMER` daily ask | `CivilianSchedule.DEFAULT_WORK_ASK_TICKS` (`7:00` / `8,400t`) | `rules.Occupation` |
| `StandNearShedAction.CYCLE_TICKS` | `40` (`2s`) | `civilian.ai.StandNearShedAction` |
| `FarmerStandByShedTask.STAND_REACH` | `2.25` blocks | `civilian.ai.FarmerStandByShedTask` |

---

## 3. Architecture

- **Pure rules (`dev.brights0ng.empire.rules`, zero Minecraft imports):**
  - `Occupation` enum (`UNEMPLOYED`, `FARMER`) with `workplaceType`, `indoor`, `defaultDailyAskTicks`, `langKey`, and `forRegionType(RegionType)`.
  - `RegionRules.FARM_MAX_WORKERS` and `RegionRules.workerCapacity(RegionType)`.
  - `CivilianLines.Category.HIRED` (3 lines) and `CivilianLines.Category.UNEMPLOYED` (3 lines).
- **Persistence (`dev.brights0ng.empire.data`):**
  - `WorkAssignment` extended with `Occupation` and optional `UUID workplaceRegionId` (preserving backward compatibility with coordinate-only debug work stubs).
  - `CivilianScheduleNbt` persists `"occupation"` and `"region"` tolerantly inside the existing `work` compound.
  - `RegionRecord` adds a separate `workers` UUID set (saved as optional `"workers"` list, keeping `HOME` `inhabitants` cleanly separated from workplace `workers`).
- **Lifecycle & AI (`dev.brights0ng.empire.civilian` & `civilian.ai`):**
  - `CivilianOccupations`: `assign`, `unassign`, `unassignRecord` (handles unloaded bodies), `detachAllWorkers` (region deletion), `detachFromWorkplace` (death/re-recruit/clear), and `onWorkplaceValidityChanged` (pause/resume without detaching).
  - `LocationResolver`: resolves `LocationKey.WORK` dynamically to a standable block adjacent to the assigned `FARM`'s seed shed (returning `null` while the farm is invalid).
  - `WorkSlotProvider.DEFAULT`: dispatches by `Occupation` (`FARMER` → `FarmerStandByShedTask` → `StandNearShedAction`; debug stubs → `StubWorkTask`).
  - `CivilianSlotRunner`: cleanly stops an active `WORK` slot if a workplace region becomes unavailable mid-shift.
- **UX & Debug:**
  - `LedgerInteractions.onEntityInteract` handles assign / shift-unemploy and suppresses sneak-click debug info while holding a ledger (also guarded in `CivilianEntity.talkTo`).
  - `/empire debug civilian job assign [here | <regionIdPrefix>]` and `/empire debug civilian job clear`; occupation shown in `/empire debug civilian info`, `roster`, `schedule`, and worker count shown in `/empire debug region list` / `info`.

---

## 4. Verification (2026-11-26)

- **Unit & architecture tests (`:empire:compileJava :empire:compileTestJava :empire:test :test`):**
  - `:empire:test`: **241 passed, 0 failed, 0 skipped** across 38 suites (including `OccupationTest`, new `FarmerUtilityTest`, and updated `CivilianLinesTest` verifying `hired` and `unemployed` lang keys).
  - `:test` (core): **33 passed, 0 failed, 0 skipped** across 5 suites (including `ArchitectureTest`).
- **Empire standalone game test server (`:empire:runGameTestServer`, 193 tests total):**
  - All **5/5 new `FarmerJobGameTests`**, all **4/4 `FarmOccupationGameTests`**, all `FarmRegionGameTests`, all `CivilianAiGameTests`, and all `CivilianNeedsGameTests` passed on both runs.
  - Pre-existing random-origin chunk-boundary / block-light propagation flakes in unrelated `HOME` bed tests (`unreachablebedskippeduntilregionupdated`, `turningoneroomoutdoordetachesitscivilianwhileotherindoorroomstaysvalid`, `nightskipteleportsawakecitizensandleavessleepersinplace`, etc., documented previously in `civilian-fulfilment.md`) occurred across different coordinates between runs and were left untouched.
- **Not verified (left for Bright's in-game playtest):** live client feel of harvesting/replanting animations & sounds, chest lid/sound interactions, offhand ledger crop override/clear feedback, end-of-shift gear stowage, and full-day Farmer rhythm.
