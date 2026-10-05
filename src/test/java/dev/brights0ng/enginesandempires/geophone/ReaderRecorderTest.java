package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.geophone.ReaderRecorder.Recording;

class ReaderRecorderTest {

    /** The reader stands at the origin. */
    private static final ReaderRecorder.Reader READER = new ReaderRecorder.Reader(1, 0, 0, 0);

    /** The worst case the tests' vibrations allow: a struck plate's, 16 blocks. */
    private static final double MAX_ERROR = ReaderAccuracy.maxErrorFor(SeismicShots.PLATE_RANGE);

    private static Recording choose(ReaderRecorder.Reader reader, List<SeismicWave.Echoer> echoers,
                                    List<SeismicWave.Receiver> receivers, List<SeismicWave.Pulse> pulses) {
        return ReaderRecorder.choose(reader, echoers, receivers, pulses, MAX_ERROR);
    }

    private static SeismicWave.Receiver geophone(long key, double x, double y, double z) {
        return new SeismicWave.Receiver(key, x, y, z, GeophoneTier.ANDESITE);
    }

    /** A deposit that is a single ore block at a spot, as block-centre coordinates. */
    private static SeismicWave.Echoer deposit(long id, double x, double y, double z) {
        return new SeismicWave.Echoer(id, "iron", new WaveModel.OreCells(new double[]{x}, new double[]{y}, new double[]{z}));
    }

    private static SeismicWave.Pulse heard(long geophone, long deposit, int start) {
        return new SeismicWave.Pulse(geophone, "iron", start, start + 10, deposit);
    }

