// Written by Claude.
package dev.brights0ng.empire.client;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.rules.CivilianAppearance;
import dev.brights0ng.empire.rules.CivilianSkinComposer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Composed civilian skins as dynamic textures, by packed look, least recently used dropped first. Render thread only.
 */
public final class CivilianSkinCache {

    /** Most composed skins kept as textures at once (provisional). */
    public static final int SKIN_CACHE_MAX = 512;

    static final ResourceLocation FALLBACK = ResourceLocation.withDefaultNamespace("textures/entity/player/slim/alex.png");

    private static final Map<Integer, ResourceLocation> CACHE = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, ResourceLocation> eldest) {
            if (size() > SKIN_CACHE_MAX) {
                Minecraft.getInstance().getTextureManager().release(eldest.getValue());
                return true;
            }
            return false;
        }
    };

    public static ResourceLocation get(CivilianAppearance look) {
        int packed = look.pack();
        ResourceLocation cached = CACHE.get(packed);
        if (cached != null) {
            return cached;
        }
        CivilianSkinLibrary.Library library = CivilianSkinLibrary.current();
        if (library == null) {
            return FALLBACK;
        }
        CivilianSkinComposer.Result result = library.compose(look);
        if (result.misses() > 0) {
            Empire.LOGGER.warn("Civilian skin {} has {} pixels with no shade colour", look.code(), result.misses());
        }
        int size = CivilianSkinLibrary.SIZE;
        NativeImage image = new NativeImage(size, size, true);
        int[] argb = result.argb();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int c = argb[y * size + x];
                // setPixelRGBA takes ABGR in 1.21.1.
                int abgr = (c & 0xFF000000) | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >>> 16) & 0xFF);
                image.setPixelRGBA(x, y, abgr);
            }
        }
        ResourceLocation id = Empire.id("dynamic/civilian_" + String.format(Locale.ROOT, "%08x", packed));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        CACHE.put(packed, id);
        return id;
    }

    public static void clear() {
        var textures = Minecraft.getInstance().getTextureManager();
        CACHE.values().forEach(textures::release);
        CACHE.clear();
    }

    private CivilianSkinCache() {
    }
}
