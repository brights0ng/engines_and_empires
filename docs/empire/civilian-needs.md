<!-- Written by Gemini (2026-10-26) from Bright's decisions -->
<!-- Written and implemented by ChatGPT; the requested Gemini heading above is retained verbatim, not an authorship claim. -->
# Empire: Civilian needs

**Date:** 2026-10-26 · **Status:** built and waiting for Bright's review on branch `civilian-needs`. Client presentation remains unchecked in-game.
**Scope:** tasks 1–5 implement ability scores, hunger, regeneration, carried inventory, food hand-over and vanilla item-use eating, with persistent record VERSION 2. The task sections below retain their original implementation and verification history; the overview and open items describe the finished series.

## Decisions (Bright, 2026-10-26)

- **D1** Every civilian rolls ENDURANCE, PRECISION, WILLPOWER, WITS, ORGANIZATION and TEAMWORK, each as 3d6 (3–18).
- **D2** Reroll the whole set when its mean is below 8 or above 13 (about 3.1% of sets). Both boundaries are accepted.
- **D3** Store and display raw scores; gameplay can use `(raw - 3) / 15`, normalized to 0–1.
- **D4** No gameplay effects yet: roll, save, preserve across bodies and display. Jobs do not exist yet.
- **D5** Sneak + right-click any non-leaving civilian, including travellers, sends the clicking player its name and six raw scores in chat. No dialogue, speech line or item consumption.
- **D6–D8** Vanilla food rules plus basal exhaustion; hunger continues without online members, starvation stops at half maximum health on every difficulty, and food-based regeneration obeys `naturalRegeneration`.
- **D9** Nine carried slots until Encumbered replaces them; eat from these pockets through vanilla item use, including effects, sound, particles and the player's EAT/DRINK held-item pose.
- **D10** Count nutrition eaten per active civilian day. Eat as soon as nutrition fits the missing food; meal times and storage stocking wait for the scheduler.
- **D11** Non-spectator players can stock citizens by right-clicking with main-hand food without sneaking. Survival consumes only what fits; creative consumes nothing. Travellers keep their want-click; FREE civilians take nothing.

## Provisional defaults (one named constant each)

| Constant | Value | Where |
|---|---|---|
| `MAX_REROLLS` | 1000 retries after the initial roll; keep the last set at the cap | `rules.CivilianAbilities` |
| `STARVE_FLOOR_FRACTION` | `0.5F` of maximum health | `rules.CivilianFood` |
| `DAY_LENGTH` | `24000L` active ticks | `rules.CivilianFood` |
| `civilianBasalExhaustionPerTick` | `0.0037`; SERVER config range `0.0`–`1.0` | `EmpireConfig`, section `civilianNeeds` |
| `RECORD_SYNC_INTERVAL` | 100 ticks | `civilian.CivilianEntity` |
| `INVENTORY_SIZE` | 9 slots | `civilian.CivilianEntity` |
| `EAT_CHECK_INTERVAL` | 20 ticks | `civilian.CivilianEntity` |

Decided mean bounds are named `REROLL_MIN_MEAN` (8.0) and `REROLL_MAX_MEAN` (13.0). Existing maximum health remains 30. All table entries are provisional.

## Classes added or changed

Pure rules (`dev.brights0ng.empire.rules`, no Minecraft imports):

- `CivilianAbilities` (new): immutable six-score record, `Ability` enum with lowercase ids, validation, raw/normalized/mean, capped 3d6 rolling, 24-bit packing and hyphen-separated code parsing.
- `CivilianAbilitiesTest` (new): packing, validation, normalization, 10,000 seeded rolls, whole-set retry, retry cap and codes.
- `CivilianFood` and `CivilianFoodTest` (new): vanilla food arithmetic, regeneration/starvation tick results, intake days, saved state and independent copies; 22 unit cases.
- `TravellerWantsTest`: existing language coverage extended to assert citizen hand-over replies.

Data and civilians:

