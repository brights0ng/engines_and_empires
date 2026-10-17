package dev.brights0ng.meteodyne.heat.world;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.brights0ng.meteodyne.Meteodyne;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sources that are on by their block entity rather than their block state (weather phase 8f-3): a {@code heat_power}
 * variant with a {@code driver} names one of these, and each plan asks it how strongly the source runs now, 0 (off) to
 * 1 (the table's power). Unknown drivers count as off.
 *
 * <p>Meteodyne has no drivers of its own (split seam S13): other mods register them at mod setup with
 * {@link #register}. The pack registers its machines' (engines, hot air burners) and Cold Sweat's hearths'.
 */
public final class HeatDrivers {

    /** How strongly a driven source runs now, 0-1. Server thread. */
    @FunctionalInterface
    public interface Driver {
        double share(ServerLevel level, BlockPos pos, BlockState state, BlockEntity blockEntity);
    }

    private static final HeatDriverTable<ResourceLocation, Driver> DRIVERS = new HeatDriverTable<>();
    private static final Set<ResourceLocation> WARNED = ConcurrentHashMap.newKeySet();

    /** Adds (or replaces) a driver. */
    public static void register(ResourceLocation id, Driver driver) {
        DRIVERS.register(id, driver);
    }

    /** The share of {@code variant}'s power that its block entity gives now (0 without one). */
    public static double share(ServerLevel level, BlockPos pos, BlockState state, HeatPowerData.Variant variant) {
        Driver driver = DRIVERS.get(variant.driver());
        if (driver == null) {
            if (WARNED.add(variant.driver())) {
                Meteodyne.LOGGER.warn("Unknown heat driver {} (for {}); that source stays off",
                        variant.driver(), state.getBlock());
            }
            return 0;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return 0;
        }
        return HeatDriverTable.share(() -> driver.share(level, pos, state, be), e -> {
            if (WARNED.add(variant.driver())) {
                Meteodyne.LOGGER.warn("Heat driver {} failed; that source stays off", variant.driver(), e);
            }
        });
    }

    /** How many drivers are registered (tests). */
    static int registered() {
        return DRIVERS.size();
    }

    private HeatDrivers() {
    }
}
