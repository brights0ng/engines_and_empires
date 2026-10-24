// Written by Claude.
package dev.brights0ng.empire.client;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.rules.CivilianAppearance;
import dev.brights0ng.empire.rules.CivilianSkinComposer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads the civilian skin templates and their shade lookup tables from the resource packs
 * ({@code civilian_skins/catalog.json}, {@code civilian_skins/shade_luts.json}) and composes a look from them. If
 * anything is missing or malformed the library stays empty and civilians wear the Alex skin.
 */
public final class CivilianSkinLibrary extends SimplePreparableReloadListener<CivilianSkinLibrary.Library> {

    static final int SIZE = 64;
    private static final ResourceLocation CATALOG = Empire.id("civilian_skins/catalog.json");
    private static final ResourceLocation SHADE_LUTS = Empire.id("civilian_skins/shade_luts.json");

    /**
     * {@code order}: categories in composition order. {@code templates}: shade IDs by style id ({@code -1} transparent).
     * {@code luts}: shade ID to opaque ARGB by {@code styleId/paletteId}.
     */
    record Library(List<CivilianAppearance.Category> order, Map<String, int[]> templates,
                   Map<String, Map<Integer, Integer>> luts) {

        CivilianSkinComposer.Result compose(CivilianAppearance look) {
            List<int[]> layers = new ArrayList<>(order.size());
            List<Map<Integer, Integer>> layerLuts = new ArrayList<>(order.size());
            for (CivilianAppearance.Category category : order) {
                String style = styleId(category, look.style(category));
                layers.add(templates.get(style));
                layerLuts.add(luts.get(style + "/" + paletteId(category, look.color(category))));
            }
            return CivilianSkinComposer.compose(layers, layerLuts, SIZE * SIZE);
        }
    }

    @Nullable
    private static volatile Library current;

    /** The loaded library, or null when the last load failed (civilians then wear the fallback skin). */
    @Nullable
    static Library current() {
        return current;
    }

    static String styleId(CivilianAppearance.Category category, int style) {
        return category.id() + "_0" + style;
    }

    static String paletteId(CivilianAppearance.Category category, int color) {
        return category.id() + "_color_0" + color;
    }

    @Override
    @Nullable
    protected Library prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        try {
            return load(resourceManager);
        } catch (Exception e) {
            Empire.LOGGER.error("Civilian skins failed to load; civilians will wear the default skin", e);
            return null;
        }
    }

    @Override
    protected void apply(@Nullable Library library, ResourceManager resourceManager, ProfilerFiller profiler) {
        current = library;
        CivilianSkinCache.clear();
    }

    private static Library load(ResourceManager resourceManager) throws Exception {
        JsonObject catalog = readJson(resourceManager, CATALOG);
        JsonObject shadeLuts = readJson(resourceManager, SHADE_LUTS).getAsJsonObject("categories");
        JsonObject categories = catalog.getAsJsonObject("categories");

        List<CivilianAppearance.Category> order = new ArrayList<>();
        for (JsonElement e : catalog.getAsJsonArray("composition_order")) {
            order.add(CivilianAppearance.Category.valueOf(e.getAsString().toUpperCase(Locale.ROOT)));
        }

        Map<String, int[]> templates = new HashMap<>();
        Map<String, Map<Integer, Integer>> luts = new HashMap<>();
        for (CivilianAppearance.Category category : order) {
            JsonObject entry = categories.getAsJsonObject(category.id());
            Map<String, String> textures = new HashMap<>();
            for (JsonElement e : entry.getAsJsonArray("styles")) {
                JsonObject style = e.getAsJsonObject();
                textures.put(style.get("id").getAsString(), style.get("texture").getAsString());
            }
            JsonObject categoryLuts = shadeLuts.getAsJsonObject(category.id());
            for (int s = 1; s <= CivilianAppearance.VARIANTS; s++) {
                String styleId = styleId(category, s);
                String texture = textures.get(styleId);
                if (texture == null) {
                    throw new IllegalStateException("catalog has no style " + styleId);
                }
                templates.put(styleId, readTemplate(resourceManager, ResourceLocation.parse(texture)));

                JsonObject styleLut = categoryLuts.getAsJsonObject(styleId);
                JsonArray grays = styleLut.getAsJsonArray("gray_values");
                JsonObject colors = styleLut.getAsJsonObject("colors_rgb");
                for (int c = 1; c <= CivilianAppearance.VARIANTS; c++) {
                    String paletteId = paletteId(category, c);
                    JsonArray rgb = colors.getAsJsonArray(paletteId);
                    if (rgb == null || rgb.size() != grays.size()) {
                        throw new IllegalStateException("shade LUT " + styleId + "/" + paletteId + " missing or the wrong length");
                    }
                    Map<Integer, Integer> lut = new HashMap<>();
                    for (int i = 0; i < grays.size(); i++) {
                        lut.put(grays.get(i).getAsInt(), 0xFF000000 | parseHex(rgb.get(i).getAsString()));
                    }
                    luts.put(styleId + "/" + paletteId, Map.copyOf(lut));
                }
            }
        }
        return new Library(List.copyOf(order), Map.copyOf(templates), Map.copyOf(luts));
    }

    private static JsonObject readJson(ResourceManager resourceManager, ResourceLocation id) throws Exception {
        Resource resource = resourceManager.getResource(id).orElseThrow(() -> new IllegalStateException("missing " + id));
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /** A template as shade IDs: {@code -1} where alpha is 0, else the red byte. */
    private static int[] readTemplate(ResourceManager resourceManager, ResourceLocation id) throws Exception {
        Resource resource = resourceManager.getResource(id).orElseThrow(() -> new IllegalStateException("missing " + id));
        try (InputStream in = resource.open(); NativeImage image = NativeImage.read(in)) {
            if (image.getWidth() != SIZE || image.getHeight() != SIZE) {
                throw new IllegalStateException(id + " is " + image.getWidth() + "x" + image.getHeight() + ", not 64x64");
            }
            int[] shades = new int[SIZE * SIZE];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    // NativeImage pixels are ABGR: red in the low byte.
                    int abgr = image.getPixelRGBA(x, y);
                    shades[y * SIZE + x] = (abgr >>> 24) == 0 ? -1 : (abgr & 0xFF);
                }
            }
            return shades;
        }
    }

    private static int parseHex(String hex) {
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        if (digits.length() != 6) {
            throw new IllegalArgumentException("not #RRGGBB: " + hex);
        }
        return Integer.parseInt(digits, 16);
    }
}
