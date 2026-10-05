package dev.brights0ng.enginesandempires.oregen.rich;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The rich ore blocks. Each is a copy of an ordinary ore block that drops {@link RichOres#RICHNESS}
 * times as much; everything else about it is the same.
 *
 * <p>"The same" is achieved by copying rather than restating. A rich block is built from
 * {@link BlockBehaviour.Properties#ofFullCopy} of its counterpart, so hardness, blast resistance, sounds,
 * map colour, light level and whether it needs the right tool all come from the counterpart itself, and
 * follow it if it ever changes. It uses the same block class too: experience-dropping ores drop the same
 * experience, and rich redstone ore lights up and glows exactly as redstone ore does.
 *
 * <p>The counterpart is looked up by id when the block is registered, so blocks from other mods (Create's
 * zinc ore) need no compile-time dependency on that mod. That is why this mod loads after Create: the
 * counterpart must already exist by then.
 */
public final class RichOreBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EnginesAndEmpiresMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EnginesAndEmpiresMod.MODID);

    /** Every rich block, by name, in registration order. */
    private static final Map<String, DeferredBlock<Block>> BY_NAME = new LinkedHashMap<>();

    static {
        for (RichOres.Spec spec : RichOres.ALL) {
            for (RichOres.Variant variant : spec.variants()) {
                DeferredBlock<Block> block = BLOCKS.register(variant.name(), () -> create(spec, variant));
                ITEMS.registerSimpleBlockItem(variant.name(), block);
                BY_NAME.put(variant.name(), block);
            }
        }
    }

    /** Call from the mod constructor with the mod event bus. */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        modEventBus.addListener(RichOreBlocks::addToCreativeTab);
    }

    /** The rich block with this name, such as {@code rich_deepslate_iron_ore}. */
    public static Block block(String name) {
        DeferredBlock<Block> block = BY_NAME.get(name);
        if (block == null) {
            throw new IllegalArgumentException("No rich ore block named '" + name + "'");
        }
        return block.get();
    }

    /** The default state of the rich block with this name. */
    public static BlockState state(String name) {
        return block(name).defaultBlockState();
    }

    /** Every rich block. */
    public static Iterable<Block> all() {
        return BY_NAME.values().stream().<Block>map(DeferredBlock::get).toList();
    }

    private static Block create(RichOres.Spec spec, RichOres.Variant variant) {
        Block counterpart = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(variant.counterpart()))
                .orElseThrow(() -> new IllegalStateException("Cannot register " + variant.name() + ": the block it copies, "
                        + variant.counterpart() + ", is not registered. The mod that adds it must load before this one."));
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.ofFullCopy(counterpart);

        if (spec.redstone()) {
            return new RedStoneOreBlock(properties);
        }
        if (spec.maxXp() > 0) {
            return new DropExperienceBlock(UniformInt.of(spec.minXp(), spec.maxXp()), properties);
        }
        return new Block(properties);
    }

    /** Puts the rich ores in the creative inventory's natural blocks tab. */
    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            for (DeferredBlock<Block> block : BY_NAME.values()) {
                event.accept(block.get());
            }
        }
    }

    private RichOreBlocks() {
    }
}
