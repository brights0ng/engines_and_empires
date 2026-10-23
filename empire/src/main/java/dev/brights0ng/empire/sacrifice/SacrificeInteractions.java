// Written by Gemini; god sacrifices (founding meat, adoption, paying demands, the demand readout) and the client's offering check by Claude.
package dev.brights0ng.empire.sacrifice;

import java.util.List;

import javax.annotation.Nullable;

import dev.brights0ng.empire.EmpireConfig;
import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.god.Demands;
import dev.brights0ng.empire.god.GodDefinition;
import dev.brights0ng.empire.god.GodOfferings;
import dev.brights0ng.empire.god.Gods;
import dev.brights0ng.empire.god.Offerings;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.DawnClock;
import dev.brights0ng.empire.rules.DemandLine;
import dev.brights0ng.empire.rules.DemandOption;
import dev.brights0ng.empire.rules.EmpireState;
import dev.brights0ng.empire.tier.TierChecks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/**
 * Sacrifices at a bonfire, to the gods (Bright's design; the paying and readout rules are PROVISIONAL):
 * <ul>
 *   <li>Founding, at an unclaimed bonfire: {@link EmpireState#FOUNDING_MEAT} of one god's founding (tier 1) meat, from
 *       the one stack in the hand used, found an empire under that god and roll its first demand.</li>
 *   <li>Adoption, at a godless empire's main bonfire (records from before gods): the same meat gives it that god and a
 *       demand; its dominion stays as it is. A godless empire has nothing to pay.</li>
 *   <li>Paying, at the main bonfire: an item or fluid container that matches an unpaid line of the current demand goes
 *       towards it (as much as the line still needs). When every line is paid the empire gains
 *       {@link EmpireState#DOMINION_PER_SACRIFICE} dominion and the god rolls its next demand.</li>
 *   <li>An empty hand reads out the demand (anyone may look).</li>
 * </ul>
 * Founding meat must be fresh or ripe when a spoilage mod says how fresh it is, for every unit taken; a god that accepts
 * rotting meat (the Carrion Crow) takes its own meat at any stage, and any raw meat once it is rotting. With
 * {@link EmpireConfig#uniqueGodsPerServer()} a god another empire has is refused.
 */
public final class SacrificeInteractions {

    /** Any raw meat a god that accepts rotting meat takes once it is rotting. */
    private static final TagKey<Item> RAW_MEAT = Tags.Items.FOODS_RAW_MEAT;

