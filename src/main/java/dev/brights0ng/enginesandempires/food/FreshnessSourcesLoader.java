package dev.brights0ng.enginesandempires.food;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * Loads the {@link FreshnessSources} buckets from data packs: every {@code data/<namespace>/freshness_sources/<name>.json}.
 * A data pack replaces one of the pack's buckets by using the same namespace and name, or adds its own under any other.
 *
 * <pre>{@code
 * {
 *   "loot_tables": ["minecraft:chests/village/*", "somemod:chests/farmhouse"],
 *   "traders": ["minecraft:villager"],
 *   "stages": { "fresh": 1, "ripe": 1 }
 * }
 * }</pre>
 * All three are optional (a bucket with no stages hands out fresh food). Stages are {@code fresh}, {@code ripe},
 * {@code stale} and {@code rotting}, each with a whole-number weight.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class FreshnessSourcesLoader extends SimpleJsonResourceReloadListener {

    public static final String DIRECTORY = "freshness_sources";

    private static final Gson GSON = new GsonBuilder().create();

    private static final Codec<FoodStage> STAGE = Codec.STRING.comapFlatMap(name -> {
        try {
            return DataResult.success(FoodStage.valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "Unknown freshness stage '" + name + "' (fresh, ripe, stale or rotting)");
        }
    }, FoodStage::id);

    private record Json(List<String> lootTables, List<String> traders, Map<FoodStage, Integer> stages) {
    }

    private static final Codec<Json> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().optionalFieldOf("loot_tables", List.of()).forGetter(Json::lootTables),
            Codec.STRING.listOf().optionalFieldOf("traders", List.of()).forGetter(Json::traders),
            Codec.unboundedMap(STAGE, ExtraCodecs.NON_NEGATIVE_INT).optionalFieldOf("stages", Map.of()).forGetter(Json::stages)
    ).apply(instance, Json::new));

    private static volatile FreshnessSources current = FreshnessSources.EMPTY;

    /** The buckets now loaded (empty before the server's data has loaded). */
    public static FreshnessSources current() {
        return current;
    }

    public FreshnessSourcesLoader() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        List<FreshnessSources.Bucket> buckets = new ArrayList<>();
        // Sorted by id, so ties between buckets are settled the same way every time
        for (Map.Entry<ResourceLocation, JsonElement> file : new TreeMap<>(files).entrySet()) {
            CODEC.parse(JsonOps.INSTANCE, file.getValue())
                    .resultOrPartial(error -> EnginesAndEmpiresMod.LOGGER.error("Freshness source {}: {}", file.getKey(), error))
                    .ifPresent(json -> buckets.add(new FreshnessSources.Bucket(file.getKey().toString(), json.lootTables(),
                            json.traders(), json.stages())));
        }
        current = new FreshnessSources(buckets);
        EnginesAndEmpiresMod.LOGGER.info("Loaded {} food freshness source buckets", buckets.size());
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new FreshnessSourcesLoader());
    }
}
