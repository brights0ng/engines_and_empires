package dev.brights0ng.sparseores.chips;

import java.util.concurrent.atomic.AtomicReference;

import com.mojang.serialization.MapCodec;

import dev.brights0ng.sparseores.SparseOres;
import dev.brights0ng.sparseores.SparseOresConfig;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The load condition {@code sparse_ores:chip_drops} (split step 4c): true while the config has the ordinary chip ores drop
 * chips ({@link SparseOresConfig#chipDrops}). The ordinary chip ores' smelting and, with Create, crushing carry it
 * ({@link ChipRecipes}), so a silk-touched ore smelts into what mining it drops and crushing follows the same setting. A
 * file whose conditions fail is skipped as it loads; recipes are fine with that, each chip recipe having a whole-item twin
 * under the opposite condition. Loot tables are not: a skipped table leaves the block dropping nothing, so the loot
 * follows the setting at every drop instead ({@link ChipDropFunction}).
 *
 * <p>Conditions are tested when the server loads its data. The setting is a startup config (split step 4d), which the game
 * reads while the mod is constructed, so even the first data load sees the real value and nothing is reloaded. (As a
 * server config, which NeoForge reads only after its world's first data load, the first load saw the default; 4c reloaded
 * the data once when that turned out wrong.) Changing the setting needs a restart.
 */
public final class ChipDropsCondition implements ICondition {

    public static final ResourceLocation ID = ResourceLocation.parse(Chips.CHIP_DROPS_CONDITION);

    public static final ChipDropsCondition INSTANCE = new ChipDropsCondition();

    public static final MapCodec<ChipDropsCondition> CODEC = MapCodec.unit(INSTANCE);

    private static final DeferredRegister<MapCodec<? extends ICondition>> CONDITIONS =
            DeferredRegister.create(NeoForgeRegistries.Keys.CONDITION_CODECS, SparseOres.MODID);

    static {
        CONDITIONS.register(ID.getPath(), () -> CODEC);
    }

    /** What the condition saw the first time anything tested it: whether the config was loaded, and its value. */
    public record Seen(boolean configLoaded, boolean chipDrops) {
    }

    private static final AtomicReference<Seen> FIRST = new AtomicReference<>();

    /** Call from the mod constructor with the mod event bus. */
    public static void register(IEventBus modEventBus) {
        CONDITIONS.register(modEventBus);
    }

    /**
     * What the first test saw (the first data load's, in a game), or null if nothing has tested the condition yet. A game
     * test checks that the config was already loaded then, which is what a startup config is for.
     */
    public static Seen firstSeen() {
        return FIRST.get();
    }

    private ChipDropsCondition() {
    }

    @Override
    public boolean test(IContext context) {
        boolean chipDrops = SparseOresConfig.chipDrops();
        if (FIRST.compareAndSet(null, new Seen(SparseOresConfig.SPEC.isLoaded(), chipDrops))) {
            SparseOres.LOGGER.info("The first data load found chip drops {} in {} (config {})", chipDrops ? "on" : "off",
                    SparseOresConfig.FILE_NAME, SparseOresConfig.SPEC.isLoaded() ? "loaded" : "NOT loaded yet");
        }
        return chipDrops;
    }

    @Override
    public MapCodec<? extends ICondition> codec() {
        return CODEC;
    }

    @Override
    public String toString() {
        return ID.toString();
    }
}
