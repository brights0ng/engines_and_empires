package dev.brights0ng.enginesandempires.oregen;

import java.util.List;
import java.util.function.Predicate;

/**
 * One biome's effect on an ore's deposits there.
 *
 * <p>The selector names biomes the way a datapack does: {@code "#minecraft:is_badlands"} is a biome tag,
 * {@code "minecraft:swamp"} a single biome, and {@value #OTHERWISE} means "every biome no other rule
 * matched".
 *
 * <p>The factor multiplies the ore count of a shallow deposit (centred at or above the depth profile's
 * origin, y=0 in the overworld) whose surface biome matches. It is applied after the size profile's cap,
 * so a boosted deposit can exceed it.
 *
 * <p>A rule can also raise the top of the height range deposits are drawn from, so the ore reaches higher in
 * that biome only. {@link #raising} builds such a rule with its factor scaled so that a deposit there holds
 * the same ore on average as it would have without the raise: more of the deposits are shallow, and the
 * factor grows to make up for the big deep ones that are no longer drawn.
 *
 * <p>Nothing here touches Minecraft; the worldgen side decides whether a selector matches.
 *
 * @param selector a biome tag ({@code #namespace:path}), a biome id ({@code namespace:path}), or {@value #OTHERWISE}
 * @param factor   how many times larger (above 1) or smaller (below 1) matching shallow deposits are
 * @param maxY     the highest a deposit's centre may be drawn in this biome, or {@link #KEEP_HEIGHT}
 */
public record BiomeRule(String selector, double factor, int maxY) {

    /** The selector for the fallback rule: it applies wherever no other rule matched. */
    public static final String OTHERWISE = "*";

    /** A {@code maxY} meaning the ore's own height range is used unchanged. */
    public static final int KEEP_HEIGHT = Integer.MIN_VALUE;

    /** Factors outside this range are almost certainly a typo. */
    public static final double MIN_FACTOR = 0.25;
    public static final double MAX_FACTOR = 4.0;

    public BiomeRule {
        if (selector == null || !(OTHERWISE.equals(selector) || selector.matches("#?[a-z0-9_.-]+:[a-z0-9_./-]+"))) {
            throw new IllegalArgumentException("Bad biome selector '" + selector + "'");
        }
        if (!(factor >= MIN_FACTOR && factor <= MAX_FACTOR)) {
            throw new IllegalArgumentException("Biome factor " + factor + " for " + selector
                    + " must be between " + MIN_FACTOR + " and " + MAX_FACTOR);
        }
    }

    /** A rule that only changes size. */
    public BiomeRule(String selector, double factor) {
        this(selector, factor, KEEP_HEIGHT);
    }

    public static BiomeRule of(String selector, double factor) {
        return new BiomeRule(selector, factor);
    }

    /**
     * A rule that raises the ore's height range to {@code maxY} in these biomes and scales deposits there by
     * whatever factor keeps their average ore the same as {@code factor} would have without the raise.
     *
     * @param depth the ore's normal depth profile
     */
    public static BiomeRule raising(String selector, double factor, DepthProfile depth, int maxY) {
        if (maxY <= depth.maxY()) {
            throw new IllegalArgumentException("A raised top (" + maxY + ") must be above the ore's own (" + depth.maxY() + ")");
        }
        double target = depth.meanSize(factor);
        return new BiomeRule(selector, depth.withMaxY(maxY).shallowFactorFor(target), maxY);
    }

    /** True if the selector is a tag rather than a single biome. */
    public boolean isTag() {
        return selector.startsWith("#");
    }

    /** True if this is the fallback rule, which is never tested against a biome. */
    public boolean isOtherwise() {
        return OTHERWISE.equals(selector);
    }

    /** True if this rule changes the height range. */
    public boolean raisesHeight() {
        return maxY != KEEP_HEIGHT;
    }

    /** The tag or biome id without the leading '#'. */
    public String id() {
        return isTag() ? selector.substring(1) : selector;
    }

    /** The depth profile deposits under this rule are drawn from. */
    public DepthProfile depthFor(DepthProfile normal) {
        return raisesHeight() ? normal.withMaxY(maxY) : normal;
    }

    /**
     * The rule that applies to a biome, given a test of which selectors it matches. Where several rules
     * match, the strongest one wins (the one furthest from 1, boost or cut, compared on a log scale), and the
     * earlier rule wins a tie, so rules never stack. If nothing matches, the {@value #OTHERWISE} rule applies,
     * or none (null). The fallback rule is never passed to {@code matches}.
     */
    public static BiomeRule select(List<BiomeRule> rules, Predicate<BiomeRule> matches) {
        BiomeRule best = null;
        double strength = -1.0;
        BiomeRule otherwise = null;
        for (BiomeRule rule : rules) {
            if (rule.isOtherwise()) {
                otherwise = rule;
                continue;
            }
            double s = Math.abs(Math.log(rule.factor()));
            if (s > strength && matches.test(rule)) {
                best = rule;
                strength = s;
            }
        }
        return best != null ? best : otherwise;
    }

    /** The factor of the rule that applies to a biome (see {@link #select}), or 1 if none does. */
    public static double factorFor(List<BiomeRule> rules, Predicate<BiomeRule> matches) {
        BiomeRule rule = select(rules, matches);
        return rule == null ? 1.0 : rule.factor();
    }

    /** The largest factor any of these rules can give, or 1 if none is a boost. */
    public static double maxFactor(List<BiomeRule> rules) {
        double most = 1.0;
        for (BiomeRule rule : rules) {
            most = Math.max(most, rule.factor());
        }
        return most;
    }
}
