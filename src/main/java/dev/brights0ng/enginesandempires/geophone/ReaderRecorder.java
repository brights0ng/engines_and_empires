package dev.brights0ng.enginesandempires.geophone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides what a wind-up reader records when a vibration is made.
 *
 * <p>A reader listens through the geophones around it. It does not work anything out from them: it does not use when they
 * light, or how far away the deposit is from each. The geophones simply tell it which deposit they heard, and it is told
 * where that deposit is. That is the shortcut that makes it simple to use: it needs the geophones to hear the deposit,
 * and nothing else about them, in order to fix on which deposit it is at all.
 *
 * <p>Once it has settled on a deposit, though, the position it actually records is blurred by how good the listening array
 * was and by what made the vibration: see {@link ReaderAccuracy}. A tight cluster of geophones gives the shot's worst-case
 * fix; a wide one a better one.
 *
 * <p>The rules:
 * <ul>
 *   <li>Only geophones within {@link #LISTEN_RADIUS} of the reader count.</li>
 *   <li>A deposit is only recorded if at least {@link #MIN_GEOPHONES} of them heard it. That is enough to know where it is
 *       across the ground. It reads at most {@link #MAX_GEOPHONES}: a fourth gives it the height as well, and more add nothing.</li>
 *   <li>If several deposits qualify, it records the one nearest the reader, so it is never confused by a busy vibration.</li>
 *   <li>The position recorded is the deposit's ore block nearest the reader, blurred by the array that heard it, see
 *       {@link ReaderAccuracy}.</li>
 *   <li>It has the reading when the last of the geophones it used has lit, not before: it cannot know what none of them has
 *       told it yet.</li>
 * </ul>
 *
 * <p>Nothing here touches Minecraft.
 */
public final class ReaderRecorder {

    /** How close a geophone must be to the reader for the reader to listen through it, in blocks. */
    public static final double LISTEN_RADIUS = 12.0;

    /** How many geophones must have heard a deposit for the reader to record it. */
    public static final int MIN_GEOPHONES = 3;

    /** How many geophones the reader uses. With this many it knows the height. */
    public static final int MAX_GEOPHONES = 4;

    /** A reader: an identifier the caller can map back to it, and where it stands. */
    public record Reader(long key, double x, double y, double z) {
    }

    /**
     * What a reader records.
     *
     * @param x          the position of the deposit's ore block nearest the reader, blurred according to {@link #confidence}
     * @param hasHeight  whether at least {@link #MAX_GEOPHONES} geophones heard it, so the height is known
     * @param readyTicks ticks after the vibration was made when the reader has heard from the last geophone it used, and so
     *                   has its reading
     * @param deposit    the id of the deposit, see {@link SeismicWave.Echoer#id()}
     * @param confidence how good the array that produced this reading was; see {@link ReaderAccuracy}
     * @param ore        which ore the deposit is, see {@link SeismicWave.Echoer#oreId()}
     */
    public record Recording(int x, int y, int z, boolean hasHeight, int readyTicks, long deposit, ReaderAccuracy.Confidence confidence,
                            String ore) {
    }

    /**
     * What one reader records, given the outcome of a vibration, or null if it hears nothing it can use.
     *
     * @param echoers   the deposits the vibration reached
     * @param receivers the geophones listening, with where they are
     * @param pulses    which of them heard which deposit, and when
     * @param maxError  the worst the vibration's source allows a reading to be off by, see {@link ReaderAccuracy#maxErrorFor}
     */
    public static Recording choose(Reader reader, List<SeismicWave.Echoer> echoers,
                                   List<SeismicWave.Receiver> receivers, List<SeismicWave.Pulse> pulses, double maxError) {
        List<Recording> all = chooseAll(reader, LISTEN_RADIUS, echoers, receivers, pulses, maxError);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Everything a listener records from one vibration: one recording for every deposit that at least {@link #MIN_GEOPHONES}
     * geophones within {@code listenRadius} of it heard, nearest first (ties broken by the deposit's id). A wind-up reader keeps
     * only the first of these ({@link #choose}); the smart logger keeps them all.
     */
    public static List<Recording> chooseAll(Reader reader, double listenRadius, List<SeismicWave.Echoer> echoers,
                                            List<SeismicWave.Receiver> receivers, List<SeismicWave.Pulse> pulses,
                                            double maxError) {
        Map<Long, SeismicWave.Receiver> geophones = new HashMap<>();
        for (SeismicWave.Receiver receiver : receivers) {
            geophones.put(receiver.key(), receiver);
        }

        // For each deposit, the geophones close enough to the reader that heard it, each once.
        Map<Long, List<SeismicWave.Pulse>> heard = new HashMap<>();
        Map<Long, Set<Long>> counted = new HashMap<>();
        for (SeismicWave.Pulse pulse : pulses) {
            SeismicWave.Receiver geophone = geophones.get(pulse.receiver());
            if (geophone == null || distance(reader.x(), reader.y(), reader.z(), geophone.x(), geophone.y(), geophone.z()) > listenRadius) {
                continue;
            }
            if (counted.computeIfAbsent(pulse.deposit(), ignored -> new HashSet<>()).add(pulse.receiver())) {
                heard.computeIfAbsent(pulse.deposit(), ignored -> new ArrayList<>()).add(pulse);
            }
        }

        record Found(Recording recording, double distance) {
        }
        List<Found> found = new ArrayList<>();
        for (SeismicWave.Echoer echoer : echoers) {
            List<SeismicWave.Pulse> pulsesForDeposit = heard.get(echoer.id());
            if (pulsesForDeposit == null || pulsesForDeposit.size() < MIN_GEOPHONES || echoer.cells().size() == 0) {
                continue;
            }

            // The reader has what it needs when the last of the geophones it uses (the first few to hear it) has lit.
            pulsesForDeposit.sort(Comparator.comparingInt(SeismicWave.Pulse::startTicks));
            int used = Math.min(pulsesForDeposit.size(), MAX_GEOPHONES);
            int ready = pulsesForDeposit.get(used - 1).startTicks();

            // Where it is: the deposit's ore block nearest the reader.
            WaveModel.OreCells cells = echoer.cells();
            int nearest = 0;
            double nearestDistance = Double.POSITIVE_INFINITY;
            for (int i = 0; i < cells.size(); i++) {
                double d = distance(reader.x(), reader.y(), reader.z(), cells.x()[i], cells.y()[i], cells.z()[i]);
                if (d < nearestDistance) {
                    nearestDistance = d;
                    nearest = i;
                }
            }

            // How good the array that produced this fix was: the geophones actually used, the same ones that decided
            // `ready` and `hasHeight` above.
            List<SeismicWave.Pulse> usedPulses = pulsesForDeposit.subList(0, used);
            double[] usedX = new double[used];
            double[] usedZ = new double[used];
            long[] usedKeys = new long[used];
            for (int i = 0; i < used; i++) {
                long key = usedPulses.get(i).receiver();
                SeismicWave.Receiver usedGeophone = geophones.get(key);
                usedX[i] = usedGeophone.x();
                usedZ[i] = usedGeophone.z();
                usedKeys[i] = key;
            }
            double spread = ReaderAccuracy.spreadOf(usedX, usedZ);
            double errorRadius = ReaderAccuracy.errorRadius(spread, maxError);
            ReaderAccuracy.Confidence confidence = ReaderAccuracy.confidenceFor(errorRadius);
            int[] jitter = ReaderAccuracy.jitterOffset(ReaderAccuracy.arraySeed(echoer.id(), usedKeys), errorRadius);

            found.add(new Found(new Recording((int) Math.floor(cells.x()[nearest] + jitter[0]),
                    (int) Math.floor(cells.y()[nearest] + jitter[1]), (int) Math.floor(cells.z()[nearest] + jitter[2]),
                    used >= MAX_GEOPHONES, ready, echoer.id(), confidence, echoer.oreId()), nearestDistance));
        }
        found.sort(Comparator.comparingDouble(Found::distance).thenComparingLong(f -> f.recording().deposit()));
        List<Recording> recordings = new ArrayList<>(found.size());
        for (Found f : found) {
            recordings.add(f.recording());
        }
        return recordings;
    }

    private static double distance(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private ReaderRecorder() {
    }
}
