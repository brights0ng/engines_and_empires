package dev.brights0ng.sparseores.chips;

import com.mojang.serialization.MapCodec;

import dev.brights0ng.sparseores.SparseOres;
import dev.brights0ng.sparseores.SparseOresConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The load condition {@code sparse_ores:chip_drops} (split step 4c): true while the server config has the ordinary chip
 * ores drop chips ({@link SparseOresConfig#chipDrops}). The ordinary chip ores' smelting carries it ({@link ChipRecipes}),
 * so a silk-touched ore smelts into what mining it drops. A file whose conditions fail is skipped as it loads; recipes
 * are fine with that, each chip recipe having a whole-item twin under the opposite condition. Loot tables are not: a
 * skipped table leaves the block dropping nothing, so the loot follows the setting at every drop instead
 * ({@link ChipDropFunction}).
 *
 * <p>Conditions are tested when the server loads its data, and a world's server config is only read after the world's
 * first data load (NeoForge loads server configs at {@code ServerAboutToStartEvent}), so that first load always sees the
 * default. When the setting then turns out different, or is changed while the server runs, the server reloads its data
 * once, as {@code /reload} would, so the recipes follow it.
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

    /** What the last data load was given: null until a load has tested the condition. */
    private static volatile Boolean loadedWith;

    /** Call from the mod constructor with the mod event bus. */
    public static void register(IEventBus modEventBus) {
        CONDITIONS.register(modEventBus);
        modEventBus.addListener(ChipDropsCondition::onConfigReloading);
        NeoForge.EVENT_BUS.addListener(ChipDropsCondition::onServerStarted);
    }

    private ChipDropsCondition() {
    }

    @Override
    public boolean test(IContext context) {
        boolean chipDrops = SparseOresConfig.chipDrops();
        loadedWith = chipDrops;
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

    private static void onServerStarted(ServerStartedEvent event) {
        reloadIfStale(event.getServer());
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (event.getConfig().getSpec() == SparseOresConfig.SPEC && server != null) {
            server.execute(() -> reloadIfStale(server));
        }
    }

    private static void reloadIfStale(MinecraftServer server) {
        boolean chipDrops = SparseOresConfig.chipDrops();
        if (Chips.needsReload(loadedWith, chipDrops)) {
            SparseOres.LOGGER.info("Chip drops are {} in the server config; reloading the data so the chip ores' smelting "
                    + "follows", chipDrops ? "on" : "off");
            loadedWith = chipDrops;
            server.reloadResources(server.getPackRepository().getSelectedIds());
        }
    }
}
