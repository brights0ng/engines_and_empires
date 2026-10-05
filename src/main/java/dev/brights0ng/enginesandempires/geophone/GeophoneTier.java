package dev.brights0ng.enginesandempires.geophone;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * What a kind of geophone can hear. The andesite geophone only picks up metallic deposits; the brass one hears
 * every ore.
 */
public enum GeophoneTier {

    ANDESITE(Set.of("iron", "copper", "zinc", "gold", "nether_gold")),
    BRASS(null);

    private final Set<String> ores;

    /** @param ores the ores this tier hears, or null for every ore */
    GeophoneTier(Set<String> ores) {
        this.ores = ores;
    }

    /** Whether this tier hears this ore. */
    public boolean detects(String oreId) {
        return ores == null || ores.contains(oreId);
    }

    /**
     * The ores worth looking for when geophones of these tiers are listening: everything any of them hears. Null
     * means every ore. Looking only for what someone can hear saves work.
     */
    public static Set<String> oresFor(Collection<GeophoneTier> tiers) {
        Set<String> wanted = new HashSet<>();
        for (GeophoneTier tier : tiers) {
            if (tier.ores == null) {
                return null;
            }
            wanted.addAll(tier.ores);
        }
        return Set.copyOf(wanted);
    }
}
