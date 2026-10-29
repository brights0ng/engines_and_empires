// Written by Claude.
package dev.brights0ng.meteodyne.heat.airgrid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.brights0ng.meteodyne.Meteodyne;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * What every block state is to the air-cell grid ({@link AirCellType}), classified once per state and kept in a table
 * indexed by {@link Block#BLOCK_STATE_REGISTRY} id. Built on server start and rebuilt when tags reload. Rules, first
 * match wins (Bright's D2, D3, D5, D7, D9):
 * <ol>
 *   <li>Overrides: the block tags {@code meteodyne:air_cell/<type>}, checked in {@link AirCellType} order.</li>
 *   <li>Water or lava (source, flowing or waterlogged): FLUID.</li>
 *   <li>Doors, trapdoors, fence gates: DOOR.</li>
 *   <li>Leaves, scaffolding, cobweb, iron bars: PERMEABLE.</li>
 *   <li>Fences, walls, glass panes (plain and stained), chains: THIN.</li>
 *   <li>Snow layers: 1-3 AIR, 4-7 BOTTOM_HALF, 8 SOLID.</li>
 *   <li>The collision shape clipped to the block's own cell: empty AIR, full SOLID, all in the lower half BOTTOM_HALF,
 *       all in the upper half TOP_HALF; otherwise the volume rule: at least 0.75 SOLID, at most 0.25 AIR, else
 *       BOTTOM_HALF or TOP_HALF by the volume centroid.</li>
 * </ol>
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class AirCellClassifier {
    private static final double EPS = 1e-9;
    private static final int TOP_FALL_THROUGH = 20;

    /** The override tags, {@code meteodyne:air_cell/<type lowercase>}. */
    public static final Map<AirCellType, TagKey<Block>> OVERRIDES = overrideTags();

    /** A classified state, and whether it reached the volume rule. */
    public record Classification(AirCellType type, boolean volumeRule) {
    }

    /** States of one block that reached the volume rule. */
    public record FallThrough(String block, int states) {
    }

    /** One build of the table: ordinals per state id, and the volume-rule report. */
    public record Table(int[] types, int volumeRuleStates, List<FallThrough> topVolumeRule, double buildMs) {
        public int states() {
            return types.length;
        }
    }

    private static volatile Table table;

    /** The memoized type of a state. */
    public static AirCellType type(BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        int[] types = table().types();
        return id >= 0 && id < types.length ? AirCellType.of(types[id]) : classify(state, OVERRIDES).type();
    }

    /** The current table, built on first use if the server has not built it yet. */
    public static Table table() {
        Table t = table;
        return t != null ? t : rebuild();
    }

    public static synchronized Table rebuild() {
        table = build(OVERRIDES);
        return table;
    }

    /** Classifies every registered block state. */
    public static Table build(Map<AirCellType, TagKey<Block>> overrides) {
        long start = System.nanoTime();
        int size = Block.BLOCK_STATE_REGISTRY.size();
        int[] types = new int[size];
        int volumeRule = 0;
        Map<Block, Integer> fallThrough = new IdentityHashMap<>();
        for (int id = 0; id < size; id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null) {
                types[id] = AirCellType.SOLID.ordinal();
                continue;
            }
            Classification c = classify(state, overrides);
            types[id] = c.type().ordinal();
            if (c.volumeRule()) {
                volumeRule++;
                fallThrough.merge(state.getBlock(), 1, Integer::sum);
            }
        }
        List<FallThrough> top = new ArrayList<>();
        fallThrough.forEach((block, n) -> top.add(new FallThrough(BuiltInRegistries.BLOCK.getKey(block).toString(), n)));
        top.sort((a, b) -> a.states() != b.states() ? Integer.compare(b.states(), a.states()) : a.block().compareTo(b.block()));
        return new Table(types, volumeRule, Collections.unmodifiableList(new ArrayList<>(top.subList(0, Math.min(TOP_FALL_THROUGH, top.size())))),
                (System.nanoTime() - start) / 1e6);
    }

    /** Classifies one state, uncached. {@code overrides} maps types to the tags that force them. */
    public static Classification classify(BlockState state, Map<AirCellType, TagKey<Block>> overrides) {
        for (AirCellType type : AirCellType.values()) {
            TagKey<Block> tag = overrides.get(type);
            if (tag != null && state.is(tag)) {
                return new Classification(type, false);
            }
        }
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && (fluid.is(FluidTags.WATER) || fluid.is(FluidTags.LAVA))) {
            return new Classification(AirCellType.FLUID, false);
        }
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)) {
            return new Classification(AirCellType.DOOR, false);
        }
        Block block = state.getBlock();
        if (state.is(BlockTags.LEAVES) || block == Blocks.SCAFFOLDING || block == Blocks.COBWEB || block == Blocks.IRON_BARS) {
            return new Classification(AirCellType.PERMEABLE, false);
        }
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || block == Blocks.GLASS_PANE
                || block instanceof StainedGlassPaneBlock || block instanceof ChainBlock) {
            return new Classification(AirCellType.THIN, false);
        }
        if (block instanceof SnowLayerBlock) {
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            return new Classification(layers <= 3 ? AirCellType.AIR : layers <= 7 ? AirCellType.BOTTOM_HALF : AirCellType.SOLID, false);
        }
        return byShape(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    /** The shape rules on a collision shape, clipped to the unit cube first (fence-style shapes rise above it). */
    static Classification byShape(VoxelShape raw) {
        if (raw.isEmpty()) {
            return new Classification(AirCellType.AIR, false);
        }
        VoxelShape shape = Shapes.join(raw, Shapes.block(), BooleanOp.AND);
        if (shape.isEmpty()) {
            return new Classification(AirCellType.AIR, false);
        }
        if (!Shapes.joinIsNotEmpty(Shapes.block(), shape, BooleanOp.ONLY_FIRST)) {
            return new Classification(AirCellType.SOLID, false);
        }
        if (shape.max(Direction.Axis.Y) <= 0.5 + EPS) {
            return new Classification(AirCellType.BOTTOM_HALF, false);
        }
        if (shape.min(Direction.Axis.Y) >= 0.5 - EPS) {
            return new Classification(AirCellType.TOP_HALF, false);
        }
        // The joined shape's boxes are disjoint.
        double[] sums = new double[2];
        shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
            double v = (x1 - x0) * (y1 - y0) * (z1 - z0);
            sums[0] += v;
            sums[1] += v * (y0 + y1) / 2;
        });
        double volume = sums[0];
        AirCellType type;
        if (volume >= 0.75 - EPS) {
            type = AirCellType.SOLID;
        } else if (volume <= 0.25 + EPS) {
            type = AirCellType.AIR;
        } else {
            type = sums[1] / volume < 0.5 ? AirCellType.BOTTOM_HALF : AirCellType.TOP_HALF;
        }
        return new Classification(type, true);
    }

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        Table t = rebuild();
        Meteodyne.LOGGER.debug("Air cells: classified {} block states in {} ms; {} by the volume rule",
                t.states(), String.format(Locale.ROOT, "%.1f", t.buildMs()), t.volumeRuleStates());
    }

    @SubscribeEvent
    static void onTagsUpdated(TagsUpdatedEvent event) {
        rebuild();
    }

    private static Map<AirCellType, TagKey<Block>> overrideTags() {
        Map<AirCellType, TagKey<Block>> tags = new EnumMap<>(AirCellType.class);
        for (AirCellType type : AirCellType.values()) {
            tags.put(type, TagKey.create(Registries.BLOCK, Meteodyne.id("air_cell/" + type.name().toLowerCase(Locale.ROOT))));
        }
        return Collections.unmodifiableMap(tags);
    }

    private AirCellClassifier() {
    }
}
