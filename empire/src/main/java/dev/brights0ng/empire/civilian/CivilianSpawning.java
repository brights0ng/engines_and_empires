// Written by Claude.
package dev.brights0ng.empire.civilian;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.CivilianAppearance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Puts a civilian into the world: the debug commands, the game tests, travellers and replacement bodies. */
public final class CivilianSpawning {

    /** A replacement body appears this far east of a bonfire home, so not in the fire. */
    private static final int HOME_SPAWN_OFFSET = 2;

    /** Spawns a free civilian at {@code pos}; {@code appearance} null rolls a random look. */
    public static CivilianEntity spawn(ServerLevel level, Vec3 pos, @Nullable CivilianAppearance appearance) {
        return spawn(level, pos, appearance, civilian -> {
        });
    }

    /**
     * Spawns a civilian at {@code pos}; {@code beforeAdd} sets it up after its spawn rolls and before it joins the level
     * (a traveller's or citizen's slot or record must name it by then, or {@link CivilianRoster} refuses it).
     */
    public static CivilianEntity spawn(ServerLevel level, Vec3 pos, @Nullable CivilianAppearance appearance,
                                       Consumer<CivilianEntity> beforeAdd) {
        CivilianEntity civilian = EmpireContent.CIVILIAN.get().create(level);
        if (civilian == null) {
            throw new IllegalStateException("could not create a civilian");
        }
        if (appearance != null) {
            civilian.presetAppearance(appearance);
        }
        civilian.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360.0F, 0.0F);
        civilian.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(pos)), MobSpawnType.COMMAND, null);
        beforeAdd.accept(civilian);
        level.addFreshEntity(civilian);
        return civilian;
    }

    /**
     * A new body for a citizen whose body is lost (Bright, D6): a citizen with the record's id, name and look, at home if
     * the home chunk is entity-ticking, else at the record's last position. The record names the new body.
     */
    public static CivilianEntity respawnBody(CivilianRecord record, ServerLevel level) {
        EmpireSavedData data = EmpireSavedData.of(level);
        EmpireRecord empire = data.getEmpire(record.empire());
        BlockPos home = empire != null ? CivilianRoster.homeFor(empire, level, record.lastPos().getY()) : record.lastPos();
        Vec3 pos = level.isPositionEntityTicking(home)
                ? Vec3.atBottomCenterOf(level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, home.east(HOME_SPAWN_OFFSET)))
                : Vec3.atBottomCenterOf(record.lastPos());
        CivilianAppearance look;
        try {
            look = CivilianAppearance.unpack(record.appearance());
        } catch (IllegalArgumentException e) {
            look = null;
        }
        return spawn(level, pos, look, civilian -> {
            civilian.setCustomName(Component.literal(record.name()));
            civilian.becomeCitizen(record.id(), home);
            record.setBody(civilian.getUUID());
            data.setDirty();
        });
    }

    private CivilianSpawning() {
    }
}
