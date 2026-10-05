package dev.brights0ng.enginesandempires.geophone;

/**
 * The pure rules of what the combustive thumper does when it is fired: how far the vibration carries, how much of its
 * tank a shot burns, and whether it blows up. Nothing here touches Minecraft; {@link ThumperFluids} sorts a real fluid
 * into one of the {@link Charge}s below.
 *
 * <ul>
 *   <li><b>Premium fuel</b> (diesel): the best shot, {@link #PREMIUM_RANGE}, and the cheapest, {@link #PREMIUM_COST} mB.</li>
 *   <li><b>Fuel</b> (gasoline, biodiesel): {@link #FUEL_RANGE}, for {@link #FUEL_COST} mB.</li>
 *   <li><b>Volatile</b> (anything combustible that is not a thumper fuel: crude oil, plant oil, ethanol, and any other
 *       fluid in the common {@code c:fuel} tag): it still fires at full {@link #PREMIUM_RANGE}, since the blast still
 *       drives the head into the plate, but it also blows up. Costs {@link #VOLATILE_COST} mB.</li>
 *   <li><b>Inert</b> (water, lava, anything else): no ignition. The head just drops, a {@link #DUD_RANGE} thump. And while
 *       an inert fluid is in the tank, the head cannot be lifted again at all: see {@link #canLift}.</li>
 *   <li><b>Empty</b>: the same dud drop, but nothing stops it being lifted again.</li>
 * </ul>
 *
 * <p>A tank holding less than one shot's worth of its fluid is treated as a dud too: the head drops, nothing is burned,
 * and nothing explodes.
 */
public final class CombustiveFiring {

    /** Diesel's range: the furthest any thumper carries. */
    public static final int PREMIUM_RANGE = 1024;

    /** Gasoline and biodiesel's range. */
    public static final int FUEL_RANGE = 768;

    /** A dud: the head falling on its own weight, as heavy as a hand-struck strike plate. */
    public static final int DUD_RANGE = 128;

    public static final int PREMIUM_COST = 150;
    public static final int FUEL_COST = 250;
    public static final int VOLATILE_COST = 250;

    /** What kind of fluid is in the tank, as far as firing is concerned. */
    public enum Charge {
        PREMIUM_FUEL, FUEL, VOLATILE, INERT, EMPTY
    }

    /**
     * What one firing does.
     *
     * @param range     how far the vibration carries
     * @param burnMb    how much of the tank it uses up (0 for a dud)
     * @param explodes  whether it also blows up around the thumper
     * @param ignited   whether the charge actually went off (false for a dud)
     */
    public record Shot(int range, int burnMb, boolean explodes, boolean ignited) {
        static final Shot DUD = new Shot(DUD_RANGE, 0, false, false);
    }

    /** What firing with this much of this kind of fluid does. */
    public static Shot fire(Charge charge, int amountMb) {
        return switch (charge) {
            case PREMIUM_FUEL -> amountMb >= PREMIUM_COST ? new Shot(PREMIUM_RANGE, PREMIUM_COST, false, true) : Shot.DUD;
            case FUEL -> amountMb >= FUEL_COST ? new Shot(FUEL_RANGE, FUEL_COST, false, true) : Shot.DUD;
            case VOLATILE -> amountMb >= VOLATILE_COST ? new Shot(PREMIUM_RANGE, VOLATILE_COST, true, true) : Shot.DUD;
            case INERT, EMPTY -> Shot.DUD;
        };
    }

    /** Whether the head may be lifted with this in the tank. An inert fluid jams it: it has to be drained first. */
    public static boolean canLift(Charge charge) {
        return charge != Charge.INERT;
    }

    private CombustiveFiring() {
    }
}
