package dev.brights0ng.enginesandempires.oregen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.Test;

/**
 * Checks that each shape looks like what it claims to be, by measuring real deposits. Thresholds sit well
 * inside what the shapes actually produce, so these catch a shape breaking, not ordinary variation.
 */
class ShapeBehaviourTest {

    @Test
    void seamsAndVeinsAreThinSheetsAndLumpsAreNot() {
        for (OreType type : List.of(OreTypes.COAL, OreTypes.LAPIS, OreTypes.REDSTONE, OreTypes.GOLD, OreTypes.NETHER_GOLD)) {
            double ratio = median(type, b -> {
                BodyMetrics.Spread s = BodyMetrics.spread(b);
                return s.longest() / s.shortest();
            });
            assertTrue(ratio > 2.0, type.id() + " should be a thin sheet, longest/shortest = " + ratio);
        }
        for (OreType type : List.of(OreTypes.LAPIS, OreTypes.GOLD)) {
            double ratio = median(type, b -> {
                BodyMetrics.Spread s = BodyMetrics.spread(b);
                return s.longest() / s.shortest();
            });
            assertTrue(ratio > 3.5, type.id() + " should be very thin, longest/shortest = " + ratio);
        }
        for (OreType type : List.of(OreTypes.IRON, OreTypes.ZINC)) {
            double ratio = median(type, b -> {
                BodyMetrics.Spread s = BodyMetrics.spread(b);
                return s.longest() / s.shortest();
            });
            assertTrue(ratio < 2.0, type.id() + " should be a chunky lump, longest/shortest = " + ratio);
        }
    }

    @Test
    void seamsLieFlatAndVeinsStandSteep() {
        for (OreType type : List.of(OreTypes.COAL, OreTypes.LAPIS)) {
            double vertical = median(type, b -> Math.abs(BodyMetrics.spread(b).shortestAxis()[1]));
            assertTrue(vertical > 0.8, type.id() + " seam normal should be near vertical, was " + vertical);
        }
        for (OreType type : List.of(OreTypes.REDSTONE, OreTypes.GOLD, OreTypes.NETHER_GOLD)) {
            double vertical = median(type, b -> Math.abs(BodyMetrics.spread(b).shortestAxis()[1]));
            assertTrue(vertical < 0.65, type.id() + " vein normal should be near horizontal, was " + vertical);
        }
    }

    @Test
    void diamondIsATallVerticalPipe() {
        double elongation = median(OreTypes.DIAMOND, b -> {
            BodyMetrics.Spread s = BodyMetrics.spread(b);
            return s.longest() / s.middle();
        });
        double vertical = median(OreTypes.DIAMOND, b -> Math.abs(BodyMetrics.spread(b).longestAxis()[1]));
        assertTrue(elongation > 3.0, "pipe should be much taller than wide, was " + elongation);
        assertTrue(vertical > 0.9, "pipe should be near vertical, was " + vertical);
    }

    @Test
    void disseminatedBodiesAreSparseAndScatteredWhileOthersAreDense() {
        for (OreType type : OreTypes.ALL) {
            double density = median(type, b -> b.oreCount() / (double) (b.oreCount() + b.hostCount()));
            if (type.shape().name().equals("disseminated")) {
                assertTrue(density < 0.15, type.id() + " should be sparse, density " + density);
                double clumps = median(type, b -> BodyMetrics.components(b));
                assertTrue(clumps > 30, type.id() + " ore should be scattered into many clumps, was " + clumps);
            } else {
                assertTrue(density > 0.2, type.id() + " should be dense, density " + density);
            }
        }
    }

    @Test
    void pocketOresFormSeveralSeparateClumps() {
        for (OreType type : List.of(OreTypes.EMERALD, OreTypes.CRYSTAL, OreTypes.NETHER_QUARTZ)) {
            double clumps = median(type, b -> BodyMetrics.components(b));
            assertTrue(clumps >= 2, type.id() + " should be several pockets, was " + clumps);
        }
    }

