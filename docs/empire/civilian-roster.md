<!-- Written by Claude (2026-10-24) from Bright's decisions in this session. -->
# Empire: Civilian roster and travellers

**Date:** 2026-10-24 · **Status:** built on branch `civilian-roster`. Speech bubbles with arguments (the want lines) are unchecked in a client; Bright checks them.
**Scope:** a saved record for each citizen (the master copy; the entity is its body), travellers that arrive and join an empire, death, lost bodies, and disbanding.

## Decisions (Bright, 2026-10-24)

- **D1** Every citizen has a `CivilianRecord` in `EmpireSavedData`. It is the master copy and the entity is its body. It is a mutable class (fields such as skills get added later) and versioned.
- **D2** Travellers come to a lit main bonfire until the empire has 3 citizens. Past 3 a Pub is needed (not built yet), so arrivals simply stop at 3.
- **D3** A member accepts a traveller by right-clicking it with the food it wants. Each traveller rolls one of: bread, baked potato, cooked porkchop, cooked mutton, sweet berries, cooked cod.
- **D4** Arrivals: one per in-game day, only while all of these hold: the main bonfire is lit; a member is within 64 blocks of it; the empire has fewer than 3 citizens; no traveller is already waiting for it. The traveller appears at the edge of the empire's land and walks to the bonfire. If it isn't accepted within one in-game day, it walks off and vanishes.
- **D5** Death is permanent: the record is deleted and online members get a chat message. The Downed state comes later.
- **D6** Missing body (the record is alive but the entity is gone: chunk rollback, a command, etc.): a new body appears with the same name and look. Not a death.
- **D7** Disbanded empire: its citizens walk off and vanish. Frozen empire (no members left): its citizens stay.

## Provisional defaults (one named constant each)

| Constant | Value | Where |
|---|---|---|
| `PIONEER_LIMIT` | 3 | `rules.ArrivalRule` |
| `ARRIVAL_INTERVAL` | 24000 ticks | `rules.ArrivalRule` |
| `ARRIVAL_CHECK` | 100 ticks | `civilian.TravellerArrivals` |
| `MEMBER_NEAR` | 64 blocks (horizontal) | `civilian.TravellerArrivals` |
| `TRAVELLER_WAIT` | 24000 ticks | `civilian.TravellerArrivals` |
| `TRAVELLER_RADIUS` | 8 | `civilian.CivilianEntity` |
| `LEAVE_DISTANCE` | 48 | `civilian.CivilianEntity` |
| `LEAVE_TIMEOUT` | 1200 ticks | `civilian.CivilianEntity` |
| `MISSING_CHECK` | 200 ticks | `civilian.CivilianRoster` |
| `MISSING_STRIKES` | 2 | `civilian.CivilianRoster` |
| want counts | bread 3, baked potato 4, cooked porkchop 2, cooked mutton 2, sweet berries 8, cooked cod 3 | `rules.TravellerWants.WANTS` |
| freshness | not checked (any spoilage stage is accepted) | `CivilianRoster.travellerClicked` |

Fixed by the brief and kept as private constants: a lost slot is cleared 1200 ticks after its leave time, up to 10 edge chunks are tried for a spawn spot, a waiting traveller announces its want within 6 blocks of the bonfire, a leaving civilian vanishes when no player is within 24 blocks.

## Classes added or changed

Pure rules (`dev.brights0ng.empire.rules`, no Minecraft imports, unit tested):
- `TravellerWants` (new): the six wants in order, `pick`, `wantKey`. Test: `TravellerWantsTest` (also checks the want keys are in `en_us.json`).
- `ArrivalRule` (new): `canArrive` and the D4 gates, `PIONEER_LIMIT`, `ARRIVAL_INTERVAL`. Test: `ArrivalRuleTest`.

