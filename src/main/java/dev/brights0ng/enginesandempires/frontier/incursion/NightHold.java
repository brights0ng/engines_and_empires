package dev.brights0ng.enginesandempires.frontier.incursion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The night held still by Squelching Hearts (Bright, after playtesting): every heart eaten at night stops the clock for five
 * minutes more, so a greater incursion has time to be beaten before dawn. Each player can eat one a night; three players
 * can hold it for fifteen minutes. The moon does not move while it is held.
 *
 * <p>Saved with the Overworld ({@code data/engines_and_empires_night.dat}), whose clock every dimension shares.
 */
public final class NightHold extends SavedData {

    static final String NAME = "engines_and_empires_night";
    /** How long one heart holds the night: five minutes. */
    public static final int TICKS_PER_HEART = 6000;

    private long held;
    private final Map<UUID, Long> ateOnNight = new HashMap<>();

    public static NightHold of(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(new SavedData.Factory<>(NightHold::new, NightHold::load, null), NAME);
    }

    /** Whether it is night by the clock (nightfall to dawn), when incursions come and hearts can be eaten. */
    public static boolean isNight(long dayTime) {
        long timeOfDay = dayTime % 24000;
        return timeOfDay >= Incursions.NIGHTFALL && timeOfDay < Incursions.DAWN;
    }

    /** Which night it is: it does not change while the night is held. */
    public static long night(long dayTime) {
        return dayTime / 24000;
    }

    /** Whether {@code player} may eat a heart now: at night, and not having eaten one already tonight. */
    public boolean canEat(ServerLevel level, Player player) {
        long dayTime = level.getDayTime();
        Long last = ateOnNight.get(player.getUUID());
        return isNight(dayTime) && (last == null || last != night(dayTime));
    }

    /** {@code player} ate a heart: the night holds five minutes more. */
    public void eat(ServerLevel level, Player player) {
        ateOnNight.put(player.getUUID(), night(level.getDayTime()));
        held += TICKS_PER_HEART;
        setDirty();
    }

    /** How much longer the night holds, in ticks. */
    public long held() {
        return held;
    }

    public void clear() {
        held = 0;
        setDirty();
    }

    /** Runs once a tick, for the Overworld: takes back the tick the clock just moved on, while the night holds. */
    void tick(ServerLevel overworld) {
        if (held <= 0) {
            return;
        }
        long dayTime = overworld.getDayTime();
        if (!isNight(dayTime)) {
            held = 0; // someone set the time, or it is otherwise over
            setDirty();
            return;
        }
        if (overworld.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            overworld.setDayTime(dayTime - 1);
            held--;
            if (held % 200 == 0) {
                setDirty();
            }
        }
    }

    static NightHold load(CompoundTag tag, HolderLookup.Provider registries) {
        NightHold hold = new NightHold();
        hold.held = tag.getLong("held");
        ListTag eaters = tag.getList("ate", Tag.TAG_COMPOUND);
        for (int i = 0; i < eaters.size(); i++) {
            CompoundTag e = eaters.getCompound(i);
            hold.ateOnNight.put(e.getUUID("player"), e.getLong("night"));
        }
        return hold;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("held", held);
        ListTag eaters = new ListTag();
        for (Map.Entry<UUID, Long> entry : ateOnNight.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("player", entry.getKey());
            e.putLong("night", entry.getValue());
            eaters.add(e);
        }
        tag.put("ate", eaters);
        return tag;
    }
}
