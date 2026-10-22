// Written by Claude.
package dev.brights0ng.empire.god;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import dev.brights0ng.empire.Empire;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * The gods, read from every data pack's {@code data/<namespace>/empire/god/*.json} (see {@link GodDefinition}) whenever the
 * server's data loads or {@code /reload} runs. A god's id is its file's path.
 */
public final class Gods extends SimpleJsonResourceReloadListener {

    public static final String DIRECTORY = "empire/god";

    private static final Gson GSON = new GsonBuilder().create();

    /** Sorted by id, so every walk over the gods is in the same order. */
    private static volatile Map<ResourceLocation, GodDefinition> gods = Map.of();

    private Gods() {
        super(GSON, DIRECTORY);
    }

    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new Gods());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, GodDefinition> loaded = new TreeMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
            try {
                loaded.put(file.getKey(), GodDefinition.parse(file.getKey(), file.getValue()));
            } catch (RuntimeException e) {
                Empire.LOGGER.error("Skipping god {}: {}", file.getKey(), e.getMessage());
            }
        }
        gods = Collections.unmodifiableMap(loaded);
        Empire.LOGGER.info("Loaded {} gods", loaded.size());
    }

    /** Every loaded god, in id order. */
    public static Collection<GodDefinition> all() {
        return gods.values();
    }

    @Nullable
    public static GodDefinition get(@Nullable ResourceLocation id) {
        return id == null ? null : gods.get(id);
    }
}
