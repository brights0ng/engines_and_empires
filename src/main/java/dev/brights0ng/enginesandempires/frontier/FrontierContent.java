package dev.brights0ng.enginesandempires.frontier;

import java.util.function.Supplier;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierChunkData;
import dev.brights0ng.enginesandempires.frontier.upkeep.DryTorchBlock;
import dev.brights0ng.enginesandempires.frontier.upkeep.DryWallTorchBlock;
import dev.brights0ng.enginesandempires.frontier.upkeep.FrontierDataGen;
import net.minecraft.core.Direction;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Registers what Frontier adds to the game. */
public final class FrontierContent {

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, EnginesAndEmpiresMod.MODID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EnginesAndEmpiresMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EnginesAndEmpiresMod.MODID);

    /** Each chunk's anchor counts, inhabited time and torch clocks, saved with the chunk. */
    public static final Supplier<AttachmentType<FrontierChunkData>> CHUNK_DATA = ATTACHMENTS.register("frontier",
            () -> AttachmentType.serializable(holder -> holder instanceof LevelChunk chunk
                    ? new FrontierChunkData(chunk.getMinSection(), chunk.getSectionsCount())
                    : new FrontierChunkData(-4, 24)).build());

    /** A burnt-out torch: a torch in every way but light and flame. */
    public static final DeferredBlock<DryTorchBlock> DRY_TORCH = BLOCKS.register("dry_torch",
            () -> new DryTorchBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.TORCH).lightLevel(state -> 0)));

    public static final DeferredBlock<DryWallTorchBlock> DRY_WALL_TORCH = BLOCKS.register("dry_wall_torch",
            () -> new DryWallTorchBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.WALL_TORCH).lightLevel(state -> 0)
                    .dropsLike(DRY_TORCH.get())));

    /** Placed against a wall it is a dry wall torch, like a torch. */
    public static final DeferredItem<StandingAndWallBlockItem> DRY_TORCH_ITEM = ITEMS.register("dry_torch",
            () -> new StandingAndWallBlockItem(DRY_TORCH.get(), DRY_WALL_TORCH.get(), new Item.Properties(), Direction.DOWN));

    /** A Warden's heart: eaten at night, it holds the night still for five minutes more. */
    public static final DeferredItem<dev.brights0ng.enginesandempires.frontier.incursion.SquelchingHeartItem> SQUELCHING_HEART =
            ITEMS.register("squelching_heart", () -> new dev.brights0ng.enginesandempires.frontier.incursion.SquelchingHeartItem(
                    new Item.Properties().rarity(net.minecraft.world.item.Rarity.UNCOMMON)));

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        modEventBus.addListener(FrontierContent::addToCreativeTab);
        modEventBus.addListener(FrontierDataGen::gatherData);
        modEventBus.addListener(dev.brights0ng.enginesandempires.frontier.deep.DeepEvents::registerRegistries);
        modEventBus.addListener(dev.brights0ng.enginesandempires.frontier.incursion.IncursionEvents::registerDataMaps);
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(DRY_TORCH_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS) {
            event.accept(SQUELCHING_HEART.get());
        }
    }

    private FrontierContent() {
    }
}
