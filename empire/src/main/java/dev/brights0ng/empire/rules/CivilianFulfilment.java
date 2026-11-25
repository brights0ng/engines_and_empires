// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable per-civilian fulfilment counters, rolling 3-day windows, comfort day samples, preferences, and grievance
 * ({@code docs/empire/civilian-fulfilment.md}). Shares {@link CivilianFood}'s active-day clock via
 * {@link #advanceDay()}; no Minecraft imports.
 */
public final class CivilianFulfilment {

    /** Update cadence: recompute meters, sample comfort, and advance grievance every schedule slot (1,200 ticks). */
    public static final long SLOT_TICKS = 1_200L;
    /** Proximity scan cadence for Community (100 ticks = 5 seconds). */
    public static final int COMMUNITY_SCAN_TICKS = 100;
    /** Horizontal/Euclidean scan radius for Community. */
    public static final double COMMUNITY_RADIUS = 16.0;
    /** Minimum active ticks between counted social interactions with the same partner (1 in-game hour). */
    public static final long SOCIAL_PAIR_COOLDOWN_TICKS = 1_200L;
    /** Rolling window length in active days (today, yesterday, day before). */
    public static final int WINDOW_DAYS = 3;
    /** Maximum comfort samples retained (20 slots = 1 active day). */
    public static final int COMFORT_DAY_SLOTS = 20;

    private CivilianPreferences preferences;
    /** Active ticks lived as a citizen of the current empire (drives Commitment + newcomer decay). */
    private long citizenActiveTicks;
    /** Distinct nearby people seen per active day: index 0 = today, 1 = yesterday, 2 = day before. */
    @SuppressWarnings("unchecked")
    private final Set<UUID>[] communityDays = new Set[] {new HashSet<>(), new HashSet<>(), new HashSet<>()};
    /** Social interactions counted per active day: index 0 = today, 1 = yesterday, 2 = day before. */
    private final int[] socializingDays = new int[WINDOW_DAYS];
    /** Active-tick timestamp of the last counted social interaction per partner UUID (prevents spam). */
    private final Map<UUID, Long> lastSocialTickByPartner = new HashMap<>();
    /** Liked meals eaten per active day: index 0 = today, 1 = yesterday, 2 = day before. */
    private final int[] likedMealsDays = new int[WINDOW_DAYS];
    /** Active ticks spent performing liked hobbies per active day: index 0 = today, 1 = yesterday, 2 = day before. */
    private final long[] likedHobbyTicksDays = new long[WINDOW_DAYS];
    /** Recent per-slot comfort scores (0–1), capped at {@link #COMFORT_DAY_SLOTS}. */
    private final List<Float> comfortSamples = new ArrayList<>(COMFORT_DAY_SLOTS);
    /** Accumulated grievance (0–1). */
    private float grievance;
    /** Active ticks elapsed since the last slot evaluation (for grievance step delta). */
    private long ticksSinceLastSlot;

    public CivilianFulfilment(CivilianPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences);
    }

    public CivilianPreferences preferences() {
        return preferences;
    }

    public void setPreferences(CivilianPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences);
    }

    /** Increments active settlement age by one tick while the civilian is a citizen. */
    public void countCitizenActiveTick() {
        citizenActiveTicks++;
        ticksSinceLastSlot++;
    }

    public long citizenActiveTicks() {
        return citizenActiveTicks;
    }

    public void setCitizenActiveTicks(long ticks) {
        this.citizenActiveTicks = Math.max(0L, ticks);
    }

    public double daysLived() {
        return citizenActiveTicks / (double) CivilianFood.DAY_LENGTH;
    }

    /** Resets settlement tenure and grievance when joining a new empire (keeps preferences). */
    public void resetForNewEmpire() {
        citizenActiveTicks = 0L;
        grievance = 0.0F;
        ticksSinceLastSlot = 0L;
    }

    /**
     * Rolls all 3-day windows forward by one active day (called when {@link CivilianFood#countActiveTick()} crosses
     * {@link CivilianFood#DAY_LENGTH}).
     */
    public void advanceDay() {
        communityDays[2] = communityDays[1];
        communityDays[1] = communityDays[0];
        communityDays[0] = new HashSet<>();

        socializingDays[2] = socializingDays[1];
        socializingDays[1] = socializingDays[0];
        socializingDays[0] = 0;

        likedMealsDays[2] = likedMealsDays[1];
        likedMealsDays[1] = likedMealsDays[0];
        likedMealsDays[0] = 0;

        likedHobbyTicksDays[2] = likedHobbyTicksDays[1];
        likedHobbyTicksDays[1] = likedHobbyTicksDays[0];
        likedHobbyTicksDays[0] = 0L;
    }

    /** Records a nearby person (other civilian or empire member) seen within {@link #COMMUNITY_RADIUS} blocks today. */
    public boolean recordNearbyPerson(UUID personId) {
        return personId != null && communityDays[0].add(personId);
    }

    /** Distinct people seen across today, yesterday, and the day before. */
    public int distinctCommunityPeople() {
        Set<UUID> union = new HashSet<>(communityDays[0]);
        union.addAll(communityDays[1]);
        union.addAll(communityDays[2]);
        return union.size();
    }

    /**
     * Counts a social interaction with {@code partnerId} if at least {@link #SOCIAL_PAIR_COOLDOWN_TICKS} active ticks
     * have passed since the last counted interaction with that partner.
     */
    public boolean recordSocialInteraction(UUID partnerId, long currentActiveTicks) {
        if (partnerId == null) {
            return false;
        }
        Long last = lastSocialTickByPartner.get(partnerId);
        if (last != null && currentActiveTicks >= last && currentActiveTicks - last < SOCIAL_PAIR_COOLDOWN_TICKS) {
            return false;
        }
        lastSocialTickByPartner.put(partnerId, currentActiveTicks);
        socializingDays[0]++;
        pruneExpiredSocialCooldowns(currentActiveTicks);
        return true;
    }

    private void pruneExpiredSocialCooldowns(long currentActiveTicks) {
        if (lastSocialTickByPartner.size() <= 16) {
            return;
        }
        lastSocialTickByPartner.entrySet().removeIf(e ->
                currentActiveTicks < e.getValue() || currentActiveTicks - e.getValue() >= SOCIAL_PAIR_COOLDOWN_TICKS);
    }

    public int socializingCount() {
        return socializingDays[0] + socializingDays[1] + socializingDays[2];
    }

    /** Records a eaten meal whose item ID matched this civilian's favourite foods. */
    public void recordLikedMeal() {
        likedMealsDays[0]++;
    }

    public int likedMealsCount() {
        return likedMealsDays[0] + likedMealsDays[1] + likedMealsDays[2];
    }

    /** Records active ticks spent performing one of this civilian's favourite hobbies today. */
    public void recordLikedHobbyTicks(long ticks) {
        if (ticks > 0L) {
            likedHobbyTicksDays[0] += ticks;
        }
    }

    /** Total ticks spent performing liked hobbies across today, yesterday, and the day before. */
    public long likedHobbyTicksCount() {
        return likedHobbyTicksDays[0] + likedHobbyTicksDays[1] + likedHobbyTicksDays[2];
    }

    /** Appends one slot's comfort score (0–1), keeping at most {@link #COMFORT_DAY_SLOTS} recent samples. */
    public void recordComfortSample(float score) {
        if (!Float.isFinite(score)) {
            return;
        }
        float clamped = (float) FulfilmentModel.clamp01(score);
        if (comfortSamples.size() >= COMFORT_DAY_SLOTS) {
            comfortSamples.remove(0);
        }
        comfortSamples.add(clamped);
    }

    public boolean hasComfortSamples() {
        return !comfortSamples.isEmpty();
    }

    public int comfortSampleCount() {
        return comfortSamples.size();
    }

    /** Average comfort over the retained last-day samples, or {@code Double.NaN} if no samples exist. */
    public double averageComfort() {
        if (comfortSamples.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (float s : comfortSamples) {
            sum += s;
        }
        return sum / comfortSamples.size();
    }

    public float grievance() {
        return grievance;
    }

    public void setGrievance(float grievance) {
        this.grievance = (float) FulfilmentModel.clamp01(grievance);
    }

    /** True once {@link #SLOT_TICKS} citizen active ticks have elapsed since the previous slot step. */
    public boolean isSlotDue() {
        return ticksSinceLastSlot >= SLOT_TICKS;
    }

    /** Consumes accumulated ticks since the last slot (returns how many ticks to advance grievance by). */
    public long consumeSlotTicks() {
        long elapsed = ticksSinceLastSlot;
        ticksSinceLastSlot = 0L;
        return elapsed;
    }

    public Saved save() {
        List<Set<UUID>> comm = List.of(
                Collections.unmodifiableSet(new HashSet<>(communityDays[0])),
                Collections.unmodifiableSet(new HashSet<>(communityDays[1])),
                Collections.unmodifiableSet(new HashSet<>(communityDays[2]))
        );
        return new Saved(
                preferences,
                citizenActiveTicks,
                comm,
                socializingDays[0], socializingDays[1], socializingDays[2],
                Collections.unmodifiableMap(new LinkedHashMap<>(lastSocialTickByPartner)),
                likedMealsDays[0], likedMealsDays[1], likedMealsDays[2],
                likedHobbyTicksDays[0], likedHobbyTicksDays[1], likedHobbyTicksDays[2],
                List.copyOf(comfortSamples),
                grievance,
                ticksSinceLastSlot
        );
    }

    public static CivilianFulfilment restore(Saved saved) {
        CivilianFulfilment f = new CivilianFulfilment(saved.preferences());
        f.citizenActiveTicks = Math.max(0L, saved.citizenActiveTicks());
        for (int i = 0; i < WINDOW_DAYS; i++) {
            f.communityDays[i].clear();
            if (i < saved.communityDays().size()) {
                f.communityDays[i].addAll(saved.communityDays().get(i));
            }
        }
        f.socializingDays[0] = Math.max(0, saved.socializingDay0());
        f.socializingDays[1] = Math.max(0, saved.socializingDay1());
        f.socializingDays[2] = Math.max(0, saved.socializingDay2());
        f.lastSocialTickByPartner.putAll(saved.lastSocialTickByPartner());
        f.likedMealsDays[0] = Math.max(0, saved.likedMealsDay0());
        f.likedMealsDays[1] = Math.max(0, saved.likedMealsDay1());
        f.likedMealsDays[2] = Math.max(0, saved.likedMealsDay2());
        f.likedHobbyTicksDays[0] = Math.max(0L, saved.likedHobbyTicksDay0());
        f.likedHobbyTicksDays[1] = Math.max(0L, saved.likedHobbyTicksDay1());
        f.likedHobbyTicksDays[2] = Math.max(0L, saved.likedHobbyTicksDay2());
        int start = Math.max(0, saved.comfortSamples().size() - COMFORT_DAY_SLOTS);
        for (int i = start; i < saved.comfortSamples().size(); i++) {
            float sample = saved.comfortSamples().get(i);
            if (Float.isFinite(sample)) {
                f.comfortSamples.add((float) FulfilmentModel.clamp01(sample));
            }
        }
        f.grievance = (float) FulfilmentModel.clamp01(saved.grievance());
        f.ticksSinceLastSlot = Math.max(0L, saved.ticksSinceLastSlot());
        return f;
    }

    public CivilianFulfilment copy() {
        return restore(save());
    }

    /** Immutable snapshot for persistence and dirty-checking without Minecraft dependencies. */
    public record Saved(
            CivilianPreferences preferences,
            long citizenActiveTicks,
            List<Set<UUID>> communityDays,
            int socializingDay0,
            int socializingDay1,
            int socializingDay2,
            Map<UUID, Long> lastSocialTickByPartner,
            int likedMealsDay0,
            int likedMealsDay1,
            int likedMealsDay2,
            long likedHobbyTicksDay0,
            long likedHobbyTicksDay1,
            long likedHobbyTicksDay2,
            List<Float> comfortSamples,
            float grievance,
            long ticksSinceLastSlot
    ) {
        public static Saved empty(CivilianPreferences preferences) {
            return new CivilianFulfilment(preferences).save();
        }
    }
}
