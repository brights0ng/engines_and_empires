// Written by Claude.
package dev.brights0ng.empire.rules;

import java.util.List;

/**
 * One line of a god's current demand: what it wants ({@code kind} and {@code ids}), how much ({@code required}) and how
 * much has been brought so far ({@code paid}). Item, items and tag lines count items; fluid lines count millibuckets.
 *
 * @param ids one item id (ITEM), any of several item ids (ITEMS), one item tag id (TAG) or one fluid id (FLUID)
 */
public record DemandLine(Kind kind, List<String> ids, int required, int paid) {

    public enum Kind {
        ITEM,
        ITEMS,
        TAG,
        FLUID
    }

    /** Millibuckets in one unit of a fluid demand. */
    public static final int MB_PER_UNIT = 1000;

    public DemandLine {
        ids = List.copyOf(ids);
        required = Math.max(0, required);
        paid = Math.max(0, Math.min(paid, required));
    }

    public int remaining() {
        return required - paid;
    }

    public boolean isPaid() {
        return paid >= required;
    }

    public DemandLine withPaid(int newPaid) {
        return new DemandLine(kind, ids, required, newPaid);
    }

    /** Whether every line is paid (an empty demand counts as paid). */
    public static boolean allPaid(List<DemandLine> lines) {
        for (DemandLine line : lines) {
            if (!line.isPaid()) {
                return false;
            }
        }
        return true;
    }
}
