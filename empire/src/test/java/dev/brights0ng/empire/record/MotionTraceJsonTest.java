// Written by Gemini.
package dev.brights0ng.empire.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.brights0ng.empire.record.MotionTraceJson.SummaryTracker;
import dev.brights0ng.empire.record.MotionTraceJson.TickFrame;

class MotionTraceJsonTest {

    @Test
    void sanitizeLabelAndFilenameProduceSafeNames() {
        assertEquals("", MotionTraceJson.sanitizeLabel(null));
        assertEquals("", MotionTraceJson.sanitizeLabel("   !!!   "));
        assertEquals("wheat_harvest-1", MotionTraceJson.sanitizeLabel("  wheat   harvest-1 /..*  "));
        assertEquals(48, MotionTraceJson.sanitizeLabel("a".repeat(80)).length());

        Instant t = Instant.parse("2026-11-26T20:45:10Z");
        assertEquals("20261126-204510.jsonl", MotionTraceJson.buildFilename(t, ""));
        assertEquals("20261126-204510_farm_run_1.jsonl", MotionTraceJson.buildFilename(t, "farm run 1"));
    }

    @Test
    void wrapAngleDeltaHandles360BoundaryShortestPath() {
        assertEquals(0.0F, MotionTraceJson.wrapAngleDelta(45.0F, 45.0F), 1e-4F);
        assertEquals(10.0F, MotionTraceJson.wrapAngleDelta(5.0F, 355.0F), 1e-4F);
        assertEquals(-10.0F, MotionTraceJson.wrapAngleDelta(355.0F, 5.0F), 1e-4F);
        assertEquals(20.0F, MotionTraceJson.wrapAngleDelta(-170.0F, 170.0F), 1e-4F);
        assertEquals(-20.0F, MotionTraceJson.wrapAngleDelta(170.0F, -170.0F), 1e-4F);
    }

    @Test
    void diffInventoryCountsReportsDepositsWithdrawalsAndOmitsUnchanged() {
        Map<String, Integer> before = Map.of(
                "minecraft:wheat", 64,
                "minecraft:bread", 5,
                "minecraft:wheat_seeds", 10);
        Map<String, Integer> after = Map.of(
                "minecraft:bread", 5,
                "minecraft:wheat_seeds", 42,
                "minecraft:iron_hoe", 1);

        Map<String, Integer> diff = MotionTraceJson.diffInventoryCounts(before, after);
        assertEquals(Map.of(
                "minecraft:iron_hoe", 1,
                "minecraft:wheat", -64,
                "minecraft:wheat_seeds", 32), diff);
        assertFalse(diff.containsKey("minecraft:bread"));
    }

    @Test
    void emittedJsonLinesAreValidJsonWithExpectedFields() {
        String meta = MotionTraceJson.buildMetaLine(
                1200L, Instant.parse("2026-11-26T20:45:10Z"), "harvest_test", "Dev \"1\"",
                "00000000-0000-0000-0000-000000000001", "minecraft:overworld", "survival",
                10.5, 64.0, -3.25);
        JsonObject metaObj = JsonParser.parseString(meta).getAsJsonObject();
        assertEquals("meta", metaObj.get("t").getAsString());
        assertEquals(MotionTraceJson.FORMAT_VERSION, metaObj.get("v").getAsInt());
        assertEquals("Dev \"1\"", metaObj.get("player").getAsString());
        assertEquals(3, metaObj.getAsJsonArray("startPos").size());

        TickFrame frame = new TickFrame(
                50L, 1201L, 0,
                10.65, 64.0, -3.25,
                0.15, 0.0, 0.0, 0.15,
                90.0F, 15.5F, 2.5F, -1.0F,
                1.0F, 0.0F,
                false, false, false, true,
                true, false,
                2, "minecraft:wheat_seeds", 32,
                null,
                "{\"kind\":\"block\",\"pos\":[12,64,-3],\"face\":\"up\",\"block\":\"minecraft:wheat\",\"cropAge\":7,\"mature\":true,\"dist\":2.15}");
        JsonObject tickObj = JsonParser.parseString(MotionTraceJson.buildTickLine(frame)).getAsJsonObject();
        assertEquals("tick", tickObj.get("t").getAsString());
        assertEquals(2, tickObj.get("slot").getAsInt());
        assertEquals("minecraft:wheat_seeds", tickObj.get("held").getAsString());
        assertTrue(tickObj.getAsJsonObject("in").get("atk").getAsBoolean());
        assertTrue(tickObj.getAsJsonObject("aim").get("mature").getAsBoolean());
        assertFalse(tickObj.has("screen"));

        String eventLine = MotionTraceJson.buildEventLine(
                125L, 1202L, "server", "block_break",
                "{\"pos\":[12,64,-3],\"block\":\"minecraft:wheat\",\"cropAge\":7,\"mature\":true}");
        JsonObject eventObj = JsonParser.parseString(eventLine).getAsJsonObject();
        assertEquals("event", eventObj.get("t").getAsString());
        assertEquals("server", eventObj.get("src").getAsString());
        assertEquals("block_break", eventObj.get("kind").getAsString());

        SummaryTracker summary = new SummaryTracker();
        summary.recordTick(0.0, false, false, false, false);
        summary.recordTick(0.2, true, false, true, false);
        summary.recordTick(0.005, false, true, false, true);
        summary.recordEvent("block_break");
        summary.recordEvent("block_break");
        summary.recordEvent("mouse_button");

        JsonObject sumObj = JsonParser.parseString(summary.buildSummaryLine(150L, 1203L, "command")).getAsJsonObject();
        assertEquals("summary", sumObj.get("t").getAsString());
        assertEquals(3, sumObj.get("ticks").getAsInt());
        assertEquals(2, sumObj.get("idleTicks").getAsInt());
        assertEquals(3, sumObj.get("totalEvents").getAsInt());
        assertEquals(2, sumObj.getAsJsonObject("events").get("block_break").getAsInt());
    }
}
