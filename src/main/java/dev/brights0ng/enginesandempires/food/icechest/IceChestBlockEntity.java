package dev.brights0ng.enginesandempires.food.icechest;

import javax.annotation.Nullable;

import dev.brights0ng.enginesandempires.food.FoodContent;
import dev.brights0ng.enginesandempires.food.FoodFreshness;
import dev.brights0ng.enginesandempires.food.SpoilClock;
import dev.brights0ng.enginesandempires.food.Spoilage;
import dev.brights0ng.enginesandempires.food.SpoilageConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The ice chest: nine slots of food and one of ice. While ice burns (and there is food to cool), the food inside spoils
 * {@link SpoilageConfig#iceChestSlowdown() 90%} slower.
 *
 * <p>Food ages by the clock (its freshness is a birth time), so the chest slows it by making it younger: for every tick of
 * cooling, each unit's birth moves 0.9 of a tick later. The chest works this out lazily in {@link #settle}, from when it was
 * last settled to now, so it is right however long it went unlooked-at or unloaded. It settles before every read or change
 * of its slots, and once a second while loaded so the menu's gauge and food tooltips stay current.
 */
public class IceChestBlockEntity extends BlockEntity {

    private static final int SETTLE_INTERVAL = 20;

    private final IceChestInventory inventory = new IceChestInventory(this);

    /** The game time the cooling was last worked out to; -1 before the first time. */
    private long lastSettled = -1;
    /** What is left of the ice item now burning, and how long it lasts in all (for the gauge). */
    private long coolingLeft;
    private long coolingMax;
    private boolean settling;
    private int openers;

    public IceChestBlockEntity(BlockPos pos, BlockState state) {
        super(FoodContent.ICE_CHEST_ENTITY.get(), pos, state);
    }

    public IceChestInventory inventory() {
        return inventory;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, IceChestBlockEntity chest) {
        if (level.getGameTime() % SETTLE_INTERVAL == 0) {
            chest.settle();
        }
    }

    /** Works the cooling out up to now (server side only; the client's copy has no food to cool). */
    public void settle() {
        if (level == null || level.isClientSide()) {
            return;
        }
        settleAt(SpoilClock.now());
    }

    /** {@link #settle} as if the time were {@code now}. Public for the game tests. */
    public void settleAt(long now) {
        if (now < 0 || settling) {
            return;
        }
        if (lastSettled < 0 || now <= lastSettled) {
            if (lastSettled < 0) {
                lastSettled = now;
                setChanged();
            }
            return;
        }
        settling = true;
        try {
            boolean hasFood = false;
            for (int slot = IceChestInventory.FIRST_FOOD; slot < IceChestInventory.SIZE; slot++) {
                if (!inventory.raw(slot).isEmpty()) {
                    hasFood = true;
                    break;
                }
            }
            ItemStack ice = inventory.raw(IceChestInventory.ICE);
            IceChestCooling.Result result = IceChestCooling.run(now - lastSettled, coolingLeft, coolingMax,
                    ice.getCount(), IceChestInventory.coolingTicks(ice), hasFood);
            lastSettled = now;
            coolingLeft = result.coolingLeft();
            coolingMax = result.currentMax();
            if (result.itemsUsed() > 0) {
                ice.shrink(result.itemsUsed());
            }
            long younger = IceChestCooling.youngerBy(result.coldTicks(), SpoilageConfig.iceChestSlowdown());
            if (younger > 0) {
                for (int slot = IceChestInventory.FIRST_FOOD; slot < IceChestInventory.SIZE; slot++) {
                    ItemStack food = inventory.raw(slot);
                    FoodFreshness stored = Spoilage.isSpoilable(food) ? Spoilage.stored(food) : null;
                    if (stored != null) {
                        // Never younger than brand new
                        FoodFreshness chilled = Spoilage.view(food, now).aged(-younger).atLeastAge(0, now, Spoilage.times());
                        Spoilage.write(food, chilled);
                    }
                }
            }
            setChanged();
        } finally {
            settling = false;
        }
    }

    /** Whether ice is burning right now. */
    public boolean isCooling() {
        return coolingLeft > 0;
    }

    /** The gauge the menu shows: what is left of the ice burning, and how long it lasts in all, in whole seconds. */
    public final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return (int) Math.min(Short.MAX_VALUE, ((index == 0 ? coolingLeft : coolingMax) + 19) / 20);
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return 2;
        }
    };

    /**
     * The chest as automation sees it from each side: ice goes in the top, food in the sides, and food comes out the bottom.
     * With no side (a mod asking for the whole chest), every slot.
     */
    public IItemHandler handlerFor(@Nullable Direction side) {
        if (side == null) {
            return inventory;
        }
        return switch (side) {
            case UP -> new IceChestSides(inventory, new int[] {IceChestInventory.ICE}, true, false);
            case DOWN -> new IceChestSides(inventory, IceChestSides.FOOD_SLOTS, false, true);
            default -> new IceChestSides(inventory, IceChestSides.FOOD_SLOTS, true, false);
        };
    }

    // ---- Opening, like a barrel ----

    public void startOpen() {
        if (openers++ == 0) {
            setOpen(true, SoundEvents.BARREL_OPEN);
        }
    }

    public void stopOpen() {
        if (openers > 0 && --openers == 0) {
            setOpen(false, SoundEvents.BARREL_CLOSE);
        }
    }

    private void setOpen(boolean open, SoundEvent sound) {
        if (level == null || level.isClientSide()) {
            return;
        }
        BlockState state = getBlockState();
        if (state.hasProperty(BarrelBlock.OPEN) && state.getValue(BarrelBlock.OPEN) != open) {
            level.setBlock(worldPosition, state.setValue(BarrelBlock.OPEN, open), 3);
        }
        Direction facing = state.hasProperty(BarrelBlock.FACING) ? state.getValue(BarrelBlock.FACING) : Direction.UP;
        double x = worldPosition.getX() + 0.5 + facing.getStepX() / 2.0;
        double y = worldPosition.getY() + 0.5 + facing.getStepY() / 2.0;
        double z = worldPosition.getZ() + 0.5 + facing.getStepZ() / 2.0;
        level.playSound(null, x, y, z, sound, SoundSource.BLOCKS, 0.5F, level.random.nextFloat() * 0.1F + 0.9F);
    }

    /** Settles, then spills everything (the block is being broken). */
    public void dropContents() {
        settle();
        if (level == null) {
            return;
        }
        for (int slot = 0; slot < IceChestInventory.SIZE; slot++) {
            ItemStack stack = inventory.raw(slot);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), stack.copy());
                stack.setCount(0);
            }
        }
    }

    // ---- Saving ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", inventory.serializeNBT(registries));
        tag.putLong("last_settled", lastSettled);
        tag.putLong("cooling_left", coolingLeft);
        tag.putLong("cooling_max", coolingMax);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("inventory"));
        lastSettled = tag.contains("last_settled") ? tag.getLong("last_settled") : -1;
        coolingLeft = tag.getLong("cooling_left");
        coolingMax = tag.getLong("cooling_max");
    }
}
