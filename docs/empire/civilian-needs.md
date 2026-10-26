<!-- Written by Gemini (2026-10-26) from Bright's decisions -->
<!-- Written and implemented by ChatGPT; the requested Gemini heading above is retained verbatim, not an authorship claim. -->
# Empire: Civilian needs

**Date:** 2026-10-26 · **Status:** task 1 implemented and verified on branch `civilian-needs`. Client chat presentation is unchecked in-game.
**Scope:** ability scores, hunger, regeneration, inventory, eating. This task implements ability scores only; later prompts extend the same unreleased record VERSION 2.

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
