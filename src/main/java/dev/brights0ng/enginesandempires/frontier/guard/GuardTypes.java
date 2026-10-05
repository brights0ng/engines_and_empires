package dev.brights0ng.enginesandempires.frontier.guard;

import dev.brights0ng.enginesandempires.frontier.FrontierTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;

/**
 * Who counts as a guard: anything in the {@code engines_and_empires:guards} tag (iron golems, Guard Villagers' guards), and
 * MineColonies' colonists working a guard job. Also how the world looks to a guard deciding where it can walk.
 */
public final class GuardTypes {

    private static final boolean MINECOLONIES = ModList.get() != null && ModList.get().isLoaded("minecolonies");

    /** Whether the entity is worth tracking: a guard now, or a colonist who could be made one. */
    public static boolean isCandidate(Entity entity) {
        return entity.getType().is(FrontierTags.GUARDS) || (MINECOLONIES && MineColoniesGuards.isColonist(entity));
    }

    /** Whether the entity is a guard right now. */
    public static boolean isGuard(Entity entity) {
        return entity.getType().is(FrontierTags.GUARDS) || (MINECOLONIES && MineColoniesGuards.isGuard(entity));
    }

    /** The world as a guard walking about sees it, for {@link GuardFreedom}. Unloaded ground cannot be walked on. */
    public static GuardFreedom.Ground ground(Level level) {
        return (x, y, z) -> standable(level, new BlockPos(x, y, z));
    }

    /** Something to stand on below (a full block, a slab, a path, farmland: not a fence), and room for a body above. */
    static boolean standable(Level level, BlockPos feet) {
        BlockPos under = feet.below();
        if (!level.isLoaded(feet) || !level.isLoaded(under) || !level.isInWorldBounds(feet)) {
            return false;
        }
        BlockState floor = level.getBlockState(under);
        if (!floor.isFaceSturdy(level, under, Direction.UP)) {
            VoxelShape shape = floor.getCollisionShape(level, under);
            double top = shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y);
            if (top < 0.5 || top > 1.0) {
                return false; // nothing solid underfoot, or a fence or wall that cannot be stood on
            }
        }
        return passable(level, feet) && passable(level, feet.above());
    }

    /** Room for a body: nothing solid (a carpet or a snow layer is fine), and no lava. */
    private static boolean passable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.2;
    }

    private GuardTypes() {
    }
}
