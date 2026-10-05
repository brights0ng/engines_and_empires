package dev.brights0ng.enginesandempires.geophone;

import java.util.List;
import java.util.function.Function;

import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;

import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.client.model.generators.BlockModelBuilder;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ItemModelBuilder;
import net.neoforged.neoforge.client.model.generators.ModelBuilder;
import net.neoforged.neoforge.client.model.generators.ModelBuilder.FaceRotation;
import net.neoforged.neoforge.client.model.generators.ModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/**
 * Generates the combustive thumper's models: a three-tall copper pile-hammer tower, standing on the strike plate.
 *
 * <ul>
 *   <li><b>Base</b> ({@code combustive_thumper_base}): a solid copper housing like the Mechanical Press's, the shaft
 *       running through it, under a heavy iron anvil that the ram lands on.</li>
 *   <li><b>Middle</b> ({@code combustive_thumper_middle}): two dark iron guide rails, and nothing else, so the ram is in
 *       plain sight as it climbs and falls.</li>
 *   <li><b>Top</b> ({@code combustive_thumper_top}): the rails' last few pixels, then the fuel cylinder the raised ram
 *       sits in, open underneath. A fuel gauge on each side face, a ready lamp on each end face, an exhaust stack on top.</li>
 * </ul>
 *
 * <p>Moving and glowing parts are separate models, drawn at render time: the ram, its lift rods, the gauge needle
 * (tinted, so the renderer can turn it red), and the lit lamp.
 *
 * <p>Everything is built for the Z axis (shaft running north-south, gauges on east and west, lamps north and south); the
 * blockstates turn it a quarter for X. Every face is given explicit texture coordinates, worked out the way the game
 * would work them out from the face's position in its own block, so the same geometry can be shifted into the item model
 * (whose elements may only span -16 to 32 pixels) without the textures sliding.
 */
public final class CombustiveDataGen {

    private static final ResourceLocation COPPER = create("block/copper_casing");
    private static final ResourceLocation IRON = create("block/industrial_iron_block");
    private static final ResourceLocation IRON_TOP = create("block/industrial_iron_block_top");
    private static final ResourceLocation AXIS = create("block/axis");
    private static final ResourceLocation AXIS_TOP = create("block/axis_top");
    private static final ResourceLocation POLE = create("block/mechanical_press_pole");
    private static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("block/white_concrete");
    private static final ResourceLocation SCULK = ResourceLocation.withDefaultNamespace("block/sculk");

    public static void trackCreateTextures(ExistingFileHelper existing) {
        for (ResourceLocation texture : List.of(COPPER, IRON, IRON_TOP, AXIS, AXIS_TOP, POLE)) {
            existing.trackGenerated(texture, ModelProvider.TEXTURE);
        }
    }

    static void registerModels(BlockStateProvider provider) {
        BlockModelBuilder base = textures(provider.models().getBuilder("combustive_thumper_base"), provider);
        base(base, 0);
        BlockModelBuilder middle = textures(provider.models().getBuilder("combustive_thumper_middle"), provider);
        middle(middle, 0);
        BlockModelBuilder top = textures(provider.models().getBuilder("combustive_thumper_top"), provider);
        top(top, 0, true);

        BlockModelBuilder ram = textures(provider.models().getBuilder("combustive_thumper_ram"), provider);
        ram(ram, 0);
        BlockModelBuilder rods = textures(provider.models().getBuilder("combustive_thumper_rods"), provider);
        rods(rods, 0, 18);
        BlockModelBuilder needle = provider.models().getBuilder("combustive_thumper_needle")
                .texture("particle", WHITE).texture("needle", WHITE);
        needle(needle);
        BlockModelBuilder lampLit = provider.models().getBuilder("combustive_thumper_lamp_lit")
                .texture("particle", provider.modLoc("block/mechanical_thumper_lamp_lit"))
                .texture("lamp", provider.modLoc("block/mechanical_thumper_lamp_lit"));
        lamps(lampLit, 0, 0.05F);

        provider.getVariantBuilder(SeismicContent.COMBUSTIVE_THUMPER.get()).forAllStates(state ->
                rotated(base, state.getValue(HorizontalAxisKineticBlock.HORIZONTAL_AXIS)));
        provider.getVariantBuilder(SeismicContent.COMBUSTIVE_THUMPER_COLUMN.get()).forAllStates(state ->
                rotated(state.getValue(CombustiveThumperColumnBlock.PART) == CombustiveThumperColumnBlock.Part.MIDDLE
                        ? middle : top, state.getValue(CombustiveThumperColumnBlock.HORIZONTAL_AXIS)));

        itemModel(provider);

        // The choked thumper: only the base is left, its copper eaten through with sculk.
        BlockModelBuilder choked = textures(provider.models().getBuilder("choked_combustive_thumper"), provider)
                .texture("copper", SCULK)
                .texture("particle", SCULK);
        base(choked, 0);
        provider.getVariantBuilder(SeismicContent.CHOKED_COMBUSTIVE_THUMPER.get()).forAllStates(state ->
                rotated(choked, state.getValue(ChokedThumperBlock.HORIZONTAL_AXIS)));
        provider.itemModels().withExistingParent("choked_combustive_thumper", provider.modLoc("block/choked_combustive_thumper"));
    }

