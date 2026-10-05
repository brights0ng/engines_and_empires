package dev.brights0ng.enginesandempires.oregen.worldgen;

import dev.brights0ng.enginesandempires.geophone.ReaderReading;
import dev.brights0ng.enginesandempires.oregen.Deposit;
import dev.brights0ng.enginesandempires.oregen.DepositBody;
import dev.brights0ng.enginesandempires.oregen.OreMap;
import dev.brights0ng.enginesandempires.oregen.Realm;
import dev.brights0ng.enginesandempires.oregen.ResolvedDeposit;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Works out which deposit a reading is of, when the reading does not say.
 *
 * <p>Readings record their deposit as they are taken, but ones taken before that was kept have none, and a logbook needs it to
 * tell that two readings are of the same deposit. Every deposit is a fixed function of the world seed, so it can be found from
 * the position alone: a reading points at an ore block, and that block belongs to exactly one deposit's body. This looks through the
 * deposits close enough to reach the block and returns the one that has ore there.
 */
public final class ReadingDeposits {

    /**
     * The reading with its deposit filled in, if it did not have one and one can be found. A reading that already says which
     * deposit it is of is returned as it is, and so is one that cannot be placed (in a dimension with no deposits or that is not
     * loaded, or at a block that is not ore of any deposit).
     *
     * <p>Must be called on the main thread: it may need to work out deposits that have not been looked at yet.
     */
    public static ReaderReading identify(MinecraftServer server, ReaderReading reading) {
        if (reading.knowsDeposit()) {
            return reading;
        }
        Realm realm = Realm.ofDimension(reading.dimension());
        ResourceLocation dimension = ResourceLocation.tryParse(reading.dimension());
        if (realm == null || dimension == null) {
            return reading;
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) {
            return reading;
        }
        LevelDeposits deposits = OreMaps.of(level);
        for (OreMap map : OreMaps.forRealm(level.getSeed(), realm)) {
            int reach = OreMaps.typeOf(map).maxHorizontalReach();
            for (Deposit deposit : map.depositsInBox(reading.x() - reach, reading.z() - reach, reading.x() + reach, reading.z() + reach)) {
                ResolvedDeposit resolved = deposits.resolve(deposit);
                if (!resolved.viable()) {
                    continue;
                }
                DepositBody body = resolved.body();
                byte kind = body.at(reading.x() - deposit.x(), reading.y() - body.centerY(), reading.z() - deposit.z());
                if (kind == DepositBody.ORE || kind == DepositBody.RICH) {
                    ReaderReading identified = reading.withDeposit(deposit.seed());
                    return identified.knowsOre() ? identified : identified.withOre(OreMaps.typeOf(map).id());
                }
            }
        }
        return reading;
    }

    private ReadingDeposits() {
    }
}
