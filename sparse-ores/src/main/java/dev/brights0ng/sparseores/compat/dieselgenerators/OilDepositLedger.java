// Written by Gemini.
// Edited by Gemini: serialized maxRadius in NBT for exact reservoir reconstruction.
package dev.brights0ng.sparseores.compat.dieselgenerators;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.brights0ng.sparseores.Deposit;
import dev.brights0ng.sparseores.OreLayer;
import dev.brights0ng.sparseores.OreMap;
import dev.brights0ng.sparseores.shape.oil.OilDepositGenerator;
import dev.brights0ng.sparseores.shape.oil.OilTrapType;
import dev.brights0ng.sparseores.worldgen.WorldgenOilTerrainContext;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Manages procedural multi-chunk oil reservoirs and their extraction/depletion states.
 */
public final class OilDepositLedger extends SavedData {

    public static final String NAME = "sparse_ores_oil_deposits";
    public static final OreLayer OIL_LAYER = OilDepositGenerator.OIL_LAYER;

    private final ServerLevel level;
    private final OreMap oilMap;
    private final Map<Long, OilDeposit> depositsBySeed = new ConcurrentHashMap<>();

    public OilDepositLedger(ServerLevel level) {
        this.level = level;
        this.oilMap = new OreMap(level.getSeed(), OIL_LAYER);
    }

    public static SavedData.Factory<OilDepositLedger> factory(ServerLevel level) {
        return new SavedData.Factory<>(() -> new OilDepositLedger(level), (tag, registries) -> load(tag, level, registries), null);
    }

    public static OilDepositLedger get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(factory(level), NAME);
    }

    public OilDeposit getOrCreateDeposit(Deposit candidate) {
        return depositsBySeed.computeIfAbsent(candidate.seed(), s -> {
            WorldgenOilTerrainContext terrain = new WorldgenOilTerrainContext(level);
            OilDepositGenerator.GeneratedReservoir res = OilDepositGenerator.generate(
                    level.getSeed(), candidate.x(), candidate.z(), candidate.seed(), terrain);
            setDirty();
            return new OilDeposit(candidate.seed(), candidate.x(), candidate.z(),
                    res.trapType(), res.strikeAngle(), res.field(), res.capacity(), res.capacity());
        });
    }

    public int getChunkOilAmount(ChunkPos chunkPos) {
        int blockX = (chunkPos.x << 4) + 8;
        int blockZ = (chunkPos.z << 4) + 8;
        List<Deposit> candidates = oilMap.depositsInBox(blockX - 320, blockZ - 320, blockX + 320, blockZ + 320);
        for (Deposit candidate : candidates) {
            OilDeposit deposit = getOrCreateDeposit(candidate);
            int amt = deposit.getChunkOilAmount(chunkPos.x, chunkPos.z);
            if (amt > 0) {
                return amt;
            }
        }
        return 0;
    }

    public void setChunkOilAmount(ChunkPos chunkPos, int newAmount) {
        int blockX = (chunkPos.x << 4) + 8;
        int blockZ = (chunkPos.z << 4) + 8;
        List<Deposit> candidates = oilMap.depositsInBox(blockX - 320, blockZ - 320, blockX + 320, blockZ + 320);
        for (Deposit candidate : candidates) {
            OilDeposit deposit = getOrCreateDeposit(candidate);
            int current = deposit.getChunkOilAmount(chunkPos.x, chunkPos.z);
            if (current > 0 || deposit.field().touchesChunk(chunkPos.x, chunkPos.z, 1.0)) {
                if (newAmount < current) {
                    deposit.extractOil(current - newAmount);
                    setDirty();
                    return;
                } else if (newAmount > current) {
                    deposit.setRemainingAmount(deposit.remainingAmount() + (newAmount - current));
                    setDirty();
                    return;
                }
            }
        }
    }

    public OilDeposit getDepositAt(int blockX, int blockZ) {
        List<Deposit> candidates = oilMap.depositsInBox(blockX - 320, blockZ - 320, blockX + 320, blockZ + 320);
        for (Deposit candidate : candidates) {
            OilDeposit deposit = getOrCreateDeposit(candidate);
            if (deposit.field().contains(blockX, blockZ, deposit.fullness())) {
                return deposit;
            }
        }
        return null;
    }

    public List<OilDeposit> getDepositsInBox(int minX, int minZ, int maxX, int maxZ) {
        List<Deposit> candidates = oilMap.depositsInBox(minX - 320, minZ - 320, maxX + 320, maxZ + 320);
        List<OilDeposit> result = new ArrayList<>(candidates.size());
        for (Deposit candidate : candidates) {
            result.add(getOrCreateDeposit(candidate));
        }
        return result;
    }

    public OilDeposit getBySeed(long seed) {
        return depositsBySeed.get(seed);
    }

    public static OilDepositLedger load(CompoundTag tag, ServerLevel level, HolderLookup.Provider registries) {
        OilDepositLedger ledger = new OilDepositLedger(level);
        ListTag list = tag.getList("deposits", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag d = list.getCompound(i);
            long seed = d.getLong("seed");
            int x = d.getInt("x");
            int z = d.getInt("z");
            OilTrapType trap = OilTrapType.byId(d.getString("trap"));
            double angle = d.getDouble("angle");
            int initial = d.getInt("initial");
            int remaining = d.getInt("remaining");

            double maxRadius = d.contains("maxRadius") ? d.getDouble("maxRadius") : -1.0;
            OilDepositGenerator.GeneratedReservoir res = (maxRadius > 0)
                    ? OilDepositGenerator.buildReservoirWithRadius(trap, x, z, seed, angle, maxRadius)
                    : OilDepositGenerator.buildReservoir(trap, x, z, seed, angle);
            OilDeposit deposit = new OilDeposit(seed, x, z, trap, angle, res.field(), initial, remaining);
            ledger.depositsBySeed.put(seed, deposit);
        }
        return ledger;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (OilDeposit dep : depositsBySeed.values()) {
            CompoundTag d = new CompoundTag();
            d.putLong("seed", dep.seed());
            d.putInt("x", dep.centerX());
            d.putInt("z", dep.centerZ());
            d.putString("trap", dep.trapType().id());
            d.putDouble("angle", dep.strikeAngle());
            d.putInt("initial", dep.initialAmount());
            d.putInt("remaining", dep.remainingAmount());
            d.putDouble("maxRadius", dep.field().maxRadius());
            list.add(d);
        }
        tag.put("deposits", list);
        return tag;
    }
}
