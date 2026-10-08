package dev.brights0ng.enginesandempires.frontier.spawn;

import dev.brights0ng.enginesandempires.frontier.Tier;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevels;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Whether it is dark enough for a monster to spawn, the Frontier way: below y 0 it always is, and above it only daylight
 * counts (vanilla's check with block light left out). Everywhere that is not Frontier, vanilla decides.
 */
public final class FrontierDarkness {

    /** The answer in Frontier land, or null to let vanilla decide. */
    public static Boolean override(ServerLevelAccessor accessor, BlockPos pos, RandomSource random) {
        // Only the live level, on its own thread: world generation's spawns run elsewhere and are left to vanilla.
        if (!(accessor instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return null;
        }
        FrontierLevel frontier = FrontierLevels.of(level);
        if (frontier == null || frontier.tierAt(pos) != Tier.FRONTIER) {
            return null;
        }
        if (pos.getY() < frontier.params().frontierBelowY()) {
            return true;
        }
        return darkBySkyAlone(level, pos, random);
    }

    /** Vanilla's {@code Monster.isDarkEnoughToSpawn}, with its block-light steps taken out. */
    static boolean darkBySkyAlone(ServerLevel level, BlockPos pos, RandomSource random) {
        int sky = level.getBrightness(LightLayer.SKY, pos);
        if (sky > random.nextInt(32)) {
            return false;
        }
        // Thunder darkens the sky only under a thunderstorm (weather phase 6a), as in MonsterThunderMixin.
        boolean thunder = dev.brights0ng.enginesandempires.weather.WeatherOwnership.owns(level)
                ? dev.brights0ng.enginesandempires.weather.rain.WeatherQueries.thunderOver(level, pos)
                : level.isThundering();
        int darkened = sky - (thunder ? 10 : level.getSkyDarken());
        DimensionType type = level.dimensionType();
        return Math.max(0, darkened) <= type.monsterSpawnLightTest().sample(random);
    }

    private FrontierDarkness() {
    }
}
