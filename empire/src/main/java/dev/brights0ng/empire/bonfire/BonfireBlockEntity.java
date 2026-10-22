// Written by Gemini.
package dev.brights0ng.empire.bonfire;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import dev.brights0ng.empire.EmpireContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class BonfireBlockEntity extends BlockEntity {

    public static final int ROAR_DURATION_TICKS = 600;

    private int roarTicks;
    private boolean everLit;
    @Nullable
    private UUID empire;

    public BonfireBlockEntity(BlockPos pos, BlockState state) {
        super(EmpireContent.BONFIRE_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BonfireBlockEntity be) {
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
