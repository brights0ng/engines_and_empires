package dev.brights0ng.enginesandempires.mixin.compat.sable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import dev.ryanhcode.sable.platform.SablePlotPlatform;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * A fix for Sable 2.0.5's plot saving (weather phase 8f-5, {@code claude/weather-backbone-phase8f.md}; Bright,
 * 2026-10-15): {@code ServerLevelPlot.save} writes each plot chunk's NeoForge attachments and auxiliary light data
 * into the plot's own tag (where the next chunk overwrites them) instead of the chunk's, while {@code load} reads them
 * from the chunk's. So nothing a mod attached to a ship's chunks survived a reload; the heat simulation keeps a ship's
 * room temperatures there. Both are sent to the chunk's tag instead.
 */
@Mixin(value = ServerLevelPlot.class, remap = false)
public abstract class PlotChunkAttachmentSaveMixin {

    @WrapOperation(method = "save", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/platform/SablePlotPlatform;writeChunkAttachments(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void engines_and_empires$attachmentsIntoTheChunk(SablePlotPlatform platform, CompoundTag plotTag,
                                                             RegistryAccess registries, LevelChunk chunk,
                                                             Operation<Void> original,
                                                             @Local(name = "chunkTag") CompoundTag chunkTag) {
        original.call(platform, chunkTag, registries, chunk);
    }

    @WrapOperation(method = "save", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/platform/SablePlotPlatform;writeLightData(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void engines_and_empires$lightIntoTheChunk(SablePlotPlatform platform, CompoundTag plotTag,
                                                       RegistryAccess registries, LevelChunk chunk,
                                                       Operation<Void> original,
                                                       @Local(name = "chunkTag") CompoundTag chunkTag) {
        original.call(platform, chunkTag, registries, chunk);
    }
}