    /** Geophones 1 to n in a line out from the reader, the furthest (the sixth) nine blocks away: all within its range. */
    private static List<SeismicWave.Receiver> nearby(int count) {
        List<SeismicWave.Receiver> geophones = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            geophones.add(geophone(i, 1.5 * i, 0, 0));
        }
        return geophones;
    }

    @Test
    void threeGeophonesHearingADepositGiveItsPositionButNotItsHeight() {
        List<SeismicWave.Pulse> pulses = List.of(heard(1, 7, 20), heard(2, 7, 25), heard(3, 7, 31));
        Recording recording = choose(READER, List.of(deposit(7, 30.5, -12.5, 8.5)), nearby(3), pulses);
        assertNotNull(recording);
        // The position itself is blurred by the array's precision; see ReaderAccuracyTest and the dedicated tests below for that.
        assertFalse(recording.hasHeight(), "three geophones fix it across the ground and no more");
        assertEquals(7, recording.deposit());
    }

    @Test
    void aFourthGeophoneGivesTheHeight() {
        List<SeismicWave.Pulse> pulses = List.of(heard(1, 7, 20), heard(2, 7, 25), heard(3, 7, 31), heard(4, 7, 40));
        Recording recording = choose(READER, List.of(deposit(7, 30.5, -12.5, 8.5)), nearby(4), pulses);
        assertNotNull(recording);
        assertTrue(recording.hasHeight());
    }

    @Test
    void twoGeophonesAreNotEnough() {
        List<SeismicWave.Pulse> pulses = List.of(heard(1, 7, 20), heard(2, 7, 25));
        assertNull(choose(READER, List.of(deposit(7, 30.5, 0.5, 8.5)), nearby(2), pulses));
    }

    @Test
    void aReaderIsReadyWhenTheLastGeophoneItUsedHasLit() {
        // The geophones light in the order 30, 20, 25: the reader has all three once the one at 30 has lit.
        List<SeismicWave.Pulse> pulses = List.of(heard(1, 7, 30), heard(2, 7, 20), heard(3, 7, 25));
        assertEquals(30, choose(READER, List.of(deposit(7, 30.5, 0.5, 8.5)), nearby(3), pulses).readyTicks());
    }

    @Test
    void itUsesOnlyTheFirstFourGeophonesToHearItSoItIsNotHeldUpByLateOnes() {
        List<SeismicWave.Pulse> pulses = List.of(heard(1, 7, 10), heard(2, 7, 20), heard(3, 7, 30), heard(4, 7, 40),
                heard(5, 7, 90), heard(6, 7, 100));
        Recording recording = choose(READER, List.of(deposit(7, 30.5, 0.5, 8.5)), nearby(6), pulses);
        assertNotNull(recording);
        assertEquals(40, recording.readyTicks(), "the fourth to hear it, not the sixth");
        assertTrue(recording.hasHeight());
    }

    @Test
    void onlyGeophonesWithinTwelveBlocksOfTheReaderCount() {
        // Three geophones heard it, but one of them is thirteen blocks away.
        List<SeismicWave.Receiver> geophones = List.of(geophone(1, 5, 0, 0), geophone(2, 0, 0, 6), geophone(3, 13, 0, 0));
        List<SeismicWave.Pulse> pulses = List.of(heard(1, 7, 20), heard(2, 7, 25), heard(3, 7, 31));
        assertNull(choose(READER, List.of(deposit(7, 30.5, 0.5, 8.5)), geophones, pulses));

        // Move it in to exactly twelve blocks and it counts.
        List<SeismicWave.Receiver> closer = List.of(geophone(1, 5, 0, 0), geophone(2, 0, 0, 6), geophone(3, 12, 0, 0));
        assertNotNull(choose(READER, List.of(deposit(7, 30.5, 0.5, 8.5)), closer, pulses));
    }

    @Test
    void radiusIsMeasuredInThreeDimensions() {
        List<SeismicWave.Receiver> geophones = List.of(geophone(1, 5, 0, 0), geophone(2, 0, 0, 6), geophone(3, 0, 13, 0));
        List<SeismicWave.Pulse> pulses = List.of(heard(1, 7, 20), heard(2, 7, 25), heard(3, 7, 31));
        assertNull(choose(READER, List.of(deposit(7, 30.5, 0.5, 8.5)), geophones, pulses),
                "a geophone thirteen blocks above is too far, whatever its x and z");
    }

    @Test
    void aFourthGeophoneOutOfRangeDoesNotGiveTheHeight() {
        List<SeismicWave.Receiver> geophones = new ArrayList<>(nearby(3));
        geophones.add(geophone(4, 50, 0, 0));
        List<SeismicWave.Pulse> pulses = List.of(heard(1, 7, 20), heard(2, 7, 25), heard(3, 7, 31), heard(4, 7, 35));
        Recording recording = choose(READER, List.of(deposit(7, 30.5, 0.5, 8.5)), geophones, pulses);
        assertNotNull(recording);
        assertFalse(recording.hasHeight());
    }

    /** The point of the design: a busy vibration must not confuse it. */
    @Test
    void withSeveralDepositsItRecordsTheOneNearestTheReader() {
        // Three deposits, each heard by all four geophones. The nearest to the reader is not the first to be heard, nor the
        // one heard by the most geophones.
        List<SeismicWave.Echoer> echoers = List.of(
                deposit(1, 60.5, 0.5, 0.5),   // 60 away, heard first
                deposit(2, 0.5, 0.5, 20.5),   // 20 away
                deposit(3, -40.5, 0.5, 0.5)); // 40 away
        List<SeismicWave.Pulse> pulses = new ArrayList<>();
        for (int geophone = 1; geophone <= 4; geophone++) {
            pulses.add(heard(geophone, 1, 10 + geophone));
            pulses.add(heard(geophone, 2, 50 + geophone));
            pulses.add(heard(geophone, 3, 30 + geophone));
        }
        Recording recording = choose(READER, echoers, nearby(4), pulses);
        assertNotNull(recording);
        assertEquals(2, recording.deposit());
    }

    @Test
    void aNearerDepositThatTooFewGeophonesHeardIsNotRecorded() {
        List<SeismicWave.Echoer> echoers = List.of(deposit(1, 5.5, 0.5, 0.5), deposit(2, 60.5, 0.5, 0.5));
        List<SeismicWave.Pulse> pulses = List.of(
                heard(1, 1, 10), heard(2, 1, 15),                 // the near deposit: only two geophones heard it
                heard(1, 2, 20), heard(2, 2, 25), heard(3, 2, 31)); // the far one: three did
        Recording recording = choose(READER, echoers, nearby(3), pulses);
        assertNotNull(recording);
        assertEquals(2, recording.deposit(), "it can only record what it has heard enough to place");
    }

    @Test
    void geophonesFarFromTheReaderDoNotSwayWhichDepositItPicks() {
        // A deposit close to the reader, but heard only by geophones a long way off. Another, far away, heard by nearby ones.
        List<SeismicWave.Receiver> geophones = new ArrayList<>(nearby(3));
        geophones.add(geophone(10, 80, 0, 0));
        geophones.add(geophone(11, 81, 0, 3));
        geophones.add(geophone(12, 82, 0, -3));
        List<SeismicWave.Echoer> echoers = List.of(deposit(1, 6.5, 0.5, 0.5), deposit(2, 60.5, 0.5, 0.5));
        List<SeismicWave.Pulse> pulses = List.of(heard(10, 1, 5), heard(11, 1, 6), heard(12, 1, 7),
                heard(1, 2, 20), heard(2, 2, 25), heard(3, 2, 31));
        Recording recording = choose(READER, echoers, geophones, pulses);
        assertNotNull(recording);
        assertEquals(2, recording.deposit());
    }

    @Test
    void theRecordedPositionStaysWithinTheArraysOwnErrorRadiusOfTheTruth() {
        // One deposit of several ore blocks; the reader is at the origin.
        SeismicWave.Echoer seam = new SeismicWave.Echoer(5, "iron", new WaveModel.OreCells(
                new double[]{40.5, 12.5, 25.5, -30.5}, new double[]{-8.5, -3.5, 9.5, 0.5}, new double[]{6.5, 4.5, -2.5, 3.5}));
        List<SeismicWave.Pulse> pulses = List.of(heard(1, 5, 20), heard(2, 5, 25), heard(3, 5, 31));
        Recording recording = choose(READER, List.of(seam), nearby(3), pulses);
        assertNotNull(recording);

        // The true nearest ore block is (12, -4, 4). Work out, from the same array choose() used, how far off it is allowed
        // to have put the reading: nearby(3) is three geophones on a line at (1.5, 0, 0), (3, 0, 0), (4.5, 0, 0).
        double spread = ReaderAccuracy.spreadOf(new double[]{1.5, 3.0, 4.5}, new double[]{0.0, 0.0, 0.0});
        double radius = ReaderAccuracy.errorRadius(spread, MAX_ERROR);

        double dx = recording.x() - 12.0;
        double dy = recording.y() - (-4.0);
        double dz = recording.z() - 4.0;
        double actual = Math.sqrt(dx * dx + dy * dy + dz * dz);
        assertTrue(actual <= radius * Math.sqrt(1 + ReaderAccuracy.VERTICAL_FACTOR * ReaderAccuracy.VERTICAL_FACTOR),
                "the reading landed " + actual + " blocks off the truth, more than its own array (radius " + radius + ") should allow");
    }

    @Test
    void callingChooseTwiceWithIdenticalInputsGivesTheExactSameBlurredReading() {
        List<SeismicWave.Pulse> pulses = List.of(heard(1, 7, 20), heard(2, 7, 25), heard(3, 7, 31));
        List<SeismicWave.Echoer> echoers = List.of(deposit(7, 30.5, -12.5, 8.5));
        List<SeismicWave.Receiver> geophones = nearby(3);
        Recording first = choose(READER, echoers, geophones, pulses);
        Recording second = choose(READER, echoers, geophones, pulses);
        assertEquals(first, second, "the same array must blur the same reading the same way every time, not roll fresh each time");
    }

    @Test
    void aWiderArrayIsNeverLessConfidentThanATightClusterForTheSameDeposit() {
        List<SeismicWave.Echoer> echoers = List.of(deposit(7, 100.5, 0.5, 0.5));
        List<SeismicWave.Pulse> pulses = List.of(heard(1, 7, 20), heard(2, 7, 25), heard(3, 7, 31));

        List<SeismicWave.Receiver> tight = List.of(geophone(1, 1.0, 0, 0), geophone(2, 1.1, 0, 0), geophone(3, 0.9, 0, 0));
        List<SeismicWave.Receiver> wide = List.of(geophone(1, 8, 0, 8), geophone(2, -8, 0, 8), geophone(3, 0, 0, -11));

        Recording tightRecording = choose(READER, echoers, tight, pulses);
        Recording wideRecording = choose(READER, echoers, wide, pulses);
        assertNotNull(tightRecording);
        assertNotNull(wideRecording);
        assertTrue(wideRecording.confidence().ordinal() >= tightRecording.confidence().ordinal(),
                "a spread-out array (" + wideRecording.confidence() + ") should never be less confident than a clustered one (" + tightRecording.confidence() + ")");
    }

    @Test
    void oneGeophoneHearingADepositTwiceIsStillOneGeophone() {
        List<SeismicWave.Pulse> pulses = List.of(heard(1, 7, 20), heard(1, 7, 24), heard(2, 7, 25));
        assertNull(choose(READER, List.of(deposit(7, 30.5, 0.5, 8.5)), nearby(2), pulses));
    }

    @Test
    void whenTwoDepositsAreExactlyAsNearTheLowerIdWinsSoItIsAlwaysTheSame() {
        List<SeismicWave.Echoer> echoers = List.of(deposit(9, 0.5, 0.5, 20.5), deposit(4, 20.5, 0.5, 0.5));
        List<SeismicWave.Pulse> pulses = new ArrayList<>();
        for (int geophone = 1; geophone <= 3; geophone++) {
            pulses.add(heard(geophone, 9, 10 + geophone));
            pulses.add(heard(geophone, 4, 20 + geophone));
        }
        assertEquals(4, choose(READER, echoers, nearby(3), pulses).deposit());
        assertEquals(4, choose(READER, List.of(echoers.get(1), echoers.get(0)), nearby(3), pulses).deposit());
    }

    @Test
    void nothingHeardMeansNothingRecorded() {
        assertNull(choose(READER, List.of(deposit(7, 30.5, 0.5, 8.5)), nearby(3), List.of()));
        assertNull(choose(READER, List.of(), nearby(3), List.of(heard(1, 7, 20), heard(2, 7, 25), heard(3, 7, 31))));
        assertNull(choose(READER, List.of(deposit(7, 30.5, 0.5, 8.5)), List.of(), List.of(heard(1, 7, 20))));
    }

    @Test
    void theRulesAreTheOnesTheDesignSays() {
        assertEquals(12.0, ReaderRecorder.LISTEN_RADIUS, 0.0);
        assertEquals(3, ReaderRecorder.MIN_GEOPHONES);
        assertEquals(4, ReaderRecorder.MAX_GEOPHONES);
    }

    /** However far away the deposit and however tight the array, a reading is never off by more than its shot allows. */
    @Test
    void aReadingIsNeverFurtherOffThanItsShotsWorstCase() {
        double maxError = ReaderAccuracy.maxErrorFor(SeismicShots.MECHANICAL_RANGE);
        List<SeismicWave.Receiver> tight = List.of(geophone(1, 1.0, 0, 0), geophone(2, 1.1, 0, 0), geophone(3, 0.9, 0, 0));
        for (long id = 1; id <= 300; id++) {
            double x = 500.5 + id;
            List<SeismicWave.Pulse> pulses = List.of(heard(1, id, 20), heard(2, id, 25), heard(3, id, 31));
            Recording recording = ReaderRecorder.choose(READER, List.of(deposit(id, x, 0.5, 0.5)), tight, pulses, maxError);
            double dx = recording.x() + 0.5 - x;
            double dz = recording.z();
            assertTrue(Math.sqrt(dx * dx + dz * dz) <= maxError,
                    "deposit " + id + " was recorded " + Math.sqrt(dx * dx + dz * dz) + " blocks across from the truth");
        }
    }
}
