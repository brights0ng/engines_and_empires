package dev.brights0ng.enginesandempires.oregen.debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.oregen.BiomeRule;
import dev.brights0ng.enginesandempires.oregen.DepthProfile;
import dev.brights0ng.enginesandempires.oregen.OreType;
import dev.brights0ng.enginesandempires.oregen.OreTypes;
import dev.brights0ng.enginesandempires.oregen.Realm;
import dev.brights0ng.enginesandempires.oregen.worldgen.LevelDeposits;
import dev.brights0ng.enginesandempires.oregen.worldgen.OreMaps;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /eae biomes [radius]}: how the biome rules play out in the land around you.
 *
 * <p>Deposit centres are spread evenly over the map, so the average biome factor over an even grid of columns
 * is the average factor a shallow deposit gets. Combined with the share of an ore's blocks that sit in shallow
 * deposits, that says how much the rules change the ore's total supply in this area.
 */
final class BiomeBalance {

    static final int DEFAULT_RADIUS = 2048;
    /** About this many columns are sampled along each side of the square. */
    private static final int SAMPLES_PER_SIDE = 64;
    private static final int BIOMES_LISTED = 10;

    private record Report(String here, Map<String, Double> hereFactors, List<Map.Entry<String, Integer>> biomes,
                          int samples, Map<String, Double> meanFactor, Map<String, Double> meanTotal, long millis) {
    }

    static int report(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        if (Realm.ofDimension(level.dimension().location().toString()) != Realm.OVERWORLD) {
            source.sendFailure(Component.literal("Biome rules only apply in the overworld."));
            return 0;
        }
        LevelDeposits deposits = OreMaps.of(level);
        Vec3 position = source.getPosition();
        int cx = Mth.floor(position.x);
        int cz = Mth.floor(position.z);
        List<OreType> ores = OreTypes.forRealm(Realm.OVERWORLD).stream().filter(OreType::hasBiomeRules).toList();

        source.sendSuccess(() -> Component.literal("Sampling surface biomes within " + radius + " blocks...")
                .withStyle(ChatFormatting.GRAY), false);
        CompletableFuture.supplyAsync(() -> sample(deposits, ores, cx, cz, radius))
                .whenComplete((report, error) -> source.getServer().execute(() -> {
                    if (error != null) {
                        EnginesAndEmpiresMod.LOGGER.error("Biome balance report failed", error);
                        source.sendFailure(Component.literal("Biome report failed: " + error.getMessage()));
                    } else {
                        send(source, ores, report);
                    }
                }));
        return 1;
    }

    private static Report sample(LevelDeposits deposits, List<OreType> ores, int cx, int cz, int radius) {
        long start = System.nanoTime();
        Holder<Biome> hereBiome = deposits.surfaceBiome(cx, cz);
        Map<String, Double> hereFactors = new HashMap<>();
        for (OreType type : ores) {
            hereFactors.put(type.id(), factor(LevelDeposits.biomeRuleFor(type, hereBiome)));
        }

        int step = Math.max(16, (2 * radius) / SAMPLES_PER_SIDE);
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Double> sums = new HashMap<>();
        Map<String, Double> totals = new HashMap<>();
        int samples = 0;
        for (int x = cx - radius; x <= cx + radius; x += step) {
            for (int z = cz - radius; z <= cz + radius; z += step) {
                Holder<Biome> biome = deposits.surfaceBiome(x, z);
                counts.merge(name(biome), 1, Integer::sum);
                for (OreType type : ores) {
                    BiomeRule rule = LevelDeposits.biomeRuleFor(type, biome);
                    sums.merge(type.id(), factor(rule), Double::sum);
                    totals.merge(type.id(), total(type, rule), Double::sum);
                }
                samples++;
            }
        }
        Map<String, Double> means = new HashMap<>();
        Map<String, Double> meanTotals = new HashMap<>();
        for (OreType type : ores) {
            means.put(type.id(), sums.getOrDefault(type.id(), 0.0) / Math.max(1, samples));
            meanTotals.put(type.id(), totals.getOrDefault(type.id(), 0.0) / Math.max(1, samples));
        }
        List<Map.Entry<String, Integer>> biomes = new ArrayList<>(counts.entrySet());
        biomes.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        return new Report(name(hereBiome), hereFactors, biomes, samples, means, meanTotals,
                (System.nanoTime() - start) / 1_000_000L);
    }

    private static double factor(BiomeRule rule) {
        return rule == null ? 1.0 : rule.factor();
    }

    /** How much ore a deposit under this rule holds on average, relative to one with no biome effect. */
    private static double total(OreType type, BiomeRule rule) {
        DepthProfile normal = type.depth();
        DepthProfile depth = rule == null ? normal : rule.depthFor(normal);
        return depth.meanSize(factor(rule)) / normal.meanSize(1.0);
    }

    private static void send(CommandSourceStack source, List<OreType> ores, Report report) {
        StringBuilder here = new StringBuilder();
        for (OreType type : ores) {
            double f = report.hereFactors().get(type.id());
            if (f != 1.0) {
                here.append(here.isEmpty() ? "" : ", ").append(type.id()).append(String.format(Locale.ROOT, " x%.2f", f));
            }
        }
        String hereLine = "Here: " + report.here() + (here.isEmpty() ? " (no ore is affected)" : " (" + here + ")");
        source.sendSuccess(() -> Component.literal(hereLine).withStyle(ChatFormatting.GOLD), false);

        StringBuilder cover = new StringBuilder();
        for (int i = 0; i < Math.min(BIOMES_LISTED, report.biomes().size()); i++) {
            Map.Entry<String, Integer> entry = report.biomes().get(i);
            cover.append(i == 0 ? "" : ", ").append(entry.getKey())
                    .append(String.format(Locale.ROOT, " %.0f%%", 100.0 * entry.getValue() / report.samples()));
        }
        String coverLine = "Surface biomes (" + report.samples() + " columns, " + report.millis() + " ms): " + cover;
        source.sendSuccess(() -> Component.literal(coverLine).withStyle(ChatFormatting.GRAY), false);

        for (OreType type : ores) {
            double mean = report.meanFactor().get(type.id());
            double shallow = type.depth().shallowOreShare();
            double overall = report.meanTotal().get(type.id());
            ChatFormatting colour = Math.abs(overall - 1.0) < 0.05 ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
            String line = String.format(Locale.ROOT,
                    "  %s: shallow deposits x%.2f on average; %.0f%% of its ore is normally shallow; its total here is x%.2f",
                    type.id(), mean, shallow * 100.0, overall);
            source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
        }
    }

    private static String name(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.location().getPath()).orElse("?");
    }

    private BiomeBalance() {
    }
}
