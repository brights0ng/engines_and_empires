<!-- Written by Gemini (2026-10-26) from Bright's decisions -->
<!-- Written and implemented by ChatGPT; the requested Gemini heading above is retained verbatim, not an authorship claim. -->
# Empire: Civilian needs

**Date:** 2026-10-26 · **Status:** tasks 1–3 implemented on branch `civilian-needs`; verification for each is below. Client chat presentation is unchecked in-game.
**Scope:** ability scores, hunger, regeneration, inventory, eating. Tasks 1–3 implement abilities, pure food rules, entity needs and record persistence; later prompts extend the same unreleased record VERSION 2 with inventory and eating.

## Decisions (Bright, 2026-10-26)

- **D1** Every civilian rolls ENDURANCE, PRECISION, WILLPOWER, WITS, ORGANIZATION and TEAMWORK, each as 3d6 (3–18).
- **D2** Reroll the whole set when its mean is below 8 or above 13 (about 3.1% of sets). Both boundaries are accepted.
- **D3** Store and display raw scores; gameplay can use `(raw - 3) / 15`, normalized to 0–1.
- **D4** No gameplay effects yet: roll, save, preserve across bodies and display. Jobs do not exist yet.
- **D5** Sneak + right-click any non-leaving civilian, including travellers, sends the clicking player its name and six raw scores in chat. No dialogue, speech line or item consumption.

## Provisional defaults (one named constant each)

| Constant | Value | Where |
|---|---|---|
| `MAX_REROLLS` | 1000 retries after the initial roll; keep the last set at the cap | `rules.CivilianAbilities` |

Decided mean bounds are named `REROLL_MIN_MEAN` (8.0) and `REROLL_MAX_MEAN` (13.0). No hunger, regeneration, inventory or eating defaults are introduced yet.

## Classes added or changed

Pure rules (`dev.brights0ng.empire.rules`, no Minecraft imports):
- `CivilianAbilities` (new): immutable six-score record, `Ability` enum with lowercase ids, validation, raw/normalized/mean, capped 3d6 rolling, 24-bit packing and hyphen-separated code parsing.
- `CivilianAbilitiesTest` (new): packing, validation, normalization, 10,000 seeded rolls, whole-set retry, retry cap and codes.

Data and civilians:
- `CivilianRecord`: VERSION 2, packed `abilities`; UUID-seeded stable default for missing or corrupt abilities. Each new field loads independently, so later VERSION 2 additions can default on missing keys.
- `CivilianEntity`: rolled or preset abilities, entity NBT `Abilities`, getter/setter writing through to its own citizen record, server-side sneak inspection before traveller and dialogue handling.
- `CivilianRoster`: recruitment copies body scores; join restores the record's master scores.
- `CivilianSpawning`: replacement bodies preset scores before `finalizeSpawn`.
- `CivilianDebugCommands`: `info` prints code and mean to one decimal; `abilities <code>` edits the nearest civilian through its setter.
- `en_us.json`: inspection message and six ability display names (added by ChatGPT).
- `CivilianNeedsGameTests` (new, batch `civilians`): recruitment and write-through, respawn, stable v1 migration, entity NBT repair and sneak inspection with food/name tags. Setup and cleanup follow Claude's roster tests.

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
