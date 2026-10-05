package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The logbook's texture, model, recipe and every piece of text its screen and menu can show. */
class LogbookContentTest {

    private static final Path GENERATED = Path.of("src/generated/resources");
    private static final Path LANG = Path.of("src/main/resources/assets/engines_and_empires/lang/en_us.json");

    @Test
    void theIconIsASixteenBySixteenPictureOfABook() {
        Map<String, BufferedImage> textures = LogbookTextures.all();
        assertEquals(1, textures.size());
        BufferedImage icon = textures.get("item/logbook");
        assertEquals(16, icon.getWidth());
        assertEquals(16, icon.getHeight());
        assertEquals(0, icon.getRGB(0, 0) >>> 24, "a clear corner");
        int visible = 0;
        int amber = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int pixel = icon.getRGB(x, y);
                if ((pixel >>> 24) != 0) {
                    visible++;
                }
                if (((pixel >> 16) & 0xFF) > 200 && ((pixel >> 8) & 0xFF) > 150 && (pixel & 0xFF) < 100) {
                    amber++;
                }
            }
        }
        assertTrue(visible > 120, "a book fills most of the picture, and this has " + visible + " visible pixels");
        assertTrue(amber >= 8, "the flaps of the board on the cover are amber");
    }

    @Test
    void theIconIsDeterministic() {
        BufferedImage first = LogbookTextures.all().get("item/logbook");
        BufferedImage second = LogbookTextures.all().get("item/logbook");
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(first.getRGB(x, y), second.getRGB(x, y));
            }
        }
    }

    @Test
    void theGeneratedFilesExist() throws IOException {
        assertTrue(Files.exists(GENERATED.resolve("assets/engines_and_empires/textures/item/logbook.png")), "the texture");
        String model = Files.readString(GENERATED.resolve("assets/engines_and_empires/models/item/logbook.json"));
        assertTrue(model.contains("engines_and_empires:item/logbook"), "the model uses the texture");
        String recipe = Files.readString(GENERATED.resolve("data/engines_and_empires/recipe/logbook.json"));
        assertTrue(recipe.contains("\"id\": \"engines_and_empires:logbook\""));
        assertTrue(recipe.contains("minecraft:book"), "a book is what it is made from");
    }

    /**
     * Every piece of text the screen and menu use. If one were missing, the player would see the raw key, such as
     * "gui.engines_and_empires.logbook.confirm", where a word should be.
     */
    @Test
    void everyPieceOfTextTheLogbookCanShowIsWritten() throws IOException {
        String lang = Files.readString(LANG);
        List<String> keys = List.of(
                "item.engines_and_empires.logbook",
                "item.engines_and_empires.logbook.tooltip",
                "container.engines_and_empires.logbook",
                "gui.engines_and_empires.logbook.save",
                "gui.engines_and_empires.logbook.load",
                "gui.engines_and_empires.logbook.delete",
                "gui.engines_and_empires.logbook.clear",
                "gui.engines_and_empires.logbook.rename",
                "gui.engines_and_empires.logbook.name_hint",
                "gui.engines_and_empires.logbook.confirm",
                "gui.engines_and_empires.logbook.reader",
                "gui.engines_and_empires.logbook.entry",
                "gui.engines_and_empires.logbook.elsewhere",
                "gui.engines_and_empires.logbook.here",
                "gui.engines_and_empires.logbook.up",
                "gui.engines_and_empires.logbook.down",
                "gui.engines_and_empires.logbook.level",
                "message.engines_and_empires.logbook.saved",
                "message.engines_and_empires.logbook.updated",
                "message.engines_and_empires.logbook.already",
                "message.engines_and_empires.logbook.older",
                "message.engines_and_empires.logbook.renamed",
                "message.engines_and_empires.logbook.full",
                "message.engines_and_empires.logbook.no_reader",
                "message.engines_and_empires.logbook.no_reading",
                "message.engines_and_empires.logbook.reader_cleared",
                "message.engines_and_empires.logbook.reader_empty",
                "message.engines_and_empires.logbook.loaded",
                "message.engines_and_empires.logbook.deleted");
        for (String key : keys) {
            assertTrue(lang.contains("\"" + key + "\""), "no text for " + key);
        }
    }

    @Test
    void theTextThatTakesANumberHasAPlaceForIt() throws IOException {
        String lang = Files.readString(LANG);
        for (String key : List.of("message.engines_and_empires.logbook.saved", "message.engines_and_empires.logbook.updated",
                "message.engines_and_empires.logbook.already", "message.engines_and_empires.logbook.older",
                "message.engines_and_empires.logbook.renamed",
                "message.engines_and_empires.logbook.loaded", "message.engines_and_empires.logbook.deleted",
                "gui.engines_and_empires.logbook.entry", "gui.engines_and_empires.logbook.up", "gui.engines_and_empires.logbook.down",
                "gui.engines_and_empires.logbook.elsewhere")) {
            int at = lang.indexOf("\"" + key + "\"");
            String line = lang.substring(at, lang.indexOf('\n', at));
            assertTrue(line.contains("%s"), key + " is given something to say but has no %s: " + line);
        }
    }

    @Test
    void everyActionTheScreenCanAskForHasItsOwnId() {
        int[] actions = {ReadingBoardMenu.SAVE, ReadingBoardMenu.CLEAR_READER, ReadingBoardMenu.LOAD, ReadingBoardMenu.DELETE,
                ReadingBoardMenu.RENAME};
        assertEquals(actions.length, java.util.Arrays.stream(actions).distinct().count(), "no two actions share an id");
    }

    /** The name the creative tab was asked for, exactly. */
    @Test
    void theCreativeTabIsCalledEnginesAndEmpiresSeismology() throws IOException {
        String lang = Files.readString(LANG);
        assertTrue(lang.contains("\"itemGroup.engines_and_empires.seismology\": \"Engines and Empires: Seismology\""));
    }
}
