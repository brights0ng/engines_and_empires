package dev.brights0ng.enginesandempires.frontier.upkeep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.frontier.upkeep.TorchClock.Action;

class TorchClockTest {

    private static final long DAY = 24_000;

    @Test
    void outsideSettledLandATorchBurnsForADay() {
        assertEquals(Action.KEEP, TorchClock.decide(1000, 1000 + DAY - 1, false, DAY));
        assertEquals(Action.BURN_OUT, TorchClock.decide(1000, 1000 + DAY, false, DAY));
    }

    @Test
    void inSettledLandItIsLookedAfterAndNeverGoesOut() {
        assertEquals(Action.TEND, TorchClock.decide(0, 10 * DAY, true, DAY), "even an old torch is looked after");
        assertEquals(Action.KEEP, TorchClock.decide(5000, 5000 + TorchClock.TEND_EVERY - 1, true, DAY),
                "recently looked after: nothing to save");
    }

    @Test
    void aTorchLeftBehindGetsItsDayFromWhenItWasLastLookedAfter() {
        long lastTended = 50_000;
        assertEquals(Action.KEEP, TorchClock.decide(lastTended, lastTended + DAY / 2, false, DAY));
        assertEquals(Action.BURN_OUT, TorchClock.decide(lastTended, lastTended + DAY, false, DAY));
    }

    @Test
    void theDryTorchTextureIsAStickWithACharredTip() {
        BufferedImage image = FrontierTextures.all().get("block/dry_torch");
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());
        for (int y = 6; y <= 15; y++) {
            assertEquals(0xFF, image.getRGB(7, y) >>> 24, "the stick is solid at y " + y);
        }
        assertEquals(0, image.getRGB(0, 0) >>> 24, "outside the stick is clear");
        assertTrue(brightness(image.getRGB(7, 6)) < brightness(image.getRGB(7, 12)), "the tip is darker than the wood");
    }

    private static int brightness(int argb) {
        return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
    }
}
