package dev.brights0ng.enginesandempires;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** R1 prototype: the toy core mod, which requires every other mod; the root project is also the dev aggregator. */
@Mod(EnginesAndEmpiresMod.MODID)
public final class EnginesAndEmpiresMod {

    public static final String MODID = "engines_and_empires";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredBlock<Block> CORE_BLOCK = BLOCKS.registerSimpleBlock("core_block", BlockBehaviour.Properties.of());
    public static final DeferredItem<BlockItem> CORE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(CORE_BLOCK);

    public EnginesAndEmpiresMod(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        LOGGER.info("[R1] engines_and_empires constructed");
    }
}
