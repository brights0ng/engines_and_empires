package dev.brights0ng.enginesandempires.oregen.worldgen;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.oregen.Deposit;
import dev.brights0ng.enginesandempires.oregen.DepositBody;
import dev.brights0ng.enginesandempires.oregen.DepositLedger;
import dev.brights0ng.enginesandempires.oregen.OreMap;
import dev.brights0ng.enginesandempires.oregen.OreType;
import dev.brights0ng.enginesandempires.oregen.Realm;
import dev.brights0ng.enginesandempires.oregen.ResolvedDeposit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Builds deposits into the world from the ore maps.
 *
 * <p>The game calls this once for every chunk it generates, with {@code origin} at that chunk's
 * lowest corner. The same feature is added to overworld and nether biomes; it works out which realm it
 * is generating in and only builds that realm's ores. For each ore we ask the ore map which deposits are
 * near this chunk, resolve each against the terrain (see {@link LevelDeposits}) so that deposits with no
 * rock to be in are redrawn or left out, rebuild the body, and write only the part of it that falls inside
 * this chunk. The neighbouring chunk writes its own part when it is generated, so deposits that cross
 * chunk borders still come out whole and nothing is ever written outside the chunk being generated.
 *
 * <p>In real-depth mode each chunk also tells the deposit ledger how much ore it really placed, which is
 * how phantom deposits, ones that end up with no ore despite passing the terrain check, are found and
 * reported.
 *
 * <p>Ore blocks come in stone and deepslate versions, and so does rich ore. Each block of a deposit uses
 * the version that matches the rock it is in, which puts deep deposits in deepslate ore and deepslate
 * rich ore. There are two modes, chosen by {@link OreWorldgen#SKY_SHOWCASE}:
 * <ul>
 *   <li><b>Sky showcase</b> (temporary): every body is built around {@link OreWorldgen#SHOWCASE_Y} with
 *       its host rock filled in and its cavities cleared, so it can be looked at from outside. There is
 *       no real rock to look at, so the version is chosen by the height the block would really be at:
 *       deepslate below {@link OreBlocks#DEEPSLATE_BELOW_Y}, and the host rock follows suit. No terrain
 *       check is made and the ledger is not used.</li>
 *   <li><b>Real</b>: each body is built at its own height, replacing only the rock {@link RockRules}
 *       allows (stone-type and deepslate-type rock in the overworld, netherrack and its kin in the
 *       Nether). The version is chosen by the rock being replaced, as vanilla ores do. Cavities become
 *       air, and the host rock, and everything that is not rock, is left exactly as the terrain made it.</li>
 * </ul>
 */
public class DepositFeature extends Feature<NoneFeatureConfiguration> {

    /** What writing one deposit's part of a chunk did. */
    private record Placement(int changed, int ore) {
    }

    public DepositFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        Realm realm = Realm.ofDimension(level.getLevel().dimension().location().toString());
        if (realm == null) {
            return false; // no deposits in this dimension
        }
        LevelDeposits deposits = OreMaps.of(level.getLevel());
        boolean showcase = OreWorldgen.SKY_SHOWCASE;

        ChunkPos chunk = new ChunkPos(context.origin());
        int minX = chunk.getMinBlockX();
        int maxX = chunk.getMaxBlockX();
        int minZ = chunk.getMinBlockZ();
        int maxZ = chunk.getMaxBlockZ();

        boolean placedAny = false;
        for (OreMap map : OreMaps.forRealm(level.getSeed(), realm)) {
            OreType type = OreMaps.typeOf(map);

            // A deposit centred outside this chunk can still reach into it, so look one full reach further out.
            int reach = type.maxHorizontalReach();
            for (Deposit deposit : map.depositsInBox(minX - reach, minZ - reach, maxX + reach, maxZ + reach)) {
                ResolvedDeposit resolved = deposits.resolve(deposit);
                if (!resolved.viable()) {
                    if (!showcase) {
                        deposits.noteDropped(resolved);
                    }
                    continue; // no rock to be in: the deposit does not exist
                }
                DepositBody body = resolved.body();
                boolean touchesChunk = deposit.x() + body.reachX() >= minX && deposit.x() - body.reachX() <= maxX
                        && deposit.z() + body.reachZ() >= minZ && deposit.z() - body.reachZ() <= maxZ;
                if (!touchesChunk) {
                    continue;
                }
                Placement placement = placeBody(level, realm, deposit, body, minX, maxX, minZ, maxZ);
                if (!showcase) {
                    deposits.recordChunk(resolved, DepositLedger.chunkKey(chunk.x, chunk.z), placement.ore());
                }
                if (placement.changed() > 0) {
                    placedAny = true;
                    EnginesAndEmpiresMod.LOGGER.debug(
                            "Placed {} blocks of {} deposit ({}, {}) in chunk {} (whole deposit: {} ore, {} rich)",
                            placement.changed(), deposit.oreId(), deposit.x(), deposit.z(), chunk,
                            body.oreCount(), body.richCount());
                }
            }
        }
        return placedAny;
    }

    /** Writes the part of one deposit that lies inside the given chunk bounds. */
    private static Placement placeBody(WorldGenLevel level, Realm realm, Deposit deposit, DepositBody body,
                                       int minX, int maxX, int minZ, int maxZ) {
        boolean showcase = OreWorldgen.SKY_SHOWCASE;
        int baseY = showcase ? OreWorldgen.SHOWCASE_Y : body.centerY();
        OreBlocks.Palette palette = OreBlocks.paletteFor(deposit.oreId(), realm);
        BlockState air = Blocks.AIR.defaultBlockState();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] counts = {0, 0}; // blocks changed, ore blocks placed
        body.forEach((dx, dy, dz, kind) -> {
            int x = deposit.x() + dx;
            int z = deposit.z() + dz;
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                return;
            }
            int y = baseY + dy;
            if (level.isOutsideBuildHeight(y)) {
                return;
            }
            pos.set(x, y, z);

            BlockState state;
            if (showcase) {
                // No rock to look at: use deepslate wherever the block would really be deep underground.
                boolean deep = realm == Realm.OVERWORLD && body.centerY() + dy < OreBlocks.DEEPSLATE_BELOW_Y;
                state = switch (kind) {
                    case DepositBody.ORE -> palette.ore(deep);
                    case DepositBody.RICH -> palette.rich(deep);
                    case DepositBody.HOST -> palette.host(deep);
                    default -> air; // DepositBody.VOID: a hollow cavity
                };
            } else {
                state = undergroundState(realm, level.getBlockState(pos), kind, palette, air);
                if (state == null) {
                    return;
                }
            }
            if (level.setBlock(pos, state, Block.UPDATE_CLIENTS)) {
                counts[0]++;
                if (kind == DepositBody.ORE || kind == DepositBody.RICH) {
                    counts[1]++;
                }
            }
        });
        return new Placement(counts[0], counts[1]);
    }

    /**
     * What to put where a deposit meets existing terrain, or null to leave the block alone. A block is
     * only ever replaced if {@link RockRules} says it is plain host rock; air, fluids, caves and every
     * other kind of block are skipped, and the deposit's own host rock is never rewritten. Ore and rich
     * ore take the deepslate version if the rock they replace is deepslate-type, the stone version if not.
     */
    private static BlockState undergroundState(Realm realm, BlockState existing, byte kind,
                                               OreBlocks.Palette palette, BlockState air) {
        if (kind == DepositBody.HOST || !RockRules.isReplaceable(realm, existing)) {
            return null;
        }
        if (kind == DepositBody.VOID) {
            return air;
        }
        boolean deep = RockRules.isDeepslateType(existing);
        return kind == DepositBody.RICH ? palette.rich(deep) : palette.ore(deep);
    }
}
