// Written by Claude.
package dev.brights0ng.empire.civilian;

import javax.annotation.Nullable;

import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.rules.CivilianAppearance;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

/** Puts a civilian into the world the way the debug commands and the game tests do. */
public final class CivilianSpawning {

    /** Spawns a civilian at {@code pos}; {@code appearance} null rolls a random look. */
    public static CivilianEntity spawn(ServerLevel level, Vec3 pos, @Nullable CivilianAppearance appearance) {
        CivilianEntity civilian = EmpireContent.CIVILIAN.get().create(level);
        if (civilian == null) {
            throw new IllegalStateException("could not create a civilian");
        }
        if (appearance != null) {
            civilian.presetAppearance(appearance);
        }
        civilian.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360.0F, 0.0F);
        civilian.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(pos)), MobSpawnType.COMMAND, null);
        level.addFreshEntity(civilian);
        return civilian;
    }

    private CivilianSpawning() {
    }
}