    public static ItemInteractionResult handleSacrifice(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand) {
        if (stack.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            // The client knows neither the gods (server data) nor the empire's demand. It claims the click only for an
            // item some god could take (the server sends that list), so the item is not also eaten, poured or used; any
            // other item does what it would do. The server decides.
            return level.dimension() == Level.OVERWORLD && state.getValue(BonfireBlock.LIT) && GodOfferings.clientMightTake(stack)
                    ? ItemInteractionResult.SUCCESS : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        EmpireSavedData data = EmpireSavedData.of(serverLevel.getServer());
        boolean overworld = level.dimension() == Level.OVERWORLD;
        ChunkPos chunkPos = new ChunkPos(pos);
        long chunk = ChunkKeys.pack(chunkPos.x, chunkPos.z);
        EmpireRecord owner = overworld ? data.getEmpireByChunk(chunk) : null;
        GodDefinition ownerGod = owner != null ? Gods.get(owner.god()) : null;

        // 0. Is it an offering at all? (Anything else is left to do what it would do.) An unclaimed bonfire and a godless
        // empire take founding meat; an empire with a god takes what its demand still needs (nothing, if its god's data
        // is gone).
        boolean offering = owner == null || owner.god() == null ? isFoundingMeat(stack)
                : ownerGod != null && demandLineFor(stack, owner, ownerGod) >= 0;
        if (!offering) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // 1. Overworld check (P10)
        if (!overworld) {
            player.displayClientMessage(Component.translatable("message.empire.sacrifice.overworld_only"), true);
            return ItemInteractionResult.FAIL;
        }

        // 2. Bonfire must be lit or roaring
        if (!state.getValue(BonfireBlock.LIT)) {
            player.displayClientMessage(Component.translatable("message.empire.sacrifice.unlit"), true);
            return ItemInteractionResult.FAIL;
        }

        // 3. Resolve owner
        if (owner == null) {
            return found(stack, serverLevel, data, pos, chunkPos, chunk, player);
        }

        // Claimed bonfire: player must be founder, and must be main bonfire
        if (!player.getUUID().equals(owner.founder())) {
            player.displayClientMessage(Component.translatable("message.empire.sacrifice.not_founder"), true);
            return ItemInteractionResult.FAIL;
        }

        if (!pos.equals(owner.mainBonfire())) {
            player.displayClientMessage(Component.translatable("message.empire.sacrifice.not_main"), true);
            return ItemInteractionResult.FAIL;
        }

        if (owner.god() == null) {
            return adopt(stack, serverLevel, data, pos, owner, player);
        }
        return pay(stack, serverLevel, data, pos, owner, ownerGod, player, hand);
    }

    /** An empty-handed right click: what the bonfire's empire owes its god, to anyone who looks. */
    public static InteractionResult showDemand(Level level, BlockPos pos, Player player) {
        if (!player.getMainHandItem().isEmpty() || level.dimension() != Level.OVERWORLD) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        EmpireSavedData data = EmpireSavedData.of(((ServerLevel) level).getServer());
        EmpireRecord owner = data.getEmpireByChunk(ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4));
        Component message;
        if (owner == null) {
            message = Component.translatable("message.empire.sacrifice.offer_meat", EmpireState.FOUNDING_MEAT);
        } else if (owner.god() == null) {
            message = Component.translatable("message.empire.god.none", EmpireState.FOUNDING_MEAT);
        } else {
            GodDefinition god = Gods.get(owner.god());
            Component name = god != null ? god.displayName() : Component.translatable(GodDefinition.nameKey(owner.god()));
            message = Demands.status(name, owner.currentDemand());
        }
        player.displayClientMessage(message, true);
        return InteractionResult.CONSUME;
    }

    // ---- founding and adoption ----

