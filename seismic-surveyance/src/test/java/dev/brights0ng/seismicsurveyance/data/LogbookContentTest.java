// Reorganized by Claude
package dev.brights0ng.seismicsurveyance.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.brights0ng.seismicsurveyance.datagen.LogbookTextures;
import dev.brights0ng.seismicsurveyance.menu.ReadingBoardMenu;
import org.junit.jupiter.api.Test;

/** The logbook's texture, model, recipe and every piece of text its screen and menu can show. */
class LogbookContentTest {

    private static final Path GENERATED = Path.of("src/generated/resources");
    private static final Path LANG = Path.of("src/main/resources/assets/seismic_surveyance/lang/en_us.json");

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
        assertTrue(Files.exists(GENERATED.resolve("assets/seismic_surveyance/textures/item/logbook.png")), "the texture");
        String model = Files.readString(GENERATED.resolve("assets/seismic_surveyance/models/item/logbook.json"));
        assertTrue(model.contains("seismic_surveyance:item/logbook"), "the model uses the texture");
        String recipe = Files.readString(GENERATED.resolve("data/seismic_surveyance/recipe/logbook.json"));
        assertTrue(recipe.contains("\"id\": \"seismic_surveyance:logbook\""));
        assertTrue(recipe.contains("minecraft:book"), "a book is what it is made from");
    }

    /**
     * Every piece of text the screen and menu use. If one were missing, the player would see the raw key, such as
     * "gui.seismic_surveyance.logbook.confirm", where a word should be.
     */
    @Test
    void everyPieceOfTextTheLogbookCanShowIsWritten() throws IOException {
        String lang = Files.readString(LANG);
        List<String> keys = List.of(
                "item.seismic_surveyance.logbook",
                "item.seismic_surveyance.logbook.tooltip",
                "container.seismic_surveyance.logbook",
                "gui.seismic_surveyance.logbook.save",
                "gui.seismic_surveyance.logbook.load",
                "gui.seismic_surveyance.logbook.delete",
                "gui.seismic_surveyance.logbook.clear",
                "gui.seismic_surveyance.logbook.rename",
                "gui.seismic_surveyance.logbook.name_hint",
                "gui.seismic_surveyance.logbook.confirm",
                "gui.seismic_surveyance.logbook.reader",
                "gui.seismic_surveyance.logbook.entry",
                "gui.seismic_surveyance.logbook.elsewhere",
                "gui.seismic_surveyance.logbook.here",
                "gui.seismic_surveyance.logbook.up",
                "gui.seismic_surveyance.logbook.down",
                "gui.seismic_surveyance.logbook.level",
                "message.seismic_surveyance.logbook.saved",
                "message.seismic_surveyance.logbook.updated",
                "message.seismic_surveyance.logbook.already",
                "message.seismic_surveyance.logbook.older",
                "message.seismic_surveyance.logbook.renamed",
                "message.seismic_surveyance.logbook.full",
                "message.seismic_surveyance.logbook.no_reader",
                "message.seismic_surveyance.logbook.no_reading",
                "message.seismic_surveyance.logbook.reader_cleared",
                "message.seismic_surveyance.logbook.reader_empty",
                "message.seismic_surveyance.logbook.loaded",
                "message.seismic_surveyance.logbook.deleted");
        for (String key : keys) {
            assertTrue(lang.contains("\"" + key + "\""), "no text for " + key);
        }
    }

    @Test
    void theTextThatTakesANumberHasAPlaceForIt() throws IOException {
        String lang = Files.readString(LANG);
        for (String key : List.of("message.seismic_surveyance.logbook.saved", "message.seismic_surveyance.logbook.updated",
                "message.seismic_surveyance.logbook.already", "message.seismic_surveyance.logbook.older",
                "message.seismic_surveyance.logbook.renamed",
                "message.seismic_surveyance.logbook.loaded", "message.seismic_surveyance.logbook.deleted",
                "gui.seismic_surveyance.logbook.entry", "gui.seismic_surveyance.logbook.up", "gui.seismic_surveyance.logbook.down",
                "gui.seismic_surveyance.logbook.elsewhere")) {
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

    /** The name the creative tab was asked for, exactly, and the key binding category's (Bright, after split step 2e). */
    @Test
    void theCreativeTabIsCalledSeismicSurveyance() throws IOException {
        String lang = Files.readString(LANG);
        assertTrue(lang.contains("\"itemGroup.seismic_surveyance.seismology\": \"Seismic Surveyance\""));
        assertTrue(lang.contains("\"key.categories.seismic_surveyance\": \"Seismic Surveyance\""));
    }
}
