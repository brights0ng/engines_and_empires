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

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** The setting, or its default before the config has loaded. */
    public static boolean uniqueGodsPerServer() {
        return SPEC.isLoaded() ? UNIQUE_GODS_PER_SERVER.get() : false;
    }

    private EmpireConfig() {
    }
}
