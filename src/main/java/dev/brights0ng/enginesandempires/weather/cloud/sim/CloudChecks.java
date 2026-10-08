package dev.brights0ng.enginesandempires.weather.cloud.sim;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;

/**
 * The audit's rules (2026-10-06): what a cloud shouldn't be doing, given the air where it is now. Pure, so the rules
 * themselves are tested. Each problem is a short code plus a reason.
 *
 * <ul>
 *   <li><b>UNWANTED</b>: a layer cloud still going where its type isn't wanted (it should be dissolving).</li>
 *   <li><b>NS_NO_LIFT</b>: nimbostratus with no front, low centre or upslope wind under it.</li>
 *   <li><b>RAIN_UNDER_HIGH</b>: a layer cloud raining under a high's sinking air (light drizzle from stratus or
 *       stratocumulus is allowed: it happens under highs in saturated air).</li>
 *   <li><b>STORM_COLD_DRY</b>: a cumulonimbus in air too cold or too dry to feed one.</li>
 *   <li><b>RAIN_DRY_AIR</b>: rain reaching the ground through dry air (should be virga).</li>
 *   <li><b>VIRGA_MOIST_AIR</b>: virga in nearly saturated air.</li>
 *   <li><b>NIGHT_SUN</b>: a heap cloud credited to the sun while the sun isn't heating the ground.</li>
 * </ul>
 */
public final class CloudChecks {

    public record Problem(String code, String reason) {
    }

    public static List<Problem> check(SimCloud c, CloudDiagnostics.Need need, CloudDiagnostics.Air air, long now) {
        List<Problem> out = new ArrayList<>();
        boolean live = !c.dissolving && !c.manual;
        boolean raining = c.precipitation > 0.02 && Float.isInfinite(c.rainBottom)
                && c.phase(now).precipitation() > 0.05;
        double rhNow = 1 - need.lclMetres() / 2500;
        if (live && c.type.layer() && need.cover(c.type) < CloudSim.Settings.DEFAULT.keepCover()) {
            out.add(new Problem("UNWANTED", String.format("%s wanted %.0f%% here", c.type.id,
                    100 * need.cover(c.type))));
        }
        if (live && c.type == CloudType.NIMBOSTRATUS && need.frontal() < 0.15 && need.convergence() < 0.3
                && need.orographic() < 0.15) {
            out.add(new Problem("NS_NO_LIFT", String.format("front %.2f, low centre %.2f, upslope %.2f, source %s",
                    need.frontal(), need.convergence(), need.orographic(), need.sourceOf(c.type))));
        }
        boolean drizzle = (c.type == CloudType.STRATUS || c.type == CloudType.STRATOCUMULUS)
                && c.precipitation <= DRIZZLE_UNDER_HIGH;
        if (raining && c.type.layer() && need.subsidence() > 0.7 && !drizzle) {
            out.add(new Problem("RAIN_UNDER_HIGH", String.format("sinking %.2f", need.subsidence())));
        }
        if ((c.type == CloudType.CUMULONIMBUS_CALVUS || c.type == CloudType.CUMULONIMBUS_CAPILLATUS) && live
                && (air.q() < 0.8 * CloudDiagnostics.STORM_WATER || air.t() < 0)) {
            out.add(new Problem("STORM_COLD_DRY", String.format("air %.1f C, %.1f mm of water", air.t(), air.q())));
        }
        if (raining && need.rh() < 0.4) {
            out.add(new Problem("RAIN_DRY_AIR", String.format("rh %.2f, base y %.0f", need.rh(), c.baseY)));
        }
        if (c.precipitation > 0.02 && !Float.isInfinite(c.rainBottom) && rhNow > 0.85) {
            out.add(new Problem("VIRGA_MOIST_AIR", String.format("rh now %.2f", rhNow)));
        }
        // Over water the sun's heating reads 0: a cumulus drifting off the land isn't a night one.
        if (c.type.heap() && c.cause.startsWith(CloudDiagnostics.SUN) && now - c.birth < 1200 && air.surface() != 2
                && need.heating() <= 0) {
            out.add(new Problem("NIGHT_SUN", String.format("sun heating %.1f C", need.heating())));
        }
        return out;
    }

    /** The most a drizzling stratus or stratocumulus may rain under a high (strength 0-1). */
    static final double DRIZZLE_UNDER_HIGH = 0.45;

    private CloudChecks() {
    }
}
