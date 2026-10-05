package dev.brights0ng.enginesandempires.geophone;

import java.util.List;
import java.util.Map;

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
 * Generates the mechanical thumper's models, blockstate and item model.
 *
 * <p>Everything is built as if the rotation axis were Z; the blockstate turns the housing 90 degrees around Y for the
 * X-axis case, and {@code ThumperRenderer} / {@code ThumperVisual} turn the head and lamp to match.
 *
 * <p>The look follows Create's Mechanical Press, reusing its textures, but split down the middle: two press-style halves
 * (z 0-6 and z 10-16) with a vertical slot between them, where Create's own shaftless cogwheel spins (drawn at render
 * time, not baked here). The cog's teeth reach one pixel past every face around the axis, which is what lets a
 * neighbouring cog mesh with it.
 *
 * <p>Block models:
 * <ul>
 *   <li>{@code mechanical_thumper_housing}: the static block model, with the dark lamp baked in.</li>
 *   <li>{@code mechanical_thumper_head}: the moving part, drawn by the renderer. A slab hung just below the cog's reach,
 *       with two press-pole prongs rising through the halves on either side of the cog. It is modelled in its fully
 *       wound (highest) position; the renderer only ever moves it down from there.</li>
 *   <li>{@code mechanical_thumper_lamp_lit}: the lit lamp, drawn by the renderer over the dark one while armed.</li>
 * </ul>
 *
 * <p>The item model follows Create's convention for its machines: not a flat sprite but the whole machine in 3D, moving
 * parts frozen in place, shown at the press's inventory angle. It is assembled from the same pieces as the real thing
 * (housing, head, and Create's cogwheel stood up onto the Z axis), so it cannot drift from the block.
 */
public final class ThumperDataGen {

    /** How far the head travels, in pixels, between fully wound (up) and unwound (resting on the strike plate). */
    public static final float HEAD_TRAVEL_PIXELS = 8.0F;

    // The prong, and the hole it slides through, in north-half coordinates (the south half mirrors it).
    private static final float PRONG_X1 = 6.0F;
    private static final float PRONG_X2 = 10.0F;
    private static final float PRONG_Z1 = 1.75F;
    private static final float PRONG_Z2 = 5.75F;

    private static final ResourceLocation PRESS_TOP = create("block/mechanical_press_top");
    private static final ResourceLocation PRESS_SIDE = create("block/mechanical_press_side");
    private static final ResourceLocation PRESS_BOTTOM = create("block/mechanical_press_bottom");
    private static final ResourceLocation PRESS_HEAD = create("block/mechanical_press_head");
    private static final ResourceLocation PRESS_POLE = create("block/mechanical_press_pole");
    private static final ResourceLocation GEARBOX_TOP = create("block/gearbox_top");
    private static final ResourceLocation ANDESITE_CASING = create("block/andesite_casing");
    private static final ResourceLocation COGWHEEL = create("block/cogwheel");
    private static final ResourceLocation GEARBOX = create("block/gearbox");
    private static final ResourceLocation SCULK = ResourceLocation.withDefaultNamespace("block/sculk");

    /**
     * Tells the model generator Create's textures exist. They live in Create's jar, which the data run's file helper
     * does not necessarily search, so without this it would refuse to write a model that uses them.
     */
    public static void trackCreateTextures(ExistingFileHelper existing) {
        for (ResourceLocation texture : List.of(PRESS_TOP, PRESS_SIDE, PRESS_BOTTOM, PRESS_HEAD, PRESS_POLE, GEARBOX_TOP,
                ANDESITE_CASING, COGWHEEL, GEARBOX)) {
            existing.trackGenerated(texture, ModelProvider.TEXTURE);
        }
    }

    static void registerModels(BlockStateProvider provider) {
        ResourceLocation lampTexture = provider.modLoc("block/mechanical_thumper_lamp");

        BlockModelBuilder housing = housingTextures(provider.models().getBuilder("mechanical_thumper_housing"), lampTexture);
        housing(housing);

        ResourceLocation lampLitTexture = provider.modLoc("block/mechanical_thumper_lamp_lit");
        BlockModelBuilder lampLit = provider.models().getBuilder("mechanical_thumper_lamp_lit")
                .texture("particle", lampLitTexture)
                .texture("lamp", lampLitTexture);
        // A twentieth of a pixel bigger all round than the dark lamp, so the two never share a plane and z-fight.
        lamp(lampLit, 0.05F);

        BlockModelBuilder head = headTextures(provider.models().getBuilder("mechanical_thumper_head"))
                .texture("particle", PRESS_HEAD);
        head(head);

        provider.getVariantBuilder(SeismicContent.MECHANICAL_THUMPER.get()).forAllStates(state -> {
            Axis axis = state.getValue(HorizontalAxisKineticBlock.HORIZONTAL_AXIS);
            ConfiguredModel.Builder<?> configured = ConfiguredModel.builder().modelFile(housing);
            if (axis == Axis.X) {
                configured = configured.rotationY(90);
            }
            return configured.build();
        });

        ItemModelBuilder item = provider.itemModels().withExistingParent("mechanical_thumper", "block/block");
        headTextures(housingTextures(item, lampTexture)).texture("cog", COGWHEEL);
        housing(item);
        head(item);
        cog(item);
        // The same inventory angle and size as Create's own press, which is about as tall.
        item.transforms()
                .transform(ItemDisplayContext.GUI).rotation(30, 225, 0).translation(0, 0, 0).scale(0.55F).end()
                .transform(ItemDisplayContext.FIXED).translation(0, 0, 0).scale(0.5F).end()
                .end();

        // The choked thumper: the whole machine frozen, as in the item model, with sculk grown over its casing and lamp.
        BlockModelBuilder choked = headTextures(housingTextures(provider.models().getBuilder("choked_mechanical_thumper"), SCULK))
                .texture("casing", SCULK)
                .texture("particle", SCULK)
                .texture("cog", COGWHEEL);
        housing(choked);
        head(choked);
        cog(choked);
        provider.getVariantBuilder(SeismicContent.CHOKED_MECHANICAL_THUMPER.get()).forAllStates(state -> {
            ConfiguredModel.Builder<?> configured = ConfiguredModel.builder().modelFile(choked);
            if (state.getValue(ChokedThumperBlock.HORIZONTAL_AXIS) == Axis.X) {
                configured = configured.rotationY(90);
            }
            return configured.build();
        });
        provider.itemModels().withExistingParent("choked_mechanical_thumper", provider.modLoc("block/choked_mechanical_thumper"))
                .transforms()
                .transform(ItemDisplayContext.GUI).rotation(30, 225, 0).translation(0, 0, 0).scale(0.55F).end()
                .transform(ItemDisplayContext.FIXED).translation(0, 0, 0).scale(0.5F).end()
                .end();

        CombustiveDataGen.registerModels(provider);
    }

    private static <T extends ModelBuilder<T>> T housingTextures(T model, ResourceLocation lamp) {
        return model.texture("particle", PRESS_SIDE)
                .texture("top", PRESS_TOP)
                .texture("side", PRESS_SIDE)
                .texture("bottom", PRESS_BOTTOM)
                .texture("frame", GEARBOX_TOP)
                .texture("casing", ANDESITE_CASING)
                .texture("lamp", lamp);
    }

    private static <T extends ModelBuilder<T>> T headTextures(T model) {
        return model.texture("head", PRESS_HEAD).texture("pole", PRESS_POLE);
    }

    // ---- housing ----

    /** Both halves of the housing, and the dark lamp on the south one (the middle of the top is where the cog pokes through). */
    private static <T extends ModelBuilder<T>> void housing(T model) {
        housingHalf(model, false);
        housingHalf(model, true);
        lamp(model, 0.0F);
    }

    /**
     * One press-style half of the housing: a top slab (with a hole for the prong), two side walls and an inset core,
     * the same layout as the press's own block model, cut down to the six pixels between the block's face and the cog.
     * Built in north-half coordinates and mirrored across z = 8 for the south half.
     */
    private static <T extends ModelBuilder<T>> void housingHalf(T model, boolean south) {
        // Top slab, in four pieces around the prong's hole.
        part(model, south, 0, 14, 0, PRONG_X1, 16, 6, "#top", "#frame", "#side", "#casing", "#casing");
        part(model, south, PRONG_X2, 14, 0, 16, 16, 6, "#top", "#frame", "#side", "#casing", "#casing");
        part(model, south, PRONG_X1, 14, 0, PRONG_X2, 16, PRONG_Z1, "#top", "#frame", null, "#casing", "#casing");
        part(model, south, PRONG_X1, 14, PRONG_Z2, PRONG_X2, 16, 6, "#top", "#frame", null, "#casing", "#casing");
        // Side walls, like the press's.
        part(model, south, 0, 2, 0, 2, 14, 6, null, "#frame", "#side", "#frame", "#frame");
        part(model, south, 14, 2, 0, 16, 14, 6, null, "#frame", "#side", "#frame", "#frame");
        // The core, inset a pixel from the outer face the way the press's is. Its top shows through the prong's hole.
        part(model, south, 2, 4, 1, 14, 14, 6, "#casing", "#bottom", null, "#casing", "#casing");
    }

    /**
     * One box of a housing half. Faces get the texture for their role (null leaves that face out, for faces that are
     * always hidden): top, bottom, the two faces around the axis ("side", where the cogs mesh), the outer face along the
     * axis, and the inner face looking into the cog's slot. Texture coordinates are left to the default, which follows
     * the box's position in the block, so each piece shows the matching part of the press's textures.
     */
    private static <T extends ModelBuilder<T>> void part(T model, boolean south, float x1, float y1, float z1, float x2,
                                                         float y2, float z2, String up, String down, String side,
                                                         String outer, String inner) {
        float fromZ = south ? 16 - z2 : z1;
        float toZ = south ? 16 - z1 : z2;
        Direction outerFace = south ? Direction.SOUTH : Direction.NORTH;
        var element = model.element().from(x1, y1, fromZ).to(x2, y2, toZ);
        for (Direction direction : Direction.values()) {
            String texture = switch (direction) {
                case UP -> up;
                case DOWN -> down;
                case EAST, WEST -> side;
                default -> direction == outerFace ? outer : inner;
            };
            if (texture != null) {
                element.face(direction).texture(texture).end();
            }
        }
        element.end();
    }

    /**
     * The lamp on top of the south half (x/z 11-14, y 16-18), grown by {@code grow} pixels on every side. Every face
     * shows the middle of the texture. Never tinted: the lit lamp's amber is baked into its texture.
     */
    private static <T extends ModelBuilder<T>> void lamp(T model, float grow) {
        var lamp = model.element().from(11 - grow, 16 - grow, 11 - grow).to(14 + grow, 18 + grow, 14 + grow);
        for (Direction direction : Direction.values()) {
            if (direction.getAxis().isVertical()) {
                lamp.face(direction).texture("#lamp").uvs(6.5F, 6.5F, 9.5F, 9.5F).end();
            } else {
                lamp.face(direction).texture("#lamp").uvs(6.5F, 7, 9.5F, 9).end();
            }
        }
        lamp.end();
    }

    // ---- head ----

    /**
     * The head, in its fully wound position. The slab uses the press's head texture but is wider, 14x14 (the strike
     * plate's own footprint), so it spans both prongs with a pixel to spare; it hangs from y -6 to -2 to clear the cog's
     * lowest tooth at y -1. The prongs are press poles, 4x4, one either side of the cog, and reach up to y 22.5: six and a
     * half pixels proud of the housing when fully wound, and still half a pixel below the top slab when fully unwound, so
     * the prongs double as a charge gauge.
     */
    private static <T extends ModelBuilder<T>> void head(T model) {
        var slab = model.element().from(1, -6, 1).to(15, -2, 15);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            slab.face(direction).texture("#head").uvs(1, 0, 11, 4).end();
        }
        slab.face(Direction.UP).texture("#head").uvs(1, 5, 11, 15).rotation(FaceRotation.UPSIDE_DOWN).end();
        slab.face(Direction.DOWN).texture("#head").uvs(1, 5, 11, 15).rotation(FaceRotation.UPSIDE_DOWN).end();
        slab.end();

        for (boolean south : new boolean[]{false, true}) {
            float z1 = south ? 16 - PRONG_Z2 : PRONG_Z1;
            float z2 = south ? 16 - PRONG_Z1 : PRONG_Z2;
            var lower = model.element().from(PRONG_X1, -2, z1).to(PRONG_X2, 10, z2);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                lower.face(direction).texture("#pole").uvs(6, 4, 10, 16).end();
            }
            lower.end();
            var upper = model.element().from(PRONG_X1, 10, z1).to(PRONG_X2, 22.5F, z2);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                upper.face(direction).texture("#pole").uvs(6, 0, 10, 12.5F).end();
            }
            upper.face(Direction.UP).texture("#pole").uvs(11, 1, 15, 5).end();
            upper.end();
        }
    }

    // ---- cog (item model only) ----

    /** One face of a box in Create's {@code cogwheel_shaftless} model: its texture coordinates and rotation in degrees. */
    private record CogFace(float u1, float v1, float u2, float v2, int rotation) {
    }

    /** One box of Create's {@code cogwheel_shaftless} model, as written there (lying flat, around the Y axis). */
    private record CogBox(float[] from, float[] to, float yRotation, Map<Direction, CogFace> faces) {
    }

    private static final CogFace TOOTH_LONG = new CogFace(7, 8, 16, 9.5F, 0);
    private static final CogFace TOOTH_END = new CogFace(5, 8, 6.5F, 9.5F, 0);
    private static final CogFace TOOTH_FLAT = new CogFace(7, 6, 16, 7.5F, 0);

    /** A tooth bar along X, turned {@code angle} degrees around Y: three of these and {@link #COG_BOXES}' fourth make eight teeth. */
    private static CogBox toothBar(float angle) {
        return new CogBox(new float[]{-1, 6.5F, 6.5F}, new float[]{17, 9.5F, 9.5F}, angle, Map.of(
                Direction.NORTH, TOOTH_LONG, Direction.SOUTH, TOOTH_LONG, Direction.EAST, TOOTH_END,
                Direction.WEST, TOOTH_END, Direction.UP, TOOTH_FLAT, Direction.DOWN, TOOTH_FLAT));
    }

    private static CogBox ring(float[] from, float[] to, CogFace side, CogFace flat) {
        return new CogBox(from, to, 0, Map.of(Direction.NORTH, side, Direction.SOUTH, side, Direction.EAST, side,
                Direction.WEST, side, Direction.UP, flat, Direction.DOWN, flat));
    }

    /** Create's {@code models/block/cogwheel_shaftless.json}, box for box. */
    private static final List<CogBox> COG_BOXES = List.of(
            toothBar(0),
            toothBar(45),
            toothBar(-45),
            new CogBox(new float[]{6.5F, 6.5F, -1}, new float[]{9.5F, 9.5F, 17}, 0, Map.of(
                    Direction.NORTH, TOOTH_END, Direction.SOUTH, TOOTH_END, Direction.EAST, TOOTH_LONG,
                    Direction.WEST, TOOTH_LONG, Direction.UP, new CogFace(7, 6, 16, 7.5F, 90),
                    Direction.DOWN, new CogFace(7, 6, 16, 7.5F, 90))),
            ring(new float[]{2, 6.55F, 2}, new float[]{14, 9.45F, 14}, new CogFace(0, 6, 6, 7.5F, 0),
                    new CogFace(4, 0, 10, 6, 0)),
            ring(new float[]{4, 6, 4}, new float[]{12, 10, 12}, new CogFace(0, 4, 4, 6, 0),
                    new CogFace(0, 0, 4, 4, 0)));

    /**
     * Create's cogwheel, stood up from the Y axis onto the Z axis, for the item model. Block models cannot turn a box a
     * quarter turn, so each box is rewritten instead: every point (x, y, z) goes to (x, 16 - z, y), a quarter turn around
     * X about the block's centre. That sends the up face to the south, south to down, down to north and north to up, and
     * leaves east and west where they are but turned a quarter on themselves; the face rotations below make up for how
     * each face's texture ends up turned. A box's own 45 degree turn around Y becomes the same turn around Z.
     */
    private static <T extends ModelBuilder<T>> void cog(T model) {
        for (CogBox box : COG_BOXES) {
            var element = model.element()
                    .from(box.from()[0], 16 - box.to()[2], box.from()[1])
                    .to(box.to()[0], 16 - box.from()[2], box.to()[1]);
            if (box.yRotation() != 0) {
                element.rotation().origin(8, 8, 8).axis(Axis.Z).angle(box.yRotation()).end();
            }
            for (Map.Entry<Direction, CogFace> entry : box.faces().entrySet()) {
                CogFace face = entry.getValue();
                Direction to;
                int extraRotation;
                switch (entry.getKey()) {
                    case UP -> { to = Direction.SOUTH; extraRotation = 0; }
                    case SOUTH -> { to = Direction.DOWN; extraRotation = 0; }
                    case DOWN -> { to = Direction.NORTH; extraRotation = 180; }
                    case NORTH -> { to = Direction.UP; extraRotation = 180; }
                    default -> { to = entry.getKey(); extraRotation = 90; }
                }
                element.face(to).texture("#cog").uvs(face.u1(), face.v1(), face.u2(), face.v2())
                        .rotation(faceRotation(face.rotation() + extraRotation)).end();
            }
            element.end();
        }
    }

    private static FaceRotation faceRotation(int degrees) {
        return switch (Math.floorMod(degrees, 360)) {
            case 90 -> FaceRotation.CLOCKWISE_90;
            case 180 -> FaceRotation.UPSIDE_DOWN;
            case 270 -> FaceRotation.COUNTERCLOCKWISE_90;
            default -> FaceRotation.ZERO;
        };
    }

    private static ResourceLocation create(String path) {
        return ResourceLocation.fromNamespaceAndPath("create", path);
    }

    private ThumperDataGen() {
    }
}
