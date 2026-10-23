// Vanilla creative-tab entry removed by Claude (see MeteodyneTab).
package dev.brights0ng.meteodyne.surface;

import dev.brights0ng.meteodyne.Meteodyne;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** What the weather adds on the ground (phase 5c): glaze, the hail damage type and the crops hail knocks back. */
public final class SurfaceContent {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Meteodyne.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Meteodyne.MODID);

    /** Freezing rain's ice layer. */
    public static final DeferredBlock<GlazeBlock> GLAZE = BLOCKS.register("glaze", () -> new GlazeBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.ICE)
                    .friction(0.98F)
                    .strength(0.5F)
                    .sound(SoundType.GLASS)
                    .noOcclusion()
                    .randomTicks()
                    .forceSolidOff()
                    .pushReaction(PushReaction.DESTROY)
                    .isRedstoneConductor((s, l, p) -> false)
                    .isSuffocating((s, l, p) -> false)
                    .isViewBlocking((s, l, p) -> false)));

    public static final DeferredItem<BlockItem> GLAZE_ITEM = ITEMS.registerSimpleBlockItem("glaze", GLAZE);

    /** Crops strong hail knocks back a growth stage (data: vanilla and Farmer's Delight crops). */
    public static final TagKey<Block> HAIL_DAMAGEABLE = BlockTags.create(id("hail_damageable"));

    /** Hail's damage (flat, ignores armour; data/meteodyne/damage_type/hail.json). */
    public static final ResourceKey<DamageType> HAIL = ResourceKey.create(Registries.DAMAGE_TYPE, id("hail"));

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        modEventBus.addListener(SurfaceDataGen::gatherData);
    }

    private static ResourceLocation id(String path) {
        return Meteodyne.id(path);
    }

    private SurfaceContent() {
    }
}
