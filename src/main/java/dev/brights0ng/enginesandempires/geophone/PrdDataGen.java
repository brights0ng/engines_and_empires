package dev.brights0ng.enginesandempires.geophone;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ItemModelBuilder;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.client.model.generators.ModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/**
 * Generates the portable record display's models.
 *
 * <ul>
 *   <li>{@code item/portable_record_display_body}: the display itself, box by box from {@link PrdShape}. It belongs to no
 *       item: {@code PrdItemRenderer} asks for it by name and draws it, then the live map and the lit lamp on top.</li>
 *   <li>{@code item/portable_record_display}: the item's model. Its parent is {@code builtin/entity}, which tells the game
 *       to hand the drawing to the item's own renderer (see {@code PrdClient}); all this file adds is where the display sits
 *       in each view: the inventory, the ground, an item frame, and the hand.</li>
 * </ul>
 *
 * <p>In the hand it is carried by its handle: in third person hanging from the fist like a toolbox, handle up, screen
 * facing out; in first person low on the right, turned a little towards the middle of the view. Raised in both hands, while
 * its map is open, is not a model transform: see {@code PrdPoses}. Left-hand views are left out, so the game mirrors the
 * right-hand ones.
 */
public final class PrdDataGen {

    public static final String BODY = "portable_record_display_body";
    public static final String BUTTON = "portable_record_display_button";
    public static final String EJECT = "portable_record_display_eject";

    /**
     * Tells the model generator that the textures the display borrows exist. Create's live in Create's jar, which the data
     * run's file helper does not necessarily search.
     */
    public static void trackCreateTextures(ExistingFileHelper existing) {
        for (PrdShape.Tex tex : PrdShape.Tex.values()) {
            if (tex.borrowed()) {
                existing.trackGenerated(ResourceLocation.parse(tex.location()), ModelProvider.TEXTURE);
            }
        }
    }

    static void registerModels(BlockStateProvider provider) {
        ItemModelBuilder body = textured(provider, BODY);
        for (PrdShape.Box box : PrdShape.bodyBoxes()) {
            box(body, box);
        }
        // The buttons are drawn apart from the body, so they can be pushed in: the top side button, where it is (the renderer
        // moves it down for the others), and the eject button.
        box(textured(provider, BUTTON), PrdShape.button(0));
        box(textured(provider, EJECT), PrdShape.eject());

        ItemModelBuilder item = provider.itemModels().getBuilder("portable_record_display")
                .parent(new ModelFile.UncheckedModelFile("builtin/entity"))
                .texture("particle", ResourceLocation.parse(PrdShape.Tex.CASING.location()));
        item.transforms()
                .transform(ItemDisplayContext.GUI).rotation(20, -25, 0).translation(1.1F, -2.2F, 0).scale(0.55F).end()
                .transform(ItemDisplayContext.GROUND).translation(0, 2, 0).scale(0.3F).end()
                .transform(ItemDisplayContext.FIXED).rotation(0, 180, 0).scale(0.6F).end()
                .transform(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
                .rotation(PrdShape.CARRY_ROTATION[0], PrdShape.CARRY_ROTATION[1], PrdShape.CARRY_ROTATION[2])
                .translation(PrdShape.CARRY_TRANSLATION[0], PrdShape.CARRY_TRANSLATION[1], PrdShape.CARRY_TRANSLATION[2])
                .scale(PrdShape.CARRY_SCALE).end()
                .transform(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND).rotation(0, -25, 0).translation(0, -3, 0).scale(0.45F).end()
                .end();
    }

    private static String key(PrdShape.Tex tex) {
        return tex.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** A model with every one of the display's textures named, ready for boxes. */
    private static ItemModelBuilder textured(BlockStateProvider provider, String name) {
        ItemModelBuilder model = provider.itemModels().getBuilder(name);
        for (PrdShape.Tex tex : PrdShape.Tex.values()) {
            model.texture(key(tex), ResourceLocation.parse(tex.location()));
        }
        return model.texture("particle", ResourceLocation.parse(PrdShape.Tex.CASING.location()));
    }

    /**
     * One box. Each face shows a piece of its texture at one texel per model pixel, in one of three ways:
     * <ul>
     *   <li>The brass trim and the dark oak grip are cut out of their textures where they sit, the way a block model's faces
     *       are ({@link #cutout}): each part is a proportional piece of the brass block, and neighbouring parts carry on
     *       where each other leave off.</li>
     *   <li>A part marked {@code fullUv} (the glass, a lens) shows its whole texture, squeezed to fit.</li>
     *   <li>Anything else (the train casing, the dark iron) shows the middle of its texture, so big faces get the casing's
     *       brass frame and small ones its plain centre.</li>
     * </ul>
     */
    private static void box(ItemModelBuilder model, PrdShape.Box box) {
        var element = model.element().from(box.x1(), box.y1(), box.z1()).to(box.x2(), box.y2(), box.z2());
        if (box.rotationX() != 0) {
            element.rotation().angle(box.rotationX()).axis(Direction.Axis.X)
                    .origin((box.x1() + box.x2()) / 2, (box.y1() + box.y2()) / 2, (box.z1() + box.z2()) / 2).end();
        }
        float sizeX = box.x2() - box.x1();
        float sizeY = box.y2() - box.y1();
        float sizeZ = box.z2() - box.z1();
        for (Direction direction : Direction.values()) {
            if (!box.has(1 << direction.ordinal())) {
                continue;
            }
            float across;
            float up;
            float acrossFrom;  // where the face starts, across and down, in the texture's own terms (y runs down it)
            float downFrom;
            if (direction.getAxis().isVertical()) {
                across = sizeX;
                up = sizeZ;
                acrossFrom = box.x1();
                downFrom = box.z1();
            } else if (direction.getStepX() != 0) {
                across = sizeZ;
                up = sizeY;
                acrossFrom = box.z1();
                downFrom = 16 - box.y2();
            } else {
                across = sizeX;
                up = sizeY;
                acrossFrom = box.x1();
                downFrom = 16 - box.y2();
            }
            var face = element.face(direction).texture("#" + key(box.texture()));
            if (box.texture() == PrdShape.Tex.TRIM || box.texture() == PrdShape.Tex.GRIP) {
                float u = cutout(acrossFrom, across);
                float v = cutout(downFrom, up);
                face.uvs(u, v, u + across, v + up);
            } else if (box.fullUv()) {
                face.uvs(0, 0, 16, 16);
            } else {
                face.uvs(8 - across / 2, 8 - up / 2, 8 + across / 2, 8 + up / 2);
            }
            face.end();
        }
        element.end();
    }

    /**
     * Where on a 16-texel texture a face's piece starts: where the face sits, wrapped into the texture (the model reaches past
     * the block on every side), and moved back if need be so the whole piece fits on it.
     */
    static float cutout(float from, float size) {
        float start = ((from % 16) + 16) % 16;
        return Math.max(0, Math.min(start, 16 - size));
    }

    private PrdDataGen() {
    }
}
