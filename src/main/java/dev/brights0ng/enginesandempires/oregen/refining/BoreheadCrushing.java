package dev.brights0ng.enginesandempires.oregen.refining;

import java.util.List;
import java.util.Optional;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * What Create Aeronautics' borehead bearing gets from an ore block: the same as putting the block through Create's
 * crushing wheels, instead of what a pickaxe would drop. Its rock cutting wheels grind the rock, so they give crushed
 * ore; Create's own mechanical drill still drops what a pickaxe does.
 *
 * <p>Only ores (anything in {@code #c:ores}, which includes the rich ores) are crushed. Other blocks, such as stone,
 * drop as normal: crushing wheels would turn stone into gravel.
 *
 * <p>Called by {@code mixin.BoreheadBreakingMixin}, where Offroad breaks a block for a borehead.
 */
public final class BoreheadCrushing {

    /**
     * The items crushing this block would give, rolled now, or {@code null} if the borehead should drop it as normal:
     * it is not an ore, or there is no crushing recipe for it.
     */
    public static List<ItemStack> crush(Level level, BlockState state) {
        if (!state.is(Tags.Blocks.ORES)) {
            return null;
        }
        ItemStack block = new ItemStack(state.getBlock().asItem());
        if (block.isEmpty()) {
            return null;
        }
        Optional<RecipeHolder<Recipe<SingleRecipeInput>>> recipe = AllRecipeTypes.CRUSHING.find(new SingleRecipeInput(block), level);
        if (recipe.isEmpty() || !(recipe.get().value() instanceof ProcessingRecipe<?, ?> crushing)) {
            return null;
        }
        return crushing.rollResults(level.random);
    }

    private BoreheadCrushing() {
    }
}
