package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Checks the generated resources of the mechanical thumper: the block model's axis split, the blockstate's rotation, the
 * lamp's tint, the recipe and the name. If any of these fail, run the {@code Data} run configuration.
 */
class ThumperContentTest {

    private static final Path GENERATED = Path.of("src/generated/resources");
    private static final String ASSETS = "assets/engines_and_empires/";
    private static final String DATA = "data/engines_and_empires/";

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Missing " + path + ": has the Data run been done?", e);
        }
    }

    private static String generated(String relative) {
        return read(GENERATED.resolve(relative));
    }

    @Test
    void everyTextureExistsAndIsSixteenBySixteen() throws IOException {
        for (String texture : ThumperTextures.all().keySet()) {
            Path path = GENERATED.resolve(ASSETS + "textures/" + texture + ".png");
            assertTrue(Files.exists(path), path + " is missing");
            BufferedImage image = ImageIO.read(path.toFile());
            assertEquals(16, image.getWidth(), texture);
            assertEquals(16, image.getHeight(), texture);
        }
    }

    /** The whole reason the block can be driven by a cog at all depends on this being right: see MechanicalThumperBlock. */
    @Test
    void theBlockstateRotatesTheSameModelForTheXAxisRatherThanUsingASecondOne() {
        String state = generated(ASSETS + "blockstates/mechanical_thumper.json");
        assertTrue(state.contains("\"axis=z\""));
        assertTrue(state.contains("\"axis=x\""));
        assertTrue(state.contains("engines_and_empires:block/mechanical_thumper_housing"),
                "both variants should point at the one housing model");
        // Only one model file should exist: the x variant reuses the z one via rotation, not a second model.
        assertFalse(state.contains("mechanical_thumper_housing_x"), "there should be no separate model for the X axis");
        assertTrue(state.contains("\"y\": 90"), "the X-axis variant must rotate the model a quarter turn");
    }

    /**
     * Create's cogwheel spins in the z 6-10 slice (the model is built for the Z axis), its teeth reaching a pixel past
     * every face around the axis. No part of the housing may intrude on that slice, or the cog would clip through it and
     * a neighbouring cog would look like it meshes with a wall.
     */
    @Test
    void theHousingLeavesTheCogsSlotOpen() {
        List<float[][]> boxes = boxes(generated(ASSETS + "models/block/mechanical_thumper_housing.json"));
        assertFalse(boxes.isEmpty());
        for (float[][] box : boxes) {
            assertFalse(box[0][2] < 10 && box[1][2] > 6, "a housing box intrudes on the cog's slot: z "
                    + box[0][2] + " to " + box[1][2]);
        }
    }

    /** Wherever the head is in its travel, it must never pass through the cog: only its prongs may rise past y -1. */
    @Test
    void theHeadClearsTheCog() {
        List<float[][]> boxes = boxes(generated(ASSETS + "models/block/mechanical_thumper_head.json"));
        assertFalse(boxes.isEmpty());
        for (float[][] box : boxes) {
            boolean inSlot = box[0][2] < 10 && box[1][2] > 6;
            assertFalse(inSlot && box[1][1] > -1, "a head box reaches up into the cog: y to " + box[1][1]);
        }
    }

    /** The housing reuses Create's own press textures rather than drawing its own. */
    @Test
    void theHousingUsesThePressTextures() {
        String model = generated(ASSETS + "models/block/mechanical_thumper_housing.json");
        assertTrue(model.contains("create:block/mechanical_press_side"));
        assertTrue(model.contains("create:block/mechanical_press_top"));
        String head = generated(ASSETS + "models/block/mechanical_thumper_head.json");
        assertTrue(head.contains("create:block/mechanical_press_head"));
        assertTrue(head.contains("create:block/mechanical_press_pole"));
    }

    /** The dark lamp is part of the housing's own model, so the block looks right with no custom rendering at all. */
    @Test
    void theDarkLampIsBakedIntoTheHousingAndIsNotTinted() {
        String model = generated(ASSETS + "models/block/mechanical_thumper_housing.json");
        assertTrue(model.contains("engines_and_empires:block/mechanical_thumper_lamp\""));
        assertFalse(model.contains("tintindex"), "only the separate lit lamp model may be tinted");
    }

    /**
     * The lit lamp carries its own amber in its texture and is drawn untinted, simply on or off; a tinted face would take
     * whatever colour the renderer passed instead.
     */
    @Test
    void theLitLampModelIsNotTinted() {
        String model = generated(ASSETS + "models/block/mechanical_thumper_lamp_lit.json");
        assertEquals(0, countMatches(model, "tintindex"));
        assertTrue(model.contains("engines_and_empires:block/mechanical_thumper_lamp_lit\""));
    }

    /**
     * Create's style of item icon: the machine in 3D (housing, head and cog), on the vanilla block parent, not a flat
     * sprite. And the old sprite is gone.
     */
    @Test
    void theItemModelIsTheMachineIn3D() {
        String item = generated(ASSETS + "models/item/mechanical_thumper.json");
        assertTrue(item.contains("\"parent\": \"minecraft:block/block\""), "should be a 3D block-style model");
        assertFalse(item.contains("layer0"), "should not be a flat sprite");
        assertTrue(item.contains("create:block/mechanical_press_side"), "the housing");
        assertTrue(item.contains("create:block/mechanical_press_head"), "the head");
        assertTrue(item.contains("create:block/cogwheel"), "the cog");
        assertFalse(Files.exists(GENERATED.resolve(ASSETS + "textures/item/mechanical_thumper.png")),
                "the old flat icon should be gone");
    }

    @Test
    void itCanBeCraftedFromACasingACogwheelAndAnIronBlockAndDropsItself() {
        String recipe = generated(DATA + "recipe/mechanical_thumper.json");
        assertTrue(recipe.contains("\"id\": \"engines_and_empires:mechanical_thumper\""));
        assertTrue(recipe.contains("create:cogwheel"), "the internal gearing should be the same cogwheel it meshes with");
        assertTrue(recipe.contains("create:andesite_casing"));
        assertTrue(recipe.contains("minecraft:iron_block"));

        String loot = generated(DATA + "loot_table/blocks/mechanical_thumper.json");
        assertTrue(loot.contains("engines_and_empires:mechanical_thumper"));
    }

    @Test
    void aChokedThumperDropsItsMetalOrWithSilkTouchItselfAndCooksClean() {
        String mechanical = generated(DATA + "loot_table/blocks/choked_mechanical_thumper.json");
        assertTrue(mechanical.contains("minecraft:silk_touch"));
        assertTrue(mechanical.contains("minecraft:iron_block"));
        assertTrue(mechanical.contains("create:andesite_alloy"));
        String combustive = generated(DATA + "loot_table/blocks/choked_combustive_thumper.json");
        assertTrue(combustive.contains("create:copper_sheet"));
        String cook = generated(DATA + "recipe/mechanical_thumper_from_choked.json");
        assertTrue(cook.contains("minecraft:smelting"));
        assertTrue(cook.contains("engines_and_empires:choked_mechanical_thumper"));
    }

    @Test
    void itIsMineableWithAPickaxe() {
        assertTrue(generated("data/minecraft/tags/block/mineable/pickaxe.json").contains("engines_and_empires:mechanical_thumper"));
    }

    @Test
    void itIsNamed() throws IOException {
        String lang = Files.readString(Path.of("src/main/resources/assets/engines_and_empires/lang/en_us.json"));
        assertTrue(lang.contains("\"block.engines_and_empires.mechanical_thumper\""));
    }

    private static int countMatches(String text, String what) {
        int count = 0;
        for (int at = text.indexOf(what); at >= 0; at = text.indexOf(what, at + what.length())) {
            count++;
        }
        return count;
    }

    private static final Pattern FROM = Pattern.compile("\"from\":\\s*\\[\\s*([-\\d.]+),\\s*([-\\d.]+),\\s*([-\\d.]+)\\s*]");
    private static final Pattern TO = Pattern.compile("\"to\":\\s*\\[\\s*([-\\d.]+),\\s*([-\\d.]+),\\s*([-\\d.]+)\\s*]");

    /** Every element's {from, to} corners, in order. */
    private static List<float[][]> boxes(String model) {
        List<float[]> froms = corners(FROM.matcher(model));
        List<float[]> tos = corners(TO.matcher(model));
        assertEquals(froms.size(), tos.size());
        List<float[][]> boxes = new ArrayList<>();
        for (int i = 0; i < froms.size(); i++) {
            boxes.add(new float[][]{froms.get(i), tos.get(i)});
        }
        return boxes;
    }

    private static List<float[]> corners(Matcher matcher) {
        List<float[]> corners = new ArrayList<>();
        while (matcher.find()) {
            corners.add(new float[]{Float.parseFloat(matcher.group(1)), Float.parseFloat(matcher.group(2)),
                    Float.parseFloat(matcher.group(3))});
        }
        return corners;
    }
}
