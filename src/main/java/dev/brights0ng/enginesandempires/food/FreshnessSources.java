package dev.brights0ng.enginesandempires.food;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How fresh food is when it comes from somewhere other than a player: loot chests and villager trades. Each source belongs to
 * a bucket, and each bucket says which stages its food can be in and how likely each is. Buckets come from data packs
 * ({@code data/<namespace>/freshness_sources/<name>.json}, loaded by {@link FreshnessSourcesLoader}), so modded loot tables
 * and traders can be sorted into them without code.
 *
 * <p>Which bucket a loot table is in: an exact id beats any pattern, and a longer pattern beats a shorter one. A pattern is an
 * id ending in {@code *} (so {@code minecraft:chests/village/*} is every village chest, and {@code *} alone is every loot
 * table: the "everything else" bucket). A trader is matched by its exact entity type id. Food from a trader in no bucket is
 * simply fresh.
 *
 * <p>Bright's buckets (2026-09-27): village chests and villager trades fresh or ripe; illager (pillager outpost, woodland
 * mansion) and piglin (bastion) chests ripe or stale; every other loot chest stale or rotting; each 50/50.
 *
 * <p>Plain Java on purpose (no Minecraft types), so the matching can be unit tested.
 */
public final class FreshnessSources {

    public static final FreshnessSources EMPTY = new FreshnessSources(List.of());

    /**
     * One bucket. {@code stages} maps each stage its food can be in to a weight (so {@code fresh: 1, ripe: 1} is 50/50).
     */
    public record Bucket(String id, List<String> lootTables, List<String> traders, Map<FoodStage, Integer> stages) {

        public Bucket {
            lootTables = List.copyOf(lootTables);
            traders = List.copyOf(traders);
            stages = Map.copyOf(stages);
        }

        /** The stage for a roll {@code r} in [0, 1), by weight, in stage order. Fresh if the bucket names no stages. */
        public FoodStage roll(double r) {
            int total = 0;
            for (FoodStage stage : FoodStage.values()) {
                total += Math.max(0, stages.getOrDefault(stage, 0));
            }
            if (total <= 0) {
                return FoodStage.FRESH;
            }
            double pick = r * total;
            for (FoodStage stage : FoodStage.values()) {
                int weight = Math.max(0, stages.getOrDefault(stage, 0));
                if (pick < weight) {
                    return stage;
                }
                pick -= weight;
            }
            // r was 1 or rounding put it past the end: the last stage with any weight
            for (int i = FoodStage.values().length - 1; i >= 0; i--) {
                if (stages.getOrDefault(FoodStage.values()[i], 0) > 0) {
                    return FoodStage.values()[i];
                }
            }
            return FoodStage.FRESH;
        }
    }

    private final List<Bucket> buckets;

    /** Later buckets win ties (the loader passes them in id order). */
    public FreshnessSources(List<Bucket> buckets) {
        this.buckets = List.copyOf(buckets);
    }

    public List<Bucket> buckets() {
        return buckets;
    }

    /** The bucket for a loot table (null or blank for a table with no id, which only {@code *} matches). */
    public Optional<Bucket> forLootTable(String lootTable) {
        String id = lootTable == null ? "" : lootTable;
        Bucket best = null;
        int bestScore = -1;
        for (Bucket bucket : buckets) {
            for (String pattern : bucket.lootTables()) {
                int score = score(pattern, id);
                if (score >= bestScore && score >= 0) {
                    best = bucket;
                    bestScore = score;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** The bucket for a trader's entity type id. */
    public Optional<Bucket> forTrader(String entityType) {
        Bucket found = null;
        for (Bucket bucket : buckets) {
            if (bucket.traders().contains(entityType)) {
                found = bucket;
            }
        }
        return Optional.ofNullable(found);
    }

    /** How well a pattern matches: -1 not at all, the prefix length for a pattern, above any prefix for an exact id. */
    private static int score(String pattern, String id) {
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return id.startsWith(prefix) ? prefix.length() : -1;
        }
        return pattern.equals(id) ? Integer.MAX_VALUE : -1;
    }
}