Data (`dev.brights0ng.empire.data`):
- `CivilianRecord` (new): id, empire, body, name, appearance, last position and chunk, joined time; `save`/`load` with a `version`.
- `TravellerSlot` (new): the waiting traveller's body, want and leave time.
- `EmpireSavedData`: save version 3 (version 2 files load with no civilians); `civilians` and `travellers` lists; the empire-to-civilians index (rebuilt on load); `addCivilian`, `removeCivilian`, `civilian`, `civiliansOf`, `allCivilians`, `citizenCount`, `traveller`, `setTraveller`. Disbanding drops the records and slot and calls `CivilianRoster.onDisband`; both main bonfire changes go through `onMainBonfireChanged`.
- `EmpireRecord`: `nextTravellerTime`, saved as `next_traveller` (default 0).

Civilians (`dev.brights0ng.empire.civilian`):
- `CivilianEntity`: saved `Status` (FREE, TRAVELLER, CITIZEN, LEAVING), `CivilianId`, `TravellerEmpire`, `Want`, `LeaveAt`; no empire from the spawn chunk any more; citizens keep their record's last position and look current; death hook; leaving walk; traveller expiry and want line; traveller clicks before the dialogue; `sayKey(key, args...)`.
- `CivilianRoster` (new): join-time refusal of orphan and duplicate bodies, `homeFor`, `refreshHomes`, `onDisband`, death, `travellerClicked`, `recruit`, the missing-body check.
- `TravellerArrivals` (new): the arrival check, edge spawn spots, `spawnTraveller`, `force` (the debug path).
- `CivilianSpawning`: a `beforeAdd` hook (a slot or record must name the body before it joins), `respawnBody`.
- `CivilianNetwork`, `CivilianSpeechStore`: lines carry text arguments (network version 2).

Elsewhere: `client/CivilianBubbleRenderer` passes the arguments to `Component.translatable`; `command/EmpireCommand` (`info` line `Civilians: N/3`, new `/empire civilians [player]`); `command/CivilianDebugCommands` (`traveller`, `recruit`, `roster`, `clear` dropping records and slots, `info` showing status, civilian id and empire); lang lines in `en_us.json`; `gametest/CivilianRosterGameTests` (batch `civilians`); `MembershipGameTests` now expects save version 3.

## Deviations and open items

1. **Traveller with no home.** The brief has a waiting traveller leave when its empire "has neither a main bonfire nor a core chunk". Every empire always has a core chunk (`EmpireRecord.coreChunk()` falls back to the last main bonfire's chunk, then the founding chunk), so that case cannot happen and is not coded. Bright: is there a case meant here, such as leaving when the main bonfire is lost?
2. **`civiliansOf` returns records**, not ids (unmodifiable `List<CivilianRecord>`); every caller wanted the records. `allCivilians()` was added for the missing-body check.
3. **`onDisband` also takes the removed traveller slot**, so a disbanded empire's loaded waiting traveller walks off too, like its citizens.
4. **`runMissingCheck` is public**, not package-private: the game tests live in `empire.gametest`, a different package.
5. **The forced arrival** (`/empire debug civilian traveller`) skips only the day timer, so it also needs a member within 64 blocks. It reports the first failed gate in this order: no main bonfire, citizens at the cap, a traveller waiting, bonfire unlit, no member near, no spawn spot.
6. **Bodies the roster no longer backs while loaded.** A loaded citizen whose record is gone (an empire disbanded with no server to tell it, as some game tests do) starts leaving; a loaded citizen that is no longer its record's body discards itself.
7. **Replacement body spot.** At home it spawns on the surface 2 blocks east of the home, so it does not appear in the bonfire's fire.
8. **The want announcement** is made the first time the traveller is within 6 blocks of the bonfire *and* a player is within `SPEECH_RADIUS`; if no player is near on arrival it waits for one. It is not saved, so a reload can repeat it once.
9. **Traveller clicks** always get a line (no 10-tick click gap), and the wanted food must be in the main hand. Travellers still say ambient lines and greetings; only the dialogue's greeting and reply are replaced by the want line.
10. **Leaving civilians** consume a click and do nothing.
11. **Only the look writes through** to the record (as the brief says); a renamed citizen keeps its record name and gets it back when it next joins.
12. **Records of empires that no longer exist** are not pruned on load (disbanding removes them, so none should exist).
13. **Unrelated working-tree change.** `client/CivilianBubbleRenderer.java` already had an uncommitted change (its Z offsets) before this branch; it is left uncommitted, and the branch only commits the `translatable(key, args)` line.
