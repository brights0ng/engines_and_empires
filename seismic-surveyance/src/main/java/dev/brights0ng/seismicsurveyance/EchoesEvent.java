package dev.brights0ng.seismicsurveyance;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/**
 * A thumper shot's echoes have come back off these deposits (split seam S5). Posted on the game bus
 * ({@code NeoForge.EVENT_BUS}), on the server thread, once the survey has found them, before the geophones are told when
 * to glow. Only for thumper shots, and only when geophones were listening (with nobody listening, nothing is searched).
 */
public final class EchoesEvent extends Event {

    private final ServerLevel level;
    private final ShotSource source;
    private final List<SeismicWave.Echoer> echoers;

    public EchoesEvent(ServerLevel level, ShotSource source, List<SeismicWave.Echoer> echoers) {
        this.level = level;
        this.source = source;
        this.echoers = List.copyOf(echoers);
    }

    public ServerLevel level() {
        return level;
    }

    /** What made the shot. */
    public ShotSource source() {
        return source;
    }

    /** The deposits the shot's echoes came back off, one each. */
    public List<SeismicWave.Echoer> echoers() {
        return echoers;
    }
}
