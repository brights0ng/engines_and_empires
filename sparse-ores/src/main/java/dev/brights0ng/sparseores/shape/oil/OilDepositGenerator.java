// Written by Gemini.
// Edited by Gemini: expanded size/capacity variation (800k..200M mB) and enforced neighbor clearance clamping to prevent reservoir overlap.
package dev.brights0ng.sparseores.shape.oil;

import java.util.List;

import dev.brights0ng.sparseores.Deposit;
import dev.brights0ng.sparseores.Hashing;
import dev.brights0ng.sparseores.OreLayer;
import dev.brights0ng.sparseores.OreMap;

/**
 * Procedurally generates realistic oil reservoir geometries and capacities influenced by
 * regional terrain structure, tectonic ridge folding, surface biomes, and neighbor clearance.
 */
public final class OilDepositGenerator {

    public static final OreLayer OIL_LAYER = new OreLayer("crude_oil", 700);

    private OilDepositGenerator() {
    }

    public record GeneratedReservoir(OilTrapType trapType, double strikeAngle, OilPotentialField field, int capacity) {
    }

    /**
     * Generates a reservoir field and capacity. Context may be null for offline testing.
     * Computes neighbor clearance to guarantee reservoirs never overlap.
     */
    public static GeneratedReservoir generate(long worldSeed, int centerX, int centerZ, long depositSeed, OilTerrainContext context) {
        OilTrapType trapType = chooseTrapType(depositSeed, centerX, centerZ, context);
        double strikeAngle = computeStrikeAngle(depositSeed, centerX, centerZ, context);

        double maxClearance = Double.POSITIVE_INFINITY;
        if (worldSeed != 0) {
            OreMap oilMap = new OreMap(worldSeed, OIL_LAYER);
            List<Deposit> neighbors = oilMap.depositsNear(centerX, centerZ, 1200);
            for (Deposit n : neighbors) {
                if (n.x() != centerX || n.z() != centerZ) {
                    double dist = Math.hypot(n.x() - centerX, n.z() - centerZ);
                    maxClearance = Math.min(maxClearance, dist * 0.44);
                }
            }
        }

        return buildReservoir(trapType, centerX, centerZ, depositSeed, strikeAngle, maxClearance);
    }

    private static OilTrapType chooseTrapType(long depositSeed, int cx, int cz, OilTerrainContext context) {
        if (context == null) {
            int roll = (int) Math.floorMod(depositSeed, 6);
            return OilTrapType.values()[roll];
        }

        try {
            String biome = context.biomeId(cx, cz).toLowerCase();

            if (context.hasBiomeTag(cx, cz, "#minecraft:is_ocean") || context.hasBiomeTag(cx, cz, "#minecraft:is_deep_ocean") || biome.contains("ocean")) {
                return (Math.floorMod(depositSeed, 2) == 0) ? OilTrapType.MOUND : OilTrapType.CRESCENT;
            }
            if (context.hasBiomeTag(cx, cz, "#minecraft:is_river") || biome.contains("river") || biome.contains("swamp") || biome.contains("mangrove")) {
                return OilTrapType.RIBBON;
            }
            if (context.hasBiomeTag(cx, cz, "#minecraft:is_badlands") || biome.contains("badlands") || biome.contains("desert")) {
                return (Math.floorMod(depositSeed, 2) == 0) ? OilTrapType.CRESCENT : OilTrapType.FAULT_WEDGE;
            }
            if (context.hasBiomeTag(cx, cz, "#minecraft:is_mountain") || biome.contains("peaks") || biome.contains("mountain") || biome.contains("hills")) {
                return (Math.floorMod(depositSeed, 3) == 0) ? OilTrapType.FAULT_WEDGE : OilTrapType.ANTICLINE;
            }

            // Plains, meadows, savannas, basins
            int roll = (int) Math.floorMod(depositSeed, 10);
            if (roll < 4) {
                return OilTrapType.SHEET;
            } else if (roll < 7) {
                return OilTrapType.ANTICLINE;
            } else if (roll < 9) {
                return OilTrapType.MOUND;
            } else {
                return OilTrapType.FAULT_WEDGE;
            }
        } catch (Throwable ignored) {
            int roll = (int) Math.floorMod(depositSeed, 6);
            return OilTrapType.values()[roll];
        }
    }

    public static double computeStrikeAngle(long depositSeed, int cx, int cz, OilTerrainContext context) {
        double fallbackAngle = (((depositSeed & 0xFFFF) / 65535.0) * Math.PI);
        if (context == null) {
            return fallbackAngle;
        }

        try {
            // First try ridge noise router to align with continental folding
            double rE = context.ridgeNoise(cx + 32, cz);
            double rW = context.ridgeNoise(cx - 32, cz);
            double rS = context.ridgeNoise(cx, cz + 32);
            double rN = context.ridgeNoise(cx, cz - 32);
            if (!Double.isNaN(rE) && !Double.isNaN(rW) && !Double.isNaN(rS) && !Double.isNaN(rN)) {
                double gradX = rE - rW;
                double gradZ = rS - rN;
                if (Math.hypot(gradX, gradZ) > 0.04) {
                    // Strike is perpendicular to ridge gradient
                    return Math.atan2(gradZ, gradX) + (Math.PI / 2.0);
                }
            }

            // Fallback to surface height topography gradient
            int yE = context.surfaceY(cx + 32, cz);
            int yW = context.surfaceY(cx - 32, cz);
            int yS = context.surfaceY(cx, cz + 32);
            int yN = context.surfaceY(cx, cz - 32);
            int dx = yE - yW;
            int dz = yS - yN;
            if (Math.abs(dx) + Math.abs(dz) >= 3) {
                return Math.atan2(dz, dx) + (Math.PI / 2.0);
            }
        } catch (Throwable ignored) {
        }

        return fallbackAngle;
    }

