package dev.brights0ng.seismicsurveyance;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/**
 * A thumper's head has landed on its strike plate (split seam S5). Posted on the game bus ({@code NeoForge.EVENT_BUS}),
 * on the server thread, whether or not any geophone is listening, before the survey starts. What a shot sets off below
 * the survey (sculk, mobs, incursions) listens for it; Seismic Surveyance itself does nothing more with it.
 */
public final class ThumpEvent extends Event {

    private final ServerLevel level;
    private final BlockPos pos;
    private final ShotSource source;
    private final int range;

    public ThumpEvent(ServerLevel level, BlockPos pos, ShotSource source, int range) {
        this.level = level;
        this.pos = pos;
        this.source = source;
        this.range = range;
    }

    public ServerLevel level() {
        return level;
    }

    /** The strike plate the head landed on. */
    public BlockPos pos() {
        return pos;
    }

    /** What made the shot. */
    public ShotSource source() {
        return source;
    }

    /** How far the vibration carries, blocks. */
    public int range() {
        return range;
    }
}
