// Written by Claude.
package dev.brights0ng.empire.god;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.EmpireConfig;
import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.bonfire.BonfireBlockEntity;
import dev.brights0ng.empire.compat.meteodyne.MeteodyneCompat;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.GodSatiety;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * A god's hunger (Bright's design, 2026-10-23; the rules are {@link GodSatiety}). Every {@link #STEP_TICKS} ticks, each
 * empire with a god and a member online (in any dimension) grows {@link #STEP_TICKS} hungrier; an empire with no member
 * online, or none at all (frozen), does not. Crossing into hunger tells the members online (and a member who logs in
 * while the god is hungry); a hungry god puts out a lit main bonfire whose chunk is loaded; at the limit the god
 * abandons the empire, which keeps its dominion and chunks and can adopt a god again.
 */
public final class GodHunger {

    public static final int STEP_TICKS = 20;
    /** The year when nothing else says how long it is: 72 days, Meteodyne's and Serene Seasons' default. */
    public static final long DEFAULT_YEAR_TICKS = 72 * 24000L;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % STEP_TICKS != 0) {
            return;
        }
        EmpireSavedData data = EmpireSavedData.of(server);
        long limit = limitTicks(server.overworld());
        for (EmpireRecord record : data.allEmpires()) {
            if (record.god() != null && !record.members().isEmpty()) {
                step(server, data, record, anyMemberOnline(server, record), limit);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        EmpireRecord record = EmpireSavedData.of(server).getEmpireOf(player.getUUID());
        if (record != null && record.god() != null
                && GodSatiety.state(record.hungerTicks(), limitTicks(server.overworld())) == GodSatiety.State.HUNGRY) {
            player.sendSystemMessage(Component.translatable("message.empire.god.hungry", godName(record.god())));
        }
    }

    /**
     * One step of the empire's hunger: {@link #STEP_TICKS} more when it has a god, a member and one of them online, then
     * the warning, the bonfire and abandonment. Tests pass {@code anyMemberOnline} (mock players are never online).
     */
    public static void step(MinecraftServer server, EmpireSavedData data, EmpireRecord record, boolean anyMemberOnline, long limit) {
        if (record.god() == null || record.members().isEmpty() || !anyMemberOnline) {
            return;
        }
        long before = record.hungerTicks();
        long after = before + STEP_TICKS;
        record.setHungerTicks(after);
        data.setDirty();
        if (GodSatiety.extinguishDue(before, after, limit)) {
            extinguishMainBonfire(server.overworld(), record);
        }
        settle(server, data, record, before, limit);
    }

    /** Sets the empire's hunger (the debug command), with the warning or abandonment it brings. */
    public static void setHunger(MinecraftServer server, EmpireSavedData data, EmpireRecord record, long ticks, long limit) {
        long before = record.hungerTicks();
        record.setHungerTicks(ticks);
        data.setDirty();
        settle(server, data, record, before, limit);
    }

    /** After hunger moved from {@code before} to the record's: abandons the empire at the limit, else warns on crossing. */
    private static void settle(MinecraftServer server, EmpireSavedData data, EmpireRecord record, long before, long limit) {
        ResourceLocation god = record.god();
        if (god == null) {
            return;
        }
        long after = record.hungerTicks();
        GodSatiety.State state = GodSatiety.state(after, limit);
        if (state == GodSatiety.State.ABANDONED) {
            abandon(server, data, record);
        } else if (state == GodSatiety.State.HUNGRY && GodSatiety.crossedWarning(before, after, limit)) {
            EmpireSavedData.messageMembers(record, server, Component.translatable("message.empire.god.hungry", godName(god)));
        }
    }

    /** The god leaves: no god, no demand, hunger 0. Dominion and chunks stay. */
    public static void abandon(MinecraftServer server, EmpireSavedData data, EmpireRecord record) {
        ResourceLocation god = record.god();
        if (god == null) {
            return;
        }
        record.setGod(null);
        record.setCurrentDemand(List.of());
        record.setHungerTicks(0);
        data.setDirty();
        EmpireSavedData.messageMembers(record, server, Component.translatable("message.empire.god.abandoned", godName(god)));
    }

    /** Puts out the empire's main bonfire if its chunk is loaded and it is lit; returns whether it did. */
    public static boolean extinguishMainBonfire(ServerLevel overworld, EmpireRecord record) {
        BlockPos pos = record.mainBonfire();
        if (pos == null || !overworld.isLoaded(pos)) {
            return false;
        }
        BlockState state = overworld.getBlockState(pos);
        if (!(state.getBlock() instanceof BonfireBlock) || !state.getValue(BonfireBlock.LIT)) {
            return false;
        }
        overworld.setBlock(pos, state.setValue(BonfireBlock.LIT, false).setValue(BonfireBlock.ROARING, false), Block.UPDATE_ALL);
        if (overworld.getBlockEntity(pos) instanceof BonfireBlockEntity be) {
            be.setRoarTicks(0);
        }
        overworld.playSound(null, pos, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 1.0F, 1.0F);
        overworld.sendParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 20, 0.5, 0.5, 0.5, 0.02);
        return true;
    }

    /**
     * How long a god goes unfed before it abandons its empire: {@link EmpireConfig#godPatienceTicks()} when set, else one
     * year (Meteodyne's when it is installed and has one, else {@link #DEFAULT_YEAR_TICKS}).
     */
    public static long limitTicks(Level level) {
        long configured = EmpireConfig.godPatienceTicks();
        if (configured > 0) {
            return configured;
        }
        return yearTicks(level);
    }

    /** One in-game year: Meteodyne's when it is installed and has one, else {@link #DEFAULT_YEAR_TICKS}. */
    public static long yearTicks(Level level) {
        long meteodyne = MeteodyneCompat.yearTicks(level);
        return meteodyne > 0 ? meteodyne : DEFAULT_YEAR_TICKS;
    }

    public static boolean anyMemberOnline(MinecraftServer server, EmpireRecord record) {
        for (UUID member : record.members()) {
            if (server.getPlayerList().getPlayer(member) != null) {
                return true;
            }
        }
        return false;
    }

    public static Component godName(@Nullable ResourceLocation god) {
        if (god == null) {
            return Component.literal("?");
        }
        GodDefinition definition = Gods.get(god);
        return definition != null ? definition.displayName() : Component.translatable(GodDefinition.nameKey(god));
    }

    private GodHunger() {
    }
}