- `CivilianRecord`: VERSION 2 abilities, food snapshot, health and nine copied inventory stacks; tolerant optional fields and UUID-seeded stable ability defaults.
- `CivilianFoodNbt` (new): shared body/record codec with independent defaults and bounded corrupt values.
- `EmpireSavedData`: passes registry access for civilian inventory codecs; SAVE_VERSION remains 4.
- `CivilianEntity`: roll/save/inspect abilities, tick needs and movement/damage exhaustion, persist inventory, accept citizen hand-over, deterministic death drops, eat the first fitting pocket item through vanilla use, restore or drop leftovers/interrupted meals, and write through to the current master record.
- `CivilianRoster`: recruitment copies body scores, needs and inventory; citizen join restores the master snapshot.
- `CivilianSpawning`: replacement bodies preset scores before `finalizeSpawn` and restore needs/inventory.
- `EmpireConfig`: server-side basal exhaustion setting and pre-load default getter.
- `CivilianDebugCommands`: ability/mean, food/health/intake, pocket contents and `eating <item id>` in `info`; `abilities <code>` and `food <0-20> [saturation]` setters; roster food and health.
- `client.CivilianRenderer`: resets both shared model arm poses per civilian, assigning the using EAT/DRINK arm vanilla's `ITEM` pose. Existing `HumanoidMobRenderer` supplies `ItemInHandLayer`.
- `en_us.json`: inspection message, six ability names and two citizen food hand-over placeholders (added by ChatGPT).
- `CivilianNeedsGameTests` (new, batch `civilians`): 32 tests across the series for abilities, needs, inventory, hand-over, vanilla eating, overflow, interruption and persistence. Setup and cleanup follow Claude's roster tests.
- `sparse-ores/build.gradle`: its auxiliary data/Create-test source set now consumes the library's existing `requiredRuntimeElements`, fixing the missing mandatory GeckoLib runtime discovered during the full check. No dependency versions or repositories changed.

## Open items after tasks 1–5

- Meal-time stocking and pickup from storage wait for the scheduler.
- Ability effects wait for jobs.
- Encumbered replaces the provisional nine-slot pockets.
- Bright must check the eating arm pose, eating particles, held-food render, scores chat message and hand-over lines in a client. Compilation and server tests do not verify their appearance.
- No Peaceful extras: no player-only bonus healing, food refill or saturation refill.
- No catch-up while unloaded; only active body ticks count.

## Deviations collected from prompts 1–5 and the eating-particles follow-up

The detailed task notes below preserve the original explanations. This is the complete series index, including choices originally recorded as deviations and now-resolved staging notes.

| Task | Deviation / smallest safe interpretation |
|---|---|
| 1 | Existing record constructor retained with UUID-seeded default abilities; recruitment replaces them with actual body scores. |
| 1 | Invalid upper ability bits or wrong NBT types default safely: stable UUID roll for records, fresh roll for bodies. Existing appearance defaults remain unchanged. |
| 1 | Requested Gemini heading retained verbatim, but actual authorship is ChatGPT; JSON edit attribution lives here because JSON cannot contain comments. |
| 1 | Sneak tests use the real interaction path with mock-player shift state; no extracted testing branch. Client chat remains unchecked. |
| 1 | Later needs work was staged for tasks 2–5 and is now complete; ability effects and exhaustion-warning logging remain excluded. |
| 2 | Vanilla `Player.isHurt` also requires health above zero; dead civilians cannot regenerate. |
| 2 | Deliberate half-maximum starvation floor replaces vanilla difficulty thresholds and clips final damage, including on Peaceful and with regeneration off. |
| 2 | Player-only Peaceful healing, food refill and NeoForge saturation refill are excluded. |
| 2 | `CivilianFood.eat(int, float)` accepts absolute saturation, matching the `FoodProperties` overload rather than vanilla's legacy two-argument modifier overload. |
| 2 | Pure `restore` copies state verbatim; Minecraft codec validation is separate. |
| 3 | Water movement statistics live in `ServerPlayer` in 1.21.1; copied its water branches and centimetre rounding. |
| 3 | Damage delegates to the living-entity pipeline and charges exhaustion only after health loss; preserves mitigation, absorption and hooks. |
| 3 | Missing fields default independently; corrupt ranges/nonfinite values are bounded in the codec. Legacy unknown health stays `unknown/30` until body write-back. |
| 3 | Gamerule test uses 200 synchronous `aiStep` calls with `finally` restoration; periodic sync test uses 100 real server ticks. Basal drain still runs all 1100 requested ticks at defaults. |
| 4 | Death drops retain `doMobLoot` and captured-drop hooks. Hands bypass equipment chance/enchantment checks and clear before superclass drops; armor retains vanilla behavior. Discard/unload/replacement cause no inventory-wide death drop. |
| 4 | Existing `restoreNeeds` restores inventory for join and replacement; no duplicate `CivilianSpawning` restore added. |
| 4 | Creative mock player needs explicit `GameType.CREATIVE.updatePlayerAbilities`; real interaction still checks `instabuild`. |
| 4 | Optional tolerant inventory fields retain CivilianRecord VERSION 2 and EmpireSavedData SAVE_VERSION 4. |
| 5 | `usingConvertsTo` is implemented by `Player.eat`, not `LivingEntity.eat` or base `Item.finishUsingItem`. Civilian `eat` copies only that conversion after `super.eat`; vanilla completion installs the result before `stopUsingItem` stores/drops it. |
| 5 | EAT/DRINK have no special third-person pose branch in `PlayerRenderer.getArmPose`; they fall through to `ITEM`. Use that pose on the using arm and reset both arms before each render. No hand-to-mouth animation added. |
| 5 | Vanilla does not save use progress. Entity serialization cancels an active pocket meal before superclass equipment serialization, then snapshots inventory/record; removal and LEAVING also return it. A later check retries it. |
| 5 | A cancelled NeoForge start-use event immediately returns the moved meal. Only pocket-owned meals are cleared, preserving an occupied hand's skip rule. |
| 5 | Added three lifecycle/overflow tests beyond the five requested eating cases; no tests removed, skipped or weakened. The stale project-wide split baseline is distinguished from the actual pre-series baseline in final verification. |
| 5 | The single full command returned success even though the Sparse Ores + Create launcher failed before running tests: its auxiliary source set omitted the library's mandatory GeckoLib dependency. Fixed only that source set's consumption of existing `requiredRuntimeElements` in separate commit `afeb753`, then reran only `:sparse-ores:runGameTestServerWithCreate` (13/13). Did not invoke the full command a second time. |
| Eating particles | `LivingEntity.tick` detects equipment changes before `aiStep` equips food and starts use, so the eating flag reaches the client before the hand item. Pairing also sends entity data before equipment, affecting players who start tracking mid-meal. The client-only `CivilianEntity.updatingUsingItem` branch waits for the hand item, then restores `useItem` and its duration before delegating to vanilla; server meal behavior is unchanged. In the decompiled NeoForge 21.1.251 / MC 1.21.1 Mojmap source, both fields are already `protected`, but `updatingUsingItem()` is `private`. Empire's new standard `META-INF/accesstransformer.cfg` widens only that method to `protected` so the requested override can run; no field access transformer or reflection is needed. |

