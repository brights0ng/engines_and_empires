// Written by Claude.
// Civilian needs settings added by ChatGPT.
package dev.brights0ng.empire;

import dev.brights0ng.empire.rules.LoadPlan;
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

    static {
        BUILDER.comment("Civilians keeping their empire's chunks running.").push("civilians");
    }

    /** Bright, D8; PROVISIONAL default (P6). */
    public static final ModConfigSpec.EnumValue<LoadPlan.Mode> CIVILIAN_LOADING = BUILDER
            .comment("Whether civilians keep the empire chunks around them running with no player near.",
                    "ALWAYS: yes, while the empire has members and one was online within civilianLoadingInactiveTicks.",
                    "MEMBER_ONLINE: only while a member of the empire is online. OFF: never.")
            .defineEnum("civilianLoading", LoadPlan.Mode.ALWAYS);

    /** Bright, D11: 5 days of server time. */
    public static final ModConfigSpec.LongValue CIVILIAN_LOADING_INACTIVE_TICKS = BUILDER
            .comment("With civilianLoading ALWAYS: how long, in ticks of server time (time the server is off does not",
                    "count), an empire's civilians keep loading after its last member was online. 8640000 is 5 days at 20 TPS.")
            .defineInRange("civilianLoadingInactiveTicks", LoadPlan.INACTIVE_TICKS, 0L, Long.MAX_VALUE);

    static {
        BUILDER.pop();
    }

    static {
        BUILDER.comment("Civilian food and hunger.").push("civilianNeeds");
    }

    /** PROVISIONAL (Bright): about two bread's worth of food per active day. */
    public static final ModConfigSpec.DoubleValue CIVILIAN_BASAL_EXHAUSTION_PER_TICK = BUILDER
            .comment("Exhaustion a civilian gains every tick on top of what it does. Every 4.0 exhaustion uses 1 saturation, or 1 food once saturation is empty. 0.0037 is about two bread's worth (10 food) a day.")
            .defineInRange("civilianBasalExhaustionPerTick", 0.0037, 0.0, 1.0);

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

    /** The setting, or ALWAYS before the config has loaded. */
    public static LoadPlan.Mode civilianLoading() {
        return SPEC.isLoaded() ? CIVILIAN_LOADING.get() : LoadPlan.Mode.ALWAYS;
    }

    /** The setting, or 5 days before the config has loaded. */
    public static long civilianLoadingInactiveTicks() {
        return SPEC.isLoaded() ? CIVILIAN_LOADING_INACTIVE_TICKS.get() : LoadPlan.INACTIVE_TICKS;
    }

    /** The setting, or the default before the config has loaded. */
    public static double civilianBasalExhaustionPerTick() {
        return SPEC.isLoaded() ? CIVILIAN_BASAL_EXHAUSTION_PER_TICK.get() : 0.0037;
    }

    private EmpireConfig() {
    }
}
