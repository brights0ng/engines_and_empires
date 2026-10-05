package dev.brights0ng.enginesandempires.geophone;

import java.util.EnumMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IProxyHoveringInformation;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The smart logger: a 2x2 brass desk that listens through the geophones around it while it turns, keeps every deposit it
 * hears, and shows them on a map across its top. See {@link SmartLoggerBlockEntity} for how it works, and
 * {@link LoggerDisplay} for the layout of its top.
 *
 * <p>It is four blocks of this one block, told apart by {@link #PART}. The front-left one (the block you place; the rest
 * go to your right and away from you) is the master: it holds the readings, the map and the buttons. The others hand
 * everything to it. Breaking any quarter takes down the whole desk, which drops once, from the master.
 *
 * <p>Driven the way Create's Mechanical Mixer is: each quarter is a small cog on a vertical axis inside the casing, so a
 * cogwheel set against the side meshes with it. All four mesh with each other, so they always turn together.
 */
public class SmartLoggerBlock extends HorizontalKineticBlock
        implements IBE<SmartLoggerBlockEntity>, ICogWheel, IProxyHoveringInformation {

    /** Which quarter of the desk a block is: how far to the right, and how far back, from the front-left master. */
    public enum Part implements StringRepresentable {
        FRONT_LEFT("front_left", 0, 0),
        FRONT_RIGHT("front_right", 1, 0),
        BACK_LEFT("back_left", 0, 1),
        BACK_RIGHT("back_right", 1, 1);

        private final String name;
        private final int right;
        private final int back;

        Part(String name, int right, int back) {
            this.name = name;
            this.right = right;
            this.back = back;
        }

        public int right() {
            return right;
        }

        public int back() {
            return back;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    /** The outline and collision of each quarter, for each way the desk can face. */
    private static final Map<Direction, Map<Part, VoxelShape>> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Map<Part, VoxelShape> byPart = new EnumMap<>(Part.class);
            for (Part part : Part.values()) {
                byPart.put(part, buildShape(facing, part));
            }
            SHAPES.put(facing, byPart);
        }
    }

    public SmartLoggerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(PART, Part.FRONT_LEFT));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART);
        super.createBlockStateDefinition(builder);
    }

    // ---- where the quarters are ----

    /**
     * To the right across the desk, for someone standing at its front. The desk's facing is the way its front faces,
     * towards whoever placed it.
     */
    public static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** Away from someone standing at the desk's front. */
    public static Direction forward(Direction facing) {
        return facing.getOpposite();
    }

    public static LoggerDisplay.Frame frame(Direction facing) {
        Direction right = right(facing);
        Direction forward = forward(facing);
        return new LoggerDisplay.Frame(right.getStepX(), right.getStepZ(), forward.getStepX(), forward.getStepZ());
    }

    /** Where a quarter of the desk whose master is at {@code master} is. */
    public static BlockPos partPos(BlockPos master, Direction facing, Part part) {
        return master.relative(right(facing), part.right()).relative(forward(facing), part.back());
    }

    /** Where the master of the desk this block belongs to is. */
    public static BlockPos masterPos(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(HORIZONTAL_FACING);
        Part part = state.getValue(PART);
        return pos.relative(right(facing), -part.right()).relative(forward(facing), -part.back());
    }

    private static SmartLoggerBlockEntity master(BlockGetter level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(masterPos(pos, state)) instanceof SmartLoggerBlockEntity logger && logger.isMaster()
                ? logger : null;
    }

    // ---- placing and breaking ----

    /** Only placeable with room for the whole desk: to the right, and away from the player. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        for (Part part : Part.values()) {
            BlockPos at = partPos(pos, facing, part);
            if (part != Part.FRONT_LEFT && (!level.getWorldBorder().isWithinBounds(at)
                    || !level.getBlockState(at).canBeReplaced(context))) {
                return null;
            }
        }
        return defaultBlockState().setValue(HORIZONTAL_FACING, facing).setValue(PART, Part.FRONT_LEFT);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        placeParts(level, pos, state.getValue(HORIZONTAL_FACING));
    }

    /** The three quarters beside a master at {@code pos}. Also used by the game tests, which place blocks directly. */
    public static void placeParts(Level level, BlockPos pos, Direction facing) {
        BlockState base = SeismicContent.SMART_LOGGER.get().defaultBlockState().setValue(HORIZONTAL_FACING, facing);
        for (Part part : Part.values()) {
            if (part != Part.FRONT_LEFT) {
                level.setBlock(partPos(pos, facing, part), base.setValue(PART, part), Block.UPDATE_ALL);
            }
        }
    }

    /** Whether every quarter of the desk whose master is at {@code pos} is there. */
    public static boolean intact(LevelReader level, BlockPos pos, Direction facing) {
        for (Part part : Part.values()) {
            BlockState state = level.getBlockState(partPos(pos, facing, part));
            if (!state.is(SeismicContent.SMART_LOGGER.get()) || state.getValue(PART) != part
                    || state.getValue(HORIZONTAL_FACING) != facing) {
                return false;
            }
        }
        return true;
    }

    /**
     * A quarter whose neighbour in the desk has gone goes too. Turning to air here makes the game break the block, with
     * its drops, so the master drops the desk whichever quarter was broken first.
     */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        Direction facing = state.getValue(HORIZONTAL_FACING);
        Part part = state.getValue(PART);
        Part expected = neighbourPart(facing, part, direction);
        if (expected != null && !(neighborState.is(this) && neighborState.getValue(PART) == expected
                && neighborState.getValue(HORIZONTAL_FACING) == facing)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    /** Which quarter of the same desk is next to this one in this direction, or null if that is outside the desk. */
    private static Part neighbourPart(Direction facing, Part part, Direction direction) {
        int right = part.right();
        int back = part.back();
        if (direction == right(facing)) {
            right++;
        } else if (direction == right(facing).getOpposite()) {
            right--;
        } else if (direction == forward(facing)) {
            back++;
        } else if (direction == forward(facing).getOpposite()) {
            back--;
        } else {
            return null;
        }
        for (Part other : Part.values()) {
            if (other.right() == right && other.back() == back) {
                return other;
            }
        }
        return null;
    }

    /** In creative, the master is removed quietly first, so breaking any quarter drops nothing. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(PART) != Part.FRONT_LEFT) {
            BlockPos master = masterPos(pos, state);
            BlockState masterState = level.getBlockState(master);
            if (masterState.is(this)) {
                level.setBlock(master, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, master, Block.getId(masterState));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    // ---- turning ----

    @Override
    public Axis getRotationAxis(BlockState state) {
        return Axis.Y;
    }

    /** Only a meshing cogwheel drives it, as with the mixer: nothing plugs in. */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return false;
    }

    // ---- using it ----

    /**
     * Pressing a button on the bar, clicking the map to zoom in on that spot (sneak to zoom out), or taking a portable record
     * display out of the dock with an empty hand.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        SmartLoggerBlockEntity logger = master(level, pos, state);
        if (logger == null) {
            return InteractionResult.PASS;
        }
        return logger.use(player, hit);
    }

    /** A portable record display in hand: it goes into the dock if clicked there, and works the map and buttons like a bare hand. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof PrdItem) {
            SmartLoggerBlockEntity logger = master(level, pos, state);
            if (logger != null && logger.use(player, hand, hit).consumesAction()) {
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    /** The master drops a display left in its dock as the desk goes. */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof SmartLoggerBlockEntity logger && logger.isMaster()) {
            logger.dropDocked();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** A redstone signal at any quarter can turn the display on. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide) {
            SmartLoggerBlockEntity logger = master(level, pos, state);
            if (logger != null) {
                logger.checkRedstone();
            }
        }
    }

    /** The desk cannot be turned in place. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    /** Picks up the whole desk, whichever quarter the wrench was used on. */
    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        BlockPos master = masterPos(context.getClickedPos(), state);
        if (master.equals(context.getClickedPos()) || context.getPlayer() == null) {
            return super.onSneakWrenched(state, context);
        }
        BlockState masterState = context.getLevel().getBlockState(master);
        if (!masterState.is(this)) {
            return InteractionResult.PASS;
        }
        BlockHitResult atMaster = new BlockHitResult(master.getCenter(), context.getClickedFace(), master, false);
        return onSneakWrenched(masterState, new UseOnContext(context.getPlayer(), context.getHand(), atMaster));
    }

    /** Goggles aimed at any quarter show the master's readout. */
    @Override
    public BlockPos getInformationSource(Level level, BlockPos pos, BlockState state) {
        return masterPos(pos, state);
    }

    // ---- shape ----

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(HORIZONTAL_FACING)).get(state.getValue(PART));
    }

    /** The casing up to the screen, plus the raised bar and its buttons on the right-hand quarters. */
    private static VoxelShape buildShape(Direction facing, Part part) {
        LoggerDisplay.Frame frame = frame(facing);
        VoxelShape shape = box(frame, part, 0, 0, 2, 2, 0, LoggerDisplay.SCREEN_Y);
        if (part.right() == 1) {
            shape = Shapes.or(shape, box(frame, part, LoggerDisplay.SCREEN_WIDTH, 0, LoggerDisplay.WIDTH, LoggerDisplay.DEPTH,
                    LoggerDisplay.SCREEN_Y, LoggerDisplay.BAR_TOP));
            for (LoggerDisplay.Button button : LoggerDisplay.Button.values()) {
                double u = LoggerDisplay.Button.centreU();
                double v = button.centreV();
                shape = Shapes.or(shape, box(frame, part, u - LoggerDisplay.BUTTON_ACROSS / 2, v - LoggerDisplay.BUTTON_ALONG / 2,
                        u + LoggerDisplay.BUTTON_ACROSS / 2, v + LoggerDisplay.BUTTON_ALONG / 2,
                        LoggerDisplay.BAR_TOP, LoggerDisplay.BAR_TOP + LoggerDisplay.BUTTON_HEIGHT));
            }
        }
        return shape.optimize();
    }

    /**
     * A box given in the desk's own {u, v} coordinates (see {@link LoggerDisplay}), cut to the part of it inside one
     * quarter and turned into that block's own coordinates. Empty if the box misses the quarter.
     */
    private static VoxelShape box(LoggerDisplay.Frame frame, Part part, double u1, double v1, double u2, double v2,
                                  double y1, double y2) {
        double a1 = Math.max(0, u1 - part.right());
        double a2 = Math.min(1, u2 - part.right());
        double b1 = Math.max(0, v1 - part.back());
        double b2 = Math.min(1, v2 - part.back());
        if (a1 >= a2 || b1 >= b2) {
            return Shapes.empty();
        }
        double[] x = new double[2];
        double[] z = new double[2];
        corner(frame, a1, b1, x, z, 0);
        corner(frame, a2, b2, x, z, 1);
        return Shapes.box(Math.min(x[0], x[1]), y1, Math.min(z[0], z[1]), Math.max(x[0], x[1]), y2, Math.max(z[0], z[1]));
    }

    /** A point (a across, b along) within a quarter, in that block's own x and z. */
    private static void corner(LoggerDisplay.Frame frame, double a, double b, double[] x, double[] z, int index) {
        x[index] = frame.rightX() != 0 ? (frame.rightX() > 0 ? a : 1 - a) : (frame.forwardX() > 0 ? b : 1 - b);
        z[index] = frame.rightZ() != 0 ? (frame.rightZ() > 0 ? a : 1 - a) : (frame.forwardZ() > 0 ? b : 1 - b);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    public Class<SmartLoggerBlockEntity> getBlockEntityClass() {
        return SmartLoggerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends SmartLoggerBlockEntity> getBlockEntityType() {
        return SeismicContent.SMART_LOGGER_ENTITY.get();
    }
}