## Task 1: ability scores (2026-10-26)

## Verification (2026-10-26)

- `./gradlew :empire:compileJava --console=plain -q`: passed.
- `./gradlew :empire:test --tests "dev.brights0ng.empire.rules.CivilianAbilitiesTest" --console=plain -q`: 9 passed, 0 failed, 0 skipped. Includes 10,000 seeded rolls.
- `./gradlew :empire:runGameTestServer --console=plain -q`: all 97 required tests passed, including 5 new needs tests (18 total in batch `civilians`).

Commands ran synchronously in the foreground, sequentially, using the installed Java 21 JDK via session `JAVA_HOME` (the terminal initially had none). No client visual check was performed.

## Deviations and open items

1. **Existing constructor retained.** Existing record callers receive UUID-seeded default abilities; recruitment immediately copies the body's actual scores. No unrelated callers or tests need changing.
2. **Corrupt packed scores.** All 24 payload bits are valid; nonzero upper bits are rejected. Wrong NBT types are treated as missing. Records fall back to the same stable UUID-seeded roll; bodies roll fresh. Appearance itself currently keeps its default when missing/corrupt, so only the new abilities follow the explicitly requested fresh-roll behavior.
3. **Authorship.** The requested Gemini heading is retained verbatim; this implementation and document were written by ChatGPT. JSON has no comments, so its edit attribution is recorded here while retaining valid JSON, under the explicitly requested language-file edit.
4. **Sneak tests.** Mock players use `setShiftKeyDown(true)` through the actual interaction path; no extracted branch or visibility change is needed. Client appearance of chat remains for Bright's in-game verification.
5. **Later tasks.** Hunger, regeneration, inventory and eating are scoped but not implemented in task 1. No gameplay ability effects or exhaustion warning logging are added.

## Task 2: pure food rules and configuration (2026-10-26)

Written and implemented by ChatGPT from Bright's decisions. This extends task 1 with pure food logic and a SERVER config value. Entity ticking, NBT wiring and food inventory integration remain for later tasks; no existing civilian behavior is wired to this class yet.

### Decisions (Bright, 2026-10-26)

- **D6** Hunger follows vanilla `FoodData`, with constant basal exhaustion added by the caller every active tick. Vanilla walking costs no exhaustion, so idle civilians otherwise would never get hungry.
- **D7** Hunger does not pause when no empire member is online. Starvation damages down to 50% of maximum HP and stops, on every difficulty. Unloaded civilians have no active ticks.
- **D8** Regeneration follows a player's food-based regeneration, including the `naturalRegeneration` gamerule: saturated full-food regeneration every 10 ticks, otherwise regeneration at 18 or more food every 80 ticks.
- **D10** Track nutrition points eaten per civilian day for future meal-time stocking. Count all nutrition supplied to `eat`, including any excess beyond the food cap. Days follow the civilian's own active ticks, rather than world time or member presence.

