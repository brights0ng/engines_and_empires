package dev.brights0ng.enginesandempires.geophone;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.client.model.generators.BlockModelBuilder;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ItemModelBuilder;
import net.neoforged.neoforge.client.model.generators.ModelBuilder;
import net.neoforged.neoforge.client.model.generators.ModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/**
 * Generates the smart logger's models: a low brass-cased desk, two blocks by two, with the map screen across most of its
 * top, a raised bar down its right-hand side for the buttons, and a long slot along each side face where the cogs inside
 * show on all four sides, the way the Mechanical Mixer's do (the cogs mesh on every side, so every side shows them).
 *
 * <p>There is one model per quarter ({@code smart_logger_front_left} and so on), all built facing south: the front faces
 * south, so right is east and away is north. The blockstate turns them to face the way the desk was placed. The shapes are
 * worked out from {@link LoggerDisplay}'s layout, in the desk's own coordinates, and cut to each quarter, so the model
 * matches what the block entity and the renderer think is where.
 *
 * <p>The buttons and the cogs move, so they are not here: the renderer draws them, the buttons from
 * {@code smart_logger_button} (a small bronze push button).
 */
public final class LoggerDataGen {

    private static final ResourceLocation BRASS = create("block/brass_casing");
    private static final ResourceLocation INNER = ResourceLocation.withDefaultNamespace("block/dark_oak_planks");

    /** The cog slot along each side face: this high, from the bottom. */
    private static final float SLOT_BOTTOM = 5;
    private static final float SLOT_TOP = 11;
    /** How deep the slot is cut into the side. */
    private static final float SLOT_DEPTH = 2;
    /** The top of the casing, under the screen. */
    private static final float DECK = (float) (LoggerDisplay.SCREEN_Y * 16);

    public static void trackCreateTextures(ExistingFileHelper existing) {
        for (ResourceLocation texture : List.of(BRASS)) {
            existing.trackGenerated(texture, ModelProvider.TEXTURE);
        }
    }

    static void registerModels(BlockStateProvider provider) {
        Map<SmartLoggerBlock.Part, BlockModelBuilder> quarters = new EnumMap<>(SmartLoggerBlock.Part.class);
        for (SmartLoggerBlock.Part part : SmartLoggerBlock.Part.values()) {
            BlockModelBuilder model = textures(provider.models().getBuilder("smart_logger_" + part.getSerializedName()), provider);
            quarter(model, part, 0, 0);
            quarters.put(part, model);
        }
        provider.getVariantBuilder(SeismicContent.SMART_LOGGER.get()).forAllStates(state -> ConfiguredModel.builder()
                .modelFile(quarters.get(state.getValue(SmartLoggerBlock.PART)))
                .rotationY((int) state.getValue(HorizontalKineticBlock.HORIZONTAL_FACING).toYRot())
                .build());

        ResourceLocation bronze = provider.modLoc("block/smart_logger_button");
        BlockModelBuilder button = provider.models().getBuilder("smart_logger_button")
                .texture("particle", bronze).texture("bronze", bronze);
        // Centred in the block, standing on y = 0: across the bar along X, along it along Z. The renderer puts it on the bar.
        float across = (float) (LoggerDisplay.BUTTON_ACROSS * 16);
        float along = (float) (LoggerDisplay.BUTTON_ALONG * 16);
        float height = (float) (LoggerDisplay.BUTTON_HEIGHT * 16);
        box(button, 0, 0, 8 - across / 2, 0, 8 - along / 2, 8 + across / 2, height, 8 + along / 2, d -> "#bronze");

        itemModel(provider);
    }

    private static <T extends ModelBuilder<T>> T textures(T model, BlockStateProvider provider) {
        return model.texture("particle", BRASS)
                .texture("brass", BRASS)
                .texture("inner", INNER)
                .texture("screen", provider.modLoc("block/smart_logger_screen"))
                .texture("bronze", provider.modLoc("block/smart_logger_button"));
    }