    private static ConfiguredModel[] rotated(BlockModelBuilder model, Axis axis) {
        ConfiguredModel.Builder<?> builder = ConfiguredModel.builder().modelFile(model);
        if (axis == Axis.X) {
            builder = builder.rotationY(90);
        }
        return builder.build();
    }

    private static <T extends ModelBuilder<T>> T textures(T model, BlockStateProvider provider) {
        return model.texture("particle", COPPER)
                .texture("copper", COPPER)
                .texture("iron", IRON)
                .texture("iron_top", IRON_TOP)
                .texture("axis", AXIS)
                .texture("axis_top", AXIS_TOP)
                .texture("pole", POLE)
                .texture("lamp", provider.modLoc("block/mechanical_thumper_lamp"))
                .texture("gauge", provider.modLoc("block/combustive_thumper_gauge"));
    }

    // ---- the three blocks ----

    /**
     * The base, solid like the Mechanical Press's housing: two copper side walls, a copper core between them inset a
     * pixel at the two ends the shaft comes through (so the shaft's ends show, as on the press), and the heavy iron anvil
     * the ram lands on across the top. {@code dy} shifts it (the item model needs it lower).
     */
    private static <T extends ModelBuilder<T>> void base(T m, float dy) {
        box(m, dy, 0, 0, 0, 2, 13, 16, d -> d == Direction.UP ? null : "#copper");
        box(m, dy, 14, 0, 0, 16, 13, 16, d -> d == Direction.UP ? null : "#copper");
        box(m, dy, 2, 0, 1, 14, 13, 15, d -> d.getAxis() == Axis.X || d == Direction.UP ? null : "#copper");
        box(m, dy, 0, 13, 0, 16, 16, 16, d -> d == Direction.UP ? "#iron_top" : "#iron");
    }

    /** The rail section: two guide rails, on the faces the shaft does not come through. */
    private static <T extends ModelBuilder<T>> void middle(T m, float dy) {
        rails(m, dy, 16, false);
    }

    /**
     * The guide rails, one pixel in from the block's sides so they sit squarely under the cylinder's walls. With
     * {@code capped}, their tops are drawn too (in the top block, where they end inside the cylinder).
     */
    private static <T extends ModelBuilder<T>> void rails(T m, float dy, float height, boolean capped) {
        Function<Direction, String> faces = d -> d == Direction.DOWN || (d == Direction.UP && !capped) ? null : "#iron";
        box(m, dy, 1, 0, 6, 3, height, 10, faces);
        box(m, dy, 13, 0, 6, 15, height, 10, faces);
    }