### New provisional defaults

| Constant or setting | Default | Location / range |
|---|---|---|
| `STARVE_FLOOR_FRACTION` | `0.5F` | `rules.CivilianFood`; fraction of maximum HP |
| `DAY_LENGTH` | `24000L` | `rules.CivilianFood`; active ticks per intake day |
| `civilianBasalExhaustionPerTick` | `0.0037` | `EmpireConfig`, SERVER section `civilianNeeds`; double, `0.0`–`1.0` |

The config getter returns the default before the config loads. The requested config comment is preserved verbatim. At the default rate, a day adds 88.8 exhaustion, about 22.2 saturation/food points; the two-bread comparison includes bread's saturation as well as its 10 nutrition points.

### Classes added or changed

- `rules.CivilianFood`: mutable food, saturation, exhaustion and shared tick timer; pure `TickResult` healing/damage; daily intake counters; all seven state values in plain `Saved`, static `restore`, and independent `copy`. No Minecraft imports. Basal exhaustion and `countActiveTick()` are explicit caller operations; `tick()` processes hunger and returns the body's effects.
- `EmpireConfig`: `CIVILIAN_BASAL_EXHAUSTION_PER_TICK` and `civilianBasalExhaustionPerTick()`.
- `rules.CivilianFoodTest`: vanilla boundaries and timing, branch order and shared timer, gamerule, starvation floor on Peaceful and other difficulties, intake rollover, saved state and copy independence.

### Vanilla source check

Read the decompiled MC 1.21.1 sources in `build/moddev/artifacts/neoforge-21.1.251-sources.jar`: `FoodData` (`tick`, `add`, both `eat` overloads, `addExhaustion`), `FoodProperties`, and `Player` (`isHurt`, Peaceful `aiStep`). Confirmed starting food 20, saturation 5, exhaustion 0, cap 20 food, exhaustion cap 40, strict exhaustion threshold `> 4`, one depletion per tick, regeneration thresholds 20/18, intervals 10/80, saturation cap 6 per regeneration, healing `f / 6` or 1, exhaustion `f` or 6, and starvation interval 80. No numerical mismatch was found in the requested vanilla food rules.

### Deviations

1. **Living-body check.** The prompt's shorthand `health < maxHealth` differs from vanilla `Player.isHurt()`, which also requires `health > 0`. Followed vanilla, as directed: dead bodies cannot regenerate. Covered by a test.
2. **Starvation, by design.** Replaced vanilla's difficulty rules (health above 10, any health on Hard, or health above 1 on Normal) with the provisional half-maximum-health floor, clipping the last damage to avoid crossing it. This also applies on Peaceful and with natural regeneration disabled.
3. **Peaceful player behavior deliberately excluded.** Extra player healing and food refill in `Player.aiStep` were deliberately not copied. The inspected NeoForge source also refills saturation there; that is excluded too. Peaceful retains `FoodData`'s saturation depletion and exemption from exhaustion-driven food loss.
4. **Absolute saturation, by design.** `CivilianFood.eat(int, float)` follows `FoodData.eat(FoodProperties)` / `add`, passing absolute saturation unchanged. Vanilla's legacy two-argument `eat(int, float)` instead converts a saturation modifier. The new method deliberately uses the requested absolute-amount contract.
5. **Saved form.** Restore copies supplied state verbatim, matching `FoodData`'s save/load approach; later Minecraft persistence callers must handle missing or corrupt fields. No entity integration or broader persistence policy is introduced in this task.

### Verification (2026-10-26)

- `./gradlew :empire:compileJava --console=plain -q`: passed.
- `./gradlew :empire:test --tests "dev.brights0ng.empire.rules.CivilianFoodTest" --console=plain -q`: 22 passed, 0 failed, 0 errors, 0 skipped.
- `git diff --check`: passed.

Both targeted Gradle commands ran synchronously and sequentially using the installed Java 21 JDK and NeoForge 21.1.251. Source and unit verification only; no in-game or visual verification applies to this pure-logic task.

## Task 3: body hunger, regeneration and persistent needs (2026-10-26)

Written and implemented by ChatGPT from Bright's task 3. Maximum health remains 30. Every live, non-leaving body's server `aiStep` counts an active tick, adds configured basal exhaustion, runs `CivilianFood.tick` with the natural-regeneration gamerule and Peaceful flag, then applies healing or starvation through the normal entity methods. Free civilians and travellers run the same needs as citizens.

