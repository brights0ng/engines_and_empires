// Written by Claude.
package dev.brights0ng.empire.gametest;

import java.util.List;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.rules.CivilianNames;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Civilians: what a spawn rolls and keeps, the dialogue click and the debug spawn command. In their own batch, shared
 * with {@link CivilianRosterGameTests}, so no other test's empire or citizens are about while they run.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class CivilianGameTests {

    private static final String BATCH = "civilians";

    private static String name(CivilianEntity civilian) {
        Component name = civilian.getCustomName();
        return name != null ? name.getString() : null;
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void spawnRollsLookNameAndHome(GameTestHelper helper) {
        BlockPos spawn = helper.absolutePos(new BlockPos(1, 1, 1));
        CivilianEntity civilian = CivilianSpawning.spawn(helper.getLevel(), Vec3.atBottomCenterOf(spawn), null);

        String name = name(civilian);
        helper.assertTrue(name != null && CivilianNames.NAMES.contains(name), "name from the list, got " + name);
        helper.assertTrue(civilian.isCustomNameVisible(), "name always shown");
        helper.assertTrue(civilian.isPersistenceRequired(), "persistence required");
        helper.assertFalse(civilian.removeWhenFarAway(1e6), "never despawns when far away");
        helper.assertTrue(spawn.equals(civilian.home()), "home is the spawn block, got " + civilian.home());
        helper.assertTrue(spawn.equals(civilian.getRestrictCenter()), "restricted around home");
        helper.assertTrue(civilian.getRestrictRadius() == CivilianEntity.HOME_RADIUS, "restrict radius 16");
        civilian.discard();
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void saveLoadKeepsLookNameHome(GameTestHelper helper) {
        BlockPos spawn = helper.absolutePos(new BlockPos(1, 1, 1));
        CivilianEntity civilian = CivilianSpawning.spawn(helper.getLevel(), Vec3.atBottomCenterOf(spawn), null);
        CompoundTag tag = civilian.saveWithoutId(new CompoundTag());
        civilian.discard();

        CivilianEntity loaded = EmpireContent.CIVILIAN.get().create(helper.getLevel());
        helper.assertTrue(loaded != null, "civilian created");
        loaded.load(tag);
        helper.assertTrue(civilian.appearance().equals(loaded.appearance()),
                "look kept: " + civilian.appearance().code() + " vs " + loaded.appearance().code());
        helper.assertTrue(name(civilian).equals(name(loaded)), "name kept");
        helper.assertTrue(spawn.equals(loaded.home()), "home kept");
        helper.assertTrue(spawn.equals(loaded.getRestrictCenter()), "restriction re-applied");
        helper.assertTrue(loaded.getRestrictRadius() == CivilianEntity.HOME_RADIUS, "restrict radius kept");
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void rightClickStartsDialogue(GameTestHelper helper) {
        BlockPos spawn = helper.absolutePos(new BlockPos(1, 1, 1));
        CivilianEntity civilian = CivilianSpawning.spawn(helper.getLevel(), Vec3.atBottomCenterOf(spawn), null);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        InteractionResult result = civilian.interact(player, InteractionHand.MAIN_HAND);
        helper.assertTrue(result.consumesAction(), "the click is consumed, got " + result);
        helper.assertTrue(player.getUUID().equals(civilian.dialoguePlayer()), "dialogue with the clicking player");
        civilian.discard();
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = BATCH)
    public static void debugSpawnCommand(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Vec3 pos = helper.absoluteVec(new Vec3(1.5, 1.0, 1.5));
        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(pos);
        server.getCommands().performPrefixedCommand(source, "empire debug civilian spawn 3 look 1111111111");

        List<CivilianEntity> spawned = helper.getLevel().getEntitiesOfClass(CivilianEntity.class,
                new AABB(pos, pos).inflate(1.0), c -> c.appearance().code().equals("1111111111"));
        helper.assertTrue(spawned.size() == 3, "3 civilians with look 1111111111, got " + spawned.size());
        spawned.forEach(CivilianEntity::discard);
        helper.succeed();
    }

    private CivilianGameTests() {
    }
}
