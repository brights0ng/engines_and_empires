package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SeismicWaveTest {

    private static WaveModel.OreCells at(double x, double y, double z) {
        return new WaveModel.OreCells(new double[]{x}, new double[]{y}, new double[]{z});
    }

    private static SeismicWave.Receiver receiver(long key, double x, double y, double z, GeophoneTier tier) {
        return new SeismicWave.Receiver(key, x, y, z, tier);
    }

    @Test
    void anAndesiteGeophoneHearsMetalsAndIgnoresEverythingElse() {
        for (String metal : List.of("iron", "copper", "zinc", "gold", "nether_gold")) {
            assertTrue(GeophoneTier.ANDESITE.detects(metal), metal);
        }
        for (String other : List.of("coal", "redstone", "lapis", "diamond", "emerald", "crystal", "nether_quartz")) {
            assertTrue(!GeophoneTier.ANDESITE.detects(other), other);
        }
    }

    @Test
    void aBrassGeophoneHearsEveryOre() {
        for (String ore : List.of("iron", "coal", "diamond", "crystal", "nether_quartz", "anything")) {
            assertTrue(GeophoneTier.BRASS.detects(ore), ore);
        }
    }

    @Test
    void theOresWorthLookingForAreWhatSomeoneCanHear() {
        assertEquals(Set.of("iron", "copper", "zinc", "gold", "nether_gold"),
                GeophoneTier.oresFor(List.of(GeophoneTier.ANDESITE, GeophoneTier.ANDESITE)));
        assertNull(GeophoneTier.oresFor(List.of(GeophoneTier.ANDESITE, GeophoneTier.BRASS)), "brass hears everything");
        assertEquals(Set.of(), GeophoneTier.oresFor(List.of()), "nobody listening, nothing to look for");
    }

    @Test
    void aGeophoneOnlyLightsForOresItCanHear() {
        List<SeismicWave.Echoer> echoers = List.of(
                new SeismicWave.Echoer("iron", at(10, 0, 0)),
                new SeismicWave.Echoer("coal", at(-10, 0, 0)));
        List<SeismicWave.Pulse> andesite = SeismicWave.pulses(0, 0, 0, 30, echoers,
                List.of(receiver(1, 0, 0, 0, GeophoneTier.ANDESITE)));
        assertEquals(1, andesite.size());
        assertEquals("iron", andesite.get(0).oreId());

        List<SeismicWave.Pulse> brass = SeismicWave.pulses(0, 0, 0, 30, echoers,
                List.of(receiver(1, 0, 0, 0, GeophoneTier.BRASS)));
        assertEquals(2, brass.size());
    }

    @Test
    void aPulseStartsWhenTheEchoArrivesAndLastsAtLeastTheBaseTime() {
        // Source and geophone together, ore 32 blocks away: the echo is back after 64 blocks, 20 ticks.
        List<SeismicWave.Pulse> pulses = SeismicWave.pulses(0, 0, 0, 100,
                List.of(new SeismicWave.Echoer("iron", at(32, 0, 0))),
                List.of(receiver(7, 0, 0, 0, GeophoneTier.ANDESITE)));
        assertEquals(1, pulses.size());
        SeismicWave.Pulse pulse = pulses.get(0);
        assertEquals(7, pulse.receiver());
        assertEquals(20, pulse.startTicks());
        assertEquals(20 + SeismicWave.BASE_GLOW_TICKS, pulse.endTicks(), "a single ore block is a short, sharp echo");
    }

    @Test
    void aBigDepositMakesALongerGlowThanASmallOne() {
        WaveModel.OreCells small = at(30, 0, 0);
        double[] xs = new double[40];
        double[] ys = new double[40];
        double[] zs = new double[40];
        for (int i = 0; i < 40; i++) {
            xs[i] = 30 + i * 0.8; // a long seam, stretching away from the geophone
        }
        WaveModel.OreCells big = new WaveModel.OreCells(xs, ys, zs);
        SeismicWave.Receiver geophone = receiver(1, 0, 0, 0, GeophoneTier.ANDESITE);

        SeismicWave.Pulse smallPulse = SeismicWave.pulses(0, 0, 0, 100, List.of(new SeismicWave.Echoer("iron", small)), List.of(geophone)).get(0);
        SeismicWave.Pulse bigPulse = SeismicWave.pulses(0, 0, 0, 100, List.of(new SeismicWave.Echoer("iron", big)), List.of(geophone)).get(0);
        assertTrue(bigPulse.endTicks() - bigPulse.startTicks() > smallPulse.endTicks() - smallPulse.startTicks());
    }

    @Test
    void theExtraGlowFromAnEnormousDepositIsCapped() {
        double[] xs = {10, 5000};
        double[] zeros = {0, 0};
        // Range this large is silly, but it checks the cap.
        SeismicWave.Pulse pulse = SeismicWave.pulses(0, 0, 0, 1.0e6, List.of(new SeismicWave.Echoer("iron",
                new WaveModel.OreCells(xs, zeros, zeros))), List.of(receiver(1, 0, 0, 0, GeophoneTier.ANDESITE))).get(0);
        assertEquals(SeismicWave.BASE_GLOW_TICKS + SeismicWave.MAX_EXTRA_GLOW_TICKS, pulse.endTicks() - pulse.startTicks());
    }

    @Test
    void geophonesNearerTheOreLightFirstWhichIsWhatLetsYouFollowThemByEye() {
        // Source at the origin. Ore to the east. Geophones in a line running east: the further east, the sooner they light.
        List<SeismicWave.Receiver> line = List.of(
                receiver(1, -20, 0, 0, GeophoneTier.ANDESITE),
                receiver(2, 0, 0, 0, GeophoneTier.ANDESITE),
                receiver(3, 20, 0, 0, GeophoneTier.ANDESITE));
        List<SeismicWave.Pulse> pulses = SeismicWave.pulses(0, 0, 0, 80,
                List.of(new SeismicWave.Echoer("iron", at(40, 0, 0))), line);
        assertEquals(3, pulses.size());
        int west = pulses.stream().filter(p -> p.receiver() == 1).findFirst().orElseThrow().startTicks();
        int middle = pulses.stream().filter(p -> p.receiver() == 2).findFirst().orElseThrow().startTicks();
        int east = pulses.stream().filter(p -> p.receiver() == 3).findFirst().orElseThrow().startTicks();
        assertTrue(east < middle && middle < west, "east " + east + ", middle " + middle + ", west " + west);
    }

    @Test
    void aGeophoneBeyondTheRangeOfTheOreHearsNothing() {
        List<SeismicWave.Pulse> pulses = SeismicWave.pulses(0, 0, 0, 16,
                List.of(new SeismicWave.Echoer("iron", at(10, 0, 0))),
                List.of(receiver(1, 0, 0, 0, GeophoneTier.ANDESITE), receiver(2, 60, 0, 0, GeophoneTier.ANDESITE)));
        assertEquals(1, pulses.size());
        assertEquals(1, pulses.get(0).receiver());
    }

    @Test
    void aGeophoneHearingTwoDepositsGetsTwoPulses() {
        List<SeismicWave.Pulse> pulses = SeismicWave.pulses(0, 0, 0, 100,
                List.of(new SeismicWave.Echoer("iron", at(20, 0, 0)), new SeismicWave.Echoer("gold", at(-50, 0, 0))),
                List.of(receiver(1, 0, 0, 0, GeophoneTier.ANDESITE)));
        assertEquals(2, pulses.size());
        assertTrue(pulses.get(0).startTicks() <= pulses.get(1).startTicks(), "in order of when they start");
    }

    @Test
    void theResultIsInAFixedOrderWhateverTheInputOrder() {
        List<SeismicWave.Echoer> echoers = List.of(new SeismicWave.Echoer("iron", at(20, 0, 0)), new SeismicWave.Echoer("gold", at(-30, 0, 0)));
        List<SeismicWave.Receiver> receivers = List.of(receiver(9, 5, 0, 0, GeophoneTier.ANDESITE), receiver(3, -5, 0, 0, GeophoneTier.ANDESITE));
        List<SeismicWave.Pulse> forward = SeismicWave.pulses(0, 0, 0, 100, echoers, receivers);
        List<SeismicWave.Pulse> reversed = SeismicWave.pulses(0, 0, 0, 100, List.of(echoers.get(1), echoers.get(0)), List.of(receivers.get(1), receivers.get(0)));
        assertEquals(forward, reversed);
        assertEquals(3, forward.get(0).receiver(), "sorted by receiver first");
    }

    @Test
    void noGeophonesOrNoDepositsMeansNoPulses() {
        assertTrue(SeismicWave.pulses(0, 0, 0, 100, List.of(), List.of(receiver(1, 0, 0, 0, GeophoneTier.ANDESITE))).isEmpty());
        assertTrue(SeismicWave.pulses(0, 0, 0, 100, List.of(new SeismicWave.Echoer("iron", at(1, 0, 0))), List.of()).isEmpty());
    }

    /** However big the deposit, a blink is short: half a second to a second and a half in all. */
    @Test
    void everyPulseIsShort() {
        assertTrue(SeismicWave.BASE_GLOW_TICKS <= 10, "a lone echo is half a second at most");
        assertTrue(SeismicWave.BASE_GLOW_TICKS + SeismicWave.MAX_EXTRA_GLOW_TICKS <= 30, "the longest blink is a second and a half at most");
    }

    @Test
    void aDrawnOutEchoAddsOnlyAFractionOfItsSpreadToTheGlow() {
        // Source and geophone together. Ore at 10 blocks and at 74: the echo returns after 20 and after 148 blocks, 6.25 and
        // 46.25 ticks, so it is drawn out over exactly 40 ticks.
        double[] xs = {10, 74};
        double[] zeros = {0, 0};
        SeismicWave.Pulse pulse = SeismicWave.pulses(0, 0, 0, 100,
                List.of(new SeismicWave.Echoer("iron", new WaveModel.OreCells(xs, zeros, zeros))),
                List.of(receiver(1, 0, 0, 0, GeophoneTier.ANDESITE))).get(0);
        int extra = (int) Math.round(40 * SeismicWave.EXTRA_GLOW_FRACTION);
        assertEquals(SeismicWave.BASE_GLOW_TICKS + extra, pulse.endTicks() - pulse.startTicks());
        assertTrue(pulse.endTicks() - pulse.startTicks() < 40, "far less than the 40 ticks the echo is drawn out over");
    }

    /**
     * The reason pulses are short. Two deposits whose echoes reach a geophone fifteen ticks (three quarters of a second)
     * apart used to run into each other, showing as one long glow. They must now be two separate blinks.
     */
    @Test
    void twoDepositsAFewTenthsOfASecondApartAreTwoSeparateBlinks() {
        // Source and geophone together. Ore at 20 blocks: back after 40 blocks, 12.5 ticks. Ore at 44: back after 88, 27.5 ticks.
        List<SeismicWave.Pulse> pulses = SeismicWave.pulses(0, 0, 0, 100,
                List.of(new SeismicWave.Echoer("iron", at(20, 0, 0)), new SeismicWave.Echoer("gold", at(44, 0, 0))),
                List.of(receiver(1, 0, 0, 0, GeophoneTier.ANDESITE)));
        assertEquals(2, pulses.size());
        SeismicWave.Pulse first = pulses.get(0);
        SeismicWave.Pulse second = pulses.get(1);
        assertTrue(second.startTicks() - first.startTicks() >= 14, "the echoes really are about fifteen ticks apart");
        assertTrue(first.endTicks() < second.startTicks(),
                "the first blink ends at " + first.endTicks() + ", before the second begins at " + second.startTicks());
    }
}