    private static ItemInteractionResult found(ItemStack stack, ServerLevel serverLevel, EmpireSavedData data, BlockPos pos,
            ChunkPos chunkPos, long chunk, Player player) {
        if (data.getEmpireByFounder(player.getUUID()) != null) {
            player.displayClientMessage(Component.translatable("message.empire.sacrifice.already_founded"), true);
            return ItemInteractionResult.FAIL;
        }

        // D5 checks: 9 chunks free
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long c = ChunkKeys.pack(chunkPos.x + dx, chunkPos.z + dz);
                if (data.isChunkStaked(c)) {
                    player.displayClientMessage(Component.translatable("message.empire.stake.owned_by_other"), true);
                    return ItemInteractionResult.FAIL;
                }
            }
        }

        // Own chunk Settled
        if (!TierChecks.columnSettled(serverLevel, chunkPos)) {
            player.displayClientMessage(Component.translatable("message.empire.stake.not_settled"), true);
            return ItemInteractionResult.FAIL;
        }

        GodDefinition god = chooseGod(stack, data, null, player);
        if (god == null) {
            return ItemInteractionResult.FAIL;
        }

        consume(stack, EmpireState.FOUNDING_MEAT, player);
        EmpireRecord record = data.createEmpire(player.getUUID(), chunk, pos, serverLevel.getGameTime());
        record.setGod(god.id());
        Demands.rollNext(record, god);
        data.setDirty();

        celebrate(serverLevel, pos);
        sendSuccess(serverLevel, record, player);
        return ItemInteractionResult.SUCCESS;
    }

    private static ItemInteractionResult adopt(ItemStack stack, ServerLevel serverLevel, EmpireSavedData data, BlockPos pos,
            EmpireRecord owner, Player player) {
        GodDefinition god = chooseGod(stack, data, owner, player);
        if (god == null) {
            return ItemInteractionResult.FAIL;
        }
        consume(stack, EmpireState.FOUNDING_MEAT, player);
        owner.setGod(god.id());
        Demands.rollNext(owner, god);
        data.setDirty();

        celebrate(serverLevel, pos);
        player.displayClientMessage(Component.translatable("message.empire.god.adopted", god.displayName()), true);
        return ItemInteractionResult.SUCCESS;
    }

    /**
     * The god a founding (or adoption) offering of {@code stack} goes to, or null, having told the player why: fewer than
     * {@link EmpireState#FOUNDING_MEAT}, meat no god will take at its freshness, or a god another empire has.
     */
    @Nullable
    private static GodDefinition chooseGod(ItemStack stack, EmpireSavedData data, @Nullable EmpireRecord self, Player player) {
        int needed = EmpireState.FOUNDING_MEAT;
        if (stack.getCount() < needed) {
            player.displayClientMessage(Component.translatable("message.empire.sacrifice.need_meat", needed), true);
            return null;
        }
        GodDefinition god = godFor(stack, needed);
        if (god == null) {
            player.displayClientMessage(Component.translatable("message.empire.sacrifice.meat_not_fresh"), true);
            return null;
        }
        if (EmpireConfig.uniqueGodsPerServer() && Demands.heldByAnother(data, god.id(), self)) {
            player.displayClientMessage(Component.translatable("message.empire.god.taken", god.displayName()), true);
            return null;
        }
        return god;
    }

    /**
     * The god that takes {@code units} of this stack as founding meat at its freshness: the one whose meat it is, if fresh
     * enough for it; else, if they are all rotting raw meat, the first god that accepts rotting meat. Gods are tried in
     * id order.
     */
    @Nullable
    static GodDefinition godFor(ItemStack stack, int units) {
        for (GodDefinition god : Gods.all()) {
            if (isFoundingMeatOf(god, stack) && (god.acceptsRottingMeat()
                    || MeatFreshnessChecks.stalest(stack, units).isFresh())) {
                return god;
            }
        }
        if (allRottingRawMeat(stack, units)) {
            for (GodDefinition god : Gods.all()) {
                if (god.acceptsRottingMeat()) {
                    return god;
                }
            }
        }
        return null;
    }

    /** Whether a founding (or adoption) would look at this stack: some god's founding meat, or rotting raw meat. */
    private static boolean isFoundingMeat(ItemStack stack) {
        for (GodDefinition god : Gods.all()) {
            if (isFoundingMeatOf(god, stack)) {
                return true;
            }
        }
        return anyGodAcceptsRottingMeat() && allRottingRawMeat(stack, Math.min(stack.getCount(), EmpireState.FOUNDING_MEAT));
    }

    private static boolean isFoundingMeatOf(GodDefinition god, ItemStack stack) {
        for (DemandOption option : god.foundingMeat()) {
            if (Offerings.matchesItem(option, stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyGodAcceptsRottingMeat() {
        for (GodDefinition god : Gods.all()) {
            if (god.acceptsRottingMeat()) {
                return true;
            }
        }
        return false;
    }

    private static boolean allRottingRawMeat(ItemStack stack, int units) {
        return stack.is(RAW_MEAT) && MeatFreshnessChecks.freshest(stack, units) == MeatStage.ROTTING;
    }

    // ---- paying ----

    /** The index of the first unpaid line of the empire's demand this stack can go towards, or -1. */
    private static int demandLineFor(ItemStack stack, EmpireRecord owner, GodDefinition god) {
        List<DemandLine> lines = owner.currentDemand();
        for (int i = 0; i < lines.size(); i++) {
            DemandLine line = lines.get(i);
            if (line.isPaid()) {
                continue;
            }
            if (line.kind() == DemandLine.Kind.FLUID) {
                Fluid fluid = Offerings.fluid(line.ids().get(0));
                if (fluid != null && FluidUtil.getFluidContained(stack).filter(f -> f.is(fluid)).isPresent()) {
                    return i;
                }
            } else if (Offerings.matchesItem(line, stack)
                    || (god.acceptsRottingMeat() && isFoundingMeatLine(god, line) && stack.is(RAW_MEAT)
                        && allRottingRawMeat(stack, Math.min(stack.getCount(), line.remaining())))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isFoundingMeatLine(GodDefinition god, DemandLine line) {
        for (DemandOption option : god.foundingMeat()) {
            if (option.sameAs(line)) {
                return true;
            }
        }
        return false;
    }

    private static ItemInteractionResult pay(ItemStack stack, ServerLevel serverLevel, EmpireSavedData data, BlockPos pos,
            EmpireRecord owner, GodDefinition god, Player player, InteractionHand hand) {
        int index = demandLineFor(stack, owner, god);
        DemandLine line = owner.currentDemand().get(index);
        int amount;
        if (line.kind() == DemandLine.Kind.FLUID) {
            amount = drain(stack, line, player, hand);
        } else {
            amount = Math.min(stack.getCount(), line.remaining());
            // The founding meat keeps its freshness rule when it is demanded again.
            if (isFoundingMeatLine(god, line) && !god.acceptsRottingMeat()
                    && !MeatFreshnessChecks.stalest(stack, amount).isFresh()) {
                player.displayClientMessage(Component.translatable("message.empire.sacrifice.meat_not_fresh"), true);
                return ItemInteractionResult.FAIL;
            }
            consume(stack, amount, player);
        }
        if (amount <= 0) {
            player.displayClientMessage(Demands.status(god.displayName(), owner.currentDemand()), true);
            return ItemInteractionResult.FAIL;
        }

        owner.setDemandLine(index, line.withPaid(line.paid() + amount));
        data.setDirty();

        if (!DemandLine.allPaid(owner.currentDemand())) {
            serverLevel.playSound(null, pos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.6F, 1.4F);
            player.displayClientMessage(Demands.status(god.displayName(), owner.currentDemand()), true);
            return ItemInteractionResult.SUCCESS;
        }

        owner.state().addDominion(EmpireState.DOMINION_PER_SACRIFICE);
        owner.setSacrificeCount(owner.sacrificeCount() + 1);
        Demands.rollNext(owner, god);
        data.setDirty();

        celebrate(serverLevel, pos);
        sendSuccess(serverLevel, owner, player);
        return ItemInteractionResult.SUCCESS;
    }

    /**
     * Drains up to what the line still needs from the held container and gives the container back (an empty bucket, a
     * canister with whatever is left in it). In creative mode the container keeps its fluid. Returns the millibuckets
     * drained.
     */
    private static int drain(ItemStack stack, DemandLine line, Player player, InteractionHand hand) {
        Fluid fluid = Offerings.fluid(line.ids().get(0));
        if (fluid == null) {
            return 0;
        }
        boolean creative = player.getAbilities().instabuild;
        ItemStack single = stack.copyWithCount(1);
        IFluidHandlerItem handler = single.getCapability(Capabilities.FluidHandler.ITEM);
        if (handler == null) {
            return 0;
        }
        FluidStack drained = handler.drain(new FluidStack(fluid, line.remaining()),
                creative ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE);
        int amount = drained.getAmount();
        if (amount <= 0 || creative) {
            return amount;
        }
        ItemStack container = handler.getContainer();
        if (stack.getCount() == 1) {
            player.setItemInHand(hand, container);
        } else {
            stack.shrink(1);
            if (!player.getInventory().add(container)) {
                player.drop(container, false);
            }
        }
        return amount;
    }

    // ---- shared ----

    /** Takes {@code count} from the held stack (its freshest units, with a spoilage mod), except in creative mode. */
    private static void consume(ItemStack stack, int count, Player player) {
        if (!player.getAbilities().instabuild) {
            stack.shrink(count);
        }
    }

    // Feedback: sound + particles
    private static void celebrate(ServerLevel serverLevel, BlockPos pos) {
        serverLevel.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1.0F, 1.0F);
        serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 20, 0.5, 0.5, 0.5, 0.1);
        serverLevel.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 25, 0.4, 0.4, 0.4, 0.05);
    }

    // Feedback: action bar
    private static void sendSuccess(ServerLevel serverLevel, EmpireRecord record, Player player) {
        long day = DawnClock.dayOf(serverLevel.getDayTime());
        int d = record.state().dominion();
        int a = record.state().available(day);
        player.displayClientMessage(Component.translatable("message.empire.sacrifice.success", d, a), true);
    }

    private SacrificeInteractions() {
    }
}
