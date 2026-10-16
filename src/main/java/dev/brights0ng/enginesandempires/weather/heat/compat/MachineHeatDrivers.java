package dev.brights0ng.enginesandempires.weather.heat.compat;

import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.meteodyne.heat.world.HeatDrivers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;

/**
 * The pack's machines as driven sources in Meteodyne's heat simulation (weather phase 8f-3; moved out of Meteodyne's
 * {@link HeatDrivers} in split seam S13). Their {@code heat_power} entries name these drivers:
 * <ul>
 *   <li>{@link #RUNNING}: a Create Diesel Generators engine (normal, huge cylinder, modular part) or a Create
 *       Aeronautics portable engine that is running (turning, or burning fuel).</li>
 *   <li>{@link #HOT_AIR_BURNER}: a Create Aeronautics hot air burner, by how much hot air it puts out against the most
 *       one can (its own setting x the redstone signal / 15, over the server's {@code hotAirBurnerMaxHotAir}).</li>
 * </ul>
 * Cold Sweat's hearths have drivers of their own ({@code weather/climate/compat/ColdSweatHearths}).
 */
public final class MachineHeatDrivers {

    public static final ResourceLocation RUNNING = EnginesAndEmpires.id("running");
    public static final ResourceLocation HOT_AIR_BURNER = EnginesAndEmpires.id("hot_air_burner");

    /** Registers the drivers with Meteodyne's heat simulation. Call at mod setup. */
    public static void register() {
        HeatDrivers.register(RUNNING, (level, pos, state, be) -> Engines.running(be));
        if (ModList.get() == null || ModList.get().isLoaded("aeronautics")) {
            HeatDrivers.register(HOT_AIR_BURNER, (level, pos, state, be) -> Aeronautics.burner(be));
        }
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

    private MachineHeatDrivers() {
    }
}
