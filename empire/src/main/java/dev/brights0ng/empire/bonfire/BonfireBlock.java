// Written by Gemini; god sacrifices wired in, particles spread over the bonfire model, Overworld-only registration, leaving an empire, main-only lighting, model-shaped hitbox and campfire burning by Claude.
package dev.brights0ng.empire.bonfire;

import org.jetbrains.annotations.Nullable;

import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.membership.LeaveInteractions;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.sacrifice.SacrificeInteractions;
import dev.brights0ng.empire.talc.TalcInteractions;
import dev.brights0ng.empire.tier.TierChecks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.mojang.serialization.MapCodec;

public class BonfireBlock extends BaseEntityBlock {

    public static final MapCodec<BonfireBlock> CODEC = simpleCodec(BonfireBlock::new);

    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    public static final BooleanProperty ROARING = BooleanProperty.create("roaring");

    public static final int LIGHT_UNLIT = 0;
    public static final int LIGHT_LIT = 11;
    public static final int LIGHT_ROARING = 15;

    /** What the fire does to a living entity in it each tick, as a campfire's (soul campfires do 2). */
    public static final float FIRE_DAMAGE = 1.0F;

    /**
     * Where the particles go, in blocks from the core block's bottom centre, matching the 3x3x3 model: flames within
     * {@link #FLAME_RADIUS} of the centre, rising from the flame bases; smoke from about the flame tips.
     */
    private static final double FLAME_RADIUS = 0.8D;
    private static final double FLAME_BASE = 0.4D;
    private static final double FLAME_SPAN = 1.0D;
    private static final double SMOKE_RADIUS = 0.5D;
    private static final double SMOKE_BASE = 1.8D;
    private static final double SMOKE_SPAN = 0.6D;

    private static final ThreadLocal<Boolean> REMOVING_BONFIRE = ThreadLocal.withInitial(() -> false);

    public static boolean isRemovingBonfire() {
        return REMOVING_BONFIRE.get();
    }