### Classes added or changed

- `civilian.CivilianEntity`: mutable food state, `Food` entity NBT, damage/jump/water exhaustion, needs ticking, and one `syncNeedsToRecord()` write-back point for citizens through `ownRecord`. It compares the complete saved food snapshot and health before marking saved data dirty. Called every `RECORD_SYNC_INTERVAL` (**100 ticks, PROVISIONAL**), on entity save, on removal from the level, and by debug food edits. Death and stale bodies cannot write back. `restoreNeeds` applies record food and clamps known health to `[1, getMaxHealth()]`; NaN preserves the body's health.
- `data.CivilianRecord`: still VERSION 2; immutable `CivilianFood.Saved` snapshot and float health. Missing food defaults to a new food state; missing or nonfinite health is NaN. Existing constructors and older saves remain supported.
- `data.CivilianFoodNbt` (new): one codec shared by body and record. Stores all seven saved values in a compound (`food_level`, `saturation`, `exhaustion`, `tick_timer`, `eaten_today`, `eaten_yesterday`, `active_ticks`). Record keys are `food` and `health`; the entity uses `Food` and vanilla's existing health persistence.
- `civilian.CivilianRoster`: recruitment snapshots body food and health; current citizen bodies receive record needs on join.
- `civilian.CivilianSpawning`: replacement bodies receive record needs before joining the level. A missing body returns with its previous food and health.
- `command.CivilianDebugCommands`: `info` adds food, saturation (one decimal), exhaustion (two decimals), current/max health, nutrition eaten today/yesterday and active ticks. Roster lines show record food and health out of 30. `food <0-20> [saturation]` edits the nearest civilian, defaults saturation to zero, clamps it to `[0, food]`, and writes through; timers, exhaustion and intake history are retained.
- `gametest.CivilianNeedsGameTests`: 11 additional tests in batch `civilians`: starvation floor and another 200 ticks alive, slow/fast regeneration, disabled regeneration, 1100 ticks of default basal drain, replacement needs, tolerant body/record persistence and dirty-state checks, 100-tick write-back, damage/jump/leaving exhaustion, water movement, and join restoration/health bounds/unload write-back.

### Vanilla source check

Read the decompiled NeoForge 21.1.251 / MC 1.21.1 `Player.actuallyHurt`, `Player.jumpFromGround`, `LivingEntity.actuallyHurt`, and `ServerPlayer.travel/checkMovementStatistics` in `build/moddev/artifacts/neoforge-21.1.251-sources.jar`. Confirmed damage adds `DamageSource.getFoodExhaustion()` only when damage reaches health after mitigation and absorption; ordinary jumping adds `0.05F` (sprint jumping is `0.2F`, excluded here). Water movement adds `0.01F` per metre, after rounding displacement to centimetres: all three axes while swimming/submerged, horizontal axes while wading; passengers are excluded. Civilian `travel` measures its own before/after position, so teleports between ticks are not counted as movement. Sprinting, attacking and block-breaking exhaustion remain excluded.

### Deviations

1. **Movement source location.** This version puts `checkMovementStatistics` in `ServerPlayer`, rather than `Player`. Copied its water branches and rounding from that source.
2. **Damage implementation.** `actuallyHurt` delegates to the existing living-entity damage pipeline and adds source exhaustion when health decreased. This preserves NeoForge mitigation, absorption and damage hooks without duplicating the pipeline. Fully absorbed or rejected hits add no exhaustion, as in the inspected player implementation.
3. **Tolerant persistence.** Missing fields default independently; corrupt food/saturation/exhaustion/timer values are bounded to legal ranges, nonfinite floats use defaults, and intake/active-tick counters cannot load negative. This validation stays in the Minecraft codec; pure `CivilianFood.restore` retains its task 2 verbatim contract. Legacy roster health displays `unknown/30` until a body writes back, rather than inventing a healed value.
4. **Shared gamerule and test isolation.** The gamerule-off test runs 200 actual body `aiStep` calls synchronously and restores the previous value in `finally`, so other tests in the same batch cannot observe a disabled global rule. The sync test runs 100 `ServerLevel.tickNonPassenger` calls synchronously, including the real server-owned `tickCount`, and asserts that no early periodic write occurs. Other timed needs tests run normally on the game-test server; basal drain uses the full requested 1100 ticks at default config, with no direct exhaustion shortcut.

### Open items

- Hunger only runs while the body is loaded and ticking; no catch-up for unloaded time.
- Inventory and eating remain for tasks 4–5. The centralized needs write-back is ready for inventory to join it.
- Player-facing debug text and other client presentation have not been visually checked in-game.