    /**
     * One quarter of the desk, shifted by ({@code dx}, {@code dz}) pixels (the item model puts all four together).
     *
     * <ul>
     *   <li>The casing: solid below and above the cog slot, and cut back {@link #SLOT_DEPTH} pixels along both of the
     *       quarter's outer faces in between, except for a post at the desk's corner, so the cog's teeth show on every
     *       side of the desk.</li>
     *   <li>The deck on top, whose top face is the screen glass on the left, and the raised button bar on the right.</li>
     *   <li>A one-pixel brass rim around the screen.</li>
     * </ul>
     */
    private static <T extends ModelBuilder<T>> void quarter(T m, SmartLoggerBlock.Part part, float dx, float dz) {
        boolean right = part.right() == 1;
        boolean back = part.back() == 1;

        // Below and above the slot.
        box(m, dx, dz, 0, 0, 0, 16, SLOT_BOTTOM, 16, d -> "#brass");
        // The core, cut back from both outer faces: the side (west for the left quarters, east for the right ones) and
        // the end (south for the front quarters, north for the back ones).
        float xFrom = right ? 0 : SLOT_DEPTH;
        float xTo = right ? 16 - SLOT_DEPTH : 16;
        float zFrom = back ? SLOT_DEPTH : 0;
        float zTo = back ? 16 : 16 - SLOT_DEPTH;
        Direction side = right ? Direction.EAST : Direction.WEST;
        Direction end = back ? Direction.NORTH : Direction.SOUTH;
        box(m, dx, dz, xFrom, SLOT_BOTTOM, zFrom, xTo, SLOT_TOP, zTo, d -> d == side || d == end ? "#inner" : "#brass");
        // The post at the desk's corner, where the two slots meet.
        float postX = right ? 16 - SLOT_DEPTH : 0;
        float postZ = back ? 0 : 16 - SLOT_DEPTH;
        box(m, dx, dz, postX, SLOT_BOTTOM, postZ, postX + SLOT_DEPTH, SLOT_TOP, postZ + SLOT_DEPTH, d -> "#brass");

        // The deck: screen glass on top, over the part of the quarter that is screen.
        for (float[] r : clip(part, 0, 0, LoggerDisplay.SCREEN_WIDTH, LoggerDisplay.DEPTH)) {
            box(m, dx, dz, r[0], SLOT_TOP, r[1], r[2], DECK, r[3], d -> d == Direction.UP ? "#screen" : "#brass");
        }
        // The button bar, raised to the top of the block.
        for (float[] r : clip(part, LoggerDisplay.SCREEN_WIDTH, 0, LoggerDisplay.WIDTH, LoggerDisplay.DEPTH)) {
            box(m, dx, dz, r[0], SLOT_TOP, r[1], r[2], (float) (LoggerDisplay.BAR_TOP * 16), r[3], d -> "#brass");
        }
        // The rim around the screen.
        double bezel = LoggerDisplay.BEZEL;
        double[][] rims = {
                {0, 0, LoggerDisplay.SCREEN_WIDTH, bezel},
                {0, LoggerDisplay.DEPTH - bezel, LoggerDisplay.SCREEN_WIDTH, LoggerDisplay.DEPTH},
                {0, 0, bezel, LoggerDisplay.DEPTH},
                {LoggerDisplay.SCREEN_WIDTH - bezel, 0, LoggerDisplay.SCREEN_WIDTH, LoggerDisplay.DEPTH}};
        float rimTop = (float) (LoggerDisplay.RIM_TOP * 16);
        for (double[] rim : rims) {
            for (float[] r : clip(part, rim[0], rim[1], rim[2], rim[3])) {
                box(m, dx, dz, r[0], DECK, r[1], r[2], rimTop, r[3], d -> d == Direction.DOWN ? null : "#brass");
            }
        }
    }

    /**
     * A rectangle given in the desk's own coordinates ({@code u} across to the right, {@code v} away from the front, in
     * metres; see {@link LoggerDisplay}), cut to one quarter and turned into its model's pixels (built facing south, so
     * {@code u} runs east and {@code v} north). As {x1, z1, x2, z2}; empty if it misses the quarter.
     */
    private static List<float[]> clip(SmartLoggerBlock.Part part, double u1, double v1, double u2, double v2) {
        double a1 = Math.max(0, u1 - part.right());
        double a2 = Math.min(1, u2 - part.right());
        double b1 = Math.max(0, v1 - part.back());
        double b2 = Math.min(1, v2 - part.back());
        if (a1 >= a2 || b1 >= b2) {
            return List.of();
        }
        return List.<float[]>of(new float[]{(float) (a1 * 16), (float) (16 - b2 * 16), (float) (a2 * 16), (float) (16 - b1 * 16)});
    }

