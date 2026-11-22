<!-- Written by Claude from Bright's design decisions, 2026-10-25; updated 2026-10-26; updated with Bright's 2026-11-21 decisions, implementation and verification by Gemini. -->
# Civilian fulfilment model (Empire)

Written by Claude from Bright's design decisions, 2026-10-25; updated 2026-10-26. Feeds the civilian AI (Schedule → Slot → Task → Action); the scheduler itself is a later doc.

## 0. Decisions, 2026-10-26
- **One provider per meter.** A meter whose source system doesn't exist yet returns a configurable default, **0.5 for now**, and the debug output flags it as a placeholder. Each meter switches to real data when its system lands; the formula doesn't change.
- **Comfort is in the first slice** (after a quick test that temperature can be read for civilians).
- **Sleep comes first**, as a dependency: see `docs/empire/civilian-sleep.md`. Fulfilment branches off `main` after that.

### Decisions, 2026-11-21 (Bright)
- **Shelter uses `HOME` regions' walkable indoor floor blocks per occupant:** `0` with no valid `HOME` region; otherwise `clamp(walkableIndoorFloor / (inhabitants × 25), 0, 1)`, so each citizen maxes Shelter at 25 walkable indoor floor blocks.
- **Liked foods are specific food items rather than food categories:** rolled from the data-driven item tag `empire:civilian_favourite_foods` (cooked meats & fish, prepared Naturalist foods, bread, baked potato, dried kelp, stews, cookies, pumpkin pie, honey bottle, and non-rare, non-poisonous fruits/vegetables). Food preferences are rolled, saved, and live-calculated for the food half of Enjoyment (`0.0–0.5`). A traveller's join-requirement food (`TravellerWants`) is always one of its liked foods.
- **Liked activities are rolled and saved, permanently at 50% for now:** activities (`fishing`, `pub_chat`, `relax_at_home`, `walk`) are rolled and persisted on each civilian, but not monitored yet — the activity half of Enjoyment returns a permanent 50% placeholder (`0.25`, flagged partial in debug).
- **Comfort config defaults:** comfort range `10.0–35.0 °C` (matching `ColdSweatCompat`), danger margin `15.0 °C` past either bound (`0.0` at `-5 °C` / `50 °C`), sampled via `paradyne-lib`'s `TemperatureProvider` (`air + room`, falling back to `0.5` placeholder when Meteodyne is absent or returns `NaN`).
- **Grievance scale & provisional rates:** `0.0–1.0` scale, threshold `0.75`, configurable provisional build (`1.0 / day × shortfall`) and drain (`0.5 / day` above threshold), paused whenever no empire member is online. Accumulates and is shown in debug; unrest/leaving effects come later.

## 1. Terms
- **Meter**: one need, 0–1.
- **Happiness**: weighted, gated sum of meters, 0–1.
- **Expectation E(n)**: one empire-wide value driven by civilian count n.
- **Fulfilment** = Happiness / E(n). Can exceed 1.0 (no extra benefit) and cannot reach 1.0 once E > 1.0.

## 2. Tiers, weights, meters (weights sum to 100%)

| Tier | Meter | Weight | Raw value (0–1) |
|---|---|---|---|
| Survival | Hunger | 10% | min(1, hungerBarFraction × 2): only the lower half of the bar costs happiness |
| | Sleep | 10% | `lastNight` from `CivilianSleep`: min(1, credit / need); bed ticks 1.0, rough ticks 0.5; need from Endurance (5–8 min). See `docs/empire/civilian-sleep.md` |
| | Health | 5% | current health / max health |
| Security | Safety | 10% | 1 − clamp((hits − 3 × guards) / H_ref, 0, 1). hits = empire civilians damaged by monsters **or outsider players** over the last 3 days; H_ref = 10 (config) |
| | Comfort | 5% | 1.0 inside the Cold Sweat comfort range (`10–35 °C`), falling linearly to 0.0 at the dangerous level (`±15 °C` margin, config) |
| | Shelter | 10% | 0 with no valid `HOME`; otherwise `clamp(walkableIndoorFloor / (inhabitants × 25), 0, 1)` (Bright, 2026-11-21) |
| Belonging | Community | 10% | distinct people within 16 blocks over the last 3 days / 10, cap 1 |
| | Socializing | 5% | social interactions over the last 3 days / 5, cap 1 (same or different people) |
| | Commitment | 10% | days lived in the settlement / 504 (504 in-game days ≈ 7 real days), plus the newcomer bonus (§4), cap 1 |
| Purpose | Vocation | 10% | **undefined until jobs exist** (provider default 0.5) |
| | Enjoyment | 15% | min(0.5, liked-food meals over 3 days / 12) + activity half (permanent 50% placeholder = 0.25 until activities exist). Two of each per day = max |

