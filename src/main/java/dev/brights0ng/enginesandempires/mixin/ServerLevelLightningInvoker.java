package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Vanilla's strike targeting (lightning rods within 128 blocks, then living things around the column). */
@Mixin(ServerLevel.class)
public interface ServerLevelLightningInvoker {

    @Invoker("findLightningTargetAround")
    BlockPos engines_and_empires$findLightningTargetAround(BlockPos pos);
}
