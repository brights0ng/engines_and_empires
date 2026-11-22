// Written by Claude.
// Civilian needs settings added by ChatGPT.
// Civilian fulfilment settings added by Gemini.
package dev.brights0ng.empire;

import java.util.List;

import dev.brights0ng.empire.rules.FulfilmentModel;
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

    static {
        BUILDER.comment("Civilian fulfilment, expectation, comfort and grievance.").push("civilianFulfilment");
    }

    /** PROVISIONAL: default value returned by meters whose source system is not built yet. */
    public static final ModConfigSpec.DoubleValue FULFILMENT_PLACEHOLDER_DEFAULT = BUILDER
            .comment("Default value (0-1) returned by fulfilment meters whose source system does not exist yet.")
            .defineInRange("placeholderDefault", FulfilmentModel.DEFAULT_PLACEHOLDER, 0.0, 1.0);

    /** Safety meter hit reference H_ref (default 10). */
    public static final ModConfigSpec.DoubleValue FULFILMENT_SAFETY_H_REF = BUILDER
            .comment("Safety meter hit reference H_ref: hits over the last 3 active days (minus 3 per guard) that drop Safety to 0.")
            .defineInRange("safetyHitReference", FulfilmentModel.DEFAULT_SAFETY_H_REF, 1.0, 1000.0);

    /** Comfort range lower bound (C). */
    public static final ModConfigSpec.DoubleValue FULFILMENT_COMFORT_MIN_C = BUILDER
            .comment("Lower bound of the comfortable temperature range for civilians, in degrees C.")
            .defineInRange("comfortMinC", FulfilmentModel.DEFAULT_COMFORT_MIN_C, -100.0, 100.0);

    /** Comfort range upper bound (C). */
    public static final ModConfigSpec.DoubleValue FULFILMENT_COMFORT_MAX_C = BUILDER
            .comment("Upper bound of the comfortable temperature range for civilians, in degrees C.")
            .defineInRange("comfortMaxC", FulfilmentModel.DEFAULT_COMFORT_MAX_C, -100.0, 100.0);

    /** Degrees C beyond comfort range at which Comfort reaches 0.0. */
    public static final ModConfigSpec.DoubleValue FULFILMENT_COMFORT_DANGER_MARGIN_C = BUILDER
            .comment("Degrees C beyond comfortMinC / comfortMaxC at which Comfort drops linearly to 0.0.")
            .defineInRange("comfortDangerMarginC", FulfilmentModel.DEFAULT_COMFORT_DANGER_MARGIN_C, 1.0, 100.0);

    /** Default hard cap on empire civilian population (50). */
    public static final ModConfigSpec.IntValue FULFILMENT_POPULATION_CAP = BUILDER
            .comment("Hard cap on the number of civilians an empire can hold.")
            .defineInRange("populationCap", FulfilmentModel.DEFAULT_POPULATION_CAP, 1, 500);

    private static final List<String> DEFAULT_EXPECTATION_ENTRIES = List.of(
            "1:0.20", "5:0.35", "10:0.50", "15:0.60", "20:0.70", "25:0.80",
            "30:0.90", "35:1.00", "40:1.10", "45:1.20", "50:1.30"
    );

    /** Empire-wide Expectation E(n) curve points, formatted as "n:E" and linearly interpolated. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> FULFILMENT_EXPECTATION_CURVE = BUILDER
            .comment("Expectation E(n) curve points formatted as \"population:expectation\", linearly interpolated.")
            .defineListAllowEmpty("expectationCurve", () -> DEFAULT_EXPECTATION_ENTRIES, () -> "1:0.20",
                    o -> o instanceof String s && s.contains(":"));

    /** Fulfilment threshold T below which grievance builds and above which it drains. */
    public static final ModConfigSpec.DoubleValue FULFILMENT_GRIEVANCE_THRESHOLD = BUILDER
            .comment("Fulfilment threshold T: grievance builds while below T and drains while above T (paused when no member is online).")
            .defineInRange("grievanceThreshold", FulfilmentModel.DEFAULT_GRIEVANCE_THRESHOLD, 0.0, 2.0);

    /** PROVISIONAL grievance build rate per active day times shortfall. */
    public static final ModConfigSpec.DoubleValue FULFILMENT_GRIEVANCE_BUILD_PER_DAY = BUILDER
            .comment("PROVISIONAL: grievance gained per active day per unit of Fulfilment shortfall below grievanceThreshold.")
            .defineInRange("grievanceBuildPerDay", FulfilmentModel.DEFAULT_GRIEVANCE_BUILD_PER_DAY, 0.0, 100.0);

    /** PROVISIONAL grievance drain rate per active day while above threshold. */
    public static final ModConfigSpec.DoubleValue FULFILMENT_GRIEVANCE_DRAIN_PER_DAY = BUILDER
            .comment("PROVISIONAL: grievance drained per active day while Fulfilment is above grievanceThreshold.")
            .defineInRange("grievanceDrainPerDay", FulfilmentModel.DEFAULT_GRIEVANCE_DRAIN_PER_DAY, 0.0, 100.0);

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

    public static double fulfilmentPlaceholderDefault() {
        return SPEC.isLoaded() ? FULFILMENT_PLACEHOLDER_DEFAULT.get() : FulfilmentModel.DEFAULT_PLACEHOLDER;
    }

    public static double fulfilmentSafetyHitRef() {
        return SPEC.isLoaded() ? FULFILMENT_SAFETY_H_REF.get() : FulfilmentModel.DEFAULT_SAFETY_H_REF;
    }

    public static double fulfilmentComfortMinC() {
        return SPEC.isLoaded() ? FULFILMENT_COMFORT_MIN_C.get() : FulfilmentModel.DEFAULT_COMFORT_MIN_C;
    }

    public static double fulfilmentComfortMaxC() {
        return SPEC.isLoaded() ? FULFILMENT_COMFORT_MAX_C.get() : FulfilmentModel.DEFAULT_COMFORT_MAX_C;
    }

    public static double fulfilmentComfortDangerMarginC() {
        return SPEC.isLoaded() ? FULFILMENT_COMFORT_DANGER_MARGIN_C.get() : FulfilmentModel.DEFAULT_COMFORT_DANGER_MARGIN_C;
    }

    public static int fulfilmentPopulationCap() {
        return SPEC.isLoaded() ? FULFILMENT_POPULATION_CAP.get() : FulfilmentModel.DEFAULT_POPULATION_CAP;
    }

    public static List<FulfilmentModel.ExpectationPoint> fulfilmentExpectationCurve() {
        return SPEC.isLoaded()
                ? FulfilmentModel.parseExpectationCurve(FULFILMENT_EXPECTATION_CURVE.get())
                : FulfilmentModel.DEFAULT_EXPECTATION_CURVE;
    }

    public static double fulfilmentGrievanceThreshold() {
        return SPEC.isLoaded() ? FULFILMENT_GRIEVANCE_THRESHOLD.get() : FulfilmentModel.DEFAULT_GRIEVANCE_THRESHOLD;
    }

    public static double fulfilmentGrievanceBuildPerDay() {
        return SPEC.isLoaded() ? FULFILMENT_GRIEVANCE_BUILD_PER_DAY.get() : FulfilmentModel.DEFAULT_GRIEVANCE_BUILD_PER_DAY;
    }

    public static double fulfilmentGrievanceDrainPerDay() {
        return SPEC.isLoaded() ? FULFILMENT_GRIEVANCE_DRAIN_PER_DAY.get() : FulfilmentModel.DEFAULT_GRIEVANCE_DRAIN_PER_DAY;
    }

    private EmpireConfig() {
    }
}
