// Written by Gemini.
package dev.brights0ng.empire.civilian;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireConfig;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.rules.CivilianFulfilment;
import dev.brights0ng.empire.rules.CivilianPreferences;
import dev.brights0ng.empire.rules.FulfilmentModel;
import dev.brights0ng.empire.rules.FulfilmentModel.Evaluation;
import dev.brights0ng.empire.rules.FulfilmentModel.Meter;
import dev.brights0ng.empire.rules.FulfilmentModel.MeterReading;
import dev.brights0ng.empire.rules.FulfilmentModel.SourceKind;
import dev.brights0ng.empire.rules.FulfilmentModel.Tier;
import dev.brights0ng.empire.rules.RegionType;
import dev.brights0ng.paradynelib.services.TemperatureProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

/**
 * World-side fulfilment meter providers, comfort sampling, community proximity scanning, slot evaluation, and debug
 * formatting ({@code docs/empire/civilian-fulfilment.md}).
 *
 * <p>Each of the eleven meters has one dedicated provider method returning a {@link MeterReading}. Meters whose source
 * system is not built yet (or absent, e.g. Meteodyne for Comfort) return the configured placeholder default and flag
 * themselves so debug output makes placeholder sources obvious.
 */
public final class CivilianFulfilmentMeters {

    /** Data-driven pool of favourite food items rolled per civilian ({@code empire:civilian_favourite_foods}). */
    public static final TagKey<Item> FAVOURITE_FOODS_TAG = TagKey.create(
            Registries.ITEM, Empire.id("civilian_favourite_foods"));

    /** Middle-of-body vertical offset for room temperature sampling (matches Meteodyne {@code PersonClimate}). */
    private static final double BODY_MIDDLE_Y = 0.9;