### Verification (2026-10-26)

- `./gradlew :empire:compileJava --console=plain -q`: passed.
- `./gradlew :empire:test --tests "dev.brights0ng.empire.rules.*" --console=plain -q`: 137 passed across 21 suites, 0 failures, 0 errors, 0 skipped.
- `./gradlew :empire:runGameTestServer --console=plain -q`: all 108 required tests passed; 29 in batch `civilians`, including 16 needs tests (11 added by task 3).
- `git diff --check`: passed.

The first game-test run exposed a test fixture calling `Entity.tick` without advancing server-owned `tickCount`; the fixture now uses `ServerLevel.tickNonPassenger` and retains the assertion against early periodic writes. The added water test initially used NoAI, which disables physical travel; enabling AI for that fixture exercises real movement. The final complete targeted run passed. No existing tests were removed or weakened.

All Gradle commands ran sequentially and synchronously in the foreground using the installed Java 21 JDK and existing Gradle cache. These are source, unit and server behavior checks; no client visual verification was performed.

## Task 4: carried inventory and food hand-over (2026-10-26)

Written and implemented by ChatGPT from Bright's task 4.

### Decisions (Bright, 2026-10-26)

- **D9** Civilians carry their own inventory: nine slots, a hotbar, until Encumbered replaces it.
- **D11** Until the AI exists, any non-spectator player stocks a citizen by right-clicking with main-hand food without sneaking. As much as fits moves into its inventory; survival consumes only the amount moved, creative consumes nothing. Travellers retain their current want-click and FREE civilians take nothing. Food detection uses the entity-aware `stack.getFoodProperties(this)` hook.
- `CivilianEntity.INVENTORY_SIZE = 9` is **PROVISIONAL**.

### Classes and resources changed

- `civilian.CivilianEntity`: nine-slot `SimpleContainer`, `Inventory` entity NBT through `ContainerHelper` with registry access, food hand-over after sneak inspection, and guaranteed drops of inventory and both hands in the death-loot hook. Hands are cleared before the superclass equipment roll so they cannot drop twice. `restoreNeeds` now restores copied inventory stacks, so existing citizen join and `CivilianSpawning.respawnBody` calls restore the record inventory. `syncNeedsToRecord` compares item counts and components, copies changed stacks and marks saved data dirty; hand-over calls it immediately, including when pockets are full. Periodic/save/removal write-back and stale/dead-body guards also cover inventory.
- `data.CivilianRecord`: independent nine-slot `NonNullList<ItemStack>` snapshot, copy setter, lowercase `inventory` NBT compound; `save(HolderLookup.Provider)` and `load(CompoundTag, HolderLookup.Provider)`. Missing or wrong-type inventory defaults empty; `ContainerHelper` ignores out-of-range slots and uses tolerant `ItemStack.parse` for invalid stacks. VERSION remains 2.
- `data.EmpireSavedData`: forwards its registry provider to civilian record save/load. SAVE_VERSION remains 4; its own layout is unchanged.
- `civilian.CivilianRoster`: recruitment copies the body's inventory into its master record. Traveller want-click code is unchanged.
- `command.CivilianDebugCommands`: `info` lists every non-empty pocket as `count× item id`, or `Inventory: empty`.
- `gametest.CivilianNeedsGameTests`: updates record codec callers for registry access; adds the six requested tests and two extra tests for partial/creative/spectator transfer and body/record/saved-data persistence, independent copies, join precedence, dirty tracking and tolerant loads. Death also checks both hands drop exactly once on a non-player kill and the record is removed.
- `en_us.json`: adds the two requested placeholder citizen replies. Added by ChatGPT; JSON attribution is recorded here using the existing task 1 convention because JSON does not allow comments.
- `rules.TravellerWantsTest`: checks both citizen language keys and exact placeholder text alongside existing language coverage.

### Deviations

1. **Death gamerule and hooks.** Carried items drop deterministically through `dropCustomDeathLoot`, preserving vanilla's `doMobLoot` gamerule and NeoForge's captured-drop event. The requested guaranteed hands behavior bypasses the equipment chance roll and equipment-drop enchantment checks for hands; armor retains superclass behavior. No inventory drop occurs on discard, unload or missing-body replacement.
2. **Existing restore point reused.** Inventory is restored inside `restoreNeeds`, already called by citizen join and `respawnBody`; no separate duplicate restore call or edit to `CivilianSpawning` is necessary.
3. **Mock creative abilities.** MC 1.21.1 `GameTestHelper.makeMockPlayer(CREATIVE)` overrides `isCreative()` but leaves abilities at survival defaults. The extra creative test applies `GameType.CREATIVE.updatePlayerAbilities` before clicking, preserving the real `instabuild` consumption check used by traveller offers and vanilla player interaction.
4. **Save versions retained.** Both inventory additions are tolerant optional fields within the existing civilian record. Neither CivilianRecord VERSION 2 nor EmpireSavedData SAVE_VERSION 4 needs a bump.

