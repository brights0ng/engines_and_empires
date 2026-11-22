// Written by Gemini.
package dev.brights0ng.empire.gametest;

import java.util.List;
import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianFulfilmentMeters;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.CivilianQuirk;
import dev.brights0ng.empire.rules.CivilianSleep;
import dev.brights0ng.empire.rules.CivilianTraits;
import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.services.TemperatureProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Integration tests for the Big Five + Orthogonality trait system: live entity attributes, hunger/starvation/weakness
 * scaling, sleep need/rough-credit/wake-cooldown scaling, individualized thermal comfort/social/grievance fulfilment,
 * high-HP respawn preservation, and traits debug output.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class CivilianTraitsGameTests {

    private static final String BATCH = "civilians";

    private static BlockPos mainPos(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(4, 2, 4));
    }

    private static EmpireRecord found(GameTestHelper helper, Player founder) {
        BlockPos main = mainPos(helper);
        return EmpireSavedData.of(helper.getLevel()).createEmpire(founder.getUUID(),
                ChunkKeys.pack(main.getX() >> 4, main.getZ() >> 4), main, helper.getLevel().getGameTime());
    }

    private static void disband(GameTestHelper helper, EmpireRecord empire) {
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel());
        data.disbandEmpire(empire.id(), helper.getLevel().getServer());
        data.onBonfireRemoved(mainPos(helper), null);
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void attributesScaleWithOrthogonalityAndPersonality(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 pos = Vec3.atBottomCenterOf(mainPos(helper));
        CivilianEntity powerhouse = CivilianSpawning.spawn(level, pos, null);
        CivilianEntity scout = CivilianSpawning.spawn(level, pos.add(1, 0, 0), null);
        CivilianEntity tireless = CivilianSpawning.spawn(level, pos.add(2, 0, 0), null);
        CivilianEntity explosive = CivilianSpawning.spawn(level, pos.add(3, 0, 0), null);
        try {
            powerhouse.setTraits(CivilianTraits.NEUTRAL
                    .with(BaseTrait.ALLOMETRIC_SCALE, -90)
                    .with(BaseTrait.METABOLIC_RESERVE, 80)
                    .with(BaseTrait.CONTRACTILE_FORCE, 90)
                    .with(BaseTrait.ELASTIC_COMPLIANCE, -80)
                    .with(BaseTrait.NEUROTICISM, -80));
            scout.setTraits(CivilianTraits.NEUTRAL
                    .with(BaseTrait.ALLOMETRIC_SCALE, 90)
                    .with(BaseTrait.METABOLIC_RESERVE, -80)
                    .with(BaseTrait.CONTRACTILE_FORCE, -90)
                    .with(BaseTrait.ELASTIC_COMPLIANCE, 90)
                    .with(BaseTrait.NEUROTICISM, 80)
                    .with(BaseTrait.MOTOR_SENSORY, 80));
            tireless.setTraits(CivilianTraits.NEUTRAL.with(BaseTrait.AEROBIC_STAMINA, 90));
            explosive.setTraits(CivilianTraits.NEUTRAL.with(BaseTrait.AEROBIC_STAMINA, -90));

            helper.assertTrue(powerhouse.getMaxHealth() > 33.0F && scout.getMaxHealth() < 26.0F,
                    "max health scales: powerhouse=" + powerhouse.getMaxHealth() + ", scout=" + scout.getMaxHealth());
            helper.assertTrue(scout.getAttributeValue(Attributes.MOVEMENT_SPEED)
                    > powerhouse.getAttributeValue(Attributes.MOVEMENT_SPEED), "scout faster than powerhouse");
            helper.assertTrue(scout.getAttributeValue(Attributes.SAFE_FALL_DISTANCE)
                    > powerhouse.getAttributeValue(Attributes.SAFE_FALL_DISTANCE), "limber scout safer fall distance");
            helper.assertTrue(powerhouse.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER)
                    > scout.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), "stiff heavy powerhouse higher fall damage");
            helper.assertTrue(powerhouse.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)
                    > scout.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), "powerhouse higher knockback resistance");
            helper.assertTrue(powerhouse.getAttributeValue(Attributes.ATTACK_DAMAGE)
                    > scout.getAttributeValue(Attributes.ATTACK_DAMAGE), "powerhouse higher melee damage");
            helper.assertTrue(powerhouse.getAttributeValue(Attributes.ATTACK_KNOCKBACK)
                    > scout.getAttributeValue(Attributes.ATTACK_KNOCKBACK), "powerhouse higher attack knockback");
            helper.assertTrue(scout.getAttributeValue(Attributes.MOVEMENT_EFFICIENCY)
                    > powerhouse.getAttributeValue(Attributes.MOVEMENT_EFFICIENCY), "limber deft scout better terrain efficiency");
            helper.assertTrue(scout.getAttributeValue(Attributes.FOLLOW_RANGE)
                    > powerhouse.getAttributeValue(Attributes.FOLLOW_RANGE), "high-strung scout wider threat awareness");

            helper.assertTrue(tireless.getAttributeValue(Attributes.OXYGEN_BONUS)
                    > explosive.getAttributeValue(Attributes.OXYGEN_BONUS), "tireless higher oxygen bonus");
            helper.assertTrue(tireless.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY)
                    > explosive.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY), "tireless better water efficiency");
            helper.assertTrue(explosive.getAttributeValue(Attributes.JUMP_STRENGTH)
                    > tireless.getAttributeValue(Attributes.JUMP_STRENGTH), "explosive higher jump strength");
            helper.succeed();
        } finally {
            powerhouse.discard();
            scout.discard();
            tireless.discard();
            explosive.discard();
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void hungerBasalActivityStarvationAndWeaknessScaleWithTraits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 pos = Vec3.atBottomCenterOf(mainPos(helper));
        CivilianEntity furnace = CivilianSpawning.spawn(level, pos, null);
        CivilianEntity thrifty = CivilianSpawning.spawn(level, pos.add(1, 0, 0), null);
        CivilianEntity anaerobic = CivilianSpawning.spawn(level, pos.add(2, 0, 0), null);
        CivilianEntity aerobic = CivilianSpawning.spawn(level, pos.add(3, 0, 0), null);
        try {
            for (CivilianEntity c : List.of(furnace, thrifty, anaerobic, aerobic)) {
                c.setNoAi(true);
                c.setNoGravity(true);
                c.setFood(20, 0.0F);
            }
            // Hypertrophic + Lean = high basal burn, early hunger weakness, fast starvation
            furnace.setTraits(CivilianTraits.NEUTRAL
                    .with(BaseTrait.CONTRACTILE_FORCE, 90)
                    .with(BaseTrait.METABOLIC_RESERVE, -100));
            // Gracile + Fatty = low basal burn, late hunger weakness, slow starvation
            thrifty.setTraits(CivilianTraits.NEUTRAL
                    .with(BaseTrait.CONTRACTILE_FORCE, -90)
                    .with(BaseTrait.METABOLIC_RESERVE, 100));
            anaerobic.setTraits(CivilianTraits.NEUTRAL.with(BaseTrait.AEROBIC_STAMINA, -90));
            aerobic.setTraits(CivilianTraits.NEUTRAL.with(BaseTrait.AEROBIC_STAMINA, 90));

            // 1. Basal exhaustion over 200 active ticks
            for (int i = 0; i < 200; i++) {
                furnace.aiStep();
                thrifty.aiStep();
            }
            helper.assertTrue(furnace.food().exhaustion() > thrifty.food().exhaustion() * 1.5F,
                    "hypertrophic+lean burns far more basal exhaustion: "
                            + furnace.food().exhaustion() + " vs " + thrifty.food().exhaustion());

            // 2. Activity exhaustion on jump
            anaerobic.jumpFromGround();
            aerobic.jumpFromGround();
            helper.assertTrue(anaerobic.food().exhaustion() > aerobic.food().exhaustion() * 1.4F,
                    "anaerobic spends more jump exhaustion: "
                            + anaerobic.food().exhaustion() + " vs " + aerobic.food().exhaustion());

            // 3. Hunger-affects-strength dynamic modifier on ATTACK_DAMAGE
            double furnaceFullAtk = furnace.getAttributeValue(Attributes.ATTACK_DAMAGE);
            double thriftyFullAtk = thrifty.getAttributeValue(Attributes.ATTACK_DAMAGE);
            furnace.setFood(8, 0.0F); // Below Lean threshold (12-13)
            thrifty.setFood(8, 0.0F); // Above Fatty threshold (5-6)
            helper.assertTrue(furnace.getAttributeValue(Attributes.ATTACK_DAMAGE) < furnaceFullAtk * 0.95,
                    "lean civilian weakens at food 8");
            helper.assertTrue(Math.abs(thrifty.getAttributeValue(Attributes.ATTACK_DAMAGE) - thriftyFullAtk) < 1e-6,
                    "fatty civilian retains full attack strength at food 8");
            furnace.setFood(20, 0.0F);
            helper.assertTrue(Math.abs(furnace.getAttributeValue(Attributes.ATTACK_DAMAGE) - furnaceFullAtk) < 1e-6,
                    "refeeding clears hunger weakness modifier");

            // 4. Starvation interval: Lean starves by tick 60, Fatty still undamaged at tick 60
            furnace.setFood(0, 0.0F);
            thrifty.setFood(0, 0.0F);
            float furnaceHp0 = furnace.getHealth();
            float thriftyHp0 = thrifty.getHealth();
            for (int i = 0; i < 60; i++) {
                furnace.aiStep();
                thrifty.aiStep();
            }
            helper.assertTrue(furnace.getHealth() < furnaceHp0, "lean+hypertrophic starves within 60 ticks");
            helper.assertTrue(thrifty.getHealth() == thriftyHp0, "fatty+gracile resists starvation past 60 ticks");
            helper.succeed();
        } finally {
            furnace.discard();
            thrifty.discard();
            anaerobic.discard();
            aerobic.discard();
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void sleepNeedRoughCreditAndWakeCooldownScaleWithTraits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 pos = Vec3.atBottomCenterOf(mainPos(helper));
        CivilianEntity shortSleeper = CivilianSpawning.spawn(level, pos, null);
        CivilianEntity heavySleeper = CivilianSpawning.spawn(level, pos.add(1, 0, 0), null);
        try {
            shortSleeper.setTraits(CivilianTraits.NEUTRAL
                    .with(BaseTrait.AEROBIC_STAMINA, 90)
                    .with(BaseTrait.ELASTIC_COMPLIANCE, 90)
                    .with(BaseTrait.NEUROTICISM, -80));
            heavySleeper.setTraits(CivilianTraits.NEUTRAL
                    .with(BaseTrait.AEROBIC_STAMINA, -90)
                    .with(BaseTrait.CONTRACTILE_FORCE, 80)
                    .with(BaseTrait.ELASTIC_COMPLIANCE, -90)
                    .with(BaseTrait.NEUROTICISM, 80)
                    .with(BaseTrait.MOTOR_SENSORY, 80));

            helper.assertTrue(shortSleeper.sleepNeedTicks() < 6_000,
                    "tireless unflappable needs < 6000t sleep, got " + shortSleeper.sleepNeedTicks());
            helper.assertTrue(heavySleeper.sleepNeedTicks() > 9_000,
                    "explosive brawny high-strung deft needs > 9000t sleep, got " + heavySleeper.sleepNeedTicks());

            // Rough sleep credit catchUp over 1 full night
            shortSleeper.sleep().catchUp(1_000L, 24_000L + 6_000L, false,
                    shortSleeper.sleepNeedTicks(), shortSleeper.derivedStats().roughSleepCreditRate());
            heavySleeper.sleep().catchUp(1_000L, 24_000L + 6_000L, false,
                    heavySleeper.sleepNeedTicks(), heavySleeper.derivedStats().roughSleepCreditRate());
            helper.assertTrue(shortSleeper.sleep().lastNight() > 0.58F,
                    "limber unflappable higher rough sleep credit: " + shortSleeper.sleep().lastNight());
            helper.assertTrue(heavySleeper.sleep().lastNight() < 0.42F,
                    "taut high-strung lower rough sleep credit: " + heavySleeper.sleep().lastNight());

            // Wake cooldown scales with Neuroticism / Aerobic
            shortSleeper.wakeUp(true);
            heavySleeper.wakeUp(true);
            helper.assertTrue(heavySleeper.wakeCooldownRemaining() > shortSleeper.wakeCooldownRemaining() + 100,
                    "high-strung wake cooldown (" + heavySleeper.wakeCooldownRemaining()
                            + ") > unflappable (" + shortSleeper.wakeCooldownRemaining() + ")");
            helper.succeed();
        } finally {
            shortSleeper.discard();
            heavySleeper.discard();
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void fulfilmentComfortSocialTargetsAndGrievanceScaleWithTraits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, founder);
        CivilianEntity coldIntroStoic = null;
        CivilianEntity heatExtraAnxious = null;
        try {
            BlockPos main = mainPos(helper);
            coldIntroStoic = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(main), null);
            coldIntroStoic.setTraits(CivilianTraits.NEUTRAL
                    .with(BaseTrait.ALLOMETRIC_SCALE, -90)
                    .with(BaseTrait.METABOLIC_RESERVE, 90)
                    .with(BaseTrait.CONTRACTILE_FORCE, 80)
                    .with(BaseTrait.EXTRAVERSION, -90)
                    .with(BaseTrait.NEUROTICISM, -90));
            CivilianRoster.recruit(coldIntroStoic, empire, level);

            heatExtraAnxious = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(main.east()), null);
            heatExtraAnxious.setTraits(CivilianTraits.NEUTRAL
                    .with(BaseTrait.ALLOMETRIC_SCALE, 90)
                    .with(BaseTrait.METABOLIC_RESERVE, -90)
                    .with(BaseTrait.CONTRACTILE_FORCE, -80)
                    .with(BaseTrait.EXTRAVERSION, 90)
                    .with(BaseTrait.NEUROTICISM, 90));
            CivilianRoster.recruit(heatExtraAnxious, empire, level);

            // 1. Cold (6 C) vs Hot (38 C) comfort shift
            TemperatureProvider chilly = new TemperatureProvider() {
                @Override public double air(ServerLevel lvl, BlockPos pos) { return 6.0; }
                @Override public double room(ServerLevel lvl, Vec3 at) { return 0.0; }
            };
            TemperatureProvider hot = new TemperatureProvider() {
                @Override public double air(ServerLevel lvl, BlockPos pos) { return 38.0; }
                @Override public double room(ServerLevel lvl, Vec3 at) { return 0.0; }
            };
            CivilianFulfilmentMeters.sampleComfort(level, coldIntroStoic, chilly);
            CivilianFulfilmentMeters.sampleComfort(level, heatExtraAnxious, chilly);
            helper.assertTrue(Math.abs(coldIntroStoic.fulfilment().averageComfort() - 1.0) < 1e-6,
                    "cold-adapted citizen is 1.0 comfortable at 6 C");
            helper.assertTrue(heatExtraAnxious.fulfilment().averageComfort() < 0.6,
                    "heat-adapted citizen is chilly at 6 C, got " + heatExtraAnxious.fulfilment().averageComfort());

            // Now sample hot (38 C) on fresh neutral-cleared buffers via new bodies or check direction on same
            CivilianEntity coldCheck = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(main), null);
            CivilianEntity heatCheck = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(main), null);
            try {
                coldCheck.setTraits(coldIntroStoic.traits());
                heatCheck.setTraits(heatExtraAnxious.traits());
                CivilianFulfilmentMeters.sampleComfort(level, coldCheck, hot);
                CivilianFulfilmentMeters.sampleComfort(level, heatCheck, hot);
                helper.assertTrue(Math.abs(heatCheck.fulfilment().averageComfort() - 1.0) < 1e-6,
                        "heat-adapted citizen is 1.0 comfortable at 38 C");
                helper.assertTrue(coldCheck.fulfilment().averageComfort() < 0.6,
                        "cold-adapted citizen overheats at 38 C, got " + coldCheck.fulfilment().averageComfort());
            } finally {
                coldCheck.discard();
                heatCheck.discard();
            }

            // 2. Social targets scale with Extraversion
            for (int i = 0; i < 5; i++) {
                UUID person = UUID.randomUUID();
                coldIntroStoic.fulfilment().recordNearbyPerson(person);
                heatExtraAnxious.fulfilment().recordNearbyPerson(person);
            }
            for (int i = 0; i < 2; i++) {
                UUID partner = UUID.randomUUID();
                coldIntroStoic.fulfilment().recordSocialInteraction(partner, i * 2000L);
                heatExtraAnxious.fulfilment().recordSocialInteraction(partner, i * 2000L);
            }
            helper.assertTrue(CivilianFulfilmentMeters.communityMeter(coldIntroStoic).value() > 0.95,
                    "solitary citizen maxes community at 5 people");
            helper.assertTrue(CivilianFulfilmentMeters.communityMeter(heatExtraAnxious).value() < 0.40,
                    "chatterbox citizen feels lonely at 5 people");
            helper.assertTrue(CivilianFulfilmentMeters.socializingMeter(coldIntroStoic).value() > 0.95,
                    "solitary citizen maxes socializing at 2 talks");
            helper.assertTrue(CivilianFulfilmentMeters.socializingMeter(heatExtraAnxious).value() < 0.35,
                    "chatterbox citizen wants more than 2 talks");

            // 3. Grievance builds faster on high-Neuroticism citizen under identical deprivation
            coldIntroStoic.setFood(0, 0.0F);
            coldIntroStoic.setLastNight(0.0F);
            heatExtraAnxious.setFood(0, 0.0F);
            heatExtraAnxious.setLastNight(0.0F);
            CivilianFulfilmentMeters.stepSlot(level, coldIntroStoic, 24_000L, true);
            CivilianFulfilmentMeters.stepSlot(level, heatExtraAnxious, 24_000L, true);
            helper.assertTrue(heatExtraAnxious.fulfilment().grievance() > coldIntroStoic.fulfilment().grievance() * 1.4F,
                    "high-strung builds grievance faster: " + heatExtraAnxious.fulfilment().grievance()
                            + " vs " + coldIntroStoic.fulfilment().grievance());
            helper.succeed();
        } finally {
            if (coldIntroStoic != null) coldIntroStoic.discard();
            if (heatExtraAnxious != null) heatExtraAnxious.discard();
            disband(helper, empire);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void quirksDebugLinesAndHighHpRespawnPreserveTraits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, founder);
        CivilianEntity c = null;
        CivilianEntity replacement = null;
        try {
            c = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(mainPos(helper)), null);
            CivilianTraits forgeMaster = CivilianTraits.NEUTRAL
                    .with(BaseTrait.ALLOMETRIC_SCALE, -85)
                    .with(BaseTrait.CONTRACTILE_FORCE, 95)
                    .with(BaseTrait.OPENNESS, -70)
                    .with(BaseTrait.AGREEABLENESS, -75);
            c.setTraits(forgeMaster);
            c.setHealth(c.getMaxHealth());
            float fullHp = c.getMaxHealth();
            helper.assertTrue(fullHp > 35.0F, "brawny stocky citizen has > 35 max HP, got " + fullHp);

            List<CivilianQuirk> quirks = c.quirks();
            helper.assertTrue(quirks.contains(CivilianQuirk.BRAWNY)
                    && quirks.contains(CivilianQuirk.STOCKY)
                    && quirks.contains(CivilianQuirk.CANTANKEROUS)
                    && quirks.contains(CivilianQuirk.CREATURE_OF_HABIT),
                    "all four extreme quirks surfaced: " + quirks);

            CivilianRecord record = CivilianRoster.recruit(c, empire, level);
            String infoJoined = String.join("\n", c.debugInfoLines(level).stream().map(Component::getString).toList());
            helper.assertTrue(infoJoined.contains("Abilities: BRA") && infoJoined.contains("Quirks: Brawny"),
                    "debugInfoLines includes 7 abilities and quirks");
            helper.assertTrue(infoJoined.contains("Stats: Comfort"), "debugInfoLines includes compact derived stats");

            String traitsJoined = String.join("\n", c.traitsDebugLines().stream().map(Component::getString).toList());
            helper.assertTrue(traitsJoined.contains("Big Five:") && traitsJoined.contains("Orthogonality:")
                    && traitsJoined.contains("Attributes:") && traitsJoined.contains("Jobs/Combat:"),
                    "traitsDebugLines includes all sections");

            // Respawn preserves >30 HP without clipping to legacy 30.0
            c.discard();
            replacement = CivilianSpawning.respawnBody(record, level);
            helper.assertTrue(replacement.traits().equals(forgeMaster), "respawn keeps traits");
            helper.assertTrue(Math.abs(replacement.getMaxHealth() - fullHp) < 1e-4F, "respawn restores >30 max HP");
            helper.assertTrue(Math.abs(replacement.getHealth() - fullHp) < 1e-4F, "respawn does not clip >30 HP to 30");
            helper.succeed();
        } finally {
            if (c != null && !c.isRemoved()) c.discard();
            if (replacement != null && !replacement.isRemoved()) replacement.discard();
            disband(helper, empire);
        }
    }

    private CivilianTraitsGameTests() {
    }
}
