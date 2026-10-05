package dev.brights0ng.enginesandempires.frontier.upkeep;

/**
 * When a torch goes out. Outside Settled land a torch burns for a day after it was lit, then goes out. In Settled land it is
 * looked after: its clock keeps being reset, so it never goes out there, and its day only starts once the land stops being
 * Settled.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class TorchClock {

    /** In Settled land a torch's lit time is only brought up to date this often, so its chunk is not re-saved every sweep. */
    public static final long TEND_EVERY = 1200;

    public enum Action {
        /** Nothing to do. */
        KEEP,
        /** It is in Settled land and has not been looked after for a while: set its lit time to now. */
        TEND,
        /** It has burnt for its full time outside Settled land: put it out. */
        BURN_OUT
    }

    public static Action decide(long litAt, long now, boolean settled, long burnTicks) {
        long burnt = now - litAt;
        if (settled) {
            return burnt >= TEND_EVERY ? Action.TEND : Action.KEEP;
        }
        return burnt >= burnTicks ? Action.BURN_OUT : Action.KEEP;
    }

    private TorchClock() {
    }
}