    /**
     * Reconstructs a reservoir with default clearance derived from seed.
     */
    public static GeneratedReservoir buildReservoir(OilTrapType trap, int cx, int cz, long seed, double angle) {
        return buildReservoir(trap, cx, cz, seed, angle, Double.POSITIVE_INFINITY);
    }

    /**
     * Reconstructs a reservoir scaled to an exact target radius.
     */
    public static GeneratedReservoir buildReservoirWithRadius(OilTrapType trap, int cx, int cz, long seed, double angle, double targetRadius) {
        return buildReservoir(trap, cx, cz, seed, angle, targetRadius);
    }

    /**
     * Builds a reservoir with widespread capacity (800k to 200M mB) and radius (24m to 150m),
     * clamped by neighbor clearance so deposits never overlap.
     */
    public static GeneratedReservoir buildReservoir(OilTrapType trap, int cx, int cz, long seed, double angle, double maxClearance) {
        long hash = Hashing.mix64(seed ^ 0xD6E8FEB86659FD93L);
        double rollTier = ((hash >>> 16) & 0xFFFF) / 65535.0; // 0..1
        double rollVar1 = ((hash >>> 32) & 0xFFFF) / 65535.0; // 0..1
        double rollVar2 = ((hash >>> 48) & 0xFFFF) / 65535.0; // 0..1
        int seed1 = (int) (seed & 0xFFFF);
        int seed2 = (int) ((seed >>> 16) & 0xFFFF);
        int seed3 = (int) ((seed >>> 32) & 0xFFFF);

        // 4 Geological Tiers:
        // 1. Minor / Pocket (~35%): 24..42m radius, 800k..4.2M mB capacity
        // 2. Standard Field (~35%): 46..72m radius, 5M..19M mB capacity
        // 3. Major Field (~20%): 75..105m radius, 22M..65M mB capacity
        // 4. Supergiant Field (~10%): 110..150m radius, 75M..200M mB capacity
        double baseRadius;
        int baseCap;
        if (rollTier < 0.35) {
            baseRadius = 24.0 + rollVar1 * 18.0;
            baseCap = 800_000 + (int) (rollVar2 * 3_400_000);
        } else if (rollTier < 0.70) {
            baseRadius = 46.0 + rollVar1 * 26.0;
            baseCap = 5_000_000 + (int) (rollVar2 * 14_000_000);
        } else if (rollTier < 0.90) {
            baseRadius = 75.0 + rollVar1 * 30.0;
            baseCap = 22_000_000 + (int) (rollVar2 * 43_000_000);
        } else {
            baseRadius = 110.0 + rollVar1 * 40.0;
            baseCap = 75_000_000 + (int) (rollVar2 * 125_000_000);
        }

        double effectiveRadius = Math.min(baseRadius, maxClearance);
        double scaleFactor = effectiveRadius / baseRadius;
        int cap = Math.max(500_000, (int) Math.round(baseCap * (scaleFactor * scaleFactor)));

        switch (trap) {
            case ANTICLINE -> {
                double a = effectiveRadius / 1.02;
                double b = a * (0.30 + (seed2 / 65535.0) * 0.15);
                return new GeneratedReservoir(trap, angle, new AnticlinePotential(cx, cz, a, b, angle, seed), cap);
            }
            case FAULT_WEDGE -> {
                double dipRatio = 0.85;
                double spanRatio = 0.45;
                double norm = Math.hypot(dipRatio, spanRatio) * 1.05;
                double dip = (dipRatio / norm) * effectiveRadius;
                double span = (spanRatio / norm) * effectiveRadius;
                return new GeneratedReservoir(trap, angle, new FaultWedgePotential(cx, cz, dip, span, angle, seed), cap);
            }
            case RIBBON -> {
                double lenRatio = 0.82;
                double widRatio = 0.22;
                double ampRatio = Math.min(lenRatio * 0.4, 45.0 / effectiveRadius);
                double norm = Math.hypot(lenRatio, ampRatio + widRatio) * 1.05;
                double halfLen = (lenRatio / norm) * effectiveRadius;
                double halfWid = (widRatio / norm) * effectiveRadius;
                return new GeneratedReservoir(trap, angle, new RibbonPotential(cx, cz, halfLen * 2.0, halfWid * 2.0, angle, seed), cap);
            }
            case CRESCENT -> {
                double arc = Math.PI * 0.65 + (seed3 / 65535.0) * 0.25;
                double rRatio = 0.45;
                double thRatio = 0.12;
                double maxRho = rRatio + thRatio;
                double endU = maxRho * Math.cos(arc) - rRatio;
                double endV = maxRho * Math.sin(arc);
                double norm = Math.hypot(endU, endV) * 1.05;
                double r = (rRatio / norm) * effectiveRadius;
                double th = (thRatio / norm) * effectiveRadius;
                return new GeneratedReservoir(trap, angle, new CrescentPotential(cx, cz, r, th, arc, angle), cap);
            }
            case MOUND -> {
                MoundPotential probe = new MoundPotential(cx, cz, 100.0, seed);
                double baseR = (100.0 / probe.maxRadius()) * effectiveRadius;
                return new GeneratedReservoir(trap, angle, new MoundPotential(cx, cz, baseR, seed), cap);
            }
            case SHEET -> {
                double r = effectiveRadius / 1.15;
                return new GeneratedReservoir(trap, angle, new SheetPotential(cx, cz, r, seed), cap);
            }
        }
        throw new IllegalStateException("Unhandled trap: " + trap);
    }
}
