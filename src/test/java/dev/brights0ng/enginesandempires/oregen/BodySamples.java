package dev.brights0ng.enginesandempires.oregen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds, once, a set of real deposit bodies for every ore, so the tests that look at them share the work. */
final class BodySamples {

    static final long SEED = 20260920L;
    static final int PER_ORE = 40;

    private static final Map<String, List<DepositBody>> BODIES = new HashMap<>();

    static synchronized List<DepositBody> of(OreType type) {
        return BODIES.computeIfAbsent(type.id(), id -> {
            OreMap map = new OreMap(SEED, type.layer());
            List<Deposit> deposits = map.depositsNear(0, 0, type.layer().scale() * 9);
            List<DepositBody> bodies = new ArrayList<>();
            for (Deposit deposit : deposits) {
                bodies.add(DepositBody.generate(deposit, type));
                if (bodies.size() == PER_ORE) {
                    break;
                }
            }
            if (bodies.size() < PER_ORE) {
                throw new IllegalStateException("only found " + bodies.size() + " " + id + " deposits");
            }
            return bodies;
        });
    }

    private BodySamples() {
    }
}
