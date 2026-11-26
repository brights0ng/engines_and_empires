<!-- Written by Gemini (2026-11-25) from Bright's design decisions in this session. -->
# Empire: Occupations & Farmer (v1)

**Date:** 2026-11-25 · **Status:** built on branch `empire/farmer-occupation` (worktree `../EnginesAndEmpires-empire`). In-game visual and UX feel (ledger hiring/unemploying feedback, speech bubbles, farmer walking to and standing by the seed shed) remain for Bright to check in a client.
**Scope:** foundational Occupation system connecting Empire's 4-level Civilian AI (`WORK` slots) with workplace regions (`RegionType.FARM`), starting with `Occupation.FARMER` standing near their farm's seed shed during outdoor work hours.

---

## 1. Decisions (Bright, 2026-11-25)

| # | Decision |
|---|---|
| **D1** | **Ledger Assignment & Unassignment:** Select a workplace region (`FARM`) with the Administrative Ledger and **right-click** a citizen of your empire to assign them to that region's occupation (`FARMER`). **Shift-right-click** a citizen while holding the ledger to unemploy them — only allowed when that citizen's current workplace region is selected. While holding an Administrative Ledger, sneak-right-clicking a civilian **never** triggers the debug stats info dump. |
| **D2** | **Invalidation Pauses (Does Not Unassign):** If a `FARM` becomes `INVALID` (e.g. its shed is temporarily broken while remodeling), assigned farmers keep their assignment and simply pause work (`LocationKey.WORK` resolves unavailable, so they take the day off) until the farm is repaired. Workers are only detached back to `UNEMPLOYED` when their workplace region is **deleted** (last room removed, 3-day auto-expiry, debug remove, or empire disband) or when the citizen dies / is re-recruited / cleared. |
| **D3** | **Worker Capacity & Shed Targeting:** Provisional cap of **1 farmer per `FARM`** (`RegionRules.FARM_MAX_WORKERS = 1`). When a farm has multiple `SHED` rooms, the farmer targets a standable spot adjacent to the farm's **seed shed** (the room containing `farm.seed()`, falling back to the first `SHED` room). |
| **D4** | **Schedule & Vocation:** `FARMER` uses the existing scheduler defaults — outdoor shift (`04:00–16:00` via `ScheduleClock.isOutdoorTime`), `7:00` (`8,400` ticks) daily work ask scaled by traits and fulfilment, respecting meals, sleep, hobbies, and interrupts. The `Vocation` fulfilment meter stays on its `0.5` placeholder for now. |
| **D5** | **Feedback & Dialogue:** Assigning or unemploying a civilian plays a ledger page-turn sound, shows an action-bar message, and triggers a speech bubble (`CivilianLines.Category.HIRED` / `UNEMPLOYED`). Selecting or creating a `FARM` with the ledger shows its current worker count (`X/1 workers`). |

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

## 4. Verification (2026-11-25)

- **Unit & architecture tests (`:empire:compileJava :empire:compileTestJava :empire:test :test`):**
  - `:empire:test`: **237 passed, 0 failed, 0 skipped** across 37 suites (including new `OccupationTest` and updated `CivilianLinesTest` verifying `hired` and `unemployed` lang keys).
  - `:test` (core): **33 passed, 0 failed, 0 skipped** across 5 suites (including `ArchitectureTest`).
- **Empire standalone game test server (`:empire:runGameTestServer`):** **187/188 passed** — all **4/4 new `FarmOccupationGameTests`**, all `FarmRegionGameTests`, all `LedgerGameTests`, and all `CivilianAiGameTests` passed. The sole failure was the pre-existing documented timing flake `CivilianSleepGameTests.unreachableBedSkippedUntilRegionUpdated` (untouched).
- **Not verified (left for Bright's in-game playtest):** visual/UX feel of ledger hiring/unemploying feedback, speech bubbles, farm selection worker-count action bar, and watching a farmer walk to and stand beside their seed shed across a full day cycle in a live client.
