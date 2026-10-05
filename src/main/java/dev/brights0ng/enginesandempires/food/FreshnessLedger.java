package dev.brights0ng.enginesandempires.food;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out where food went during an operation that moves items around (a click in a menu, a hopper push, an insert into
 * an item handler), from what each place held before and how many it holds after.
 *
 * <p>The game's own code moves counts about with no idea of freshness; this puts the freshness back. For each kind of food:
 * every place that ended up with fewer units gave up its top ones, and every place that ended up with more took them, in
 * order. Units that appeared from nowhere (a crafted result, say) are born now. Places whose count did not change are left
 * alone, so a whole stack swapped from one place to another keeps its own freshness.
 *
 * <p>Plain Java on purpose (no Minecraft types), so it can be unit tested. {@link Spoilage.Places} feeds it from item stacks.
 */
public final class FreshnessLedger {

    /**
     * One place food can be. {@code kind} says which food it is (items that stack together share one; null for anything
     * that does not spoil, or nothing), and {@code freshness} what it held (only read for the "before" side).
     */
    public record Place(Object kind, int count, FoodFreshness freshness) {

        public static final Place NOTHING = new Place(null, 0, FoodFreshness.EMPTY);
    }

    /**
     * What each place should hold after, index for index with {@code after}; null where a place is to be left as it is.
     */
    public static FoodFreshness[] settle(List<Place> before, List<Place> after, long now, SpoilTimes times) {
        return settle(before, after, List.of(), now, times);
    }

    /**
     * As {@link #settle(List, List, long, SpoilTimes)}, with food that appeared during the operation somewhere not listed
     * ({@code minted}: a trade's result slot refilled mid-click, say). Takers draw from it after what the places gave up, and
     * before anything is born now.
     */
    public static FoodFreshness[] settle(List<Place> before, List<Place> after, List<Place> minted, long now, SpoilTimes times) {
        int size = before.size();
        if (after.size() != size) {
            throw new IllegalArgumentException("before and after must list the same places");
        }
        FoodFreshness[] result = new FoodFreshness[size];
        Set<Object> kinds = new LinkedHashSet<>();
        for (int i = 0; i < size; i++) {
            if (before.get(i).kind() != null) {
                kinds.add(before.get(i).kind());
            }
            if (after.get(i).kind() != null) {
                kinds.add(after.get(i).kind());
            }
        }
        for (Object kind : kinds) {
            int[] delta = new int[size];
            List<FoodFreshness> pool = new ArrayList<>();
            // Givers first: each gives up its top units
            for (int i = 0; i < size; i++) {
                Place b = before.get(i);
                Place a = after.get(i);
                int had = kind.equals(b.kind()) ? b.count() : 0;
                int has = kind.equals(a.kind()) ? a.count() : 0;
                delta[i] = has - had;
                if (delta[i] < 0) {
                    FoodFreshness.Split split = b.freshness().takeTop(-delta[i], now, times);
                    pool.add(split.taken());
                    if (has > 0) {
                        result[i] = split.rest();
                    }
                }
            }
            for (Place extra : minted) {
                if (kind.equals(extra.kind())) {
                    pool.add(extra.freshness());
                }
            }
            // Then takers, in order, draw from what was given up
            for (int i = 0; i < size; i++) {
                if (delta[i] <= 0) {
                    continue;
                }
                Place b = before.get(i);
                FoodFreshness base = kind.equals(b.kind()) ? b.freshness() : FoodFreshness.EMPTY;
                result[i] = base.merge(draw(pool, delta[i], now, times), now, times);
            }
        }
        return result;
    }

    /** Takes {@code n} units from the pool, top first from each piece in turn; any shortfall is born now. */
    private static FoodFreshness draw(List<FoodFreshness> pool, int n, long now, SpoilTimes times) {
        FoodFreshness drawn = FoodFreshness.EMPTY;
        int need = n;
        for (int p = 0; p < pool.size() && need > 0; p++) {
            FoodFreshness piece = pool.get(p);
            int take = Math.min(need, piece.total());
            if (take <= 0) {
                continue;
            }
            FoodFreshness.Split split = piece.takeTop(take, now, times);
            pool.set(p, split.rest());
            drawn = drawn.merge(split.taken(), now, times);
            need -= take;
        }
        if (need > 0) {
            drawn = drawn.merge(FoodFreshness.born(now, need), now, times);
        }
        return drawn;
    }

    private FreshnessLedger() {
    }
}
