package dev.brights0ng.paradynelib;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** R1 prototype: the library's entry point, with one toy block. */
@Mod(ParadyneLib.MODID)
public final class ParadyneLib {

    public static final String MODID = "paradyne_lib";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredBlock<Block> LIB_BLOCK = BLOCKS.registerSimpleBlock("lib_block", BlockBehaviour.Properties.of());
    public static final DeferredItem<BlockItem> LIB_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(LIB_BLOCK);

    public ParadyneLib(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        LOGGER.info("[R1] paradyne_lib constructed");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
