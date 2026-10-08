package dev.brights0ng.enginesandempires.weather.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Lifespans at x0.2 and the phases of a cloud's life. */
class CloudLifeTest {

    private static final int MIN = CloudLife.MINUTE;

    private static UUID region(int i) {
        return new UUID(i * 7919L + 13, i * 104729L + 7);
    }

    @Test
    void lifespansAreRealOnesAtFifthScale() {
        for (int i = 0; i < 300; i++) {
            CloudLife.Span humilis = CloudLife.span(CloudType.CUMULUS_HUMILIS, region(i));
            assertTrue(humilis.storm() >= 2 * MIN && humilis.storm() <= 8 * MIN, "humilis " + humilis);
            assertEquals(0, humilis.linger());
            CloudLife.Span storm = CloudLife.span(CloudType.CUMULONIMBUS_CAPILLATUS, region(i));
            assertTrue(storm.storm() >= 9 * MIN && storm.storm() <= 18 * MIN, "capillatus " + storm);
            assertTrue(storm.linger() >= 12 * MIN && storm.linger() <= 36 * MIN, "linger " + storm);
            assertTrue(storm.birth() + storm.death() < storm.storm(), "with a mature stretch between");
        }
    }

    @Test
    void layerCloudsFormAndFadeAtTheirOwnPace() {
        CloudLife.Span s = CloudLife.span(CloudType.NIMBOSTRATUS, region(4));
        assertEquals(4 * MIN, s.birth());
        assertEquals(4 * MIN, s.death());
        assertTrue(s.storm() >= s.birth() + s.death());
    }

    @Test
    void mostCloudsAreShortLived() {
        int n = 2000;
        double[] lives = new double[n];
        for (int i = 0; i < n; i++) {
            lives[i] = CloudLife.span(CloudType.CUMULONIMBUS_CAPILLATUS, region(i)).storm();
        }
        Arrays.sort(lives);
        double median = lives[n / 2];
        // Skewed toward the short end: the median well below the middle of 9-18 minutes.
        assertTrue(median < 12.5 * MIN, "median " + median / MIN + " min");
        assertTrue(lives[n - 1] > 17 * MIN, "but long-lived storms happen");
    }

    @Test
    void oneCloudSharesItsSpan() {
        UUID r = UUID.randomUUID();
        assertEquals(CloudLife.span("cumulonimbus_capillatus", r), CloudLife.span("cumulonimbus_capillatus", r));
    }

    @Test
    void aCloudFormsMaturesDiesAndItsAnvilLingers() {
        CloudLife.Span s = CloudLife.span(CloudType.CUMULONIMBUS_CAPILLATUS, region(3));
        int life = s.total();
        CloudLife.Phase born = CloudLife.phase(s, 0, life);
        assertEquals(0, born.growth(), 1e-9);
        assertFalse(born.visible());

        CloudLife.Phase mature = CloudLife.phase(s, s.birth() + 10, life);
        assertEquals(1, mature.growth(), 1e-9);
        assertEquals(0, mature.decay(), 1e-9);
        assertEquals("mature", mature.name());

        CloudLife.Phase dying = CloudLife.phase(s, s.storm() - s.death() / 2.0, life);
        assertEquals(0.5, dying.decay(), 1e-6);
        assertEquals(0, dying.anvilDecay(), 1e-9, "the anvil holds while the body dies");
        assertTrue(dying.precipitation() < 0.6, "rain tapers as it dies");

        CloudLife.Phase orphan = CloudLife.phase(s, s.storm() + s.linger() / 2.0, life);
        assertEquals(1, orphan.decay(), 1e-9);
        assertEquals(0.5, orphan.anvilDecay(), 1e-6);
        assertTrue(orphan.visible(), "the orphan anvil is still there");
        assertEquals(0, orphan.precipitation(), 1e-9, "and dry");

        assertFalse(CloudLife.phase(s, life, life).visible(), "gone at the end");
    }

    @Test
    void cloudsWithoutAnAnvilFadeTheirWholeShape() {
        CloudLife.Span s = CloudLife.span("cumulus_mediocris", region(5));
        CloudLife.Phase p = CloudLife.phase(s, s.storm() - s.death() / 4.0, s.total());
        assertEquals(p.decay(), p.anvilDecay(), 1e-9);
    }

    @Test
    void phasesFollowAStretchedLifetime() {
        CloudLife.Span s = CloudLife.span("cumulus_congestus", region(9));
        long longer = s.total() + 10L * MIN;
        // Still mature where the old lifetime would already have been dying.
        CloudLife.Phase p = CloudLife.phase(s, s.total() - s.death() / 2.0, longer);
        assertEquals(0, p.decay(), 1e-9);
    }

    @Test
    void growingIntoAStormExtendsTheLifetime() {
        UUID r = region(2);
        CloudLife.Span cumulus = CloudLife.span("cumulus_congestus", r);
        CloudLife.Span storm = CloudLife.span("cumulonimbus_capillatus", r);
        long age = cumulus.storm() - cumulus.death();
        long extended = CloudLife.evolvedLifetime(storm, age, cumulus.total());
        assertTrue(extended >= storm.total());
        assertTrue(extended - age >= storm.death() + storm.linger(), "room to die and linger from here");
        // Already long enough: kept.
        assertEquals(10_000_000L, CloudLife.evolvedLifetime(storm, age, 10_000_000L));
    }
}
