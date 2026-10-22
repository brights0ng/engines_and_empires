// Written by Gemini.
package dev.brights0ng.empire.bonfire;

import dev.brights0ng.empire.EmpireContent;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public final class BonfireEvents {

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        BlockState state = event.getState();
        if (state.is(EmpireContent.BONFIRE.get()) || state.is(EmpireContent.BONFIRE_PART.get())) {
            ItemStack held = event.getEntity().getMainHandItem();
            if (!held.is(ItemTags.AXES) || !held.isCorrectToolForDrops(state)) {
                event.setNewSpeed(0.0F);
            }
        }
    }

    private BonfireEvents() {
    }
}
