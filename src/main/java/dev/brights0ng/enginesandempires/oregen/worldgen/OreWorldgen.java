package dev.brights0ng.enginesandempires.oregen.worldgen;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers the pack's custom worldgen feature type as {@code engines_and_empires:deposits}.
 *
 * <p>Registering the type only makes it exist. The data files under
 * {@code data/engines_and_empires/} then say where it runs: a configured feature and a placed feature
 * of the same name, and a biome modifier that adds the placed feature to every overworld biome.
 */
public final class OreWorldgen {

    /**
     * TEMPORARY: sky showcase mode. When true, every deposit is built in the open sky around
     * {@link #SHOWCASE_Y}, with stone filled in around its ore and hollow cavities left as air, so its
     * shape can be inspected from outside. The deposit's real height still decides its size, and the
     * debug command still reports it.
     *
     * <p>When false, deposits are built at their real heights inside the existing rock: ore replaces
     * stone and deepslate only, and nothing else is touched.
     */
    public static final boolean SKY_SHOWCASE = false;

    /** TEMPORARY: the height every deposit's center is built at in sky showcase mode. */
    public static final int SHOWCASE_Y = 200;

    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, EnginesAndEmpiresMod.MODID);

    public static final DeferredHolder<Feature<?>, DepositFeature> DEPOSITS =
            FEATURES.register("deposits", DepositFeature::new);

    /** Call from the mod constructor with the mod event bus. */
    public static void register(IEventBus modEventBus) {
        FEATURES.register(modEventBus);
    }

    private OreWorldgen() {
    }
}