Tier fraction r(k) = the tier's weighted meter sum / the tier's total weight (25%).

## 3. Gates: pass-through, linear
- gate(Survival) = 1
- gate(k) = r(k−1) × gate(k−1), i.e. the product of r(j) for all j < k
- effective(k) = r(k) × gate(k) × 25%
- Happiness = Σ effective(k)
- Example: Survival 0.5 with everything else full gives Security ×0.5, Belonging ×0.5, Purpose ×0.5. Survival 0.01 shuts every tier above it down to about zero.
- Commitment stays inside the Belonging tier fraction, so it affects the gate on Purpose. Newcomers are deliberately penalised; low early E makes up for it.

## 4. Newcomer commitment bonus
- +0.5 on the Commitment meter on arrival, decaying linearly to 0 over 25 in-game days (−0.02/day).
- Commitment growth is 1/504 ≈ 0.002/day, so the combined meter falls from 0.50 to ≈0.05 at day 25, then grows normally.

## 5. Preferences
- Each civilian rolls 1–4 liked **food items** (from `empire:civilian_favourite_foods`, with vanilla fallback pool) and 1–4 liked **recreational activities**, with 2–3 most common (weights 1, 3, 3, 1).
- Activities include fishing (`fishing`), chatting at the Pub (`pub_chat`), relaxing at home (`relax_at_home`) and walks (`walk`); more to come.
- Survival hunger accepts any food. Liked food only feeds Enjoyment. No balanced-diet requirement.

## 6. Expectation E(n)
Population only. Config list of (n, E) points, linearly interpolated:

| n | 1 | 5 | 10 | 15 | 20 | 25 | 30 | 35 | 40 | 45 | 50 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| E | 0.20 | 0.35 | 0.50 | 0.60 | 0.70 | 0.80 | 0.90 | 1.00 | 1.10 | 1.20 | 1.30 |

Hard cap of 50 civilians by default (config).

## 7. Effects of fulfilment
- **Productivity**: linear 0.5× at Fulfilment 0 to 1.0× at Fulfilment 1.0, capped at 1.0. Applied at the Action level (break speed, craft time). Empires with E > 1.0 (35+ civilians) can't reach full productivity, by design.
- **Grievance**: builds while Fulfilment < T = 0.75, scaled by the shortfall; drains above T. **Paused whenever no empire member is online**, so a settlement sacked while offline gives players time to recover. Thresholds on grievance trigger complaining, then unrest, then leaving (values TBD). Fulfilment around 0.9 never complains.
- **Dialogue**: mood band from Fulfilment; topic from the meter with the largest gated shortfall, so complaints tell players what to fix.

Max sustainable population with T = 0.75 (where Happiness / E(n) = 0.75):

| Average Happiness | 1.0 | 0.9 | 0.8 | 0.7 | 0.6 |
|---|---|---|---|---|---|
| Max n | 50 (cap) | 45 | 38 | 31 | 25 |