    /** Resolves the data-driven favourite food pool, falling back to {@link CivilianPreferences#DEFAULT_FOOD_POOL}. */
    public static List<String> resolveFavouriteFoodPool() {
        Optional<HolderSet.Named<Item>> tag = BuiltInRegistries.ITEM.getTag(FAVOURITE_FOODS_TAG);
        if (tag.isEmpty()) {
            return CivilianPreferences.DEFAULT_FOOD_POOL;
        }
        List<String> ids = new ArrayList<>();
        for (Holder<Item> holder : tag.get()) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(holder.value());
            if (key != null) {
                ids.add(key.toString());
            }
        }
        if (ids.isEmpty()) {
            return CivilianPreferences.DEFAULT_FOOD_POOL;
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    /** True if {@code source} represents damage from a hostile monster or an outsider (non-member) player (§2 Safety). */
    public static boolean isSafetyHitSource(DamageSource source, EmpireRecord empire) {
        Entity attacker = source.getEntity();
        if (attacker == null) {
            return false;
        }
        if (attacker instanceof Enemy || attacker.getType().getCategory() == MobCategory.MONSTER) {
            return true;
        }
        if (attacker instanceof Player player) {
            return !empire.isMember(player.getUUID());
        }
        return false;
    }

    /**
     * Samples the world temperature at {@code civilian} via {@link TemperatureProvider} ({@code air + room}) and
     * records one comfort reading if the provider returns a finite temperature. Returns {@code true} if a sample was
     * recorded, {@code false} if no climate provider is installed or the dimension has no weather.
     */
    public static boolean sampleComfort(ServerLevel level, CivilianEntity civilian) {
        return sampleComfort(level, civilian, TemperatureProvider.get());
    }

    /** Package/test overload accepting an explicit {@link TemperatureProvider}. */
    public static boolean sampleComfort(ServerLevel level, CivilianEntity civilian, TemperatureProvider provider) {
        double air = provider.air(level, civilian.blockPosition());
        if (!Double.isFinite(air)) {
            return false;
        }
        double room = provider.room(level, civilian.position().add(0.0, BODY_MIDDLE_Y, 0.0));
        double tempC = air + (Double.isFinite(room) ? room : 0.0);
        double score = FulfilmentModel.comfortFromTemp(
                tempC,
                EmpireConfig.fulfilmentComfortMinC(),
                EmpireConfig.fulfilmentComfortMaxC(),
                EmpireConfig.fulfilmentComfortDangerMarginC());
        if (!Double.isFinite(score)) {
            return false;
        }
        civilian.fulfilment().recordComfortSample((float) score);
        return true;
    }

    /**
     * Scans within {@link CivilianFulfilment#COMMUNITY_RADIUS} blocks for other live non-leaving civilians and online
     * non-spectator members of {@code empire}, recording their distinct identities in today's Community bucket.
     */
    public static void scanCommunity(ServerLevel level, CivilianEntity civilian, @Nullable EmpireRecord empire) {
        double r = CivilianFulfilment.COMMUNITY_RADIUS;
        double r2 = r * r;
        AABB box = civilian.getBoundingBox().inflate(r);
        CivilianFulfilment fulfilment = civilian.fulfilment();

        for (CivilianEntity other : level.getEntitiesOfClass(CivilianEntity.class, box)) {
            if (other != civilian && other.isAlive() && other.status() != CivilianEntity.Status.LEAVING
                    && civilian.distanceToSqr(other) <= r2) {
                fulfilment.recordNearbyPerson(other.identityUuid());
            }
        }
        if (empire != null) {
            for (Player player : level.players()) {
                if (!player.isSpectator() && player.isAlive() && empire.isMember(player.getUUID())
                        && civilian.distanceToSqr(player) <= r2) {
                    fulfilment.recordNearbyPerson(player.getUUID());
                }
            }
        }
    }

    /** Computes a fresh {@link Evaluation} for {@code civilian} using all eleven meter providers. */
    public static Evaluation evaluate(ServerLevel level, CivilianEntity civilian) {
        EmpireSavedData data = EmpireSavedData.of(level);
        CivilianRecord record = civilian.ownRecord(data);
        EmpireRecord empire = record != null ? data.getEmpire(record.empire()) : null;
        int population = empire != null ? Math.max(1, data.citizenCount(empire.id())) : 1;
        double expectation = FulfilmentModel.expectation(population, EmpireConfig.fulfilmentExpectationCurve());
        double placeholder = EmpireConfig.fulfilmentPlaceholderDefault();

        EnumMap<Meter, MeterReading> readings = new EnumMap<>(Meter.class);
        readings.put(Meter.HUNGER, hungerMeter(civilian));
        readings.put(Meter.SLEEP, sleepMeter(civilian, placeholder));
        readings.put(Meter.HEALTH, healthMeter(civilian));
        readings.put(Meter.SAFETY, safetyMeter(empire, placeholder));
        readings.put(Meter.COMFORT, comfortMeter(civilian, placeholder));
        readings.put(Meter.SHELTER, shelterMeter(civilian, data, empire));
        readings.put(Meter.COMMUNITY, communityMeter(civilian));
        readings.put(Meter.SOCIALIZING, socializingMeter(civilian));
        readings.put(Meter.COMMITMENT, commitmentMeter(civilian));
        readings.put(Meter.VOCATION, vocationMeter(placeholder));
        readings.put(Meter.ENJOYMENT, enjoymentMeter(civilian, placeholder));

        return FulfilmentModel.evaluate(readings, expectation, placeholder);
    }

    /** Runs one schedule-slot fulfilment update: samples comfort, evaluates meters, and advances grievance if a member is online. */
    public static Evaluation stepSlot(ServerLevel level, CivilianEntity civilian, long deltaTicks) {
        EmpireSavedData data = EmpireSavedData.of(level);
        CivilianRecord record = civilian.ownRecord(data);
        EmpireRecord empire = record != null ? data.getEmpire(record.empire()) : null;
        boolean memberOnline = empire != null && EmpireSavedData.hasOnlineMember(empire, level.getServer());
        return stepSlot(level, civilian, deltaTicks, memberOnline);
    }

    /** Runs one schedule-slot fulfilment update with an explicit {@code memberOnline} flag (also used by game tests). */
    public static Evaluation stepSlot(ServerLevel level, CivilianEntity civilian, long deltaTicks, boolean memberOnline) {
        sampleComfort(level, civilian);
        Evaluation eval = evaluate(level, civilian);
        double nextGrievance = FulfilmentModel.stepGrievance(
                civilian.fulfilment().grievance(),
                eval.fulfilment(),
                EmpireConfig.fulfilmentGrievanceThreshold(),
                EmpireConfig.fulfilmentGrievanceBuildPerDay(),
                EmpireConfig.fulfilmentGrievanceDrainPerDay(),
                deltaTicks,
                memberOnline);
        civilian.fulfilment().setGrievance((float) nextGrievance);
        civilian.syncNeedsToRecord();
        return eval;
    }

    // ---- Individual meter providers ----

    public static MeterReading hungerMeter(CivilianEntity civilian) {
        int foodLevel = civilian.food().foodLevel();
        return MeterReading.live(FulfilmentModel.hunger(foodLevel), "food " + foodLevel + "/20");
    }

    public static MeterReading sleepMeter(CivilianEntity civilian, double placeholderDefault) {
        return FulfilmentModel.sleep(civilian.sleep().lastNight(), placeholderDefault);
    }

    public static MeterReading healthMeter(CivilianEntity civilian) {
        float hp = civilian.getHealth();
        float max = civilian.getMaxHealth();
        return MeterReading.live(FulfilmentModel.health(hp, max),
                String.format(Locale.ROOT, "%.1f/%.1f HP", hp, max));
    }

    public static MeterReading safetyMeter(@Nullable EmpireRecord empire, double placeholderDefault) {
        if (empire == null) {
            return MeterReading.placeholder(placeholderDefault, "no empire");
        }
        int hits = empire.safety().hitsLast3Days();
        int guards = 0; // Guards wait on jobs.
        double hRef = EmpireConfig.fulfilmentSafetyHitRef();
        return MeterReading.live(FulfilmentModel.safety(hits, guards, hRef),
                String.format(Locale.ROOT, "%d hits/3d, %d guards (H_ref=%.0f)", hits, guards, hRef));
    }

    public static MeterReading comfortMeter(CivilianEntity civilian, double placeholderDefault) {
        CivilianFulfilment f = civilian.fulfilment();
        if (!f.hasComfortSamples()) {
            return MeterReading.placeholder(placeholderDefault, "no climate samples");
        }
        double avg = f.averageComfort();
        return MeterReading.live(avg, f.comfortSampleCount() + " sample" + (f.comfortSampleCount() == 1 ? "" : "s"));
    }

    public static MeterReading shelterMeter(CivilianEntity civilian, EmpireSavedData data, @Nullable EmpireRecord empire) {
        BlockPos bed = civilian.claimedBed();
        if (empire == null || bed == null) {
            return MeterReading.live(0.0, "no home");
        }
        RegionRecord region = data.regionAt(bed);
        if (region == null || !region.empireId().equals(empire.id())
                || region.type() != RegionType.HOME || !region.valid()
                || !region.isIndoorBlock(bed)) {
            return MeterReading.live(0.0, "no valid HOME");
        }
        int floor = region.indoorFloorArea();
        int occupants = Math.max(1, region.inhabitants().size());
        double val = FulfilmentModel.shelter(true, floor, occupants);
        return MeterReading.live(val, String.format(Locale.ROOT, "%d floor / %d occ", floor, occupants));
    }

    public static MeterReading communityMeter(CivilianEntity civilian) {
        int people = civilian.fulfilment().distinctCommunityPeople();
        return MeterReading.live(FulfilmentModel.community(people), people + "/10 people (3d)");
    }

    public static MeterReading socializingMeter(CivilianEntity civilian) {
        int talks = civilian.fulfilment().socializingCount();
        return MeterReading.live(FulfilmentModel.socializing(talks), talks + "/5 talks (3d)");
    }

    public static MeterReading commitmentMeter(CivilianEntity civilian) {
        double days = civilian.fulfilment().daysLived();
        double bonus = FulfilmentModel.newcomerBonus(days);
        double val = FulfilmentModel.commitment(days);
        String detail = bonus > 0.001
                ? String.format(Locale.ROOT, "day %.1f (newcomer +%.2f)", days, bonus)
                : String.format(Locale.ROOT, "day %.1f", days);
        return MeterReading.live(val, detail);
    }

    public static MeterReading vocationMeter(double placeholderDefault) {
        return MeterReading.placeholder(placeholderDefault, "jobs not implemented");
    }

    public static MeterReading enjoymentMeter(CivilianEntity civilian, double placeholderDefault) {
        return FulfilmentModel.enjoyment(civilian.fulfilment().likedMealsCount(), placeholderDefault);
    }

    // ---- Debug formatting ----

    /** Full breakdown for {@code /empire debug civilian fulfilment}. */
    public static List<Component> formatDebugReport(ServerLevel level, CivilianEntity civilian) {
        EmpireSavedData data = EmpireSavedData.of(level);
        CivilianRecord record = civilian.ownRecord(data);
        EmpireRecord empire = record != null ? data.getEmpire(record.empire()) : null;
        int pop = empire != null ? Math.max(1, data.citizenCount(empire.id())) : 1;
        boolean memberOnline = empire != null && EmpireSavedData.hasOnlineMember(empire, level.getServer());
        Evaluation eval = evaluate(level, civilian);
        CivilianFulfilment f = civilian.fulfilment();

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("=== Fulfilment: " + civilian.getName().getString()
                + " (" + civilian.status().name() + ") ==="));
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Happiness: %.3f | E(n=%d): %.2f | Fulfilment: %.3f (prod %.2fx) | Grievance: %.3f (%s)",
                eval.happiness(), pop, eval.expectation(), eval.fulfilment(), eval.productivity(),
                f.grievance(), memberOnline ? "active" : "paused")));
        lines.add(Component.literal("Likes foods: " + shortFoodList(f.preferences().favouriteFoods())
                + " | Likes activities: " + shortActivityList(f.preferences().favouriteActivities())));

        for (Tier tier : Tier.values()) {
            lines.add(Component.literal(String.format(Locale.ROOT,
                    "  %s: r=%.2f, gate=%.2f, eff=%.3f/0.25",
                    tier.displayName(),
                    eval.tierFractions().get(tier),
                    eval.gates().get(tier),
                    eval.effectiveTiers().get(tier))));
            for (Meter meter : Meter.values()) {
                if (meter.tier() != tier) {
                    continue;
                }
                MeterReading r = eval.meters().get(meter);
                String tag = r.source() == SourceKind.PLACEHOLDER ? " [PLACEHOLDER]"
                        : (r.source() == SourceKind.PARTIAL ? " [PARTIAL]" : "");
                lines.add(Component.literal(String.format(Locale.ROOT,
                        "    %s (%.0f%%): %.2f%s (%s)",
                        meter.displayName(), meter.weight() * 100.0, r.value(), tag, r.detail())));
            }
        }
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Top shortfall: %s (+%.3f happiness if full)",
                eval.topShortfall().displayName(), eval.topShortfallGain())));
        return lines;
    }

    /** Concise two-line summary included in {@code /empire debug civilian info} and sneak + right-click stats. */
    public static List<Component> formatSummaryLines(ServerLevel level, CivilianEntity civilian) {
        Evaluation eval = evaluate(level, civilian);
        CivilianFulfilment f = civilian.fulfilment();
        return List.of(
                Component.literal(String.format(Locale.ROOT,
                        "Fulfilment: %.2f (Hap %.2f / E %.2f) | Prod: %.2fx | Grievance: %.2f | Shortfall: %s",
                        eval.fulfilment(), eval.happiness(), eval.expectation(), eval.productivity(),
                        f.grievance(), eval.topShortfall().displayName())),
                Component.literal("Likes: foods=" + shortFoodList(f.preferences().favouriteFoods())
                        + " | activities=" + shortActivityList(f.preferences().favouriteActivities()))
        );
    }

    private static String shortFoodList(List<String> foods) {
        if (foods.isEmpty()) {
            return "none";
        }
        List<String> compact = new ArrayList<>(foods.size());
        for (String id : foods) {
            compact.add(id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id);
        }
        return String.join(", ", compact);
    }

    private static String shortActivityList(List<CivilianPreferences.Activity> activities) {
        if (activities.isEmpty()) {
            return "none";
        }
        List<String> compact = new ArrayList<>(activities.size());
        for (CivilianPreferences.Activity act : activities) {
            compact.add(act.id());
        }
        return String.join(", ", compact);
    }

    private CivilianFulfilmentMeters() {
    }
}