### Open items

- **Encumbered replaces the 9 slots.**
- Eating from inventory remains for task 5.
- Client speech and debug text have not been visually verified in-game.

### Verification

- `./gradlew :empire:compileJava --console=plain -q`: passed.
- `./gradlew :empire:runGameTestServer --console=plain -q`: all **116 required tests passed**, including **37** in batch `civilians` and **24** in `CivilianNeedsGameTests` (eight added here).
- `./gradlew :empire:test --tests dev.brights0ng.empire.rules.TravellerWantsTest --console=plain -q`: **5 passed**, no failures, errors or skips.
- `git diff --check`: passed.

Gradle commands ran sequentially and synchronously using the installed Java 21 JDK and NeoForge 21.1.251. The first server run passed all six requested cases but exposed the extra creative fixture's missing abilities initialization; the corrected complete rerun passed. No tests were removed or weakened. Source and server behavior were verified; no client visual check was performed.

## Task 5: eat from carried inventory (2026-10-26)

Written and implemented by ChatGPT from Bright's D9/D10 continuation. A live, non-leaving civilian checks every `EAT_CHECK_INTERVAL` server ticks when not using an item and its main hand is empty. It takes exactly one item from the first slot whose entity-aware food nutrition fits `20 - foodLevel`, equips it and calls `startUsingItem(MAIN_HAND)`. No scheduler or storage pickup is added.

### Vanilla source check and lifecycle

Inspected the decompiled NeoForge 21.1.251 / MC 1.21.1 source jar: `LivingEntity.startUsingItem`, `updatingUsingItem`, `updateUsingItem`, `completeUsingItem`, `stopUsingItem`, `releaseUsingItem`, `eat` and `triggerItemUseEffects`; `Item.finishUsingItem`; `ItemStack.finishUsingItem`; `Foods.stew`; `Player.eat`; and `PlayerRenderer.getArmPose`.

Vanilla owns the use duration, synchronized item-use flags, sound/particles, finish hooks, effects and stack consumption. Civilian `eat` adds absolute nutrition/saturation and daily intake, delegates to `super.eat`, copies the narrow player-owned `usingConvertsTo` container conversion, and writes needs back. Vanilla then installs the finished stack and calls the overridden `stopUsingItem`: the owned meal's remainder goes to pockets, overflow drops at the feet, the hand clears, and the completed inventory writes back too. Early stop follows that same return path without food intake. A cancelled start-use event also returns its item. Death uses the existing hand/inventory death-loot hook from task 4, preserving its gamerule and captured drops.

LEAVING and non-death removal stop owned meals before write-back. Saving also stops the meal before equipment serialization, since vanilla does not serialize use progress and the master inventory must not omit or duplicate held food on reload. A future interval can retry an interrupted meal. The hand-ownership flag is transient; tools or other occupied main hands are skipped.

The renderer resets both arm poses for each entity and sets the EAT/DRINK using arm to vanilla's fall-through `ITEM` pose, respecting the main arm and used hand. Its inherited `ItemInHandLayer` remains in place. No third-person hand-to-mouth animation is introduced. `info` adds `eating <item id>` during use. All client appearance remains for Bright to verify.

### Targeted verification

- `./gradlew :empire:compileJava --console=plain -q`: passed.
- `./gradlew :empire:runGameTestServer --console=plain -q`: **124/124** required tests passed, **45** in batch `civilians`, **32** in `CivilianNeedsGameTests`.
- Five requested tests: `eatsFromInventory` (observes active use before completion), `doesNotWasteFood` (all 100 ticks), `picksFirstFittingSlot`, `bowlComesBack` (also checks immediate record write-back), `noEatingWhileLeaving`.
- Three additional tests: `interruptedMealsSurviveSaveAndRemoval`, `fullPocketsDropFinishedBowl`, `leavingAndDeathInterruptMeals`.

All commands run sequentially and synchronously in the foreground with Java 21. The initial sandboxed compile could not open the Gradle cache lock; the same command passed with cache access. No client was launched.

## Final series verification (2026-10-26)

