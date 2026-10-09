package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;

import dev.brights0ng.enginesandempires.weather.sky.StormLight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * A block's light with the sky dimmed, as gameplay sees it (monster spawning, undead burning, mobs' light checks),
 * with the storm over it dimming the sky there (weather phase 6d, {@link StormLight}). Vanilla's version is a default
 * method of {@code LevelReader} using the level's one global sky darkening; this gives {@code Level} its own, which
 * asks the darkening at the block. Only server levels in the pack's weather differ from vanilla.
 */
@Mixin(Level.class)
public abstract class LevelStormLightMixin {

    public int getMaxLocalRawBrightness(BlockPos pos) {
        Level self = (Level) (Object) this;
        return self.getMaxLocalRawBrightness(pos, StormLight.skyDarken(self, pos));
    }
}