    /**
     * Create's style of icon: the whole desk in 3D, the four quarters put together (an item model may span -16 to 32
     * pixels, and the desk is 32 across, so it is centred on the block), with the buttons standing on the bar.
     */
    private static void itemModel(BlockStateProvider provider) {
        ItemModelBuilder item = textures(provider.itemModels().withExistingParent("smart_logger", "block/block"), provider);
        for (SmartLoggerBlock.Part part : SmartLoggerBlock.Part.values()) {
            quarter(item, part, part.right() * 16 - 8, 8 - part.back() * 16);
        }
        float across = (float) (LoggerDisplay.BUTTON_ACROSS * 16);
        float along = (float) (LoggerDisplay.BUTTON_ALONG * 16);
        float height = (float) (LoggerDisplay.BUTTON_HEIGHT * 16);
        for (LoggerDisplay.Button button : LoggerDisplay.Button.values()) {
            float u = (float) (LoggerDisplay.Button.centreU() * 16);
            float v = (float) (button.centreV() * 16);
            // u runs east and v north, as in the quarters; shifted the same way as they are.
            float x = u - 8;
            float z = 8 + 16 - v;
            var element = item.element().from(x - across / 2, 16, z - along / 2).to(x + across / 2, 16 + height, z + along / 2);
            for (Direction d : Direction.values()) {
                if (d != Direction.DOWN) {
                    element.face(d).texture("#bronze").uvs(6, 6, 10, 9).end();
                }
            }
            element.end();
        }
        item.transforms()
                .transform(ItemDisplayContext.GUI).rotation(30, 225, 0).translation(0, 1, 0).scale(0.32F).end()
                .transform(ItemDisplayContext.FIXED).translation(0, 0, 0).scale(0.3F).end()
                .transform(ItemDisplayContext.GROUND).translation(0, 3, 0).scale(0.15F).end()
                .transform(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND).rotation(75, 45, 0).translation(0, 2.5F, 0).scale(0.2F).end()
                .transform(ItemDisplayContext.THIRD_PERSON_LEFT_HAND).rotation(75, 45, 0).translation(0, 2.5F, 0).scale(0.2F).end()
                .transform(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND).rotation(0, 45, 0).scale(0.2F).end()
                .transform(ItemDisplayContext.FIRST_PERSON_LEFT_HAND).rotation(0, 225, 0).scale(0.2F).end()
                .end();
    }

    /**
     * One box, shifted by ({@code dx}, {@code dz}), each face given the texture {@code tex} names for it (null leaves it
     * out), with texture coordinates worked out from the box's position in its own block before the shift, as the game
     * would work them out for an unshifted box.
     */
    private static <T extends ModelBuilder<T>> void box(T m, float dx, float dz, float x1, float y1, float z1,
                                                        float x2, float y2, float z2, Function<Direction, String> tex) {
        var element = m.element().from(x1 + dx, y1, z1 + dz).to(x2 + dx, y2, z2 + dz);
        for (Direction d : Direction.values()) {
            String texture = tex.apply(d);
            if (texture == null) {
                continue;
            }
            float[] uv = switch (d) {
                case DOWN -> new float[]{x1, 16 - z2, x2, 16 - z1};
                case UP -> new float[]{x1, z1, x2, z2};
                case NORTH -> new float[]{16 - x2, 16 - y2, 16 - x1, 16 - y1};
                case SOUTH -> new float[]{x1, 16 - y2, x2, 16 - y1};
                case WEST -> new float[]{z1, 16 - y2, z2, 16 - y1};
                case EAST -> new float[]{16 - z2, 16 - y2, 16 - z1, 16 - y1};
            };
            element.face(d).texture(texture).uvs(uv[0], uv[1], uv[2], uv[3]).end();
        }
        element.end();
    }

    private static ResourceLocation create(String path) {
        return ResourceLocation.fromNamespaceAndPath("create", path);
    }

    private LoggerDataGen() {
    }
}
