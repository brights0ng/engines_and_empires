package dev.brights0ng.sparseores;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Sparse Ores' settings. A SERVER config ({@code serverconfig/sparse_ores-server.toml} in the world folder), as the
 * other mods of the family keep their gameplay settings.
 *
 * <p>Code reads them through the methods here, which fall back to the defaults before the config has loaded.
 */
public final class SparseOresConfig {

    public static final String FILE_NAME = "sparse_ores-server.toml";

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("What ore blocks drop.").push("drops");
    }

    public static final ModConfigSpec.BooleanValue CHIP_DROPS = BUILDER
            .comment("Whether ordinary coal, diamond and nether quartz ore drop one chip (a quarter of the item; four craft",
                    "back into it) instead of the whole item. Off: they drop what vanilla gives. Rich ores drop chips either",
                    "way (five chips).")
            .define("chipDrops", true);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Whether ordinary coal, diamond and nether quartz ore drop chips. */
    public static boolean chipDrops() {
        return !SPEC.isLoaded() || CHIP_DROPS.get();
    }

    private SparseOresConfig() {
    }
}
