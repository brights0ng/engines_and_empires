package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.enginesandempires.weather.surface.GlazeBlock;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Glaze is slippery (phase 5c). Vanilla takes an entity's friction (and speed factor) from the block about half a block
 * below its feet, which on a glaze sheet one or two pixels thick is the ground under it, not the glaze. So an entity
 * standing in a glaze block's space reads the glaze. (Full-height glazed snow is already the block below the feet.)
 *
 * <p>Targets {@code Entity} (players, mobs, everything living) and {@code ItemEntity}, the only class in 1.21.1 that
 * overrides the method (items look almost a whole block down).
 *
 * <p>On a ship (phase 5d): the glaze is in Sable's plot, not where the entity stands in the world, so the entity's feet
 * are taken into the ship's frame of the ship it is standing on.
 */
@Mixin({Entity.class, ItemEntity.class})
public abstract class EntityGlazeFrictionMixin {

    @Inject(method = "getBlockPosBelowThatAffectsMyMovement", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$glaze(CallbackInfoReturnable<BlockPos> cir) {
        Entity self = (Entity) (Object) this;
        if (!self.onGround()) {
            return;
        }
        BlockPos feet = self.blockPosition();
        SubLevel ship = Sable.HELPER.getTrackingSubLevel(self);
        if (ship != null) {
            Vec3 local = ship.logicalPose().transformPositionInverse(self.position());
            feet = BlockPos.containing(local.x, local.y + 1.0E-4, local.z);
        }
        if (self.level().getBlockState(feet).getBlock() instanceof GlazeBlock) {
            cir.setReturnValue(feet);
        }
    }
}