Invoked `./gradlew compileJava compileTestJava test runGameTestServer --console=plain` **exactly once**, before the final commit. It returned exit 0 / `BUILD SUCCESSFUL` in 3m 9s. Java compilation, unit tests and ten game-test runs passed, but log inspection caught a pre-test dependency failure in the eleventh run, Sparse Ores + Create. Gradle's successful exit did not establish that server's success. After the narrow build-wiring fix in `afeb753`, the isolated `./gradlew :sparse-ores:runGameTestServerWithCreate --console=plain -q` returned exit 0 and **13/13 passed**. The final matrix below uses that corrected run; the full command was not repeated. All Gradle invocations were sequential and synchronous.

### Totals before and after prompts 1–5

The pre-series point is `bf42b7c`, the parent of task 1. Its source contains **1450 unit `@Test` methods** and **364 game tests**; the existing aggregate log from 2026-10-25 also records 364/364 passing. Pre-series counts below are reconstructed from that source and existing evidence, not a newly executed baseline. `docs/PROJECT.md`'s historical split baseline (1066 unit / 227 aggregate game tests) predates this branch's civilian work and is not the starting point for these five prompts.

| Suite / server | Before series | After series | Added |
|---|---:|---:|---:|
| Unit tests, all modules | 1450 (2 existing benchmark skips) | 1481 (same 2 skips) | 31 |
| Empire unit tests | 116 | 147 | 31 |
| Aggregate game tests | 364/364 | 396/396 | 32 |
| Coreless combined game tests | 314/314 | 346/346 | 32 |
| Empire standalone game tests | 92/92 | 124/124 | 32 |
| Empire `civilians` batch | 13 | 45 | 32 |
| Frontier standalone | 17/17 | 17/17 | 0 |
| Meteodyne standalone | 98/98 | 98/98 | 0 |
| Paradyne Lib standalone | 3/3 | 3/3 | 0 |
| Sable Fixes standalone | 2/2 | 2/2 | 0 |
| Seismic standalone | 20/20 | 20/20 | 0 |
| Spoilage standalone | 34/34 | 34/34 | 0 |
| Sparse Ores standalone | 13/13 | 13/13 | 0 |
| Sparse Ores + Create | 13/13 | 13/13 (isolated corrected run) | 0 |

The XML unit reports total **1479 passed, 2 skipped, 0 failures, 0 errors**. Across the eleven game-test configurations, final passing executions total **1066**, versus the pre-series **970**; the 32 new Empire tests each run in Empire, aggregate and coreless (96 additional executions). There are **396 distinct aggregate game tests**, not 1066 distinct cases.

| Prompt | New unit tests | New game tests |
|---|---:|---:|
| 1: abilities | 9 | 5 |
| 2: pure food | 22 | 0 |
| 3: needs ticking | 0 | 11 |
| 4: inventory / hand-over | 0 | 8 |
| 5: eating | 0 | 8 |
| Total | 31 | 32 |

Source diff against `bf42b7c` adds the two unit suites and the needs game-test class. The only changed existing unit test gains two language assertions; no existing game-test class changes. No tests were removed, newly skipped or weakened. `git diff --check` passed.

Verification logs (local, ignored build artifacts): `build/civilian-needs-task5-gametest.log`, `build/civilian-needs-full-check.log`, `build/civilian-needs-create-recheck.log`; unit XML under each module's `build/test-results/test/`.

### Commits on `civilian-needs` for this series

- `f392e50` — Empire: roll and save civilian ability scores.
- `f936243` — Empire: civilian food rules after vanilla FoodData.
- `ef468f5` — Empire: civilian hunger, regeneration and the starvation floor.
- `2d1d439` — Empire: civilian inventory and food hand-over.
- `afeb753` — Build: include required library runtime in Sparse Ores auxiliary runs.
- Final task 5 commit: **Empire: civilians eat from their inventory** (contains this document).

Bright still needs to verify **eating pose, eating particles, held-food render, scores chat message and food hand-over lines in a client**. No client boot, visual quality or animation quality is claimed by the source and server checks.

## Eating-particles follow-up verification (2026-10-26)

- `./gradlew :empire:compileJava --console=plain -q`: passed with the method access transformer above.
- `./gradlew :empire:runGameTestServer --console=plain -q`: **124/124** required tests passed, including **45** in batch `civilians` and all **32** in `CivilianNeedsGameTests`. No tests changed.
- `git diff --check`: passed.

Both Gradle commands ran sequentially and synchronously in the foreground using Java 21 and NeoForge 21.1.251 / MC 1.21.1. The sandbox initially denied the Gradle wrapper cache lock; compilation passed with cache access. The game-test log is `build/civilian-eating-particles-gametest.log` (local, ignored). No client was launched; eating particles, including when tracking a civilian mid-meal, remain for Bright's in-game visual check.
