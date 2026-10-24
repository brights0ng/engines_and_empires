// Written by Claude.
package dev.brights0ng.empire;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Empire's settings. A SERVER config, so each world has its own copy ({@code serverconfig/empire-server.toml} in the world
 * folder).
 */
public final class EmpireConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("The gods empires are founded under.").push("gods");
    }

    /** PROVISIONAL (Bright): off by default. */
    public static final ModConfigSpec.BooleanValue UNIQUE_GODS_PER_SERVER = BUILDER
            .comment("Whether each god may watch over only one empire on the server. When true, a god another empire",
                    "already has is refused at founding and adoption.")
            .define("uniqueGodsPerServer", false);

    /** Bright, 2026-10-23: one in-game year by default. */
    public static final ModConfigSpec.LongValue GOD_PATIENCE_TICKS = BUILDER
            .comment("How long, in ticks counted while a member is online, a god goes unfed before it abandons its empire.",
                    "From 75% of it the god is hungry and puts out the main bonfire. -1 means one in-game year (Meteodyne's",
                    "year when it is installed, else 1728000: 72 days).")
            .defineInRange("godPatienceTicks", -1L, -1L, Long.MAX_VALUE);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** The setting, or its default before the config has loaded. */
    public static boolean uniqueGodsPerServer() {
        return SPEC.isLoaded() ? UNIQUE_GODS_PER_SERVER.get() : false;
    }

    /** The setting, or -1 (one year) before the config has loaded. */
    public static long godPatienceTicks() {
        return SPEC.isLoaded() ? GOD_PATIENCE_TICKS.get() : -1L;
    }

    private EmpireConfig() {
    }
}
