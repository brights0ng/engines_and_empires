package dev.brights0ng.sparseores.chips;

import com.mojang.serialization.MapCodec;

import dev.brights0ng.sparseores.SparseOres;
import dev.brights0ng.sparseores.SparseOresConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The loot function {@code sparse_ores:chip_drop}, on the loot tables of the ordinary chip ores (vanilla's coal, diamond
 * and nether quartz ores, {@link ChipDataGen.OrdinaryLoot}). It leaves their chips as they are, unless the server config
 * switches chip drops off ({@link SparseOresConfig#chipDrops}): then it turns each chip back into the whole item, one for
 * one, so the block drops exactly what vanilla's table gives (the same count, Fortune formula and explosion decay; Silk
 * Touch gives the block and never reaches it). The setting is read at every drop, so changing it needs no reload.
 *
 * <p>The tables stay chip tables either way, which is what a pack that cuts other ores' drops on top of Sparse Ores'
 * (by overriding those tables, as the Engines and Empires core does) sees. Rich ores' tables do not have this function:
 * rich ores always drop chips.
 */
public final class ChipDropFunction implements LootItemFunction {

    public static final ChipDropFunction INSTANCE = new ChipDropFunction();

    public static final MapCodec<ChipDropFunction> CODEC = MapCodec.unit(INSTANCE);

    private static final DeferredRegister<LootItemFunctionType<?>> TYPES =
            DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, SparseOres.MODID);

    public static final DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<ChipDropFunction>> TYPE =
            TYPES.register("chip_drop", () -> new LootItemFunctionType<>(CODEC));

    /** Call from the mod constructor with the mod event bus. */
    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
    }

    private ChipDropFunction() {
    }

    @Override
    public LootItemFunctionType<ChipDropFunction> getType() {
        return TYPE.get();
    }

    @Override
    public ItemStack apply(ItemStack stack, LootContext context) {
        if (SparseOresConfig.chipDrops()) {
            return stack;
        }
        Chips.Chip chip = Chips.forChipId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (chip == null) {
            return stack;
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(chip.item())), stack.getCount());
    }
}
