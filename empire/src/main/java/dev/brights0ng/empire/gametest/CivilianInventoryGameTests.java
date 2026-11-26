// Written by Gemini.
package dev.brights0ng.empire.gametest;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianInventoryMenu;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.CivilianTraits;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Integration tests for {@code /empire debug civilian inv}, {@link CivilianInventoryMenu}, live read/write edits,
 * active-meal cancellation on hand edit, shift-click routing, and reach/liveness auto-close rules.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class CivilianInventoryGameTests {

    private static final String BATCH = "civilians";

    private static BlockPos mainPos(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(2, 2, 2));
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

    private static CivilianEntity bodyAt(GameTestHelper helper, BlockPos pos) {
        CivilianEntity c = CivilianSpawning.spawn(helper.getLevel(), Vec3.atBottomCenterOf(pos), null);
        c.setTraits(CivilianTraits.NEUTRAL);
        c.setNoAi(true);
        c.setNoGravity(true);
        return c;
    }

    private static ServerPlayer creativePlayerAt(GameTestHelper helper, Vec3 pos) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "civ_inv_test"), ClientInformation.createDefault());
        // Reuse NeoForge's no-op FakePlayerNetHandler so openMenu runs the real ServerPlayer path without triggering
        // PlayerList.placeNewPlayer (where third-party mods attempt client-bound login payloads on embedded channels).
        player.connection = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "civ_inv_conn")).connection;
        player.setGameMode(GameType.CREATIVE);
        player.setPos(pos.x, pos.y, pos.z);
        return player;
    }

    private static void runAsPlayer(ServerLevel level, ServerPlayer player, String command) {
        MinecraftServer server = level.getServer();
        CommandSourceStack source = player.createCommandSourceStack()
                .withPermission(2)
                .withLevel(level)
                .withPosition(player.position());
        server.getCommands().performPrefixedCommand(source, command);
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void debugInvCommandOpensLiveMenuAndSyncsEdits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = mainPos(helper);
        ServerPlayer player = creativePlayerAt(helper, Vec3.atBottomCenterOf(pos));
        EmpireRecord empire = found(helper, player);
        CivilianEntity civilian = bodyAt(helper, pos.east());
        try {
            civilian.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_HOE));
            civilian.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TORCH, 4));
            civilian.inventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 12));
            civilian.inventory().setItem(8, new ItemStack(Items.BREAD, 3));
            CivilianRecord record = CivilianRoster.recruit(civilian, empire, level);

            runAsPlayer(level, player, "empire debug civilian inv");

            helper.assertTrue(player.containerMenu instanceof CivilianInventoryMenu,
                    "command opened CivilianInventoryMenu, got " + player.containerMenu.getClass().getSimpleName());
            CivilianInventoryMenu menu = (CivilianInventoryMenu) player.containerMenu;
            helper.assertTrue(menu.slots.size() == CivilianInventoryMenu.PLAYER_INV_END,
                    "menu has exactly 11 civilian slots + 36 player slots, got " + menu.slots.size());
            helper.assertTrue(civilian.dialoguePlayer() == null, "opening debug inv does not pause civilian in dialogue");

            // Initial live contents visible in slots 0 (main), 1 (offhand), 2..10 (pockets)
            helper.assertTrue(menu.getSlot(CivilianInventoryMenu.SLOT_MAIN_HAND).getItem().is(Items.IRON_HOE),
                    "slot 0 shows main hand");
            helper.assertTrue(menu.getSlot(CivilianInventoryMenu.SLOT_OFF_HAND).getItem().is(Items.TORCH)
                    && menu.getSlot(CivilianInventoryMenu.SLOT_OFF_HAND).getItem().getCount() == 4,
                    "slot 1 shows offhand");
            helper.assertTrue(menu.getSlot(CivilianInventoryMenu.FIRST_POCKET).getItem().is(Items.WHEAT_SEEDS)
                    && menu.getSlot(CivilianInventoryMenu.FIRST_POCKET).getItem().getCount() == 12,
                    "slot 2 shows pocket 0");
            helper.assertTrue(menu.getSlot(CivilianInventoryMenu.FIRST_POCKET + 8).getItem().is(Items.BREAD)
                    && menu.getSlot(CivilianInventoryMenu.FIRST_POCKET + 8).getItem().getCount() == 3,
                    "slot 10 shows pocket 8");

            // Direct writes through menu slots mutate live entity and flush pockets to master record
            menu.getSlot(CivilianInventoryMenu.SLOT_MAIN_HAND).set(new ItemStack(Items.DIAMOND_HOE));
            menu.getSlot(CivilianInventoryMenu.SLOT_OFF_HAND).set(new ItemStack(Items.SHIELD));
            menu.getSlot(CivilianInventoryMenu.FIRST_POCKET + 3).set(new ItemStack(Items.CARROT, 9));

            helper.assertTrue(civilian.getMainHandItem().is(Items.DIAMOND_HOE), "main hand updated live");
            helper.assertTrue(civilian.getOffhandItem().is(Items.SHIELD), "offhand updated live");
            helper.assertTrue(civilian.inventory().getItem(3).is(Items.CARROT)
                    && civilian.inventory().getItem(3).getCount() == 9, "pocket 3 updated live");
            helper.assertTrue(record.inventory().get(3).is(Items.CARROT)
                    && record.inventory().get(3).getCount() == 9, "pocket edit flushed immediately to CivilianRecord");

            // Live entity mutation is immediately reflected in the open menu
            civilian.inventory().setItem(5, new ItemStack(Items.POTATO, 7));
            helper.assertTrue(menu.getSlot(CivilianInventoryMenu.FIRST_POCKET + 5).getItem().is(Items.POTATO)
                    && menu.getSlot(CivilianInventoryMenu.FIRST_POCKET + 5).getItem().getCount() == 7,
                    "live entity inventory changes show up immediately in open menu");
        } finally {
            player.closeContainer();
            civilian.discard();
            disband(helper, empire);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void editingMainHandCancelsActiveMealCleanly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = mainPos(helper);
        ServerPlayer player = creativePlayerAt(helper, Vec3.atBottomCenterOf(pos));
        CivilianEntity civilian = bodyAt(helper, pos.east());
        try {
            civilian.setFood(10, 0.0F);
            civilian.inventory().setItem(0, new ItemStack(Items.BREAD, 2));
            for (int i = 0; i < 20; i++) {
                level.tickNonPassenger(civilian);
            }
            helper.assertTrue(civilian.isUsingItem() && civilian.getMainHandItem().is(Items.BREAD)
                    && civilian.inventory().countItem(Items.BREAD) == 1,
                    "civilian started eating 1 bread from pockets");

            runAsPlayer(level, player, "empire debug civilian inv");
            CivilianInventoryMenu menu = (CivilianInventoryMenu) player.containerMenu;

            // Taking the actively-eaten bread directly out of the main-hand slot stops eating without duplicating it
            ItemStack taken = menu.getSlot(CivilianInventoryMenu.SLOT_MAIN_HAND).remove(1);
            helper.assertTrue(taken.is(Items.BREAD) && taken.getCount() == 1, "player took the 1 bread from main hand");
            helper.assertFalse(civilian.isUsingItem(), "eating stopped cleanly");
            helper.assertTrue(civilian.getMainHandItem().isEmpty(), "main hand is now empty");
            helper.assertTrue(civilian.inventory().countItem(Items.BREAD) == 1,
                    "remaining bread count is exactly 1 (no duplication)");

            // Replacing main hand with a tool while eating also stops eating and keeps the tool
            for (int i = 0; i < 20; i++) {
                level.tickNonPassenger(civilian);
            }
            helper.assertTrue(civilian.isUsingItem() && civilian.getMainHandItem().is(Items.BREAD),
                    "civilian began eating the second bread");
            menu.getSlot(CivilianInventoryMenu.SLOT_MAIN_HAND).set(new ItemStack(Items.STONE_HOE));
            helper.assertFalse(civilian.isUsingItem(), "replacing main hand cancelled active meal");
            helper.assertTrue(civilian.getMainHandItem().is(Items.STONE_HOE), "main hand holds the placed hoe");
            helper.assertTrue(civilian.inventory().countItem(Items.BREAD) == 0,
                    "replaced meal was not duplicated back into pockets");
        } finally {
            player.closeContainer();
            civilian.discard();
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void stillValidClosesWhenOutOfReachOrDead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = mainPos(helper);
        ServerPlayer player = creativePlayerAt(helper, Vec3.atBottomCenterOf(pos));
        player.setGameMode(GameType.SURVIVAL); // 3.0 entity reach + 4.0 buffer = 7.0 blocks
        CivilianEntity civilian = bodyAt(helper, pos.east(2));
        try {
            runAsPlayer(level, player, "empire debug civilian inv");
            helper.assertTrue(player.containerMenu instanceof CivilianInventoryMenu, "menu opened in survival reach");
            CivilianInventoryMenu menu = (CivilianInventoryMenu) player.containerMenu;
            helper.assertTrue(menu.stillValid(player), "menu valid at 2 blocks");

            // Move civilian beyond normal entity interaction distance (3 + 4 = 7 blocks)
            civilian.setPos(pos.getX() + 12.5, pos.getY(), pos.getZ() + 0.5);
            helper.assertFalse(menu.stillValid(player), "menu invalid when civilian walks out of reach");

            // Bring back in reach, then remove/kill civilian
            civilian.setPos(pos.getX() + 2.5, pos.getY(), pos.getZ() + 0.5);
            helper.assertTrue(menu.stillValid(player), "menu valid again in reach");
            civilian.discard();
            helper.assertFalse(menu.stillValid(player), "menu invalid once civilian is removed");
        } finally {
            player.closeContainer();
            civilian.discard();
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void quickMoveFillsPocketsBeforeHandsAndMovesBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = mainPos(helper);
        ServerPlayer player = creativePlayerAt(helper, Vec3.atBottomCenterOf(pos));
        CivilianEntity civilian = bodyAt(helper, pos.east());
        try {
            runAsPlayer(level, player, "empire debug civilian inv");
            CivilianInventoryMenu menu = (CivilianInventoryMenu) player.containerMenu;

            // Put apples in player hotbar slot 0 (menu index PLAYER_INV_START + 27)
            int hotbarZeroIndex = CivilianInventoryMenu.PLAYER_INV_START + 27;
            player.getInventory().setItem(0, new ItemStack(Items.APPLE, 5));
            menu.quickMoveStack(player, hotbarZeroIndex);
            helper.assertTrue(civilian.inventory().getItem(0).is(Items.APPLE)
                    && civilian.inventory().getItem(0).getCount() == 5,
                    "shift-click from player fills civilian pocket 0 first");
            helper.assertTrue(civilian.getMainHandItem().isEmpty(), "hands untouched while pockets have room");

            // Fill all 9 pockets, then shift-click a hoe -> should fall back to main hand
            for (int slot = 0; slot < CivilianEntity.INVENTORY_SIZE; slot++) {
                civilian.inventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            player.getInventory().setItem(1, new ItemStack(Items.IRON_HOE));
            menu.quickMoveStack(player, hotbarZeroIndex + 1);
            helper.assertTrue(civilian.getMainHandItem().is(Items.IRON_HOE),
                    "shift-click falls back to main hand when all 9 pockets are full");

            // Shift-click main hand back to player inventory
            menu.quickMoveStack(player, CivilianInventoryMenu.SLOT_MAIN_HAND);
            helper.assertTrue(civilian.getMainHandItem().isEmpty(), "shift-click emptied civilian main hand");
            helper.assertTrue(player.getInventory().countItem(Items.IRON_HOE) == 1,
                    "hoe returned to player inventory");
        } finally {
            player.closeContainer();
            civilian.discard();
        }
        helper.succeed();
    }

    private CivilianInventoryGameTests() {
    }
}
