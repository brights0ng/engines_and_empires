// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThermalTimingTest {
    @Test void percentilesUseNearestRankAndUnavailableCountersAreNotZeroCost() {
        var samples=new ThermalTiming.Samples();samples.add(-1);
        assertEquals(0,samples.summary(1).count());assertNull(samples.summary(1).mean());
        for(int n=100;n>=1;n--) { samples.add(n); }
        var summary=samples.summary(1);
        assertEquals(100,summary.count());assertEquals(50.5,summary.mean());assertEquals(50,summary.p50());
        assertEquals(95,summary.p95());assertEquals(99,summary.p99());assertEquals(100,summary.max());assertEquals(5050,summary.total());
    }
    @Test void counterDifferencesRejectUnavailableOrResetMeasurements() {
        assertEquals(-1,ThermalTiming.difference(-1,100));assertEquals(-1,ThermalTiming.difference(100,99));assertEquals(25,ThermalTiming.difference(100,125));
    }
    @Test void fixedPartialBlockSceneRetainsOpenFacesInsteadOfBecomingAFullWall() {
        var scene=new ThermalBenchmark.Scene("partial-test",24,16,24,3);
        var fixture=ThermalBenchmark.fixture(scene);
        int doorway=fixture.index(6,3,12);
        assertEquals(AirGrid.SOLID,fixture.kinds()[doorway]);
        assertEquals(AirGrid.WEST|AirGrid.EAST,fixture.open()[doorway]);
        assertEquals(9216,scene.cells());
        assertTrue(Double.isFinite(fixture.grid().offset(12,3,12)));
    }
    @Test void comparisonUsesMedianForkMeansAndPreservesARealBaseline(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var baseline=root.resolve("before");var after=root.resolve("after");
        java.nio.file.Files.createDirectories(baseline);java.nio.file.Files.createDirectories(after);
        writeForks(baseline,1,2,3,77);writeForks(after,2,4,6,77);
        ThermalBenchmark.summarize(baseline,null);ThermalBenchmark.summarize(after,baseline);
        var json=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(after.resolve("summary.json"))).getAsJsonObject();
        var row=json.getAsJsonArray("results").get(0).getAsJsonObject();
        assertEquals(4,row.get("wallMedianForkMeanMs").getAsDouble());assertEquals(2,row.get("baselineWallRatio").getAsDouble());
        assertEquals(2,row.get("wallMinForkMeanMs").getAsDouble());assertEquals(6,row.get("wallMaxForkMeanMs").getAsDouble());
    }
    @Test void comparisonRejectsChangedSceneGeometry(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var baseline=root.resolve("before");var after=root.resolve("after");
        java.nio.file.Files.createDirectories(baseline);java.nio.file.Files.createDirectories(after);
        writeForks(baseline,1,2,3,77);writeForks(after,2,4,6,78);ThermalBenchmark.summarize(baseline,null);
        assertThrows(IllegalStateException.class,()->ThermalBenchmark.summarize(after,baseline));
    }
    private static void writeForks(java.nio.file.Path path,double a,double b,double c,int fingerprint) throws Exception {
        int fork=0;
        for(double mean:new double[]{a,b,c}) {
            var row=java.util.Map.of("scene","fixture","cells",8,"phase","step-1s","geometryFingerprint",fingerprint,
                    "wallMs",java.util.Map.of("mean",mean),"cpuMs",java.util.Map.of("mean",mean));
            java.nio.file.Files.writeString(path.resolve("fork-"+(++fork)+".json"),new com.google.gson.Gson().toJson(java.util.Map.of("results",java.util.List.of(row))));
        }
    }

}
