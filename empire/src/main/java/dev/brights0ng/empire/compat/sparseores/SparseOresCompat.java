// Written by Gemini.
package dev.brights0ng.empire.compat.sparseores;

import dev.brights0ng.sparseores.DepthProfile;
import dev.brights0ng.sparseores.OreLayer;
import dev.brights0ng.sparseores.OreType;
import dev.brights0ng.sparseores.Realm;
import dev.brights0ng.sparseores.SizeProfile;
import dev.brights0ng.sparseores.SparseOresApi;
import dev.brights0ng.sparseores.shape.SeamShape;
import net.neoforged.fml.ModList;

public final class SparseOresCompat {

    public static final String MODID = "sparse_ores";

    private SparseOresCompat() {
    }

    public static void registerIfLoaded() {
        if (ModList.get().isLoaded(MODID)) {
            Hook.register();
        }
    }

    static final class Hook {
        static void register() {
            OreLayer layer = new OreLayer("talc", 192);
            SizeProfile size = new SizeProfile(350, 0.35, 100, 500);
            DepthProfile depth = new DepthProfile(-48, 96);
            OreType talc = new OreType(layer, Realm.OVERWORLD, SeamShape.COAL, size, depth, 0.15);

            SparseOresApi.registerOre(talc, "empire:talc_ore", "empire:deepslate_talc_ore");
        }
    }
}
