package dev.brights0ng.enginesandempires.food;

import javax.annotation.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Stamps food as it comes out of loot chests and villager trades, by the {@link FreshnessSources} bucket it came from: each
 * stack rolls its stage, and lands at a random point inside it. Only food that has not been stamped yet is touched.
 */
public final class SourceFreshness {

    /** After a loot table fills a container (a chest, barrel or chest minecart opened for the first time). */
    public static void stampLoot(Container container, @Nullable ResourceLocation lootTable, RandomSource random) {
        FreshnessSources.Bucket bucket = FreshnessSourcesLoader.current()
                .forLootTable(lootTable == null ? "" : lootTable.toString()).orElse(null);
        if (bucket == null) {
            return;
        }
        for (int i = 0; i < container.getContainerSize(); i++) {
            stamp(container.getItem(i), bucket, random);
        }
    }

    /**
     * A trade's result, as the trading screen puts it in the result slot. The roll is seeded by the trader, the offer and how
     * many times it has been used, so picking the trade again does not re-roll it; each actual trade rolls anew.
     */
    public static void stampTrade(ItemStack result, Entity trader, int offerIndex, int uses) {
        if (!Spoilage.isSpoilable(result) || Spoilage.stored(result) != null) {
            return;
        }
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(trader.getType()).toString();
        FreshnessSources.Bucket bucket = FreshnessSourcesLoader.current().forTrader(type).orElse(null);
        if (bucket == null) {
            return;
        }
        long seed = trader.getUUID().getLeastSignificantBits() * 31 + trader.getUUID().getMostSignificantBits();
        seed = seed * 31 + offerIndex;
        seed = seed * 31 + uses;
        stamp(result, bucket, RandomSource.create(seed));
    }

    private static void stamp(ItemStack stack, FreshnessSources.Bucket bucket, RandomSource random) {
        if (!Spoilage.isSpoilable(stack) || Spoilage.stored(stack) != null) {
            return;
        }
        FoodStage stage = bucket.roll(random.nextDouble());
        Spoilage.stampAs(stack, stage, random.nextDouble());
    }

    private SourceFreshness() {
    }
}
