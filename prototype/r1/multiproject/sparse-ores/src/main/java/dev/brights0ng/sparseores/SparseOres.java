package dev.brights0ng.sparseores;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.paradynelib.Services;
import dev.brights0ng.paradynelib.ToyProvider;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.data.LanguageProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** R1 prototype: a mod that requires the library, with one toy block, a mixin and its own data generator. */
@Mod(SparseOres.MODID)
public final class SparseOres {

    public static final String MODID = "sparse_ores";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredBlock<Block> SPARSE_ORE = BLOCKS.registerSimpleBlock("sparse_ore", BlockBehaviour.Properties.of());
    public static final DeferredItem<BlockItem> SPARSE_ORE_ITEM = ITEMS.registerSimpleBlockItem(SPARSE_ORE);

    public SparseOres(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        Services.register(ToyProvider.class, new SparseOresProvider());
        modEventBus.addListener(GatherDataEvent.class, SparseOres::gatherData);
        LOGGER.info("[R1] sparse_ores constructed; it sees the library's block {}", dev.brights0ng.paradynelib.ParadyneLib.LIB_BLOCK.getId());
    }

    private static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeClient(), new LanguageProvider(event.getGenerator().getPackOutput(), MODID, "en_us") {
            @Override
            protected void addTranslations() {
                addBlock(SPARSE_ORE, "Sparse Ore");
            }
        });
    }
}
