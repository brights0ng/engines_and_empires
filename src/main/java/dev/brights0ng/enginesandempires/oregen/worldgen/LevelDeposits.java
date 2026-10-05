package dev.brights0ng.enginesandempires.oregen.worldgen;

import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.oregen.BiomeRule;
import dev.brights0ng.enginesandempires.oregen.Deposit;
import dev.brights0ng.enginesandempires.oregen.DepositBody;
import dev.brights0ng.enginesandempires.oregen.DepositLedger;
import dev.brights0ng.enginesandempires.oregen.DepositResolver;
import dev.brights0ng.enginesandempires.oregen.OreMap;
import dev.brights0ng.enginesandempires.oregen.OreType;
import dev.brights0ng.enginesandempires.oregen.OreTypes;
import dev.brights0ng.enginesandempires.oregen.Realm;
import dev.brights0ng.enginesandempires.oregen.ResolvedDeposit;
import dev.brights0ng.enginesandempires.oregen.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Everything the pack knows about deposits in one dimension: what became of each after checking it
 * against the terrain, what really happened to each as the world generated, and how much of each is left.
 *
 * <p>Worldgen, the debug commands and the geophone all go through here, so they always agree about which
 * deposits exist and what they look like. Deposits are resolved lazily, once each, by
 * {@link DepositResolver}, and the answer is remembered. It is a pure function of the seed and the
 * terrain, so it never changes and never depends on which chunks happen to be loaded.
 *
 * <p>Thread-safe: worldgen uses it from several threads at once.
 */
public final class LevelDeposits {

    private final Realm realm;
    private final String dimensionName;
    private final TerrainProbe probe;
    private final SurfaceBiomes biomes;
    private final DepositLedger ledger;
    /** Saves the ledger with the world, or null if it is not saved. */
    private final DepositLedgerData data;
    private final ConcurrentHashMap<Long, DepositResolver.Outcome> outcomes = new ConcurrentHashMap<>();
    /** The biome rule that applies to each deposit (empty for none), looked up once. */
    private final ConcurrentHashMap<Long, Optional<BiomeRule>> biomeRules = new ConcurrentHashMap<>();

    private LevelDeposits(Realm realm, String dimensionName, TerrainProbe probe, SurfaceBiomes biomes,
                          DepositLedgerData data) {
        this.realm = realm;
        this.dimensionName = dimensionName;
        this.probe = probe;
        this.biomes = biomes;
        this.data = data;
        this.ledger = data != null ? data.ledger() : new DepositLedger();
    }

    /**
     * Sets up a dimension's deposit state. Must be called from the main thread when the level loads if
     * {@code persist} is true, since it reads the saved ledger.
     */
    static LevelDeposits create(ServerLevel level, boolean persist) {
        String dimension = level.dimension().location().toString();
        Realm realm = Realm.ofDimension(dimension);
        if (realm == null) {
            throw new IllegalArgumentException("No ore deposits are generated in " + dimension);
        }
        DepositLedgerData data = persist
                ? level.getDataStorage().computeIfAbsent(DepositLedgerData.factory(), DepositLedgerData.NAME)
                : null;
        return new LevelDeposits(realm, dimension, NoiseTerrainProbe.forLevel(level), SurfaceBiomes.forLevel(level), data);
    }

    public Realm realm() {
        return realm;
    }

    public String dimensionName() {
        return dimensionName;
    }

    public DepositLedger ledger() {
        return ledger;
    }

    /**
     * The size factor the surface biome above this deposit gives it, if it turns out shallow (see
     * {@link BiomeRule}). 1 for an ore without biome rules.
     */
    public double biomeFactor(Deposit deposit) {
        BiomeRule rule = biomeRule(deposit);
        return rule == null ? 1.0 : rule.factor();
    }

    /** The biome rule that applies to this deposit, from the surface biome above it, or null for none. */
    public BiomeRule biomeRule(Deposit deposit) {
        OreType type = OreTypes.byId(deposit.oreId());
        if (!type.hasBiomeRules()) {
            return null;
        }
        return biomeRules.computeIfAbsent(deposit.seed(),
                seed -> Optional.ofNullable(biomes.rule(type, deposit.x(), deposit.z()))).orElse(null);
    }

    /** The biome at the surface of a column, as the ore rules see it. For debug reports. */
    public Holder<Biome> surfaceBiome(int x, int z) {
        return biomes.biomeAt(x, z);
    }

    /** The rule an ore's deposits get under a biome, or null. For debug reports. */
    public static BiomeRule biomeRuleFor(OreType type, Holder<Biome> biome) {
        return BiomeRule.select(type.biomes(), rule -> SurfaceBiomes.matches(biome, rule));
    }

    /**
     * What becomes of this deposit in this dimension's terrain, with its body if it exists. The first call
     * for a deposit does the work; later ones are answered from memory.
     */
    public ResolvedDeposit resolve(Deposit deposit) {
        OreType type = OreTypes.byId(deposit.oreId());
        BiomeRule biome = biomeRule(deposit);
        DepositResolver.Outcome outcome = outcomes.get(deposit.seed());
        if (outcome == null) {
            DepositResolver.Result result = DepositResolver.resolve(deposit,
                    attempt -> attempt == 0
                            ? OreMaps.bodies().get(deposit, type, 0, biome)
                            : DepositBody.generate(deposit, type, attempt, biome), // rejected draws are not worth caching
                    probe);
            outcomes.putIfAbsent(deposit.seed(), result.outcome());
            if (result.body() != null) {
                OreMaps.bodies().put(deposit, result.outcome().attempt(), biome, result.body());
            }
            return new ResolvedDeposit(deposit, result.outcome(), result.body());
        }
        return new ResolvedDeposit(deposit, outcome,
                outcome.dropped() ? null : OreMaps.bodies().get(deposit, type, outcome.attempt(), biome));
    }