    @Test
    void layeringSeparatesOreFromHostInLumpsAndSeams() {
        for (OreType type : List.of(OreTypes.IRON, OreTypes.ZINC, OreTypes.COAL, OreTypes.LAPIS)) {
            double gap = median(type, b -> meanStructure(b, DepositBody.ORE, DepositBody.RICH)
                    - meanStructure(b, DepositBody.HOST, DepositBody.HOST));
            assertTrue(gap > 0.3, type.id() + " ore should sit in the rich layers, gap " + gap);
        }
    }

    @Test
    void veinOreSitsInTheShootsAndCopperOreInItsRichZone() {
        for (OreType type : List.of(OreTypes.GOLD, OreTypes.NETHER_GOLD, OreTypes.REDSTONE, OreTypes.COPPER, OreTypes.DIAMOND)) {
            double gap = median(type, b -> meanStructure(b, DepositBody.ORE, DepositBody.RICH)
                    - meanStructure(b, DepositBody.HOST, DepositBody.HOST));
            assertTrue(gap > 0.08, type.id() + " ore should favour the rich structure, gap " + gap);
        }
    }

    @Test
    void richOreSitsWhereTheStructureIsRichest() {
        for (OreType type : OreTypes.ALL) {
            if (type.shape().name().equals("pockets")) {
                continue; // pockets have no layered structure to compare against
            }
            double gap = median(type, b -> meanStructure(b, DepositBody.RICH, DepositBody.RICH)
                    - meanStructure(b, DepositBody.ORE, DepositBody.ORE));
            assertTrue(gap > 0.03, type.id() + " rich ore should sit in richer structure than plain ore, gap " + gap);
        }
    }

    @Test
    void hollowPocketsHaveSizeableSealedCavitiesLinedMostlyWithOre() {
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (OreType type : List.of(OreTypes.CRYSTAL, OreTypes.NETHER_QUARTZ)) {
            double share = median(type, b -> b.voidCount() / (double) b.oreCount());
            assertTrue(share > 0.05, type.id() + " cavities look too small, void/ore = " + share);
            for (DepositBody body : BodySamples.of(type)) {
                // Wall blocks touching a cavity: [ore, rock inside the deposit, nothing at all]
                int[] walls = {0, 0, 0};
                body.forEach((dx, dy, dz, kind) -> {
                    if (kind != DepositBody.VOID) {
                        return;
                    }
                    for (int[] s : steps) {
                        byte next = body.at(dx + s[0], dy + s[1], dz + s[2]);
                        if (next == DepositBody.ORE || next == DepositBody.RICH) {
                            walls[0]++;
                        } else if (next == DepositBody.HOST) {
                            walls[1]++;
                        } else if (next == DepositBody.EMPTY) {
                            walls[2]++;
                        }
                    }
                });
                assertEquals(0, walls[2], type.id() + " has a cavity that opens onto the outside of its own deposit");
                assertTrue(walls[0] >= 0.6 * (walls[0] + walls[1]), type.id() + " cavity walls should be mostly ore");
            }
        }
    }

    private static double median(OreType type, ToDoubleFunction<DepositBody> measure) {
        List<Double> values = new ArrayList<>();
        for (DepositBody body : BodySamples.of(type)) {
            if (body.oreCount() >= 20) {
                values.add(measure.applyAsDouble(body));
            }
        }
        Collections.sort(values);
        return values.get(values.size() / 2);
    }

    /** Mean structure over blocks of either of the two kinds. */
    private static double meanStructure(DepositBody body, byte kindA, byte kindB) {
        double[] sum = {0, 0};
        body.forEach((dx, dy, dz, kind) -> {
            if (kind == kindA || kind == kindB) {
                sum[0] += body.structureAt(dx, dy, dz);
                sum[1]++;
            }
        });
        return sum[1] == 0 ? 0.0 : sum[0] / sum[1];
    }
}
