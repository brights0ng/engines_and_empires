// Written by Gemini.
package dev.brights0ng.empire.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.civilian.CivilianBeds;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianFulfilmentMeters;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.data.CivilianFulfilmentNbt;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.region.HomeRegionScanner;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.CivilianFulfilment;
import dev.brights0ng.empire.rules.CivilianPreferences;
import dev.brights0ng.empire.rules.CivilianPreferences.Activity;
import dev.brights0ng.empire.rules.FulfilmentModel;
import dev.brights0ng.empire.rules.FulfilmentModel.Evaluation;
import dev.brights0ng.empire.rules.FulfilmentModel.Meter;
import dev.brights0ng.empire.rules.FulfilmentModel.MeterReading;
import dev.brights0ng.empire.rules.FulfilmentModel.SourceKind;
import dev.brights0ng.empire.rules.RegionOrigin;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.services.TemperatureProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Integration tests for civilian fulfilment meters, preference rolling, HOME shelter floor area, safety hits,
 * liked-food enjoyment, community/socializing tracking, comfort temperature sampling, grievance pause, and persistence.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class CivilianFulfilmentGameTests {

    private static final String BATCH_CIVILIANS = "civilians";

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

    private static CivilianEntity citizen(GameTestHelper helper, EmpireRecord empire, BlockPos pos) {
        CivilianEntity c = CivilianSpawning.spawn(helper.getLevel(), Vec3.atBottomCenterOf(pos), null);
        CivilianRoster.recruit(c, empire, helper.getLevel());
        return c;
    }

    /** Builds a 5x5x2 enclosed, lit stone house (25 indoor floor blocks, 50 volume) with a bed at interior (1, 0, 1). */
    private static BlockPos buildEnclosedHome(ServerLevel level, BlockPos interiorOrigin, List<BlockPos> placed) {
        int ox = interiorOrigin.getX();
        int oy = interiorOrigin.getY();
        int oz = interiorOrigin.getZ();
        for (int dx = -1; dx <= 5; dx++) {
            for (int dz = -1; dz <= 5; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos pos = new BlockPos(ox + dx, oy + dy, oz + dz);
                    boolean shell = dy == -1 || dy == 2 || dx == -1 || dx == 5 || dz == -1 || dz == 5;
                    level.setBlock(pos, shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    placed.add(pos);
                }
            }
        }
        BlockPos torch = new BlockPos(ox + 2, oy, oz + 2);
        level.setBlock(torch, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
        placed.add(torch);

        BlockPos bedHead = new BlockPos(ox + 1, oy, oz + 1);
        BlockPos bedFoot = bedHead.south();
        BlockState headState = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.NORTH)
                .setValue(BedBlock.PART, BedPart.HEAD);
        BlockState footState = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.NORTH)
                .setValue(BedBlock.PART, BedPart.FOOT);
        int bedFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(bedFoot, footState, bedFlags);
        level.setBlock(bedHead, headState, bedFlags);
        placed.add(bedFoot);
        placed.add(bedHead);
        return bedHead;
    }

    private static void restoreAir(ServerLevel level, List<BlockPos> placed) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (BlockPos p : placed) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), flags);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH_CIVILIANS)
    public static void preferencesRolledFromTagAndPersisted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, founder);
        CivilianEntity c = null;
        CivilianEntity replacement = null;
        try {
            List<String> pool = CivilianFulfilmentMeters.resolveFavouriteFoodPool();
            helper.assertTrue(pool.contains("minecraft:bread"), "favourite food tag contains minecraft:bread");
            helper.assertTrue(pool.contains("minecraft:cooked_porkchop"), "favourite food tag contains cooked_porkchop");
            helper.assertFalse(pool.contains("minecraft:golden_carrot"), "rare golden_carrot excluded");
            helper.assertFalse(pool.contains("minecraft:poisonous_potato"), "poisonous_potato excluded");

            c = citizen(helper, empire, mainPos(helper));
            CivilianPreferences rolled = c.fulfilment().preferences();
            helper.assertTrue(rolled.favouriteFoods().size() >= 1 && rolled.favouriteFoods().size() <= 4,
                    "rolled 1-4 favourite foods");
            helper.assertTrue(rolled.favouriteActivities().size() >= 1 && rolled.favouriteActivities().size() <= 4,
                    "rolled 1-4 favourite activities");

            // Customize state and verify record + replacement body persistence
            CivilianPreferences custom = new CivilianPreferences(List.of("minecraft:apple", "minecraft:bread"), List.of(Activity.FISHING));
            c.setPreferences(custom);
            c.fulfilment().recordLikedMeal();
            c.fulfilment().setGrievance(0.42F);
            c.syncNeedsToRecord();

            CivilianRecord record = EmpireSavedData.of(level).civilian(c.civilianId());
            helper.assertTrue(record != null, "record exists");
            CompoundTag savedTag = record.save(level.registryAccess());
            CivilianRecord reloaded = CivilianRecord.load(savedTag, level.registryAccess());
            helper.assertTrue(reloaded.fulfilment().preferences().equals(custom), "preferences survive record NBT round-trip");
            helper.assertTrue(Math.abs(reloaded.fulfilment().grievance() - 0.42F) < 1e-4, "grievance survives record NBT");

            c.discard();
            replacement = CivilianSpawning.respawnBody(record, level);
            helper.assertTrue(replacement.fulfilment().preferences().equals(custom), "replacement body keeps preferences");
            helper.assertTrue(replacement.fulfilment().likedMealsCount() == 1, "replacement body keeps liked meals");
            helper.assertTrue(Math.abs(replacement.fulfilment().grievance() - 0.42F) < 1e-4, "replacement body keeps grievance");
            helper.succeed();
        } finally {
            if (c != null && !c.isRemoved()) c.discard();
            if (replacement != null && !replacement.isRemoved()) replacement.discard();
            disband(helper, empire);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "fulfilment_shelter", timeoutTicks = 25)
    public static void shelterReadsValidHomeFloorPerOccupant(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, founder);
        List<BlockPos> placed = new ArrayList<>();
        BlockPos main = mainPos(helper);
        CivilianEntity c = citizen(helper, empire, main);
        // Center the 5x5x2 room squarely inside the staked founding chunk regardless of random GameTest origin.
        BlockPos origin = new BlockPos(((main.getX() >> 4) << 4) + 3, main.getY(), ((main.getZ() >> 4) << 4) + 3);
        BlockPos bedHead = buildEnclosedHome(level, origin, placed);

        helper.runAtTickTime(5, () -> {
            try {
                EmpireSavedData data = EmpireSavedData.of(level);
                // No home -> Shelter 0.0
                MeterReading noHome = CivilianFulfilmentMeters.shelterMeter(c, data, empire);
                helper.assertTrue(Math.abs(noHome.value()) < 1e-6, "homeless citizen has 0.0 shelter, got " + noHome.value());

                HomeRegionScanner.ScanResult scan = HomeRegionScanner.scan(level, data, empire, bedHead, RegionOrigin.AUTO);
                helper.assertTrue(scan.succeeded(), "HOME scan succeeded: " + scan.failure());
                RegionRecord region = scan.region();
                helper.assertTrue(region.indoorFloorArea() == 25,
                        "5x5x2 enclosed HOME has 25 walkable indoor floor blocks, got " + region.indoorFloorArea());

                data.addRegion(region);
                region.addInhabitant(c.civilianId());
                c.setClaimedBed(bedHead);

                MeterReading oneOcc = CivilianFulfilmentMeters.shelterMeter(c, data, empire);
                helper.assertTrue(Math.abs(oneOcc.value() - 1.0) < 1e-6,
                        "1 occupant in 25 floor blocks -> Shelter 1.0, got " + oneOcc.value());

                // Add a second occupant to the same 25-floor-block HOME -> 25 / (2 * 25) = 0.5
                region.addInhabitant(UUID.randomUUID());
                MeterReading twoOcc = CivilianFulfilmentMeters.shelterMeter(c, data, empire);
                helper.assertTrue(Math.abs(twoOcc.value() - 0.5) < 1e-6,
                        "2 occupants in 25 floor blocks -> Shelter 0.5, got " + twoOcc.value());

                // Invalidating the HOME drops Shelter to 0.0
                region.markInvalid(dev.brights0ng.empire.rules.RegionInvalidReason.TOO_DARK, level.getGameTime());
                MeterReading invalidHome = CivilianFulfilmentMeters.shelterMeter(c, data, empire);
                helper.assertTrue(Math.abs(invalidHome.value()) < 1e-6,
                        "invalid HOME -> Shelter 0.0, got " + invalidHome.value());
                helper.succeed();
            } finally {
                if (!c.isRemoved()) c.discard();
                restoreAir(level, placed);
                disband(helper, empire);
            }
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH_CIVILIANS)
    public static void safetyCountsMonsterAndOutsiderHits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        Player outsider = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, founder);
        CivilianEntity c = null;
        Zombie zombie = null;
        try {
            c = citizen(helper, empire, mainPos(helper));
            zombie = EntityType.ZOMBIE.create(level);
            helper.assertTrue(zombie != null, "created zombie");
            zombie.moveTo(Vec3.atBottomCenterOf(mainPos(helper)));

            helper.assertTrue(empire.safety().hitsLast3Days() == 0, "starts with 0 safety hits");
            helper.assertTrue(Math.abs(CivilianFulfilmentMeters.safetyMeter(empire, 0.5).value() - 1.0) < 1e-6,
                    "starts at Safety 1.0");

            // Damage from a monster counts
            c.hurt(level.damageSources().mobAttack(zombie), 2.0F);
            c.invulnerableTime = 0;
            helper.assertTrue(empire.safety().hitsLast3Days() == 1, "monster hit increments safety hits");

            // Damage from an outsider player counts
            c.hurt(level.damageSources().playerAttack(outsider), 2.0F);
            c.invulnerableTime = 0;
            helper.assertTrue(empire.safety().hitsLast3Days() == 2, "outsider player hit increments safety hits");

            // Damage from an empire member does NOT count
            c.hurt(level.damageSources().playerAttack(founder), 2.0F);
            c.invulnerableTime = 0;
            helper.assertTrue(empire.safety().hitsLast3Days() == 2, "member hit does not count as safety hit");

            // Generic environmental damage (starve) does NOT count
            c.hurt(level.damageSources().starve(), 1.0F);
            c.invulnerableTime = 0;
            helper.assertTrue(empire.safety().hitsLast3Days() == 2, "starvation does not count as safety hit");

            // Safety meter reflects 2 hits / H_ref=10 -> 0.8
            double safetyVal = CivilianFulfilmentMeters.safetyMeter(empire, 0.5).value();
            helper.assertTrue(Math.abs(safetyVal - 0.8) < 1e-6, "expected safety 0.8, got " + safetyVal);

            // EmpireRecord NBT round-trip preserves safety hits
            EmpireRecord reloaded = EmpireRecord.load(empire.save());
            helper.assertTrue(reloaded.safety().hitsLast3Days() == 2, "safety hits survive EmpireRecord NBT");
            helper.succeed();
        } finally {
            if (c != null && !c.isRemoved()) c.discard();
            if (zombie != null && !zombie.isRemoved()) zombie.discard();
            disband(helper, empire);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH_CIVILIANS)
    public static void enjoymentCountsLikedFoodMeals(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, founder);
        CivilianEntity c = null;
        try {
            c = citizen(helper, empire, mainPos(helper));
            c.setPreferences(new CivilianPreferences(List.of("minecraft:bread"), List.of(Activity.WALK)));
            helper.assertTrue(c.fulfilment().likedMealsCount() == 0, "starts with 0 liked meals");

            // Eat liked food (bread)
            ItemStack bread = new ItemStack(Items.BREAD);
            c.eat(level, bread, bread.getFoodProperties(c));
            helper.assertTrue(c.fulfilment().likedMealsCount() == 1, "eating liked bread increments liked meals");

            // Eat unliked food (apple)
            ItemStack apple = new ItemStack(Items.APPLE);
            c.eat(level, apple, apple.getFoodProperties(c));
            helper.assertTrue(c.fulfilment().likedMealsCount() == 1, "eating unliked apple does not increment liked meals");

            MeterReading enjoyment = CivilianFulfilmentMeters.enjoymentMeter(c, 0.5);
            helper.assertTrue(enjoyment.source() == SourceKind.PARTIAL, "enjoyment flagged PARTIAL (activity placeholder)");
            double expected = (1.0 / 12.0) + 0.25;
            helper.assertTrue(Math.abs(enjoyment.value() - expected) < 1e-6,
                    "expected enjoyment " + expected + ", got " + enjoyment.value());
            helper.succeed();
        } finally {
            if (c != null && !c.isRemoved()) c.discard();
            disband(helper, empire);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "fulfilment_community")
    public static void communityAndSocializingRespectRadiusAndCooldown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, founder);
        CivilianEntity c1 = null;
        CivilianEntity c2 = null;
        CivilianEntity leaving = null;
        try {
            BlockPos pos = mainPos(helper);
            c1 = citizen(helper, empire, pos);
            c2 = citizen(helper, empire, pos.east(2));
            leaving = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(pos.west(2)), null);
            leaving.startLeaving();

            CivilianFulfilmentMeters.scanCommunity(level, c1, empire);
            helper.assertTrue(c1.fulfilment().distinctCommunityPeople() == 1,
                    "community counts nearby live civilian c2 only (ignores self and leaving), got "
                            + c1.fulfilment().distinctCommunityPeople());

            // Player dialogue counts once per 1200 active ticks (stops right-click spam)
            c1.interact(founder, InteractionHand.MAIN_HAND);
            c1.interact(founder, InteractionHand.MAIN_HAND);
            c1.interact(founder, InteractionHand.MAIN_HAND);
            helper.assertTrue(c1.fulfilment().socializingCount() == 1,
                    "repeated clicks within 1 hour count once, got " + c1.fulfilment().socializingCount());

            // Advance active clock by 1200 ticks -> next interaction counts
            for (int i = 0; i < CivilianFulfilment.SOCIAL_PAIR_COOLDOWN_TICKS; i++) {
                c1.food().countActiveTick();
            }
            c1.interact(founder, InteractionHand.MAIN_HAND);
            helper.assertTrue(c1.fulfilment().socializingCount() == 2,
                    "click after 1 hour counts again, got " + c1.fulfilment().socializingCount());
            helper.succeed();
        } finally {
            if (c1 != null && !c1.isRemoved()) c1.discard();
            if (c2 != null && !c2.isRemoved()) c2.discard();
            if (leaving != null && !leaving.isRemoved()) leaving.discard();
            disband(helper, empire);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH_CIVILIANS)
    public static void comfortReadsTemperatureProviderAndPlaceholderFallback(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, founder);
        CivilianEntity c = null;
        try {
            c = citizen(helper, empire, mainPos(helper));

            // 1. Check the real installed TemperatureProvider (Meteodyne in aggregate/coreless; NONE in bare Empire)
            TemperatureProvider installed = TemperatureProvider.get();
            boolean sampledReal = CivilianFulfilmentMeters.sampleComfort(level, c);
            if (installed != TemperatureProvider.NONE && Double.isFinite(installed.air(level, c.blockPosition()))) {
                helper.assertTrue(sampledReal, "Meteodyne present: sampleComfort reads real civilian temperature");
                MeterReading live = CivilianFulfilmentMeters.comfortMeter(c, 0.5);
                helper.assertTrue(live.source() == SourceKind.LIVE, "Meteodyne present: Comfort meter is LIVE");
            } else {
                helper.assertFalse(sampledReal, "No climate provider: sampleComfort returns false");
                MeterReading fallback = CivilianFulfilmentMeters.comfortMeter(c, 0.5);
                helper.assertTrue(fallback.isPlaceholder(), "No climate provider: Comfort is PLACEHOLDER");
                helper.assertTrue(Math.abs(fallback.value() - 0.5) < 1e-6, "Comfort placeholder is 0.5");
            }

            // 2. Verify explicit TemperatureProvider sampling math (20 C -> 1.0, -5 C -> 0.0)
            CivilianEntity c2 = citizen(helper, empire, mainPos(helper));
            try {
                TemperatureProvider warm = new TemperatureProvider() {
                    @Override public double air(ServerLevel lvl, BlockPos pos) { return 20.0; }
                    @Override public double room(ServerLevel lvl, Vec3 at) { return 0.0; }
                };
                TemperatureProvider freezing = new TemperatureProvider() {
                    @Override public double air(ServerLevel lvl, BlockPos pos) { return -5.0; }
                    @Override public double room(ServerLevel lvl, Vec3 at) { return 0.0; }
                };
                helper.assertTrue(CivilianFulfilmentMeters.sampleComfort(level, c2, warm), "warm sample recorded");
                helper.assertTrue(Math.abs(CivilianFulfilmentMeters.comfortMeter(c2, 0.5).value() - 1.0) < 1e-6,
                        "20 C gives 1.0 comfort");
                helper.assertTrue(CivilianFulfilmentMeters.sampleComfort(level, c2, freezing), "freezing sample recorded");
                helper.assertTrue(Math.abs(CivilianFulfilmentMeters.comfortMeter(c2, 0.5).value() - 0.5) < 1e-6,
                        "avg(1.0, 0.0) gives 0.5 comfort");
            } finally {
                if (!c2.isRemoved()) c2.discard();
            }
            helper.succeed();
        } finally {
            if (c != null && !c.isRemoved()) c.discard();
            disband(helper, empire);
        }
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH_CIVILIANS)
    public static void grievanceBuildsWhenOnlinePausesOfflineAndDebugFormats(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, founder);
        CivilianEntity c = null;
        try {
            c = citizen(helper, empire, mainPos(helper));
            // Starve citizen so Survival/Happiness/Fulfilment are very low (< 0.75)
            c.setFood(0, 0.0F);
            c.setLastNight(0.0F);
            Evaluation low = CivilianFulfilmentMeters.evaluate(level, c);
            helper.assertTrue(low.fulfilment() < FulfilmentModel.DEFAULT_GRIEVANCE_THRESHOLD,
                    "starving sleepless citizen has fulfilment < 0.75, got " + low.fulfilment());

            // Offline -> grievance stays paused at 0
            CivilianFulfilmentMeters.stepSlot(level, c, 24_000L, false);
            helper.assertTrue(Math.abs(c.fulfilment().grievance()) < 1e-6,
                    "offline grievance stays paused at 0, got " + c.fulfilment().grievance());

            // Online -> grievance builds
            CivilianFulfilmentMeters.stepSlot(level, c, 24_000L, true);
            float built = c.fulfilment().grievance();
            helper.assertTrue(built > 0.1F, "online low fulfilment builds grievance, got " + built);

            // Recover food + sleep -> for n=1 (E=0.20), Happiness > 0.15 easily gives Fulfilment > 0.75 -> drains!
            c.setFood(20, 5.0F);
            c.setLastNight(1.0F);
            CivilianFulfilmentMeters.stepSlot(level, c, 24_000L, true);
            helper.assertTrue(c.fulfilment().grievance() < built,
                    "high fulfilment drains grievance when online: " + built + " -> " + c.fulfilment().grievance());

            // Debug report includes placeholders and summary metrics
            List<Component> report = CivilianFulfilmentMeters.formatDebugReport(level, c);
            String joined = String.join("\n", report.stream().map(Component::getString).toList());
            helper.assertTrue(joined.contains("Happiness:"), "report contains Happiness");
            helper.assertTrue(joined.contains("Fulfilment:"), "report contains Fulfilment");
            helper.assertTrue(joined.contains("[PLACEHOLDER]"), "report flags Vocation placeholder");
            helper.assertTrue(joined.contains("[PARTIAL]"), "report flags Enjoyment partial placeholder");

            // Tolerant NBT codec survives empty/corrupt tags
            CivilianFulfilment.Saved loaded = CivilianFulfilmentNbt.load(new CompoundTag(),
                    () -> CivilianPreferences.defaultFor(UUID.randomUUID()));
            helper.assertTrue(!loaded.preferences().favouriteFoods().isEmpty(), "empty NBT falls back to valid preferences");
            helper.succeed();
        } finally {
            if (c != null && !c.isRemoved()) c.discard();
            disband(helper, empire);
        }
    }
}
