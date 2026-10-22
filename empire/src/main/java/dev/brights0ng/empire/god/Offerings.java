// Written by Claude.
package dev.brights0ng.empire.god;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.DemandLine;
import dev.brights0ng.empire.rules.DemandOption;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** What the ids of a god's demand stand for in game: matching held items and fluids against them, and naming them. */
public final class Offerings {

    /** The item an id names, if it is registered. */
    public static Optional<Item> item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(rl);
    }

    public static TagKey<Item> tag(String id) {
        return TagKey.create(Registries.ITEM, ResourceLocation.parse(id));
    }

    /** The fluid an id names, or null if it is not registered. */
    @Nullable
    public static Fluid fluid(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Fluid fluid = rl == null ? null : BuiltInRegistries.FLUID.getOptional(rl).orElse(null);
        return fluid == null || fluid == Fluids.EMPTY ? null : fluid;
    }

    /** Whether a stack is one of the items an item, items or tag demand names (never for a fluid demand). */
    public static boolean matchesItem(DemandLine.Kind kind, List<String> ids, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return switch (kind) {
            case ITEM, ITEMS -> {
                for (String id : ids) {
                    if (item(id).filter(stack::is).isPresent()) {
                        yield true;
                    }
                }
                yield false;
            }
            case TAG -> stack.is(tag(ids.get(0)));
            case FLUID -> false;
        };
    }

    public static boolean matchesItem(DemandOption option, ItemStack stack) {
        return matchesItem(option.kind(), option.ids(), stack);
    }

    public static boolean matchesItem(DemandLine line, ItemStack stack) {
        return matchesItem(line.kind(), line.ids(), stack);
    }

    /** What a demand line asks for, by name: an item's name, several joined by " / ", a tag's name, a fluid's name. */
    public static Component name(DemandLine.Kind kind, List<String> ids) {
        return switch (kind) {
            case ITEM, ITEMS -> {
                List<Component> names = new ArrayList<>();
                for (String id : ids) {
                    names.add(item(id).<Component>map(Item::getDescription).orElse(Component.literal(id)));
                }
                MutableComponent joined = Component.empty();
                for (int i = 0; i < names.size(); i++) {
                    if (i > 0) {
                        joined.append(" / ");
                    }
                    joined.append(names.get(i));
                }
                yield joined;
            }
            case TAG -> {
                ResourceLocation rl = ResourceLocation.parse(ids.get(0));
                yield Component.translatableWithFallback("tag.item." + rl.getNamespace() + "." + rl.getPath().replace('/', '.'),
                        "#" + rl);
            }
            case FLUID -> {
                Fluid fluid = fluid(ids.get(0));
                yield fluid == null ? Component.literal(ids.get(0)) : fluid.getFluidType().getDescription();
            }
        };
    }

    private Offerings() {
    }
}
