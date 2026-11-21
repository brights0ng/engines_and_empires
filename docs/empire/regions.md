<!-- Written by Gemini (2026-11-20) from Bright's decisions in this session; updated with implementation and verification by Gemini (2026-11-20); updated with shared veil/chalk region rendering by Gemini (2026-11-20). -->
# Empire: Regions (framework, `HOME`, and in-world rendering)

**Date:** 2026-11-20 · **Status:** built (phases R1–R4 + shared veil/chalk rendering). In-game visual check remains for Bright.
**Scope:** arbitrary 3D `BlockPos` plots of empire land assigned to a purpose, a pure flood-fill engine, lifecycle/invalidation, the first region type (`HOME`) wired into citizen bed claiming (`CivilianBeds`), and in-world 3D region rendering sharing the boundary chalk/veil renderer.

---

## Decisions (Bright, 2026-11-20)

- **D1 Shape & storage:** Regions are arbitrary 3D sets of `BlockPos` coordinates (not restricted to cuboids), stored as packed `long[]` / `LongOpenHashSet` with cached bounds and touched chunks.
- **D2 Creation:** Regions can be created manually (future Administration Ledger item) or automatically via flood fill (triggered by events such as a citizen claiming a bed, or by the Ledger's auto-select).
- **D3 Territory & overlaps:** Every block in a region must be inside the empire's **staked chunks**. Regions are **disjoint** (a `BlockPos` belongs to at most one region; flood fill treats existing region blocks and unstaked chunks as boundaries).
- **D4 `HOME` flood-fill whitelist, boundaries & hazards:**
  - **Cap:** 500 blocks maximum (`HOME_MAX_BLOCKS = 500`); exceeding the cap aborts the fill (`TOO_LARGE`).
  - **Whitelisted (`INCLUDE`):** air, beds (`#minecraft:beds`), torches (regular/wall/soul/redstone), lanterns (regular/soul), carpets (`#minecraft:wool_carpets`), chests/trapped chests, doors (`#minecraft:doors`), and trapdoors (`#minecraft:trapdoors`) — allowing multi-room homes through interior doors. Stored in data-driven block tag `empire:region_home_interior`.
  - **Boundaries (`BOUNDARY`):** non-whitelisted blocks (full blocks such as crafting tables/furnaces/walls, plus structural pieces such as glass panes, fences, slabs, stairs), sky-exposed blocks (`canSeeSky`), unstaked chunks, and blocks belonging to another region.
  - **Hazards (`HAZARD`, early exit):** blacklisted blocks (`empire:region_home_hazards`, default: `minecraft:lava`, `minecraft:tnt`) abort the fill immediately.
- **D5 `HOME` validation rules:**
  - Volume: `50 <= blocks <= 500`.
  - Height: at least 2 blocks tall (`maxY - minY + 1 >= 2`, with at least one 2-block-tall interior column).
  - Beds: at least 1 bed (`BedPart.HEAD`) inside the region.
  - Sky: no block in the region is exposed to the sky (`!canSeeSky`). Windows are allowed (sky light through glass is fine; direct sky exposure is not).
  - Lighting: no spawnable floor spot (non-colliding, non-carpet interior block directly above a sturdy solid non-region block) has `LightLayer.BLOCK == 0` (monster-spawn light check).
  - Hazards: no hazard block inside or 6-adjacent to the region.
  - Territory: all blocks in staked chunks of the empire.
- **D6 `HOME` capacity:** `capacity = blockCount < 50 ? 0 : Math.min(4, (blockCount - 25) / 25)`.
  - 50–74 blocks → 1 citizen
  - 75–99 blocks → 2 citizens
  - 100–124 blocks → 3 citizens
  - 125–500 blocks → 4 citizens (hard cap)
- **D7 Invalidation & expiry:**
  - Regions track `valid` + `invalidReason` + `invalidSinceGameTime`.
  - When a region becomes `INVALID`, all attached civilians (e.g. `HOME` inhabitants) are immediately detached and their claimed beds inside that region are released.
  - If fixed before expiry, the region returns to `VALID`.
  - `AUTO` regions remain `INVALID` for **3 MC days** (`72_000` ticks) before being automatically deleted.
  - `MANUAL` regions are never auto-deleted.
- **D8 Bed claiming (`CivilianBeds`):** For now, citizens may **only** claim or sleep in beds inside a `VALID` `HOME` of their empire with spare capacity; otherwise they fall back to rough sleep by the bonfire.

---

## Provisional defaults (one named constant each)

| Constant | Value | Where |
|---|---|---|
| `HOME_MAX_BLOCKS` | `500` | `rules.RegionRules` |
| `HOME_MIN_BLOCKS` | `50` | `rules.RegionRules` |
| `HOME_MIN_HEIGHT` | `2` | `rules.RegionRules` |
| `HOME_MAX_CAPACITY` | `4` | `rules.RegionRules` |
| `INVALID_AUTO_DELETE_TICKS` | `72_000L` (3 MC days) | `rules.RegionRules` |
| `MIN_BLOCK_LIGHT` | `1` | `region.HomeRegionScanner` |
| `VALIDATE_INTERVAL` | `100` ticks | `region.RegionManager` |

---

## Architecture

### Pure rules (`dev.brights0ng.empire.rules`, no Minecraft imports)
- `RegionType` (enum): `HOME` (v1; later `PUB`, `FARM`, `LARDER`, `WORKSHOP`, etc.).
- `RegionOrigin` (enum): `AUTO`, `MANUAL`.
- `RegionInvalidReason` (enum): `NONE`, `TOO_SMALL`, `TOO_LARGE`, `TOO_SHORT`, `NO_BED`, `EXPOSED_TO_SKY`, `TOO_DARK`, `HAZARD`, `UNSTAKED`, `OVERLAPS_EXISTING`.
- `RegionFloodFill`: 6-neighbour BFS over packed block coordinates with a `maxBlocks` cap and a `Classifier` returning `INCLUDE`, `BOUNDARY`, or `HAZARD`. Returns either the filled `LongSet` or a failure reason (`TOO_LARGE`, `HAZARD`, etc.).
- `RegionRules`: `homeCapacity(int blockCount)` and `shouldAutoDelete(RegionOrigin, boolean valid, long invalidSince, long gameTime)`.

### Data & persistence (`dev.brights0ng.empire.data`)
- `RegionRecord` (new): `id`, `empireId`, `type`, `origin`, `LongOpenHashSet blocks` (saved as `long[]`), cached `minX..maxZ` bounds & touched chunk keys, `Set<UUID> inhabitants`, `boolean valid`, `RegionInvalidReason invalidReason`, `long invalidSinceGameTime`.
- `EmpireSavedData`: bump `SAVE_VERSION` from `4` to `5` (tolerant load of v4 files with no regions). Stores regions by `UUID`, empire → regions index, and transient `Long2ObjectMap<UUID>` (`blockPos → regionId`) spatial index rebuilt on load. `disbandEmpire` deletes all empire regions; `unstakeChunk` marks touching regions dirty/invalid.
- Data-driven tags under `empire/src/main/resources/data/empire/tags/block/`:
  - `region_home_interior.json`
  - `region_home_hazards.json`

### World logic (`dev.brights0ng.empire.region`)
- `HomeRegionScanner`: runs `RegionFloodFill` with the `HOME` classifier and validates volume, height, bed presence, sky exposure, monster-spawn block light, hazards, and staked chunks.
- `RegionManager` (`@EventBusSubscriber`):
  - Marks regions dirty on `BlockEvent.BreakEvent` and `BlockEvent.EntityPlaceEvent` when `pos` is inside `bounds.inflate(1)`.
  - Periodic tick (`VALIDATE_INTERVAL`): validates dirty regions (and sweeps loaded regions), skipping any region whose chunks are not loaded.
  - Handles `VALID → INVALID` detachment (releasing claimed beds inside the region on both `CivilianRecord` and loaded `CivilianEntity`), recovery back to `VALID`, and 3-day auto-deletion of `AUTO` regions.

### In-world rendering (`dev.brights0ng.empire.client` & `dev.brights0ng.empire.region`)
- **Shared renderer:** `VeilMesh` (pure Java, extracted from `TalcBorderGeometry.Mesh`) and `VeilMeshRenderer` (extracted from `TalcBorderRenderer`) unify chalk-ribbon and shimmering-veil rendering through `EmpireRenderTypes.BORDER_VEIL` (`LEQUAL` depth test, translucent, `NO_CULL`, `POLYGON_OFFSET_LAYERING`), sharing distance fade, chalk opacity, and veil shimmer wave so changes to boundary rendering apply identically to regions.
- **3D region geometry (`RegionGeometry`, pure Java):** builds a `VeilMesh` for each region's `BlockPos` set — exposed outer faces (`±X, ±Y, ±Z`, nudged inward by `VeilMesh.LIFT`) rendered as `VEIL_UNIFORM`, with geometric crease edges (90° outer corners and 270° inner corners, plus corner caps) framed by `VeilMesh.HALF_W` `CHALK` ribbons with zero quad overlap.
- **Trigger & palette (`RegionRenderer`):** active while holding Blessed or Tarnished Talc (until the Administration Ledger exists). Each `RegionType` has its own palette entry (`HOME` in blue, slightly dulled under Tarnished Talc; invalid regions in crimson).
- **Client sync (`RegionPayloads`, `RegionNetwork`, `RegionClientCache`):** C2S `Request` / S2C `Sync` syncs nearby regions of the player's empire on demand while holding talc, with immediate server pushes when regions are created, removed, or change validity.

### Sleep & command integration
- `CivilianBeds`: when claiming a bed, iterates unclaimed `PoiTypes.HOME` beds within 48 blocks on staked land, closest first. Accepts beds in a `VALID` `HOME` with spare capacity, or triggers an auto `HOME` flood fill if the bed is not yet in any region. Death, leaving, disband, re-recruitment, and debug clear/release detach the citizen from its `HOME` region.
- `CivilianDebugCommands` (`/empire debug region ...`): `list [player]`, `info`, `scan home`, `validate`, `remove <idPrefix>`, `clear`, `show` (brief particle outline).

---

## Implementation phases (R1–R4)

| Phase | Deliverable | Proof |
|---|---|---|
| **R1 Rules** | `RegionType`, `RegionOrigin`, `RegionInvalidReason`, `RegionFloodFill`, `RegionRules` | `:empire:test` (`RegionFloodFillTest`, `RegionRulesTest`) |
| **R2 Data & tags** | `RegionRecord`, `EmpireSavedData` v5 + spatial index, `region_home_interior` & `region_home_hazards` tags | `:empire:compileJava`, `:empire:test` |
| **R3 Scanner & manager** | `HomeRegionScanner`, `RegionManager` (dirty events, periodic validation, detachment, 3-day auto-delete) | `:empire:compileJava` |
| **R4 Integration, commands & tests** | `CivilianBeds` / `CivilianRoster` wiring, `/empire debug region ...`, update `CivilianSleepGameTests` bedrooms to enclosed lit `HOME`s, add `RegionGameTests` | Full check (`compileJava compileTestJava test runGameTestServer`) |

---

## Open / Future passes

- **Rooms sub-pass:** Split each `HOME` into sub-rooms separated by doors, and prune any room that borders sky access so covered decks and overhangs are excluded from the `HOME`.
- **Administration Ledger item:** Manual region outlining, auto-fill trigger, naming/re-typing, and management UI.
- **Cots:** Beds/cots that citizens can sleep in anywhere outside a `HOME`.
- **Citizen self-repair:** Before detaching from an invalidated region, citizens attempt to fix the issue (replace a broken block, break a placed hazard, re-light) first.
- **Additional region types & Meteodyne:** `PUB`, `FARM`, `LARDER`, `WORKSHOP`, etc., plus optional indoor temperature/shelter checks via `empire.compat.meteodyne`.

---

## Implementation notes (Gemini, 2026-11-20)

- **Pure `rules` collections:** `:empire:test` runs without Minecraft/fastutil on its test classpath, so `RegionFloodFill` uses pure JDK `Set<Long>` and a primitive `long[]` BFS queue (matching `ClaimGraph` and `EmpireState`), while `RegionRecord` in `data` stores `LongOpenHashSet` and `EmpireSavedData` uses `Long2ObjectOpenHashMap`. `BlockKeys` packs/unpacks 3D coordinates with `BlockPos.asLong`'s exact 26/26/12-bit layout.
- **Direct sky exposure vs. `Level.canSeeSky`:** In MC 1.21.1, `Level.canSeeSky(pos)` checks `LightLayer.SKY >= 15`, which treats glass roofs as sky-exposed and lags asynchronously behind roof breaks. `HomeRegionScanner.isExposedToSky(level, x, y, z)` instead checks `y + 1 >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)` — updated synchronously on block edits, allowing glass roofs/windows while catching any open sky exposure (including bare outdoor beds).
- **Commands:** `/empire debug region ...` lives in `command.RegionDebugCommands` attached under `/empire debug` in `EmpireCommand`. `scan home` accepts optional `auto` (default) or `manual`.

## Verification (2026-11-20)

- Full check (`.\gradlew.bat compileJava compileTestJava test runGameTestServer --console=plain`): **BUILD SUCCESSFUL** (3m 52s).
  - Unit tests across all modules: **1391 passed, 5 skipped** (pre-existing), **0 failed** (Empire: **175**, +11 new in `RegionRulesTest` and `RegionFloodFillTest`; `core`: 33 including `ArchitectureTest`).
  - Game test servers (all 11 passing): aggregate **440/440**, coreless **390/390**, Empire **141/141** (+6 new in `RegionGameTests`; `CivilianSleepGameTests` updated to enclosed lit `HOME` rooms), Frontier **17/17**, Meteodyne **125/125**, Paradyne Lib **3/3**, Sable Fixes **2/2**, Seismic **20/20**, Simple Spoilage **34/34**, Sparse Ores **13/13**, Sparse Ores + Create **13/13**.
- No client visual verification was performed; `/empire debug region show` particle outlines and in-game UX remain for Bright to check.
