// Written by Claude.
package dev.brights0ng.empire.loot;

import java.util.Set;

import com.mojang.serialization.MapCodec;

import dev.brights0ng.empire.EmpireContent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

/**
 * {@code empire:correct_tool_for_drops}: passes when the tool that broke the block is the right one to get its drops
 * ({@link ItemStack#isCorrectToolForDrops}, so tool tier tags like {@code minecraft:needs_diamond_tool} count), whether or
 * not the block itself requires one.
 */
public final class CorrectToolForDrops implements LootItemCondition {

    public static final CorrectToolForDrops INSTANCE = new CorrectToolForDrops();
    public static final MapCodec<CorrectToolForDrops> CODEC = MapCodec.unit(INSTANCE);

    private CorrectToolForDrops() {
    }

    @Override
    public LootItemConditionType getType() {
        return EmpireContent.CORRECT_TOOL_FOR_DROPS.get();
    }

    @Override
    public Set<LootContextParam<?>> getReferencedContextParams() {
        return Set.of(LootContextParams.TOOL, LootContextParams.BLOCK_STATE);
    }

    @Override
    public boolean test(LootContext context) {
        ItemStack tool = context.getParamOrNull(LootContextParams.TOOL);
        BlockState state = context.getParamOrNull(LootContextParams.BLOCK_STATE);
        return tool != null && state != null && tool.isCorrectToolForDrops(state);
    }
}
