<!-- Written by Gemini (2026-11-20) from Bright's decisions in this session; updated with implementation and verification by Gemini (2026-11-20); updated with shared veil/chalk region rendering by Gemini (2026-11-20); updated with whitelist expansion and H3+W3 region updates by Gemini (2026-11-21); updated with Rooms & INDOOR/OUTDOOR labelling by Gemini (2026-11-21); updated with Administrative Ledger item by Gemini (2026-11-25); updated with FARM regions and Ledger selection by Gemini (2026-11-25). -->
# Empire: Regions (framework, `HOME`, `FARM`, rooms, ledger selection, and in-world rendering)

**Date:** 2026-11-20 · **Status:** built (phases R1–R4 + shared veil/chalk rendering). In-game visual check remains for Bright.
**Scope:** arbitrary 3D `BlockPos` plots of empire land assigned to a purpose, a pure flood-fill engine, lifecycle/invalidation, the first region type (`HOME`) wired into citizen bed claiming (`CivilianBeds`), and in-world 3D region rendering sharing the boundary chalk/veil renderer.

---

## Decisions (Bright, 2026-11-20)

- **D1 Shape & storage:** Regions are arbitrary 3D sets of `BlockPos` coordinates (not restricted to cuboids), stored as packed `long[]` / `LongOpenHashSet` with cached bounds and touched chunks.
- **D2 Creation:** Regions can be created manually (future Administration Ledger item) or automatically via flood fill (triggered by events such as a citizen claiming a bed, or by the Ledger's auto-select).
- **D3 Territory & overlaps:** Every block in a region must be inside the empire's **staked chunks**. Regions are **disjoint** (a `BlockPos` belongs to at most one region; flood fill treats existing region blocks and unstaked chunks as boundaries).
- **D4 `HOME` flood-fill whitelist, boundaries & hazards:**
  - **Cap:** 500 blocks maximum (`HOME_MAX_BLOCKS = 500`); exceeding the cap aborts the fill (`TOO_LARGE`).
  - **Whitelisted (`INCLUDE`):** air, beds (`#minecraft:beds`), torches (regular/wall/soul/redstone), lanterns (regular/soul), carpets (`#minecraft:wool_carpets`), chests/trapped chests, doors (`#minecraft:doors`), trapdoors (`#minecraft:trapdoors`), climbable blocks (`#minecraft:climbable`), and any non-fluid block without collision (`getFluidState().isEmpty() && getCollisionShape(...).isEmpty()`, e.g. signs, banners, buttons, levers, pressure plates, rails, flowers). Stored in data-driven block tag `empire:region_home_interior` plus the collision check in `HomeRegionScanner.isInterior`.
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
  - `AUTO` regions remain `INVALID` for **3 MC days** (`72_000` ticks) before being automatically deleted **only if they were invalidated during an update**; candidates that fail validation on creation are never kept.
  - `MANUAL` regions are never auto-deleted.
- **D8 Bed claiming (`CivilianBeds`):** Citizens may **only** claim or sleep in beds inside a `VALID` `HOME` of their empire with spare capacity; otherwise they fall back to rough sleep by the bonfire for the rest of the night without re-querying.
  - **Pass 1:** Check existing `VALID` `HOME`s with empty beds and spare capacity first.
  - **Pass 2:** If none exist, try unmarked homeless beds one at a time to auto-create a `HOME` until one succeeds or none remain. Any bed that fails `HOME` creation is marked dirty in `RegionManager` so other civilians skip it; marks clear on a block change in the 3×3 chunk neighborhood or at dawn.
- **D9 Updating `HOME`s (Bright, 2026-11-21 — H3 + W3):**
  - **How (`AUTO` vs `MANUAL`):**
    - `AUTO` `HOME`s store a persistent `seed` coordinate (the creation anchor / first bed interacted with). On update, if `seed` is no longer a bed head and another bed exists in the region's current blocks, `seed` migrates to that bed (lowest packed coordinate); otherwise `seed` stays fixed.
    - `AUTO` `HOME`s re-flood-fill from `seed` (ignoring their own old blocks, stopping at other regions and treating sky-exposed blocks as non-whitelisted `BOUNDARY` so exterior doors work cleanly). On valid reshape, `blocks` and `EmpireSavedData`'s spatial index update in place, inhabitants whose beds were cut off are detached (and beds released), and excess inhabitants are evicted if capacity shrank. On invalidation, old `blocks` are kept so the crimson outline and buffered bounding box still catch repairs.
    - `MANUAL` `HOME`s never reshape; they keep current validation on their fixed bounds.
  - **When (event-driven, no periodic sweep):**
    - `LevelChunkRegionMixin` (`LevelChunk.setBlockState`) notifies `RegionManager` on every Overworld chunk block change (player edits, explosions, fire, pistons, `/setblock`, Create contraptions). Any region whose buffered bounding box (`bounds.inflate(1)`) contains the changed block is prodded; chunk unstaking and direct interactions (civilian bed use, future Ledger) also prod.
    - **Throttle:** 1-second (`20`-tick) cooldown per region — first prod runs on the next tick (letting multi-block edits and lighting settle); any prod during cooldown coalesces into one trailing update when the cooldown expires.
    - **Expiry:** Invalidated `AUTO` regions follow normal updates plus a one-shot trigger at `invalidSinceGameTime + 72_000`.
- **D10 Rooms & `INDOOR`/`OUTDOOR` labelling (Bright, 2026-11-21):**
  - **Partitioning:** Regions partition into 6-connected rooms separated by region-configured separator blocks (`RoomSeparator`: `DOOR` (`#minecraft:doors`), `TRAPDOOR` (`#minecraft:trapdoors`), `FENCE_GATE` (`#minecraft:fence_gates`); fences/walls are obstructions, not separators). Open and closed separator blockstates separate equally. Separator blocks belong to neither room they border.
  - **General `INDOOR`/`OUTDOOR` labelling:** Opt-in per region type (`RoomLabel`: `INDOOR`, `OUTDOOR`). Any room containing or 6-adjacent to a non-separator whitelisted block with direct sky access is labelled `OUTDOOR`; all other rooms are `INDOOR`.
  - **`HOME` rooms:** Separated by **doors only**, with `INDOOR`/`OUTDOOR` labelling enabled. `OUTDOOR` rooms remain in `region.blocks()` (not pruned), do **not** count toward `HOME` effective volume (50–500 min / capacity 1–4), and are skipped by `TOO_DARK` and `NO_BED`. Beds in `OUTDOOR` rooms cannot be claimed or slept in; if a wall/door breaks and turns a claimed room `OUTDOOR`, civilians in that room are detached and their beds released.
  - **Rendering & debug:** Region rendering draws only `INDOOR` rooms (ignoring `OUTDOOR` rooms), and highlights separator blocks in yellow (matching Empire protected boundaries). `/empire debug region info` reports room counts, labels, separator count, and effective volume.
- **D11 Administrative Ledger (Bright, 2026-11-25):**
  - Item id `empire:administrative_ledger` ("Administrative Ledger", `stacksTo(1)`, listed in `EMPIRE_TAB`), clipboard model (`stripped_spruce_log` board + paper/clip texture), shaped recipe: `minecraft:ink_sac` top center, `minecraft:paper` middle, `#minecraft:signs` bottom center.
  - Right-clicking a region's home block (bed for `HOME`, composter for `FARM`) while holding the Administrative Ledger cancels vanilla interaction, creates or selects that region (`LedgerInteractions`, `LedgerSelection`), and gives action-bar feedback with a page-turn sound.
  - Holding the Administrative Ledger (main hand or offhand) activates both 3D region rendering (`RegionRenderer`) and empire chunk border rendering (`TalcBorderRenderer`); Blessed and Tarnished Talc render chunk borders only.
- **D12 `FARM` regions & Ledger selection (Bright, 2026-11-25):**
  - **Creation & seed:** Right-clicking an unclaimed composter with the Administrative Ledger creates an `AUTO` `FARM` region seeded at the composter (`FarmRegionScanner`), flood-filling a `SHED` room (no air, no sky-access check; whitelisted via `empire:region_farm_shed`: composter, chest, trapped chest, barrel, Create `create:item_vault`) and immediately selecting the farm.
  - **Selection rules (`LedgerSelection`):** A region stays selected as long as the player holds an Administrative Ledger in their main hand or offhand. Unequipping the ledger, logging out, or **left-clicking** (block or air) while holding the ledger clears the selection. To select an existing valid region, right-click its **home block** (composter for `FARM`, bed for `HOME`); right-clicking **anywhere** in an invalidated region of the player's empire (including inner floor/wall faces of an invalidated `HOME`) also selects it so it can be repaired or deleted. Clicking a selected region's own room rescans and reports room status; **shift-right-clicking** any room of any selected region (`HOME` or `FARM`) removes that room (blocking severed door separators on `HOME` so auto-reshape does not re-absorb it, and deleting the region if no rooms remain); clicking another valid region's non-home block reports an error while a region is selected; clicking another region's home block switches selection to that region; clicking non-home blocks without a selection (or outside any region without matching an add-room trigger) interacts with the block normally.
  - **Adding, merging & removing rooms:** While a `FARM` is selected, right-clicking an unclaimed `shed` block (`empire:region_farm_shed`) or `field` block (`empire:region_farm_field` / `CropBlock` / `FarmBlock`) flood-fills and adds a `SHED` or `FIELD` room. Tilled dirt (`farmland` / `FarmBlock`) additionally whitelists the block directly above it regardless of block type, and BFS continues propagating through that block (connecting 1-block terraced fields and tall crops without leaking into ordinary air). Touching rooms with the **same** label merge; rooms with **different** labels act as boundaries for each other while still allowed to touch. **Shift-right-clicking** a room of a selected farm removes that room, deleting the farm if no rooms remain.
  - **Storage marking:** Storage blocks (`empire:region_storage`: chest, trapped chest, barrel, `create:item_vault`) inside `SHED` rooms are recorded on `RoomRecord.storageBlocks()` / `RegionRecord.storageBlocks()` (persisted in `RegionRecord` v3 NBT) for future civilian access (not separately visualized).
  - **Auto-reshape, validation, caps & expiry:** Each room stores its own `seed` and auto-reshapes on nearby block edits via `RegionManager`. A `FARM` is `VALID` as soon as its seed shed is created and remains valid as long as it has at least one `SHED` room, no hazards (`empire:region_farm_hazards`, inside or 6-adjacent), all chunks staked, and room caps respected (`FARM_SHED_MAX_BLOCKS = 500`, `FARM_FIELD_MAX_BLOCKS = 5000`, no max distance). If a shed's composter breaks while other shed blocks remain, the farm keeps working normally (and cannot be reselected until a new composter is placed in or adjacent to the shed); if all sheds break, the farm becomes `INVALID(NO_SHED)`, recovers if a composter is placed in or adjacent to it, and auto-deletes after 3 MC days if left invalid.
  - **Rendering:** `RegionRenderer` draws `FARM` `SHED` rooms in warm amber/ochre, `FIELD` rooms in meadow green, invalid regions in crimson, and highlights the currently selected region with a brighter tint.

---

## Provisional defaults (one named constant each)

| Constant | Value | Where |
|---|---|---|
| `HOME_MAX_BLOCKS` | `500` | `rules.RegionRules` |
| `HOME_MIN_BLOCKS` | `50` | `rules.RegionRules` |
| `HOME_MIN_HEIGHT` | `2` | `rules.RegionRules` |
| `HOME_MAX_CAPACITY` | `4` | `rules.RegionRules` |
| `FARM_SHED_MAX_BLOCKS` | `500` | `rules.RegionRules` |
| `FARM_FIELD_MAX_BLOCKS` | `5000` | `rules.RegionRules` |
| `INVALID_AUTO_DELETE_TICKS` | `72_000L` (3 MC days) | `rules.RegionRules` |
| `MIN_BLOCK_LIGHT` | `1` | `region.HomeRegionScanner` |
| `UPDATE_COOLDOWN_TICKS` | `20L` (1 second) | `region.RegionManager` |
| `IMMEDIATE_DEFER_TICKS` | `1L` | `region.RegionManager` |

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

### World logic (`dev.brights0ng.empire.region` & `dev.brights0ng.empire.mixin`)
- `LevelChunkRegionMixin` (`empire.mixins.json`, `EmpireMixinPlugin`): hooks `LevelChunk.setBlockState` at `RETURN` and calls `RegionManager.onBlockChanged` on Overworld server chunks.
- `HomeRegionScanner`: runs `RegionFloodFill` (treating sky-exposed blocks as `BOUNDARY`), `rescan(...)` with bed-seed migration for `AUTO` `HOME`s, and `validate(...)` for volume, height, bed presence, sky exposure, monster-spawn block light, hazards, and staked chunks.
- `RegionManager` (`@EventBusSubscriber`):
  - Event-driven updates with no periodic sweep: prods regions on chunk block changes inside `bounds.inflate(1)`, chunk unstaking, and civilian bed interactions; also tracks failed-bed marks cleared on 3×3 chunk updates or dawn.
  - Enforces a 1-second (`20`-tick) leading+trailing cooldown throttle per region and schedules one-shot 3-day expiry triggers for invalidated `AUTO` regions.
  - Handles `AUTO` `HOME` reshaping, cut-off/capacity-shrink inhabitant reconciliation, `VALID → INVALID` detachment, recovery back to `VALID`, and client sync pushes.

### In-world rendering (`dev.brights0ng.empire.client` & `dev.brights0ng.empire.region`)
- **Shared renderer:** `VeilMesh` (pure Java, extracted from `TalcBorderGeometry.Mesh`) and `VeilMeshRenderer` (extracted from `TalcBorderRenderer`) unify chalk-ribbon and shimmering-veil rendering through `EmpireRenderTypes.BORDER_VEIL` (`LEQUAL` depth test, translucent, `NO_CULL`, `POLYGON_OFFSET_LAYERING`), sharing distance fade, chalk opacity, and veil shimmer wave so changes to boundary rendering apply identically to regions.
- **3D region geometry (`RegionGeometry`, pure Java):** builds a `VeilMesh` for each region's `BlockPos` set — exposed outer faces (`±X, ±Y, ±Z`, nudged inward by `VeilMesh.LIFT`) rendered as `VEIL_UNIFORM`, with geometric crease edges (90° outer corners and 270° inner corners, plus corner caps) framed by `VeilMesh.HALF_W` `CHALK` ribbons with zero quad overlap.
- **Trigger & palette (`RegionRenderer`):** active while holding the Administrative Ledger (`empire:administrative_ledger`) in either hand (Blessed and Tarnished Talc show only chunk borders). Each `RegionType` has its own palette entry (`HOME` in blue; invalid regions in crimson; room separators in yellow).
- **Client sync (`RegionPayloads`, `RegionNetwork`, `RegionClientCache`):** C2S `Request` / S2C `Sync` syncs nearby regions of the player's empire on demand while holding the Administrative Ledger, with immediate server pushes when regions are created, removed, or change validity.

### Sleep & command integration
- `CivilianBeds`: when claiming a bed, gathers unclaimed `PoiTypes.HOME` beds within 48 blocks on staked land, closest first. Pass 1 checks beds already inside a `VALID` `HOME` with spare capacity; Pass 2 tries unmarked homeless beds one at a time to auto-create a `HOME`, marking failed beds dirty in `RegionManager` until a 3×3 chunk update or dawn. Death, leaving, disband, re-recruitment, and debug clear/release detach the citizen from its `HOME` region.
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

- **Administrative Ledger (later features):** Manual region outlining, naming/re-typing, and settlement management UI (job assignment for `FARM` is now built; see `docs/empire/occupations.md`).
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