    /**
     * The top: the rails' ends, the cylinder (open underneath), gauges, lamps, and (not in the item) the exhaust. The
     * cylinder's cap has two slots, one either side of the exhaust, for the lift rods.
     */
    private static <T extends ModelBuilder<T>> void top(T m, float dy, boolean exhaust) {
        rails(m, dy, 6, true);
        // Cylinder walls: copper outside, dark iron inside, where the raised ram sits.
        box(m, dy, 1, 6, 1, 2, 16, 15, d -> d == Direction.EAST ? "#iron" : d == Direction.DOWN ? "#iron" : "#copper");
        box(m, dy, 14, 6, 1, 15, 16, 15, d -> d == Direction.WEST ? "#iron" : d == Direction.DOWN ? "#iron" : "#copper");
        box(m, dy, 2, 6, 1, 14, 16, 2, d -> d.getAxis() == Axis.X ? null
                : d == Direction.SOUTH || d == Direction.DOWN ? "#iron" : "#copper");
        box(m, dy, 2, 6, 14, 14, 16, 15, d -> d.getAxis() == Axis.X ? null
                : d == Direction.NORTH || d == Direction.DOWN ? "#iron" : "#copper");
        Function<Direction, String> cap = d -> d == Direction.UP ? "#copper" : d == Direction.DOWN ? "#iron" : "#iron";
        box(m, dy, 2, 15, 2, 3, 16, 14, cap);
        box(m, dy, 3, 15, 2, 5, 16, 7, cap);
        box(m, dy, 3, 15, 9, 5, 16, 14, cap);
        box(m, dy, 5, 15, 2, 11, 16, 14, cap);
        box(m, dy, 11, 15, 2, 13, 16, 7, cap);
        box(m, dy, 11, 15, 9, 13, 16, 14, cap);
        box(m, dy, 13, 15, 2, 14, 16, 14, cap);

        // Fuel gauges, one on each face the shaft does not come through. The whole dial on the outer face.
        var east = m.element().from(15, 9 + dy, 5).to(15.5F, 15 + dy, 11);
        east.face(Direction.EAST).texture("#gauge").uvs(0, 0, 16, 16).end();
        gaugeRim(east, Direction.EAST);
        east.end();
        var west = m.element().from(0.5F, 9 + dy, 5).to(1, 15 + dy, 11);
        west.face(Direction.WEST).texture("#gauge").uvs(0, 0, 16, 16).end();
        gaugeRim(west, Direction.WEST);
        west.end();

        lamps(m, dy, 0);

        if (exhaust) {
            box(m, dy, 6, 16, 6, 10, 20, 10, d -> d.getAxis().isVertical() ? null : "#copper");
            box(m, dy, 5, 20, 5, 11, 22, 11, d -> d == Direction.UP ? "#iron_top" : "#copper");
        }
    }

    private static void gaugeRim(ModelBuilder<?>.ElementBuilder gauge, Direction front) {
        for (Direction d : Direction.values()) {
            if (d != front && d != front.getOpposite()) {
                gauge.face(d).texture("#copper").uvs(0, 0, 1, 6).end();
            }
        }
    }

    /**
     * The ready lamps, one on each end face of the cylinder, grown by {@code grow} pixels (the lit ones sit a twentieth of
     * a pixel outside the dark ones, so they never share a plane).
     */
    private static <T extends ModelBuilder<T>> void lamps(T m, float dy, float grow) {
        for (boolean south : new boolean[]{false, true}) {
            float z1 = south ? 15 : 0.5F - grow;
            float z2 = south ? 15.5F + grow : 1;
            Direction front = south ? Direction.SOUTH : Direction.NORTH;
            var lamp = m.element().from(6.5F - grow, 10 - grow + dy, z1).to(9.5F + grow, 13 + grow + dy, z2);
            for (Direction d : Direction.values()) {
                if (d == front.getOpposite()) {
                    continue;
                }
                if (d == front) {
                    lamp.face(d).texture("#lamp").uvs(6.5F, 6.5F, 9.5F, 9.5F).end();
                } else {
                    lamp.face(d).texture("#lamp").uvs(7, 7, 9, 9).end();
                }
            }
            lamp.end();
        }
    }

    // ---- moving parts ----

    /**
     * The ram, resting on the anvil (in the column's pixels, measured from the base: the anvil's top is at 16). A heavy
     * iron block, sized to run between the rails, with a copper crown; fully raised, 20 pixels higher, the crown sits a
     * pixel under the cylinder's cap.
     */
    private static <T extends ModelBuilder<T>> void ram(T m, float dy) {
        box(m, dy, 3, 16, 3, 13, 24, 13, d -> d == Direction.DOWN ? "#iron_top" : "#iron");
        box(m, dy, 5, 24, 5, 11, 26, 11, d -> d == Direction.DOWN ? null : "#copper");
    }

