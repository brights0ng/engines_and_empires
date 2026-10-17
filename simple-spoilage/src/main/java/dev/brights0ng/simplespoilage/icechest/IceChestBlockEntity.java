package dev.brights0ng.simplespoilage.icechest;

import javax.annotation.Nullable;

import dev.brights0ng.simplespoilage.FoodClimate;
import dev.brights0ng.simplespoilage.FoodClimateSweep;
import dev.brights0ng.simplespoilage.FoodContent;
import dev.brights0ng.simplespoilage.FoodTemperature;
import dev.brights0ng.simplespoilage.SpoilClock;
import dev.brights0ng.simplespoilage.Spoilage;
import dev.brights0ng.simplespoilage.SpoilageConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
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
 * The ice chest: nine slots of food and one of ice. While it has ice (and there is food to cool), the food inside is kept
 * at 0 C and frozen (food by temperature, 2026-10-15: it ages at the frozen rate, and thaws, at a cost, once it is out in
 * warmth). The ice melts faster the warmer the air around the chest: at its listed time at the reference temperature
 * (20 C), twice as fast every 10 C warmer, not at all at 0 C or below.
 *
 * <p>The chest works this out lazily in {@link #settle}, from when it was last settled to now (the cold part frozen, any
 * part after the ice ran out at the air around it), so it is right however long it went unlooked-at or unloaded (a long
 * gap is taken at the place's seasonal temperature). It settles before every read or change of its slots, and once a
 * second while loaded so the menu's gauge and food tooltips stay current.
 */
public class IceChestBlockEntity extends BlockEntity {

    private static final int SETTLE_INTERVAL = 20;

    private final IceChestInventory inventory = new IceChestInventory(this);

    /** The game time the cooling was last worked out to; -1 before the first time. */
    private long lastSettled = -1;
    /** What is left of the ice item now burning, and how long it lasts in all (for the gauge). */
    private long coolingLeft;
    private long coolingMax;
    /** The air around the chest at the last settle, C (NaN: unknown), and the ice's melt factor there. */
    private double outside = Double.NaN;
    private double meltFactor = 1;
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
            long elapsed = now - lastSettled;
            double t = Double.NaN;
            if (level instanceof ServerLevel server) {
                t = elapsed > FoodClimateSweep.catchUpTicks(SpoilageConfig.sweepTicks())
                        ? FoodTemperature.seasonal(server, worldPosition) : FoodTemperature.at(server, worldPosition);
            }
            FoodClimate.Rules rules = SpoilageConfig.climate();
            outside = t;
            meltFactor = SpoilageConfig.climateEnabled()
                    ? IceChestCooling.meltFactor(t, Double.isFinite(t) ? rules.rate(t) : 1) : 1;
            boolean hasFood = false;
            for (int slot = IceChestInventory.FIRST_FOOD; slot < IceChestInventory.SIZE; slot++) {
                if (!inventory.raw(slot).isEmpty()) {
                    hasFood = true;
                    break;
                }
            }
            ItemStack ice = inventory.raw(IceChestInventory.ICE);
            IceChestCooling.Result result = IceChestCooling.runScaled(elapsed, meltFactor, coolingLeft, coolingMax,
                    ice.getCount(), IceChestInventory.coolingTicks(ice), hasFood);
            lastSettled = now;
            coolingLeft = result.coolingLeft();
            coolingMax = result.currentMax();
            if (result.itemsUsed() > 0) {
                ice.shrink(result.itemsUsed());
            }
            long cold = result.coldTicks();
            long warm = elapsed - cold;
            boolean climate = SpoilageConfig.climateEnabled();
            for (int slot = IceChestInventory.FIRST_FOOD; slot < IceChestInventory.SIZE; slot++) {
                ItemStack food = inventory.raw(slot);
                if (!Spoilage.isSpoilable(food)) {
                    continue;
                }
                Spoilage.normalize(food);
                // The cold part frozen at 0 C; after the ice ran out, the air around the chest
                if (cold > 0) {
                    Spoilage.ageAt(food, now, cold, 0, true);
                }
                if (warm > 0 && climate) {
                    Spoilage.ageAt(food, now, warm, t, false);
                } else if (cold == 0 && isCooling()) {
                    // Cold with no time passed (just put in): frozen at once
                    Spoilage.ageAt(food, now, 0, 0, true);
                }
                Spoilage.markClimate(food, now);
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

    /** Whether the food inside is kept at 0 C now: ice burning, or ice waiting where the air is too cold to melt it. */
    public boolean isCold() {
        ItemStack ice = inventory.raw(IceChestInventory.ICE);
        return coolingLeft > 0 || (meltFactor <= 0 && !ice.isEmpty() && IceChestInventory.coolingTicks(ice) > 0);
    }

    /**
     * How long all the ice (burning and in the slot) lasts at the current melt rate, real seconds; -1 if it doesn't melt
     * here (0 C or colder around the chest).
     */
    public long iceSecondsLeft() {
        ItemStack ice = inventory.raw(IceChestInventory.ICE);
        long listed = coolingLeft + (long) ice.getCount() * Math.max(0, IceChestInventory.coolingTicks(ice));
        if (listed <= 0) {
            return 0;
        }
        if (meltFactor <= 0) {
            return -1;
        }
        return Math.round(listed / meltFactor / 20);
    }

    /**
     * The gauge the menu shows: what is left of the ice burning and how long it lasts in all (listed seconds), the air
     * around the chest and inside it (tenths of a C; {@link #NO_TEMPERATURE} for unknown), and how long all its ice lasts
     * at the current melt rate (minutes; -1 if it doesn't melt here).
     */
    public final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> (int) Math.min(Short.MAX_VALUE, (coolingLeft + 19) / 20);
                case 1 -> (int) Math.min(Short.MAX_VALUE, (coolingMax + 19) / 20);
                case 2 -> tenths(outside);
                case 3 -> isCold() ? 0 : tenths(outside);
                case 4 -> {
                    long s = iceSecondsLeft();
                    yield s < 0 ? -1 : (int) Math.min(Short.MAX_VALUE, (s + 59) / 60);
                }
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    /** How many values the gauge syncs. */
    public static final int DATA_COUNT = 5;
    /** A temperature the chest doesn't know (outside the Overworld). */
    public static final int NO_TEMPERATURE = Short.MIN_VALUE;

    private static int tenths(double c) {
        return Double.isFinite(c) ? (int) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, Math.round(c * 10)))
                : NO_TEMPERATURE;
    }

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
