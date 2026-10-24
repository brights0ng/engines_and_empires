// Written by Gemini; GeckoLib animatable (for the bonfire model), clearing the roar when put out and putting out non-main bonfires added by Claude.
package dev.brights0ng.empire.bonfire;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import dev.brights0ng.empire.EmpireContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * The bonfire's block entity. A GeckoLib animatable only so the client can draw the 3x3x3 model; it has no animation
 * controllers (the flames move through the texture's frames, and lit/roaring is read from the block state).
 */
public class BonfireBlockEntity extends BlockEntity implements GeoBlockEntity {

    public static final int ROAR_DURATION_TICKS = 600;
    /** How often, in ticks, a lit bonfire checks whether another bonfire is its empire's main one (and goes out if so). */
    public static final int NOT_MAIN_CHECK_TICKS = 40;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private int roarTicks;
    private boolean everLit;
    @Nullable
    private UUID empire;

    public BonfireBlockEntity(BlockPos pos, BlockState state) {
        super(EmpireContent.BONFIRE_BE.get(), pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // None: see the class comment.
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BonfireBlockEntity be) {
        // Something other than the bonfire's own code may put it out (a splash water potion, via #minecraft:campfires):
        // roaring is only ever true while lit.
        if (!state.getValue(BonfireBlock.LIT) && state.getValue(BonfireBlock.ROARING)) {
            level.setBlock(pos, state.setValue(BonfireBlock.ROARING, false), Block.UPDATE_ALL);
            be.roarTicks = 0;
            be.setChanged();
            return;
        }
        // Checked every NOT_MAIN_CHECK_TICKS, spread over bonfires by position.
        if (Math.floorMod(level.getGameTime() + pos.asLong(), NOT_MAIN_CHECK_TICKS) == 0
                && putOutIfNotMain(level, pos, state, be)) {
            return;
        }
        if (be.roarTicks > 0) {
            be.roarTicks--;
            if (be.roarTicks == 0) {
                if (state.getValue(BonfireBlock.ROARING)) {
                    level.setBlock(pos, state.setValue(BonfireBlock.ROARING, false), Block.UPDATE_ALL);
                }
                be.setChanged();
            }
        }
    }

    /**
     * Only the main bonfire burns in an empire's land (Bright, 2026-10-24): puts this bonfire out if it is lit and another
     * bonfire is its empire's main one. Returns whether it did.
     */
    public static boolean putOutIfNotMain(Level level, BlockPos pos, BlockState state, BonfireBlockEntity be) {
        if (!state.getValue(BonfireBlock.LIT) || BonfireBlock.mainElsewhere(level, pos) == null) {
            return false;
        }
        level.setBlock(pos, state.setValue(BonfireBlock.LIT, false).setValue(BonfireBlock.ROARING, false), Block.UPDATE_ALL);
        be.roarTicks = 0;
        be.setChanged();
        level.playSound(null, pos, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 0.8F, 1.0F);
        return true;
    }

    public int roarTicks() {
        return roarTicks;
    }

    public void setRoarTicks(int ticks) {
        this.roarTicks = ticks;
        setChanged();
    }

    public boolean everLit() {
        return everLit;
    }

    public void setEverLit(boolean everLit) {
        this.everLit = everLit;
        setChanged();
    }

    @Nullable
    public UUID getEmpire() {
        return empire;
    }

    public void setEmpire(@Nullable UUID empire) {
        this.empire = empire;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("RoarTicks", roarTicks);
        tag.putBoolean("EverLit", everLit);
        if (empire != null) {
            tag.putUUID("Empire", empire);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        roarTicks = tag.getInt("RoarTicks");
        everLit = tag.getBoolean("EverLit");
        empire = tag.hasUUID("Empire") ? tag.getUUID("Empire") : null;
    }
}
