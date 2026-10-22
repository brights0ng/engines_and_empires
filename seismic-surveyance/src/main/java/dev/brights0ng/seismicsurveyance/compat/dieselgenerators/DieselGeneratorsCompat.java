// Written by Gemini.
// Edited by Gemini: integrated with Sparse Ores multi-chunk shrinking oil reservoirs; include maxRadius in ore ID.
package dev.brights0ng.seismicsurveyance.compat.dieselgenerators;

import java.util.ArrayList;
import java.util.List;

import com.jesz.createdieselgenerators.CDGConfig;

import dev.brights0ng.seismicsurveyance.GeophoneRegistry;
import dev.brights0ng.seismicsurveyance.SeismicWave;
import dev.brights0ng.seismicsurveyance.WaveModel;
import dev.brights0ng.sparseores.compat.dieselgenerators.OilDeposit;
import dev.brights0ng.sparseores.compat.dieselgenerators.OilDepositLedger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Compatibility with Create: Diesel Generators for seismic oil surveying.
 */
public final class DieselGeneratorsCompat {

    public static final String MODID = "createdieselgenerators";

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    /**
     * Scans for multi-chunk oil reservoirs that can reflect vibrations back to listening brass geophones.
     */
    public static List<SeismicWave.Echoer> scanOilEchoers(ServerLevel level, Vec3 source, double range,
                                                          List<GeophoneRegistry.Entry> brassGeophones) {
        if (!isLoaded() || brassGeophones.isEmpty()) {
            return List.of();
        }

        boolean disableNormal = CDGConfig.DISABLE_NORMAL_OIL_CHUNKS.get();
        boolean disableHigh = CDGConfig.DISABLE_HIGH_OIL_CHUNKS.get();
        if (disableNormal && disableHigh) {
            return List.of();
        }

        double cellY = level.getMinBuildHeight() + 4.5;
        double dy = source.y - cellY;
        if (Math.abs(dy) > range) {
            return List.of();
        }

        double rangeSq = range * range;
        double maxHorizDist = Math.sqrt(rangeSq - dy * dy);
        int minX = Mth.floor(source.x - maxHorizDist);
        int maxX = Mth.floor(source.x + maxHorizDist);
        int minZ = Mth.floor(source.z - maxHorizDist);
        int maxZ = Mth.floor(source.z + maxHorizDist);

        OilDepositLedger ledger = OilDepositLedger.get(level);
        List<OilDeposit> deposits = ledger.getDepositsInBox(minX, minZ, maxX, maxZ);

        List<SeismicWave.Echoer> echoers = new ArrayList<>();
        for (OilDeposit deposit : deposits) {
            if (deposit.isDepleted()) {
                continue;
            }

            double cellX = deposit.centerX() + 0.5;
            double cellZ = deposit.centerZ() + 0.5;
            double maxR = deposit.field().maxRadius() * Math.sqrt(deposit.fullness());

            double toSourceDist = Math.hypot(cellX - source.x, cellZ - source.z);
            if (toSourceDist - maxR > maxHorizDist) {
                continue;
            }

            boolean reachedGeophone = false;
            for (GeophoneRegistry.Entry g : brassGeophones) {
                Vec3 gPos = g.position();
                double gDy = gPos.y - cellY;
                if (Math.abs(gDy) > range) {
                    continue;
                }
                double gMaxHoriz = Math.sqrt(rangeSq - gDy * gDy);
                double toGeophoneDist = Math.hypot(cellX - gPos.x, cellZ - gPos.z);
                if (toGeophoneDist - maxR <= gMaxHoriz) {
                    reachedGeophone = true;
                    break;
                }
            }
            if (!reachedGeophone) {
                continue;
            }

            int percent = (int) Math.round(deposit.fullness() * 100.0);
            double angleDeg = Math.toDegrees(deposit.strikeAngle());
            int radiusInt = (int) Math.round(deposit.field().maxRadius());
            String oreId = "crude_oil:" + deposit.trapType().id() + ":" + Math.round(angleDeg) + ":" + percent + ":" + radiusInt;

            long id = deposit.seed();
            WaveModel.OreCells cells = new WaveModel.OreCells(new double[]{cellX}, new double[]{cellY}, new double[]{cellZ});
            echoers.add(new SeismicWave.Echoer(id, oreId, cells));
        }

        return echoers;
    }

    private DieselGeneratorsCompat() {
    }
}
