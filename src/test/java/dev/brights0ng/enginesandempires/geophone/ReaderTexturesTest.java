package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ReaderTexturesTest {

    private static final Map<String, BufferedImage> ALL = ReaderTextures.all();

    private static boolean isRed(int argb) {
        return (argb >>> 24) != 0 && ((argb >> 16) & 0xFF) >= 170 && ((argb >> 8) & 0xFF) <= 60 && (argb & 0xFF) <= 60;
    }

    private static int visible(BufferedImage image, int fromY, int toY) {
        int count = 0;
        for (int y = fromY; y <= toY; y++) {
            for (int x = 0; x < 16; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void thereIsAPictureForEveryNeedlePositionAndTheOtherPartsToo() {
        assertEquals(ReaderCompass.FRAMES + 1 + 3 + 3, ALL.size());
        for (int frame = 0; frame < ReaderCompass.FRAMES; frame++) {
            assertTrue(ALL.containsKey("item/windup_reader_" + frame), "frame " + frame);
        }
        for (String name : new String[]{"item/windup_reader_idle", "item/windup_reader_arrow_up", "item/windup_reader_arrow_level",
                "item/windup_reader_arrow_down", "block/windup_reader", "block/windup_reader_lamp", "block/windup_reader_lamp_lit"}) {
            assertTrue(ALL.containsKey(name), name);
        }
    }

    @Test
    void everyTextureIsSixteenBySixteen() {
        for (Map.Entry<String, BufferedImage> entry : ALL.entrySet()) {
            assertEquals(16, entry.getValue().getWidth(), entry.getKey());
            assertEquals(16, entry.getValue().getHeight(), entry.getKey());
        }
    }

    @Test
    void blockTexturesAreOpaqueAndItemTexturesHaveClearCorners() {
        for (Map.Entry<String, BufferedImage> entry : ALL.entrySet()) {
            BufferedImage image = entry.getValue();
            if (entry.getKey().startsWith("block/")) {
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        assertEquals(255, image.getRGB(x, y) >>> 24, entry.getKey() + " has a see-through pixel at " + x + ", " + y);
                    }
                }
            } else {
                assertEquals(0, image.getRGB(15, 15) >>> 24, entry.getKey() + " should have a clear corner");
            }
        }
    }

    @Test
    void everyNeedlePositionIsADifferentPicture() {
        for (int a = 0; a < ReaderCompass.FRAMES; a++) {
            for (int b = a + 1; b < ReaderCompass.FRAMES; b++) {
                boolean same = true;
                for (int y = 0; y < 16 && same; y++) {
                    for (int x = 0; x < 16 && same; x++) {
                        same = ALL.get("item/windup_reader_" + a).getRGB(x, y) == ALL.get("item/windup_reader_" + b).getRGB(x, y);
                    }
                }
                assertFalse(same, "frames " + a + " and " + b + " are identical");
            }
        }
    }

    /**
     * The red half of the needle must point where the frame number says: clockwise from straight up. If the drawing turned
     * the wrong way, the compass would point to the mirror image of where the reading is.
     */
    @Test
    void theRedEndOfTheNeedlePointsClockwiseFromStraightUp() {
        for (int frame = 0; frame < ReaderCompass.FRAMES; frame++) {
            BufferedImage image = ALL.get("item/windup_reader_" + frame);
            double sumX = 0;
            double sumY = 0;
            int count = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if (isRed(image.getRGB(x, y))) {
                        sumX += x + 0.5;
                        sumY += y + 0.5;
                        count++;
                    }
                }
            }
            assertTrue(count >= 3, "frame " + frame + " has almost no red needle");
            double dx = sumX / count - 8.0;
            double dy = sumY / count - 9.0;
            double degrees = Math.toDegrees(Math.atan2(dx, -dy));
            double expected = 360.0 * frame / ReaderCompass.FRAMES;
            double difference = Math.abs(((degrees - expected) % 360.0 + 540.0) % 360.0 - 180.0);
            assertTrue(difference <= 22.0, "frame " + frame + ": the needle points " + degrees + " degrees, not " + expected);
        }
    }

    @Test
    void theIdleDialHasNoNeedle() {
        BufferedImage idle = ALL.get("item/windup_reader_idle");
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertFalse(isRed(idle.getRGB(x, y)), "the idle dial is drawing a needle at " + x + ", " + y);
            }
        }
    }

    @Test
    void theHeightArrowsPointTheWayTheyClaimAndStayInTheStripAboveTheCase() {
        BufferedImage up = ALL.get("item/windup_reader_arrow_up");
        BufferedImage down = ALL.get("item/windup_reader_arrow_down");
        BufferedImage level = ALL.get("item/windup_reader_arrow_level");

        assertEquals(1, visible(up, 0, 0));
        assertEquals(3, visible(up, 1, 1));
        assertEquals(5, visible(up, 2, 2));

        assertEquals(5, visible(down, 0, 0));
        assertEquals(3, visible(down, 1, 1));
        assertEquals(1, visible(down, 2, 2));

        assertEquals(5, visible(level, 0, 0));
        assertEquals(0, visible(level, 1, 1));
        assertEquals(5, visible(level, 2, 2));

        for (BufferedImage arrow : new BufferedImage[]{up, down, level}) {
            assertEquals(0, visible(arrow, 3, 15), "an arrow must stay above the case, or it would cover the dial");
        }
        for (int frame = 0; frame < ReaderCompass.FRAMES; frame++) {
            assertEquals(0, visible(ALL.get("item/windup_reader_" + frame), 0, 1), "the strip for the arrow must be empty in frame " + frame);
        }
    }

    @Test
    void theLitLampIsBrighterThanTheDarkOne() {
        assertTrue(brightness(ALL.get("block/windup_reader_lamp_lit")) > 2.0 * brightness(ALL.get("block/windup_reader_lamp")));
        assertNotEquals(ALL.get("block/windup_reader_lamp").getRGB(8, 8), ALL.get("block/windup_reader_lamp_lit").getRGB(8, 8));
    }

    @Test
    void theDrawingIsDeterministic() {
        Map<String, BufferedImage> again = ReaderTextures.all();
        for (Map.Entry<String, BufferedImage> entry : ALL.entrySet()) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(entry.getValue().getRGB(x, y), again.get(entry.getKey()).getRGB(x, y), entry.getKey());
                }
            }
        }
    }

    private static double brightness(BufferedImage image) {
        double total = 0.0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int p = image.getRGB(x, y);
                total += ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
            }
        }
        return total;
    }
}
