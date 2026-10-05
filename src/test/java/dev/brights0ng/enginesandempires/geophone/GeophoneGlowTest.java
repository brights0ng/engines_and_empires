package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.geophone.GeophoneGlow.ActivePulse;
import dev.brights0ng.enginesandempires.oregen.OreTypes;

class GeophoneGlowTest {

    // ---- choose ----

    @Test
    void nothingActiveShowsNothing() {
        assertNull(GeophoneGlow.choose(List.of(), null));
        assertNull(GeophoneGlow.choose(List.of(), "iron"));
    }

    @Test
    void oneActivePulseIsShown() {
        assertEquals("iron", GeophoneGlow.choose(List.of(new ActivePulse(10, "iron")), null));
    }

    @Test
    void itKeepsShowingWhatItWasAlreadyShowingWhileThatPulseIsStillActive() {
        List<ActivePulse> active = List.of(new ActivePulse(10, "coal"), new ActivePulse(50, "gold"));
        assertEquals("coal", GeophoneGlow.choose(active, "coal"), "coal was already showing and is still active: no flicker to gold");
    }

    @Test
    void whenWhatWasShowingHasEndedItPicksTheOneRunningLongest() {
        List<ActivePulse> active = List.of(new ActivePulse(50, "gold"), new ActivePulse(10, "iron"), new ActivePulse(80, "coal"));
        assertEquals("iron", GeophoneGlow.choose(active, "redstone"), "redstone is gone; iron started earliest of what remains");
    }

    @Test
    void withNothingShowingBeforeItPicksTheEarliestStarter() {
        List<ActivePulse> active = List.of(new ActivePulse(50, "gold"), new ActivePulse(10, "iron"), new ActivePulse(80, "coal"));
        assertEquals("iron", GeophoneGlow.choose(active, null));
    }

    @Test
    void aNewShorterPulseNeverStealsTheDisplayFromALongerRunningOne() {
        // A long-running deposit is already showing; a new one starts on top of it. It must not take over.
        List<ActivePulse> active = List.of(new ActivePulse(0, "lapis"), new ActivePulse(19, "diamond"));
        assertEquals("lapis", GeophoneGlow.choose(active, "lapis"));
    }

    // ---- colorFor ----

    @Test
    void everyOreThePackGeneratesHasItsOwnColour() {
        assertTrue(GeophoneGlow.coversEveryOre());
    }

    @Test
    void everyOresColourIsDistinct() {
        Set<String> seen = new HashSet<>();
        for (var type : OreTypes.ALL) {
            float[] color = GeophoneGlow.colorFor(type.id());
            String key = color[0] + "," + color[1] + "," + color[2];
            assertTrue(seen.add(key), type.id() + "'s colour collides with another ore's");
        }
    }

    @Test
    void everyColourComponentIsInRange() {
        for (var type : OreTypes.ALL) {
            for (float component : GeophoneGlow.colorFor(type.id())) {
                assertTrue(component >= 0.0F && component <= 1.0F, type.id());
            }
        }
    }

    @Test
    void anUnknownOreFallsBackToWhiteRatherThanFailing() {
        assertArrayEquals(GeophoneGlow.FALLBACK_COLOR, GeophoneGlow.colorFor("something_made_up"));
    }

    @Test
    void goldAndNetherGoldAreNoticeablyDifferentDespiteBothBeingWarm() {
        float[] gold = GeophoneGlow.colorFor("gold");
        float[] netherGold = GeophoneGlow.colorFor("nether_gold");
        assertNotEquals(gold[1], netherGold[1], 0.1F, "the two golds should not read as the same colour");
    }
}
