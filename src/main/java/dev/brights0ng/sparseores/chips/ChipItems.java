package dev.brights0ng.sparseores.chips;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.brights0ng.sparseores.SparseOres;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The chip items ({@link Chips}): a quarter of a coal, diamond or nether quartz. Four chips craft back into the whole
 * item (see {@link Chips#CHIPS_PER_ITEM}).
 */
public final class ChipItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SparseOres.MODID);

    /** Every chip, by name, in registration order. */
    private static final Map<String, DeferredItem<Item>> CHIPS = new LinkedHashMap<>();

    static {
        for (Chips.Chip chip : Chips.ALL) {
            CHIPS.put(chip.name(), ITEMS.registerSimpleItem(chip.name()));
        }
    }

    /** Call from the mod constructor with the mod event bus. */
    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(ChipItems::addToCreativeTab);
    }

    /** The chip with this name, such as {@code coal_chip}. */
    public static Item chip(String name) {
        DeferredItem<Item> chip = CHIPS.get(name);
        if (chip == null) {
            throw new IllegalArgumentException("No chip named '" + name + "'");
        }
        return chip.get();
    }

    /** Puts the chips in the creative inventory's ingredients tab. */
    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            for (DeferredItem<Item> chip : CHIPS.values()) {
                event.accept(chip.get());
            }
        }
    }

    private ChipItems() {
    }
}
