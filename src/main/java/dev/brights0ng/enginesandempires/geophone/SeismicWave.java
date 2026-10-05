package dev.brights0ng.enginesandempires.geophone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Works out which geophones light up when a vibration is made, and when. Given the vibration, the deposits it
 * reaches, and the geophones listening, it produces a pulse for each geophone that hears each deposit.
 *
 * <p>A pulse starts when the first of the deposit's echo arrives, and lasts a short base time, plus a fraction of however
 * long the echo itself is drawn out, up to a limit. So a big deposit makes a slightly longer blink than a small one. All
 * the times are in ticks after the vibration was made.
 *
 * <p>Pulses are kept short on purpose. A geophone that hears several deposits lights once for each, and a long glow would
 * run the pulses together until they could no longer be told apart: deposits whose echoes arrive a second or so apart
 * should show as separate blinks.
 *
 * <p>Nothing here touches Minecraft, so it can run on any thread.
 */
public final class SeismicWave {

    /** How long a geophone glows for the shortest echo: half a second, a clear blink and no more. */
    public static final int BASE_GLOW_TICKS = 4;

    /** The share of an echo's spread that is added to the glow. A drawn-out echo makes a longer blink, but only a little longer. */
    public static final double EXTRA_GLOW_FRACTION = 0.25;

    /** The most a drawn-out echo adds to that. */
    public static final int MAX_EXTRA_GLOW_TICKS = 10;

    /**
     * A deposit, as the wave sees it.
     *
     * @param id a number that tells this deposit from every other, such as its seed. Pulses carry it, so that something that
     *           hears several deposits of the same ore can tell them apart
     */
    public record Echoer(long id, String oreId, WaveModel.OreCells cells) {

        /** A deposit that nothing needs to tell apart from others. */
        public Echoer(String oreId, WaveModel.OreCells cells) {
            this(0L, oreId, cells);
        }
    }

    /**
     * Something that listens.
     *
     * @param key an identifier the caller can map back to the geophone, such as its packed block position
     */
    public record Receiver(long key, double x, double y, double z, GeophoneTier tier) {
    }

    /**
     * A geophone lighting up.
     *
     * @param receiver   the receiver's key
     * @param startTicks ticks after the vibration when the glow begins
     * @param endTicks   ticks after the vibration when it ends
     * @param deposit    the {@link Echoer#id() id} of the deposit whose echo this is
     */
    public record Pulse(long receiver, String oreId, int startTicks, int endTicks, long deposit) {
    }

    /**
     * Every pulse caused by a vibration made at the given place, reaching as far as {@code range}.
     *
     * @return the pulses, in a fixed order: by receiver, then start time
     */
    public static List<Pulse> pulses(double sourceX, double sourceY, double sourceZ, double range,
                                     List<Echoer> echoers, List<Receiver> receivers) {
        List<Pulse> pulses = new ArrayList<>();
        for (Echoer echoer : echoers) {
            for (Receiver receiver : receivers) {
                if (!receiver.tier().detects(echoer.oreId())) {
                    continue;
                }
                WaveModel.Arrival arrival = WaveModel.arrival(echoer.cells(), sourceX, sourceY, sourceZ,
                        receiver.x(), receiver.y(), receiver.z(), range);
                if (arrival == null) {
                    continue;
                }
                int start = (int) Math.round(arrival.earliestTicks());
                int extra = (int) Math.min(MAX_EXTRA_GLOW_TICKS, Math.round(arrival.spreadTicks() * EXTRA_GLOW_FRACTION));
                pulses.add(new Pulse(receiver.key(), echoer.oreId(), start, start + BASE_GLOW_TICKS + extra, echoer.id()));
            }
        }
        pulses.sort(Comparator.comparingLong(Pulse::receiver).thenComparingInt(Pulse::startTicks)
                .thenComparing(Pulse::oreId).thenComparingLong(Pulse::deposit));
        return pulses;
    }

    private SeismicWave() {
    }
}
