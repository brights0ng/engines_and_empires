package dev.brights0ng.sablefixes.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;

import net.minecraft.core.Holder;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.phys.Vec3;

/**
 * A guard on Sable's game events on ships (2026-10-15; {@code claude/weather-backbone-phase8f.md}, open point 4).
 *
 * <p>Sable 2.0.5 ({@code sculk_vibrations.GameEventDispatcherMixin}) re-posts every game event once per ship near it,
 * with the section range to visit swapped for the overlap of the ship's plot box and the event's box. For some ships
 * that overlap comes out enormous (or degenerate), and vanilla then walks it section by section without end: eating
 * near such a ship froze the server (seen in the food game test, thread dump 2026-10-15).
 *
 * <p>Runs after Sable's injection at the same point (higher priority) and empties the range when it spans more
 * sections on any axis than any real event could reach ({@link #MAX_SECTIONS}); those ships are skipped for that
 * event. Ordinary events (a few sections each way) are untouched.
 */
@Mixin(value = GameEventDispatcher.class, priority = 1500)
public abstract class GameEventSectionGuardMixin {

    /** Sections per axis beyond which a range is taken to be broken (vanilla's widest event, 16 blocks out, is 3). */
    private static final int MAX_SECTIONS = 16;

    @Inject(method = "post", at = @At(value = "NEW", target = "java/util/ArrayList"))
    private void engines_and_empires$guardSectionRange(Holder<GameEvent> gameEvent, Vec3 pos, GameEvent.Context context,
                                                       CallbackInfo ci,
                                                       @Local(ordinal = 1) LocalIntRef x1, @Local(ordinal = 2) LocalIntRef y1,
                                                       @Local(ordinal = 3) LocalIntRef z1, @Local(ordinal = 4) LocalIntRef x2,
                                                       @Local(ordinal = 5) LocalIntRef y2, @Local(ordinal = 6) LocalIntRef z2) {
        if (broken(x1.get(), x2.get()) || broken(y1.get(), y2.get()) || broken(z1.get(), z2.get())) {
            // An empty range (from 0 to -1), so the loops below visit nothing
            x1.set(0);
            y1.set(0);
            z1.set(0);
            x2.set(-1);
            y2.set(-1);
            z2.set(-1);
        }
    }

    private static boolean broken(int from, int to) {
        return (long) to - (long) from > MAX_SECTIONS;
    }
}