## 8. Implementation notes
- **First slice (built on branch `empire/fulfilment`):** pure `rules.FulfilmentModel`, `rules.CivilianPreferences`, `rules.CivilianFulfilment`, `rules.EmpireSafetyTracker`; saved per-meter 3-day counters and preferences; live providers for hunger, sleep, health, safety, comfort, shelter, community, socializing, commitment, and enjoyment (liked-food half); placeholders for vocation and enjoyment's activity half; `/empire debug civilian fulfilment`; no gameplay effects yet (grievance accumulates and is shown). Next slice: dialogue mood and complaint topics.
- **Shared day clock:** `CivilianFood.countActiveTick()` returns `true` on every `DAY_LENGTH` (24,000-tick) rollover and drives `CivilianFulfilment.advanceDay()`, avoiding a second clock beside `CivilianFood`'s.
- **Update cadence:** recompute meters, sample Comfort, and step grievance every 1,200 active ticks (one schedule slot). Run proximity scans for Community every 100 ticks (5 s).
- **3-day windows:** store three daily buckets (today, yesterday, day before) for socializing, liked meals, and empire safety hits, plus three daily UUID sets for Community. Advance them on active ticks so frozen empires do not expire their windows while unloaded.
- **Comfort:** reads `paradyne-lib`'s `TemperatureProvider.get()` (`air(level, pos) + room(level, bodyMiddle)`), averaging up to 20 per-slot comfort scores (one active day). Falls back to placeholder `0.5` when Meteodyne is not loaded or returns `NaN`.
- **Shelter:** reads `RegionRecord.indoorFloorArea()` (cached count of indoor cells with a non-indoor floor directly below and indoor headroom directly above) divided by `inhabitants × 25`, clamped to `[0, 1]`.
- **Community:** counts live non-leaving civilians and online non-spectator empire members within 16 blocks.
- **Socializing:** counts player dialogue clicks, citizen food hand-over, and civilian-to-civilian conversations, at most once per partner per 1,200 active ticks (1 in-game hour).
- **Item tag:** `empire/src/main/resources/data/empire/tags/item/civilian_favourite_foods.json` (written by Gemini; JSON does not allow comments). Naturalist cooked items are marked `"required": false`.
- **Debug:** `/empire debug civilian fulfilment` (plus `eval`, `grievance <0..1>`, and `days <days>` helpers), plus summary lines in `/empire debug civilian info` and sneak + right-click stats.

## 9. Open
- Grievance thresholds and final build/drain rates.
- Vocation definition (waits on jobs).
- Dialogue mood bands and complaint topics (next slice).
- Unrest and leaving behaviour.
- Emergency overrides (starvation, raids) pulling civilians off schedule: scheduler work, later.

## 10. Verification (2026-11-21)
- **Unit tests across all modules (`compileJava compileTestJava test`):** **1418 passed, 6 skipped** (pre-existing), **0 failed, 0 errors** — including **202** in Empire (+15 new tests across `FulfilmentModelTest` and `CivilianFulfilmentTest`) and **33** in `core` (including `ArchitectureTest`).
- **Empire standalone game test server (`:empire:runGameTestServer`):** **161/161 passed** (including all 7 new tests in `CivilianFulfilmentGameTests`, verifying preference rolling from `empire:civilian_favourite_foods`, `HOME` indoor floor-per-occupant shelter, safety hits, liked-food enjoyment, community/socializing cooldown, placeholder comfort fallback without Meteodyne, grievance online/offline pause, and NBT persistence).
- **Combined runs with Meteodyne (`:coreless:runGameTestServer` and `:runGameTestServer`):** all 7/7 `CivilianFulfilmentGameTests` passed in both runs (including live `TemperatureProvider` comfort sampling with Meteodyne loaded). Pre-existing failures outside this slice were observed and left untouched: `DietGameTests.loggingInDoesNotRefillEmptyGroups` (`Connection.channel()` NPE in `core`), `CivilianSleepGameTests.unreachableBedSkippedUntilRegionUpdated` (timing margin under Meteodyne weather), and random-origin chunk-boundary straddling in `RegionGameTests` (`claimPrefersExistingHome...` / `invalidationDetaches...`).
- **Not verified:** in-game client display of `/empire debug civilian fulfilment`, `/empire debug civilian info`, and sneak + right-click stats output remain for Bright to check in-game.

