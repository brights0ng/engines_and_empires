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
