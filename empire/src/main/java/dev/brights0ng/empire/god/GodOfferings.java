// Written by Claude.
package dev.brights0ng.empire.god;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.DemandOption;
import dev.brights0ng.empire.rules.OfferingKeys;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * The {@link OfferingKeys} of the loaded gods: built on the server, kept on the client (from
 * {@link GodOfferingsNetwork}) for the client's guess at whether a right click on a lit bonfire is an offering.
 */
public final class GodOfferings {

    /** What the server last sent this client; null until it has. */
    @Nullable
    private static volatile OfferingKeys client;

    /** Everything the loaded gods could take (server side). */
    public static OfferingKeys fromGods() {
        List<List<DemandOption>> optionLists = new ArrayList<>();
        boolean rotting = false;
        for (GodDefinition god : Gods.all()) {
            optionLists.addAll(god.tiers());
            rotting |= god.acceptsRottingMeat();
        }
        return OfferingKeys.of(optionLists, rotting, Tags.Items.FOODS_RAW_MEAT.location().toString());
    }

    static void acceptOnClient(OfferingKeys keys) {
        client = keys;
    }

    /**
     * Client side: whether a lit bonfire might take this stack as an offering. Before the server has said, every item
     * might (the old behaviour: nothing is eaten or placed by mistake while the server decides).
     */
    public static boolean clientMightTake(ItemStack stack) {
        OfferingKeys keys = client;
        return keys == null ? !stack.isEmpty() : matches(keys, stack);
    }

    /** Whether the stack is one of the keys' items, in one of their tags, or holds one of their fluids. */
    public static boolean matches(OfferingKeys keys, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (keys.items().contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
            return true;
        }
        for (String tag : keys.tags()) {
            if (stack.is(Offerings.tag(tag))) {
                return true;
            }
        }
        if (!keys.fluids().isEmpty()) {
            Optional<FluidStack> fluid = FluidUtil.getFluidContained(stack);
            if (fluid.isPresent()) {
                return keys.fluids().contains(BuiltInRegistries.FLUID.getKey(fluid.get().getFluid()).toString());
            }
        }
        return false;
    }

    private GodOfferings() {
    }
}
