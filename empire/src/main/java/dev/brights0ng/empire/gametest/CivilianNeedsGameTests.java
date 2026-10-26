// Written by ChatGPT; empire setup and cleanup adapted from Claude's CivilianRosterGameTests.
package dev.brights0ng.empire.gametest;

import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.civilian.TravellerArrivals;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.CivilianAbilities;
import dev.brights0ng.empire.rules.TravellerWants;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class CivilianNeedsGameTests {
    private static final String BATCH = "civilians";
    private static final String KNOWN_CODE = "12-9-11-10-14-8";

    private static BlockPos mainPos(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(1, 1, 1));
    }

    private static EmpireRecord found(GameTestHelper helper, Player founder) {
        BlockPos main = mainPos(helper);
        return EmpireSavedData.of(helper.getLevel()).createEmpire(founder.getUUID(),
                ChunkKeys.pack(main.getX() >> 4, main.getZ() >> 4), main, helper.getLevel().getGameTime());
    }

    private static void disband(GameTestHelper helper, EmpireRecord empire) {
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel());
        data.disbandEmpire(empire.id(), null);
        data.onBonfireRemoved(mainPos(helper), null);
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void recruitCopiesAbilities(GameTestHelper helper) {
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity traveller = TravellerArrivals.spawnTraveller(empire, helper.getLevel(), mainPos(helper).east());
        try {
            CivilianAbilities rolled = traveller.abilities();
            CivilianRecord record = CivilianRoster.recruit(traveller, empire, helper.getLevel());
            helper.assertTrue(rolled.pack() == record.abilities(), "recruit copies traveller scores");
            traveller.setAbilities(CivilianAbilities.parse(KNOWN_CODE));
            helper.assertTrue(record.abilities() == traveller.abilities().pack(), "setter writes through");
            helper.assertTrue(CivilianRecord.load(record.save()).abilities() == record.abilities(), "record NBT round trip");
        } finally {
            traveller.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void respawnKeepsAbilities(GameTestHelper helper) {
        EmpireRecord empire = found(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        CivilianEntity civilian = CivilianSpawning.spawn(helper.getLevel(), Vec3.atBottomCenterOf(mainPos(helper).east()), null);
        CivilianRecord record = CivilianRoster.recruit(civilian, empire, helper.getLevel());
        CivilianEntity replacement = null;
        try {
            civilian.setAbilities(CivilianAbilities.parse(KNOWN_CODE));
            UUID oldBody = civilian.getUUID();
            civilian.discard();
            CivilianRoster.runMissingCheck(helper.getLevel());
            helper.assertTrue(record.body().equals(oldBody), "first check keeps the missing body");
            CivilianRoster.runMissingCheck(helper.getLevel());
            helper.assertFalse(record.body().equals(oldBody), "second check replaces the body");
            helper.assertTrue(helper.getLevel().getEntity(record.body()) instanceof CivilianEntity, "replacement exists");
            replacement = (CivilianEntity) helper.getLevel().getEntity(record.body());
            helper.assertTrue(KNOWN_CODE.equals(replacement.abilities().code()), "replacement keeps scores");
        } finally {
            civilian.discard();
            if (replacement != null) replacement.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void v1RecordGetsStableAbilities(GameTestHelper helper) {
        UUID id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        CompoundTag tag = new CivilianRecord(id, UUID.randomUUID(), UUID.randomUUID(), "Legacy", 0,
                BlockPos.ZERO, 0, 0).save();
        tag.putInt("version", 1);
        tag.remove("abilities");
        CivilianRecord first = CivilianRecord.load(tag);
        CivilianRecord second = CivilianRecord.load(tag);
        helper.assertTrue(first.abilities() == second.abilities(), "legacy migration is stable");
        int expected = CivilianAbilities.roll(new java.util.Random(id.getMostSignificantBits() ^ id.getLeastSignificantBits())).pack();
        helper.assertTrue(first.abilities() == expected, "legacy migration uses the civilian id seed");
        tag.putInt("abilities", -1);
        helper.assertTrue(CivilianRecord.load(tag).abilities() == expected, "corrupt scores use the stable default");
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void entitySavesAbilitiesAndRepairsMissingOrCorrupt(GameTestHelper helper) {
        CivilianEntity civilian = EmpireContent.CIVILIAN.get().create(helper.getLevel());
        civilian.setAbilities(CivilianAbilities.parse(KNOWN_CODE));
        CompoundTag tag = civilian.saveWithoutId(new CompoundTag());
        CivilianEntity loaded = EmpireContent.CIVILIAN.get().create(helper.getLevel());
        loaded.load(tag);
        helper.assertTrue(KNOWN_CODE.equals(loaded.abilities().code()), "entity NBT keeps scores");
        for (boolean corrupt : new boolean[]{false, true}) {
            tag.remove("Abilities");
            if (corrupt) tag.putInt("Abilities", -1);
            loaded.load(tag);
            helper.assertTrue(loaded.abilities().mean() >= 8 && loaded.abilities().mean() <= 13, "missing/corrupt scores rerolled");
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void sneakInspectsWithoutRecruitingOrDialogue(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord empire = found(helper, player);
        CivilianEntity traveller = TravellerArrivals.spawnTraveller(empire, helper.getLevel(), mainPos(helper).east());
        try {
            player.setShiftKeyDown(true);
            helper.assertTrue(player.isSecondaryUseActive(), "mock player can sneak");
            TravellerWants.Want want = TravellerWants.WANTS.get(traveller.want());
            ItemStack food = new ItemStack(CivilianRoster.item(want), want.count());
            player.setItemInHand(InteractionHand.MAIN_HAND, food);
            helper.assertTrue(traveller.interact(player, InteractionHand.MAIN_HAND).consumesAction(), "inspection succeeds");
            helper.assertTrue(food.getCount() == want.count(), "inspection consumes no food");
            helper.assertTrue(traveller.status() == CivilianEntity.Status.TRAVELLER, "inspection does not recruit");
            helper.assertTrue(traveller.dialoguePlayer() == null, "inspection starts no dialogue");
            String name = traveller.getName().getString();
            ItemStack tag = new ItemStack(Items.NAME_TAG);
            tag.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Rename"));
            player.setItemInHand(InteractionHand.MAIN_HAND, tag);
            player.interactOn(traveller, InteractionHand.MAIN_HAND);
            helper.assertTrue(tag.getCount() == 1 && name.equals(traveller.getName().getString()), "name tag inspection preserves item and name");
            helper.assertTrue(traveller.dialoguePlayer() == null, "name tag inspection starts no dialogue");
        } finally {
            traveller.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    private CivilianNeedsGameTests() {
    }
}
