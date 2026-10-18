package dev.brights0ng.sparseores;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Sparse Ores' settings. A STARTUP config ({@code config/sparse_ores-startup.toml}; until split step 4d a SERVER config,
 * {@code serverconfig/sparse_ores-server.toml} in each world's folder): the game reads it while the mod is constructed,
 * before any data is loaded, so the recipes whose load conditions depend on {@code chipDrops}
 * ({@code sparse_ores:chip_drops}) and the loot see the real value from the first load on. A server config is only read
 * after its world's first data load, and a common config is no better placed for this: a STARTUP config is the one type
 * that is read as soon as it is registered. The price is that the setting is per instance, not per world, and that
 * changing it needs a restart (recipes are decided when the data loads).
 *
 * <p>Code reads them through the methods here, which fall back to the defaults before the config has loaded.
 */
public final class SparseOresConfig {

    public static final ModConfig.Type TYPE = ModConfig.Type.STARTUP;

    /** {@code sparse_ores-startup.toml} in the game's {@code config/} folder (the name NeoForge gives a STARTUP config). */
    public static final String FILE_NAME = "sparse_ores-startup.toml";

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("What ore blocks drop.").push("drops");
    }

    public static final ModConfigSpec.BooleanValue CHIP_DROPS = BUILDER
            .comment("Whether ordinary coal, diamond and nether quartz ore drop one chip (a quarter of the item; four craft",
                    "back into it) instead of the whole item, and so smelt into a chip, and (with Create) crush into chips.",
                    "Off: they drop, smelt and crush into what vanilla and Create give. Rich ores give chips either way",
                    "(five chips; more when crushed).",
                    "Read when the game starts: changing it needs a restart.")
            .gameRestart()
            .define("chipDrops", true);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Whether ordinary coal, diamond and nether quartz ore drop chips. */
    public static boolean chipDrops() {
        return !SPEC.isLoaded() || CHIP_DROPS.get();
    }

    /**
     * For game tests only: sets the switch in the loaded config and drops the cached value, which the restart this setting
     * asks for would otherwise do ({@code gameRestart}: a value set at run time is not picked up by {@link #chipDrops}).
     */
    public static void setChipDropsForTests(boolean chipDrops) {
        CHIP_DROPS.set(chipDrops);
        CHIP_DROPS.clearCache();
    }

    private SparseOresConfig() {
    }
}
