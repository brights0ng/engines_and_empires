package dev.brights0ng.enginesandempires.weather.surface;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Glaze: the clear ice freezing rain leaves on freezing ground (phase 5c of the weather backbone, 2026-10-08). Bright:
 * "an ice layer, similar to the snow layer except players slide like on normal ice. Melts as standard ice melts."
 *
 * <ul>
 *   <li>Up to {@link #MAX} layers, a pixel each; slippery as ice (friction 0.98: entities read it through
 *       {@code EntityGlazeFrictionMixin}, since vanilla looks half a block below the feet, past a layer this thin).</li>
 *   <li>It can coat snow layers: {@link #SNOW} is how many snow layers lie under it (0: none). Snow doesn't settle on
 *       glaze. Mining glazed snow takes the glaze off and leaves the snow.</li>
 *   <li>It melts to nothing (not water): by the weather's thaw ({@link SurfaceWeather}), or in block light
 *       {@link SurfaceRules#GLAZE_LIGHT} and up; glazed snow melts back to its snow.</li>
 *   <li>Drops itself only with Silk Touch (its loot table).</li>
 * </ul>
 */
public class GlazeBlock extends Block {

    /** The most layers of glaze (Bright, 2026-10-08: 2, a pixel each). */
    public static final int MAX = SurfaceRules.GLAZE_MAX;
    public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, MAX);
    /** Snow layers under the glaze (0: on bare ground or leaves). 8 layers is a full block: glaze goes on top of it. */
    public static final IntegerProperty SNOW = IntegerProperty.create("snow", 0, 7);

    private static final VoxelShape[] SHAPES = new VoxelShape[17];

    static {
        for (int h = 1; h <= 16; h++) {
            SHAPES[h] = Block.box(0, 0, 0, 16, h, 16);
        }
    }

    public GlazeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LAYERS, 1).setValue(SNOW, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LAYERS, SNOW);
    }

    /** Height in pixels: 2 a snow layer under it, 1 a glaze layer. */
    public static int height(BlockState state) {
        return state.getValue(SNOW) * 2 + state.getValue(LAYERS);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[height(state)];
    }

    /** Hard all the way up: glazed snow is a crust you stand on, not snow you sink into. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[height(state)];
    }

    @Override
    protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return SHAPES[height(state)];
    }

    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[height(state)];
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return type == PathComputationType.LAND && height(state) < 10;
    }

    /** Where glaze can lie: like snow (a full top, leaves, or snow 8 layers deep). */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos b = pos.below();
        BlockState below = level.getBlockState(b);
        if (below.is(Blocks.BARRIER) || below.getBlock() instanceof GlazeBlock) {
            return false;
        }
        if (below.is(BlockTags.LEAVES)) {
            return true;
        }
        if (below.is(Blocks.SNOW)) {
            return below.getValue(SnowLayerBlock.LAYERS) == 8;
        }
        return Block.isFaceFull(below.getCollisionShape(level, b), Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        return state.canSurvive(level, pos) ? super.updateShape(state, direction, neighbor, level, pos, neighborPos)
                : Blocks.AIR.defaultBlockState();
    }

    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return context.getItemInHand().is(asItem()) && state.getValue(LAYERS) < MAX;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState existing = context.getLevel().getBlockState(context.getClickedPos());
        if (existing.is(this)) {
            return existing.setValue(LAYERS, Math.min(MAX, existing.getValue(LAYERS) + 1));
        }
        return defaultBlockState();
    }

    /** Neighbouring glaze at least as tall hides the face between them (no doubled translucent sheets). */
    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacent, Direction direction) {
        if (adjacent.getBlock() instanceof GlazeBlock && direction.getAxis().isHorizontal()) {
            return height(adjacent) >= height(state);
        }
        return super.skipRendering(state, adjacent, direction);
    }

    /** Melts in strong block light, like ice (but at {@link SurfaceRules#GLAZE_LIGHT}: it's harder to stop). */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBrightness(LightLayer.BLOCK, pos) >= SurfaceRules.GLAZE_LIGHT) {
            melt(level, pos, state);
        }
    }

    /** Mining glazed snow takes off the glaze and leaves the snow. */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest,
                                       FluidState fluid) {
        int snow = state.getValue(SNOW);
        if (snow <= 0) {
            return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
        }
        playerWillDestroy(level, pos, state, player);
        return level.setBlock(pos, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, snow),
                level.isClientSide ? 11 : 3);
    }

    /** What is left when the glaze at {@code pos} melts: its snow, or nothing. */
    public static BlockState meltsInto(BlockState state) {
        int snow = state.getValue(SNOW);
        return snow > 0 ? Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, snow)
                : Blocks.AIR.defaultBlockState();
    }

    public static void melt(Level level, BlockPos pos, BlockState state) {
        level.setBlockAndUpdate(pos, meltsInto(state));
    }
}
