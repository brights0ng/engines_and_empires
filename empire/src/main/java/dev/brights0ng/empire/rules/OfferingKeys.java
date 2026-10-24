// Written by Claude.
package dev.brights0ng.empire.rules;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything any god could take at a bonfire, by id: the item ids, item tag ids and fluid ids of every option of every
 * tier. The client gets this from the server (it knows neither the gods nor any empire's demand) so that it only claims a
 * right click on a lit bonfire for an item that might really be an offering, and leaves every other item to do what it
 * would do.
 */
public record OfferingKeys(Set<String> items, Set<String> tags, Set<String> fluids) {

    public static final OfferingKeys EMPTY = new OfferingKeys(Set.of(), Set.of(), Set.of());

    public OfferingKeys {
        items = Set.copyOf(items);
        tags = Set.copyOf(tags);
        fluids = Set.copyOf(fluids);
    }

    /**
     * @param optionLists every gods' options, any grouping (a god's tiers, all gods' tiers together)
     * @param anyAcceptsRottingMeat whether some god takes any raw meat once it is rotting, so {@code rawMeatTag} counts too
     */
    public static OfferingKeys of(Collection<List<DemandOption>> optionLists, boolean anyAcceptsRottingMeat, String rawMeatTag) {
        Set<String> items = new TreeSet<>();
        Set<String> tags = new TreeSet<>();
        Set<String> fluids = new TreeSet<>();
        for (List<DemandOption> options : optionLists) {
            for (DemandOption option : options) {
                switch (option.kind()) {
                    case ITEM, ITEMS -> items.addAll(option.ids());
                    case TAG -> tags.addAll(option.ids());
                    case FLUID -> fluids.addAll(option.ids());
                }
            }
        }
        if (anyAcceptsRottingMeat) {
            tags.add(rawMeatTag);
        }
        return new OfferingKeys(items, tags, fluids);
    }
}