    /** Notes that worldgen met a deposit the resolver dropped. */
    void noteDropped(ResolvedDeposit resolved) {
        ledger.recordDropped(resolved.deposit(), resolved.outcome());
        markDirty();
    }

    /**
     * Reports how much of a deposit's ore one chunk really placed. When that completes the deposit, its
     * result is logged: a warning for a phantom, which is what the measurement exists to find.
     */
    void recordChunk(ResolvedDeposit resolved, long chunk, int orePlaced) {
        Deposit deposit = resolved.deposit();
        DepositLedger.EntryView finished = ledger.recordChunk(deposit, resolved.outcome(), resolved.body(),
                chunk, orePlaced, () -> DepositLedger.profileOf(deposit, resolved.body()));
        markDirty();
        if (finished != null) {
            report(finished);
        }
    }

    /** Marks a deposit as mined out for good, so it is never counted again. */
    public void markDepleted(long depositSeed) {
        ledger.markDepleted(depositSeed);
        markDirty();
    }

    /**
     * Recounts, in a chunk that is saving or unloading, the ore left in every tracked deposit that has some in it,
     * and records the counts. Ore only changes while its chunk is loaded, so the last count taken as a chunk
     * leaves the world is what stands until it is next loaded. Must be called on the main thread.
     *
     * <p>Cheap: deposits the ledger does not track, and ones with no ore in this chunk, are skipped without
     * being resolved.
     */
    public void refreshChunk(ChunkAccess chunk, long worldSeed) {
        ChunkPos pos = chunk.getPos();
        long key = DepositLedger.chunkKey(pos.x, pos.z);
        boolean changed = false;
        for (OreMap map : OreMaps.forRealm(worldSeed, realm)) {
            int reach = OreMaps.typeOf(map).maxHorizontalReach();
            for (Deposit deposit : map.depositsInBox(pos.getMinBlockX() - reach, pos.getMinBlockZ() - reach,
                    pos.getMaxBlockX() + reach, pos.getMaxBlockZ() + reach)) {
                if (ledger.chunkIndex(deposit.seed(), key) < 0) {
                    continue;
                }
                ResolvedDeposit resolved = resolve(deposit);
                if (resolved.viable() && ledger.updateRemaining(deposit.seed(), key, oreIn(chunk, resolved))) {
                    changed = true;
                }
            }
        }
        if (changed) {
            markDirty();
        }
    }

    /** Forgets everything the ledger has recorded. Resolutions are kept: they never change. */
    public void reset() {
        ledger.clear();
        markDirty();
    }

    private void markDirty() {
        if (data != null) {
            data.setDirty();
        }
    }

    /** How many of a deposit's ore blocks are standing in this chunk. */
    private static int oreIn(ChunkAccess chunk, ResolvedDeposit resolved) {
        Deposit deposit = resolved.deposit();
        DepositBody body = resolved.body();
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        Set<Block> ores = OreBlocks.oreBlocks(deposit.oreId());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] count = {0};
        body.forEach((dx, dy, dz, kind) -> {
            if (kind != DepositBody.ORE && kind != DepositBody.RICH) {
                return;
            }
            int x = deposit.x() + dx;
            int z = deposit.z() + dz;
            if ((x >> 4) == chunkX && (z >> 4) == chunkZ
                    && ores.contains(chunk.getBlockState(pos.set(x, body.centerY() + dy, z)).getBlock())) {
                count[0]++;
            }
        });
        return count[0];
    }

    private void report(DepositLedger.EntryView deposit) {
        long predicted = Math.round(deposit.solidFraction() * 100.0);
        switch (deposit.state()) {
            case PHANTOM -> EnginesAndEmpiresMod.LOGGER.warn(
                    "Phantom deposit in {}: {} at ({}, {}), y={}, accepted on attempt {}. The terrain estimate "
                            + "predicted {}% of its ore in rock, but it placed 0 of {} ore blocks across {} chunks.",
                    dimensionName, deposit.oreId(), deposit.x(), deposit.z(), deposit.centerY(), deposit.attempt() + 1,
                    predicted, deposit.expectedOre(), deposit.expectedChunks().length);
            case THIN -> EnginesAndEmpiresMod.LOGGER.info(
                    "Thin deposit in {}: {} at ({}, {}), y={}: predicted {}% in rock, placed {} of {} ore blocks.",
                    dimensionName, deposit.oreId(), deposit.x(), deposit.z(), deposit.centerY(), predicted,
                    deposit.placed(), deposit.expectedOre());
            default -> EnginesAndEmpiresMod.LOGGER.debug(
                    "Deposit complete in {}: {} at ({}, {}): placed {} of {} ore blocks.",
                    dimensionName, deposit.oreId(), deposit.x(), deposit.z(), deposit.placed(), deposit.expectedOre());
        }
    }
}