    /**
     * The lift rods: a pair of press poles standing on the ram, one either side of its crown, running up through the
     * cylinder and out of the slots in its cap. They are what visibly hauls the ram up: at rest they poke two pixels out of
     * the cap; fully raised, a block and a bit more, so they double as a gauge of the lift.
     *
     * <p>In the top block's pixels (the ram's top, at rest, is 8 below the top block's floor), and {@code top} is where
     * they end; they are drawn at render time, shifted with the ram.
     */
    private static <T extends ModelBuilder<T>> void rods(T m, float dy, float top) {
        for (float x : new float[]{3, 11}) {
            var rod = m.element().from(x, -8 + dy, 7).to(x + 2, top + dy, 9);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                rod.face(d).texture("#pole").uvs(7, 0, 9, 16).end();
            }
            rod.face(Direction.UP).texture("#pole").uvs(11, 1, 13, 3).end();
            rod.end();
        }
    }

    /**
     * The fuel gauge's needle, on the east gauge, in the top block's pixels: a hub at the dial's centre and a thin pointer
     * straight up. Every face tinted, so the renderer colours it (near black, or red for a fluid that will not ignite).
     */
    private static void needle(BlockModelBuilder m) {
        for (float[] b : new float[][]{{15.5F, 11.6F, 7.6F, 15.7F, 12.4F, 8.4F}, {15.5F, 12, 7.85F, 15.6F, 14.4F, 8.15F}}) {
            var e = m.element().from(b[0], b[1], b[2]).to(b[3], b[4], b[5]);
            for (Direction d : Direction.values()) {
                e.face(d).texture("#needle").uvs(0, 0, 1, 1).tintindex(0).end();
            }
            e.end();
        }
    }

    // ---- item ----

    /**
     * Create's style of icon: the whole machine in 3D, moving parts frozen (the ram on the anvil, the cam upright). Item
     * model elements may only span -16 to 32 pixels, so the column is shifted a block down, and the exhaust, which would
     * poke past the top, is left off.
     */
    private static void itemModel(BlockStateProvider provider) {
        ItemModelBuilder item = textures(provider.itemModels().withExistingParent("combustive_thumper", "block/block"), provider);
        base(item, -16);
        middle(item, 0);
        top(item, 16, false);
        ram(item, -16);
        // The rods, cut off at the top of what an item model may hold.
        rods(item, 16, 16);
        var shaft = item.element().from(6, -10, 0).to(10, -6, 16);
        shaft.face(Direction.NORTH).texture("#axis_top").uvs(6, 6, 10, 10).end();
        shaft.face(Direction.SOUTH).texture("#axis_top").uvs(6, 6, 10, 10).end();
        for (Direction d : new Direction[]{Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN}) {
            shaft.face(d).texture("#axis").uvs(6, 0, 10, 16).rotation(FaceRotation.CLOCKWISE_90).end();
        }
        shaft.end();

        item.transforms()
                .transform(ItemDisplayContext.GUI).rotation(30, 225, 0).translation(0, 0, 0).scale(0.3F).end()
                .transform(ItemDisplayContext.FIXED).translation(0, 0, 0).scale(0.33F).end()
                .transform(ItemDisplayContext.GROUND).translation(0, 3, 0).scale(0.15F).end()
                .transform(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND).rotation(75, 45, 0).translation(0, 2.5F, 0).scale(0.2F).end()
                .transform(ItemDisplayContext.THIRD_PERSON_LEFT_HAND).rotation(75, 45, 0).translation(0, 2.5F, 0).scale(0.2F).end()
                .transform(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND).rotation(0, 45, 0).scale(0.2F).end()
                .transform(ItemDisplayContext.FIRST_PERSON_LEFT_HAND).rotation(0, 225, 0).scale(0.2F).end()
                .end();
    }

    // ---- the box helper ----

    /**
     * One box, shifted up by {@code dy}, each face given the texture {@code tex} names for it (null leaves the face out),
     * with texture coordinates worked out from the box's position in its own block, before the shift: the same ones the
     * game would work out itself for an unshifted box.
     */
    static <T extends ModelBuilder<T>> void box(T m, float dy, float x1, float y1, float z1, float x2, float y2, float z2,
                                                Function<Direction, String> tex) {
        float blockBottom = 16 * (float) Math.floor(y1 / 16);
        float ly1 = y1 - blockBottom;
        float ly2 = y2 - blockBottom;
        var element = m.element().from(x1, y1 + dy, z1).to(x2, y2 + dy, z2);
        for (Direction d : Direction.values()) {
            String texture = tex.apply(d);
            if (texture == null) {
                continue;
            }
            float[] uv = switch (d) {
                case DOWN -> new float[]{x1, 16 - z2, x2, 16 - z1};
                case UP -> new float[]{x1, z1, x2, z2};
                case NORTH -> new float[]{16 - x2, 16 - ly2, 16 - x1, 16 - ly1};
                case SOUTH -> new float[]{x1, 16 - ly2, x2, 16 - ly1};
                case WEST -> new float[]{z1, 16 - ly2, z2, 16 - ly1};
                case EAST -> new float[]{16 - z2, 16 - ly2, 16 - z1, 16 - ly1};
            };
            element.face(d).texture(texture).uvs(uv[0], uv[1], uv[2], uv[3]).end();
        }
        element.end();
    }

    private static ResourceLocation create(String path) {
        return ResourceLocation.fromNamespaceAndPath("create", path);
    }

    private CombustiveDataGen() {
    }
}