    public BonfireBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(LIT, false).setValue(ROARING, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT, ROARING);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return BonfireShapes.core();
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    public ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(EmpireContent.BONFIRE_ITEM.get());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        burn(state, level, pos, entity);
        super.entityInside(state, level, pos, entity);
    }

    /**
     * Burns {@code entity} as a lit campfire would, if it is in this bonfire's fire (the pit, not the logs around it).
     * Called from the core and from every part the entity overlaps; hurt cooldown keeps that to one hit.
     */
    public static void burn(BlockState coreState, Level level, BlockPos core, Entity entity) {
        if (coreState.getValue(LIT) && entity instanceof LivingEntity && BonfireShapes.inFire(core, entity)) {
            entity.hurt(level.damageSources().campfire(), FIRE_DAMAGE);
        }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BonfireBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide() ? null : createTickerHelper(blockEntityType, EmpireContent.BONFIRE_BE.get(), BonfireBlockEntity::serverTick);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        // Only Overworld bonfires belong to empires (P10); the registry holds positions without their dimension.
        if (!state.is(oldState.getBlock()) && level instanceof ServerLevel serverLevel && serverLevel.dimension() == Level.OVERWORLD) {
            EmpireSavedData.of(serverLevel.getServer()).onBonfirePlaced(pos, serverLevel.getServer());
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            if (!REMOVING_BONFIRE.get()) {
                REMOVING_BONFIRE.set(true);
                try {
                    for (BlockPos offset : BonfirePartBlock.PART_OFFSETS) {
                        BlockPos partPos = pos.offset(offset);
                        if (level.getBlockState(partPos).is(EmpireContent.BONFIRE_PART.get())) {
                            level.setBlock(partPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                } finally {
                    REMOVING_BONFIRE.set(false);
                }
            }
            if (level instanceof ServerLevel serverLevel && serverLevel.dimension() == Level.OVERWORLD) {
                EmpireSavedData.of(serverLevel.getServer()).onBonfireRemoved(pos, serverLevel.getServer());
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /**
     * The main bonfire of the empire whose land holds {@code pos}, when that is another bonfire (so the one at
     * {@code pos} may not burn); else null. Overworld server side only.
     */
    @Nullable
    public static BlockPos mainElsewhere(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || level.dimension() != Level.OVERWORLD) {
            return null;
        }
        EmpireRecord owner = EmpireSavedData.of(serverLevel.getServer()).getEmpireByChunk(ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4));
        BlockPos main = owner != null ? owner.mainBonfire() : null;
        return main != null && !main.equals(pos) ? main : null;
    }

    public static boolean canLight(Level level, BlockPos pos, @Nullable Player player) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;
        }
        EmpireSavedData data = EmpireSavedData.of(serverLevel.getServer());
        ChunkPos chunkPos = new ChunkPos(pos);
        long chunk = ChunkKeys.pack(chunkPos.x, chunkPos.z);
        EmpireRecord owner = data.getEmpireByChunk(chunk);

        // Bonfires inside an empire's land skip the first-lighting gate, but only the main bonfire burns there
        // (Bright, 2026-10-24): with a main bonfire, no other bonfire of the empire can be lit.
        if (owner != null) {
            BlockPos main = owner.mainBonfire();
            if (main != null && !main.equals(pos)) {
                if (player != null) {
                    player.displayClientMessage(Component.translatable("message.empire.light.not_main",
                            main.getX(), main.getY(), main.getZ()), true);
                }
                return false;
            }
            return true;
        }

        // Unclaimed bonfire: first-lighting gate (D5)
        // 1. None of the 9 chunks around it may be owned by any empire
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long c = ChunkKeys.pack(chunkPos.x + dx, chunkPos.z + dz);
                if (data.isChunkStaked(c)) {
                    if (player != null) {
                        player.displayClientMessage(Component.translatable("message.empire.stake.owned_by_other"), true);
                    }
                    return false;
                }
            }
        }

        // 2. Check Settled rule on its own chunk
        if (!TierChecks.columnSettled(serverLevel, chunkPos)) {
            if (player != null) {
                player.displayClientMessage(Component.translatable("message.empire.stake.not_settled"), true);
            }
            return false;
        }

        return true;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        // A member offering tarnished talc to leave their empire (lit or not); anything else with the talc passes on.
        if (stack.is(EmpireContent.TARNISHED_TALC.get())) {
            ItemInteractionResult leave = LeaveInteractions.tryLeave(stack, level, pos, player);
            if (leave != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                return leave;
            }
        }

        if (stack.is(EmpireContent.RAW_TALC.get())) {
            return TalcInteractions.tryBless(level, pos, player, hand, stack);
        }

        if (!state.getValue(LIT)) {
            if (stack.is(Items.FLINT_AND_STEEL)) {
                if (!level.isClientSide()) {
                    if (!canLight(level, pos, player)) {
                        return ItemInteractionResult.FAIL;
                    }
                    level.setBlock(pos, state.setValue(LIT, true).setValue(ROARING, true), Block.UPDATE_ALL);
                    if (level.getBlockEntity(pos) instanceof BonfireBlockEntity be) {
                        be.setRoarTicks(BonfireBlockEntity.ROAR_DURATION_TICKS);
                        be.setEverLit(true);
                    }
                    level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.4F + 0.8F);
                    stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
                    level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide());
            }

            if (stack.is(Items.FIRE_CHARGE)) {
                if (!level.isClientSide()) {
                    if (!canLight(level, pos, player)) {
                        return ItemInteractionResult.FAIL;
                    }
                    level.setBlock(pos, state.setValue(LIT, true).setValue(ROARING, true), Block.UPDATE_ALL);
                    if (level.getBlockEntity(pos) instanceof BonfireBlockEntity be) {
                        be.setRoarTicks(BonfireBlockEntity.ROAR_DURATION_TICKS);
                        be.setEverLit(true);
                    }
                    level.playSound(null, pos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.0F, (level.getRandom().nextFloat() - level.getRandom().nextFloat()) * 0.2F + 1.0F);
                    stack.consume(1, player);
                    level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide());
            }
        } else {
            // Extinguish with shovel
            if (stack.is(ItemTags.SHOVELS)) {
                if (!level.isClientSide()) {
                    level.setBlock(pos, state.setValue(LIT, false).setValue(ROARING, false), Block.UPDATE_ALL);
                    if (level.getBlockEntity(pos) instanceof BonfireBlockEntity be) {
                        be.setRoarTicks(0);
                    }
                    level.playSound(null, pos, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 1.0F, 1.0F);
                    stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
                    level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide());
            }
        }

        // Anything the bonfire does not use itself may be an offering to the gods (founding meat, or a demand payment).
        return SacrificeInteractions.handleSacrifice(stack, state, level, pos, player, hand);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        // An empty main hand reads out the god's demand, unless the off hand holds something the bonfire itself uses.
        if (usesItself(state, player.getOffhandItem())) {
            return InteractionResult.PASS;
        }
        return SacrificeInteractions.showDemand(level, pos, player);
    }

    /** Whether the bonfire has a use of its own for this item (as opposed to taking it as an offering). */
    private static boolean usesItself(BlockState state, ItemStack stack) {
        if (stack.is(EmpireContent.RAW_TALC.get()) || stack.is(EmpireContent.TARNISHED_TALC.get())) {
            return true;
        }
        return state.getValue(LIT) ? stack.is(ItemTags.SHOVELS) : stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE);
    }

    @Override
    protected void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
        BlockPos pos = hit.getBlockPos();
        if (!level.isClientSide() && projectile.isOnFire() && projectile.mayInteract(level, pos) && !state.getValue(LIT)) {
            Player player = projectile.getOwner() instanceof Player p ? p : null;
            if (canLight(level, pos, player)) {
                level.setBlock(pos, state.setValue(LIT, true).setValue(ROARING, true), Block.UPDATE_ALL);
                if (level.getBlockEntity(pos) instanceof BonfireBlockEntity be) {
                    be.setRoarTicks(BonfireBlockEntity.ROAR_DURATION_TICKS);
                    be.setEverLit(true);
                }
                level.playSound(null, pos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
                level.gameEvent(projectile, GameEvent.BLOCK_CHANGE, pos);
            }
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(LIT)) {
            return;
        }
        boolean roaring = state.getValue(ROARING);
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;

        float volume = roaring ? 1.5F : 0.8F;
        if (random.nextFloat() < (roaring ? 0.25F : 0.12F)) {
            level.playLocalSound(x, y, z, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS, volume, 1.0F, false);
        }
        if (roaring && random.nextFloat() < 0.08F) {
            level.playLocalSound(x, y, z, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 1.0F, 1.0F, false);
        }

        int particleCount = roaring ? 6 : 2;
        for (int i = 0; i < particleCount; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double r = Math.sqrt(random.nextDouble());
            double sx = x + Math.cos(angle) * r * SMOKE_RADIUS;
            double sy = pos.getY() + SMOKE_BASE + random.nextDouble() * SMOKE_SPAN;
            double sz = z + Math.sin(angle) * r * SMOKE_RADIUS;
            level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, sx, sy, sz, 0.0D, 0.07D, 0.0D);

            angle = random.nextDouble() * Math.PI * 2.0D;
            r = Math.sqrt(random.nextDouble());
            double px = x + Math.cos(angle) * r * FLAME_RADIUS;
            double py = pos.getY() + FLAME_BASE + random.nextDouble() * FLAME_SPAN;
            double pz = z + Math.sin(angle) * r * FLAME_RADIUS;
            if (roaring) {
                level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, px, py, pz, 0.0D, 0.02D, 0.0D);
            } else {
                level.addParticle(ParticleTypes.FLAME, px, py, pz, 0.0D, 0.02D, 0.0D);
            }
        }
    }
}
