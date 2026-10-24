<!-- Written by Claude (2026-10-24) from Bright's decisions in this session. -->
# Empire: Civilian chunk loading and catch-up plan

**Date:** 2026-10-24 · **Status:** design agreed, not built. Phases 0–1 can be built now; phase 2 needs the civilian entity.
**Design source:** `docs/empire/empire-plan-brainstorming.md` §5, Pass 4 (Bright's decisions, 2026-10-24). Bright's decisions override this doc where they differ.
**Out of scope:** civilian AI, low-LOD simulation, outposts, civilians on moving vehicles, Frontier's side of "civilians count as habitation".

---

## 1. Decisions (Bright, 2026-10-24)

| # | Decision |
|---|---|
| D1 | **Option 3 with catch-up, without keeping land in memory.** Civilians keep the chunks around themselves running; all other empire land unloads normally. Growth an unloaded empire chunk missed is caught up when it runs again. Option 1 (all land loaded) is out: late-game empires reach about **200 Dominion**, mostly farms. Option 2 (block ticks everywhere) is out on performance. |
| D2 | Offline loading is a **civilian** perk, not an empire-wide one. |
| D3 | An empire keeps working while none of its members is online (its god's hunger stays paused). Intended: it keeps members who play less often from falling far behind. |
| D4 | Civilians **count as habitation** for Frontier. |
| D5 | Civilians load **empire chunks only**. A civilian outside the empire with no player near is moved back to the empire. Civilians don't work outside empire land for now. |
| D6 | Loading spreads one chunk past the border (block updates only, no entities) when a civilian stands in an edge chunk. **Accepted.** |
| D7 | Catch-up: **vanilla saplings are left out.** Dynamic Trees grows only when the chunks around it are running (§3.3). |
| D8 | A server config enables civilian loading, or limits it to empires with a member online. |

## 2. How Minecraft loads chunks (the facts this rests on)

| Ticket level | In the chunk |
|---|---|
| ≤ 31 | Everything runs, entities included |
| 32 | Block updates and block entities run; entities are frozen |
| 33 | Kept in memory; nothing runs |

- A chunk at level L holds its neighbours at L + 1 or lower. A 3×3 at 31 gives a 5×5 at ≤ 32 and a 7×7 in memory.
- **Random ticks** (crop growth) happen only in a running chunk that has a non-spectator player within 128 blocks of its centre, or (NeoForge) a forced ticket marked *ticking*. **Unverified:** phase 0 confirms the second half.
- Natural spawning refuses any mob farther than 128 blocks from the nearest player, so an offline empire gets no new hostile mobs. Mobs already there keep running. Non-persistent mobs more than 128 blocks from a player are removed while any player is in the dimension. **Civilians must be persistent.**
- Dynamic Trees (1.21.1 source, `release/1.21.1`):
  - Rooty soil (`SoilBlock.randomTick`) grows its tree one step per tick, scaled by DT's `treeGrowthMultiplier`.
  - DT saplings (`DynamicSaplingBlock.randomTick`) turn into a tree.
  - Each growth tick first checks `ChunkTreeHelper.isSurroundedByLoadedChunks`: all 8 blocks around the soil must be in entity-ticking chunks. Otherwise the tick does nothing.
  - DT ships the block tags `#dynamictrees:rooty_soil` and `#dynamictrees:saplings`.

## 3. Design

### 3.1 Catch-up (no civilians needed)
Every staked chunk keeps two timestamps in `EmpireSavedData`: the last game time it was **growing** and the last time it was **growing and surrounded**.
- **Growing:** the chunk runs block updates (`ServerLevel.shouldTickBlocksAt`), and either Empire's own civilian ticket holds it or a non-spectator player is within 128 blocks of its centre. This copies vanilla's rule; Empire computes it itself, so no access transformer is needed.
- **Surrounded:** growing, and all 8 neighbouring chunks are entity-ticking. This is Dynamic Trees' condition, applied per chunk.

Every `SCAN_TICKS` the scanner checks each staked chunk of every empire:
- **Growing now, with a gap since it last grew:** queue a catch-up for the gap. The same applies to the surrounded timestamp.
- **Growing now:** set the timestamp to now.

A queued chunk is processed within a per-tick time budget:
- For each section that may hold a tagged block (`LevelChunkSection.maybeHas`), find each tagged block.
- Call its own `randomTick` *n* times. *n* is drawn from a Poisson distribution with λ = gap × `randomTickSpeed` / 4096 (vanilla's chance per block), capped.
- A block that changed into something untagged stops early.
- Calling the block's own tick keeps modded crops, Farm & Charm, seasons and temperature in charge of the result. They see the conditions at catch-up time, not the conditions during the gap; that is an accepted approximation.

**Block tags:**

| Tag | Contents | Caught up when |
|---|---|---|
| `empire:catches_up` | `#minecraft:crops`, stems, sweet berry bush, cocoa, sugar cane, cactus, bamboo, nether wart; Farm & Charm crops not already in a crop tag | growing |
| `empire:catches_up_surrounded` | `#dynamictrees:rooty_soil`, `#dynamictrees:saplings` (both `required: false`) | surrounded |

Vanilla saplings are in neither (D7).

**Lifecycle:**
- A newly staked chunk starts at "now", so staking never pays out growth for time before the claim.
- Unstaking removes the chunk's timestamps.
- If the empire is disbanded, its timestamps go with it.

### 3.2 Civilian loading (phase 2)
- **Tickets:** a NeoForge `TicketController` with id `empire:civilians`. Tickets are owned by the **empire** UUID, and are *ticking* tickets so random ticks run (if phase 0 confirms they do).
- **Wanted set:** each empire wants the staked chunks among the 3×3 around each of its civilians. Only staked chunks get tickets (D5).
- **Recompute** every `LOAD_TICKS`. A chunk no longer wanted keeps its ticket until it has been unwanted for `KEEP_TICKS`, so civilians walking back and forth across a chunk border don't reload it.
- **Config** `civilianLoading`, one of:
  - `ALWAYS`;
  - `MEMBER_ONLINE`: only empires with a member online;
  - `OFF`: civilians freeze with their chunks.
- **Restarts:** NeoForge saves forced tickets. Its validation callback drops tickets whose empire is gone or whose mode now forbids them. `EmpireSavedData` keeps a civilian registry (civilian UUID → last chunk), so tickets can be rebuilt before civilian entities load.
- **Moving civilians back (D5):**
  - While a civilian is outside empire land, it holds a memory-only anchor ticket on its own chunk (a custom `TicketType`, level 33). The chunk can't unload under it, and nothing there runs.
  - Every `LOAD_TICKS`, a civilian that is outside with no non-spectator player within 128 blocks is teleported to the nearest staked chunk: the surface at the point of that chunk closest to the civilian. Its anchor is released.
  - After a restart, a registry entry outside the empire briefly loads that chunk to move the civilian home.
- **Habitation (D4):** Frontier counts civilians as habitation. That change lives in Frontier and is specified with the civilian entity.

### 3.3 Pure rules (`rules`, no Minecraft imports, unit-tested)
- `CatchUpMath`: ticks to run for a gap (Poisson draw from an injected uniform source, cap applied, λ = 0 → 0).
- `GrowthClock`: per-chunk timestamps; returns the gap to catch up and updates the timestamp.
- `LoadPlan`: wanted set (civilian chunks → staked 3×3s), keep-delay bookkeeping (adds, removals due), mode filter.

## 4. Provisional defaults (to be confirmed by Bright)
Each is one constant. Build exactly these; stop and ask about any gap not listed.

| # | Gap | Provisional default |
|---|---|---|
| P1 | `SCAN_TICKS` (growth scan) | 20 |
| P2 | Catch-up budget per server tick | 2 ms (checked with `System.nanoTime` between blocks) |
| P3 | Cap on ticks per block | 64 for `catches_up`; 32 for `catches_up_surrounded` (DT growth steps are heavier) |
| P4 | `LOAD_TICKS` (ticket recompute, move-back check) | 20 |
| P5 | `KEEP_TICKS` | 600 (30 s) |
| P6 | `civilianLoading` default | `ALWAYS` |
| P7 | Which empire chunks catch up | **every staked chunk**, with or without civilians. *Open:* D2 makes loading a civilian perk; catch-up for land no civilian visits may go further than intended. Bright to confirm. |

## 5. Phases
Each phase ends green on targeted tests. **Stop for Bright's review after each phase.**

| Phase | Deliverable | Proof |
|---|---|---|
| **0 Verify** | Game test only, no feature code. Raise `randomTickSpeed`, then: wheat in a chunk held by a NeoForge *ticking* ticket, far from any player, grows; wheat in a chunk held by a plain region ticket at the same level doesn't. | `:empire:runGameTestServer`. If the first wheat doesn't grow, stop: §3.2's ticket type must change. |
| **1 Catch-up** | `CatchUpMath`, `GrowthClock`, the timestamps on `EmpireSavedData` (saved, version bump), the scanner and queue, the two tags | Unit: Poisson cap/zero/mean; gap bookkeeping. Game tests: an empire's wheat chunk with a set gap reaches full age on catch-up; untagged blocks untouched; a newly staked chunk gets no back pay; an unstaked chunk drops its timestamps; the surrounded tag waits while a neighbour is not entity-ticking |
| **2 Civilian loading** | `TicketController`, `LoadPlan`, keep delay, config, civilian registry, restart validation, anchor tickets and moving civilians back | Needs the civilian entity. Unit: `LoadPlan`. Game tests: wanted set excludes unstaked chunks; ticket kept for `KEEP_TICKS` then dropped; `OFF` gives no tickets; a civilian outside with no player near is moved into staked land |

Finish: full check once (`./gradlew compileJava compileTestJava test runGameTestServer`), then commit.
