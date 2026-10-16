package dev.brights0ng.enginesandempires.weather.heat.world;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.brights0ng.meteodyne.Meteodyne;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

/**
 * Sources that are on by their block entity rather than their block state (weather phase 8f-3): a {@code heat_power}
 * variant with a {@code driver} names one of these, and each plan asks it how strongly the source runs now, 0 (off) to
 * 1 (the table's power). Unknown drivers count as off.
 *
 * <ul>
 *   <li>{@link #RUNNING}: a Create Diesel Generators engine (normal, huge cylinder, modular part) or a Create
 *       Aeronautics portable engine that is running (turning, or burning fuel).</li>
 *   <li>{@link #HOT_AIR_BURNER}: a Create Aeronautics hot air burner, by how much hot air it puts out against the most
 *       one can (its own setting x the redstone signal / 15, over the server's {@code hotAirBurnerMaxHotAir}).</li>
 *   <li>{@code cold_sweat_heating} / {@code cold_sweat_cooling}: a Cold Sweat hearth, boiler or icebox using hot or
 *       cold fuel, by Cold Sweat's own controls (redstone, smokestack, fuel); registered by the Cold Sweat compat.</li>
 * </ul>
 */
public final class HeatDrivers {

    /** How strongly a driven source runs now, 0-1. Server thread. */
    @FunctionalInterface
    public interface Driver {
        double share(ServerLevel level, BlockPos pos, BlockState state, BlockEntity blockEntity);
    }

    public static final ResourceLocation RUNNING = id("running");
    public static final ResourceLocation HOT_AIR_BURNER = id("hot_air_burner");
    public static final ResourceLocation COLD_SWEAT_HEATING = id("cold_sweat_heating");
    public static final ResourceLocation COLD_SWEAT_COOLING = id("cold_sweat_cooling");

    private static final Map<ResourceLocation, Driver> DRIVERS = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> WARNED = ConcurrentHashMap.newKeySet();

    static {
        register(RUNNING, (level, pos, state, be) -> Engines.running(be));
        if (ModList.get() == null || ModList.get().isLoaded("aeronautics")) {
            register(HOT_AIR_BURNER, (level, pos, state, be) -> Aeronautics.burner(be));
        }
    }

    /** Adds (or replaces) a driver. */
    public static void register(ResourceLocation id, Driver driver) {
        DRIVERS.put(id, driver);
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
        try {
            double s = driver.share(level, pos, state, be);
            return Double.isFinite(s) ? Math.max(0, Math.min(1, s)) : 0;
        } catch (RuntimeException | LinkageError e) {
            if (WARNED.add(variant.driver())) {
                Meteodyne.LOGGER.warn("Heat driver {} failed; that source stays off", variant.driver(), e);
            }
            return 0;
        }
    }

    private static ResourceLocation id(String path) {
        return Meteodyne.id(path);
    }

    /** Engines; each mod's classes are only touched when it is loaded. */
    private static final class Engines {
        private static final boolean DIESEL = ModList.get() == null || ModList.get().isLoaded("createdieselgenerators");
        private static final boolean SIMULATED = ModList.get() == null || ModList.get().isLoaded("simulated");

        static double running(BlockEntity be) {
            if (SIMULATED) {
                double s = Simulated.running(be);
                if (s >= 0) {
                    return s;
                }
            }
            if (DIESEL) {
                double s = Diesel.running(be);
                if (s >= 0) {
                    return s;
                }
            }
            if (be instanceof com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity g) {
                return g.getGeneratedSpeed() != 0 ? 1 : 0;
            }
            return 0;
        }
    }

    private static final class Simulated {
        /** 1 burning, 0 not, -1 not a portable engine. */
        static double running(BlockEntity be) {
            if (be instanceof dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity p) {
                return p.getCurrentBurnTime() > 0 ? 1 : 0;
            }
            return -1;
        }
    }

    private static final class Aeronautics {
        /** The burner's output against the most a burner can give, 0-1 (0 if it isn't one). */
        static double burner(BlockEntity be) {
            if (!(be instanceof dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity b)
                    || !b.canOutputGas()) {
                return 0;
            }
            double max = dev.eriksonn.aeronautics.config.AeroConfig.server().blocks.hotAirBurnerMaxHotAir.get();
            return max > 0 ? b.getGasOutput() / max : 0;
        }
    }

    private static final class Diesel {
        /** 1 running, 0 not, -1 not a diesel engine. */
        static double running(BlockEntity be) {
            if (be instanceof com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity h) {
                var shaft = h.getShaft();
                return h.validFS() && shaft != null && shaft.getGeneratedSpeed() != 0 ? 1 : 0;
            }
            if (be instanceof com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity m) {
                var controller = m.getControllerBE();
                return controller != null && controller.getGeneratedSpeed() != 0 ? 1 : 0;
            }
            if (be instanceof com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity d) {
                return d.getGeneratedSpeed() != 0 ? 1 : 0;
            }
            return -1;
        }
    }

    private HeatDrivers() {
    }
}
