package dev.brights0ng.enginesandempires.weather.cloud.sim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.field.Moisture;

/** The audit's rules flag what they should and leave healthy clouds alone. */
class CloudChecksTest {

    static boolean has(List<CloudChecks.Problem> ps, String code) {
        return ps.stream().anyMatch(p -> p.code().equals(code));
    }

    @Test
    void aNimbostratusWithNothingUnderItIsFlagged() {
        SimCloud ns = CloudRainTest.cloud(CloudType.NIMBOSTRATUS, 1);
        CloudDiagnostics.Air air = CloudDiagnosticsTest.air(5, 0.7, 0, 0);
        CloudDiagnostics.Need need = CloudDiagnostics.diagnose(0, 0, air, CloudDiagnostics.Systems.NONE, 9000, 0);
        List<CloudChecks.Problem> ps = CloudChecks.check(ns, need, air, 0);
        assertTrue(has(ps, "NS_NO_LIFT"), ps.toString());
        assertTrue(has(ps, "UNWANTED"), ps.toString());
    }

    @Test
    void aStormInColdAirIsFlagged() {
        SimCloud cb = CloudRainTest.cloud(CloudType.CUMULONIMBUS_CALVUS, 2);
        CloudDiagnostics.Air cold = new CloudDiagnostics.Air(-8, -18, 0.8 * Moisture.capacity(-8), 0, -8, 0.5, 0, 0, 0,
                0, 3, 0, 8, 0, 0);
        CloudDiagnostics.Need need = CloudDiagnostics.diagnose(0, 0, cold, CloudDiagnostics.Systems.NONE, 9000, -1);
        assertTrue(has(CloudChecks.check(cb, need, cold, 0), "STORM_COLD_DRY"));
    }

    @Test
    void aHealthyFairWeatherCumulusPasses() {
        SimCloud cu = CloudRainTest.cloud(CloudType.CUMULUS_HUMILIS, 3);
        cu.cause = CloudDiagnostics.SUN;
        CloudDiagnostics.Air air = CloudDiagnosticsTest.air(20, 0.55, 0, 0);
        CloudDiagnostics.Need need = CloudDiagnostics.diagnose(0, 0, air, CloudDiagnostics.Systems.NONE, 9000, 1);
        assertTrue(CloudChecks.check(cu, need, air, 0).isEmpty());
    }
}
