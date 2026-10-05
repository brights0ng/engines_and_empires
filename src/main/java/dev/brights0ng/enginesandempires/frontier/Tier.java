package dev.brights0ng.enginesandempires.frontier;

/**
 * How civilised a place is, from the wild to the protected. Ordered, so a tier can be capped with {@link #atMost}.
 *
 * <p>Nothing here touches Minecraft.
 */
public enum Tier {
    /** The wilds: far from anyone, or deep underground. The most dangerous. */
    FRONTIER,
    /** Nobody lives here, but people live near enough that nature knows them. Vanilla rules. */
    UNINHABITED,
    /** Somewhere people live: a bed, lantern or workstation, and enough time spent there. */
    SETTLED,
    /** Settled and guarded. The safest. */
    CIVILIZED;

    private static final Tier[] VALUES = values();

    /** This tier, or {@code cap} if that is lower. */
    public Tier atMost(Tier cap) {
        return ordinal() > cap.ordinal() ? cap : this;
    }

    public boolean atLeast(Tier other) {
        return ordinal() >= other.ordinal();
    }

    public static Tier byId(int id) {
        return VALUES[id];
    }
}
