// Written by Gemini.
package dev.brights0ng.empire.record;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

/**
 * Pure JSONL serialization, filename sanitization, angle-wrapping, inventory diffing, and summary aggregation for
 * player motion/action recordings (zero Minecraft imports).
 */
public final class MotionTraceJson {

    public static final int FORMAT_VERSION = 1;
    /** Horizontal speed below this threshold (blocks/tick) counts as an idle/paused tick in summary stats. */
    public static final double IDLE_SPEED_THRESHOLD = 0.01;

    private static final int MAX_LABEL_LENGTH = 48;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    /** Keeps only {@code [A-Za-z0-9_-]} (collapsing spaces to {@code _}), capped at {@value #MAX_LABEL_LENGTH} chars. */
    public static String sanitizeLabel(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean lastUnderscore = false;
        for (int i = 0; i < raw.length() && sb.length() < MAX_LABEL_LENGTH; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-') {
                sb.append(c);
                lastUnderscore = false;
            } else if (c == '_' || Character.isWhitespace(c)) {
                if (!lastUnderscore && sb.length() > 0) {
                    sb.append('_');
                    lastUnderscore = true;
                }
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '_') {
            end--;
        }
        return sb.substring(0, end);
    }

    /** Builds a safe timestamped {@code .jsonl} filename for a recording session. */
    public static String buildFilename(Instant startedAt, @Nullable String label) {
        String stamp = FILE_STAMP.format(startedAt);
        String clean = sanitizeLabel(label);
        return clean.isEmpty() ? stamp + ".jsonl" : stamp + "_" + clean + ".jsonl";
    }

    /** Shortest signed angular delta {@code current - previous} in degrees, wrapped to {@code [-180, 180]}. */
    public static float wrapAngleDelta(float currentDeg, float previousDeg) {
        float diff = (currentDeg - previousDeg) % 360.0F;
        if (diff > 180.0F) {
            diff -= 360.0F;
        } else if (diff < -180.0F) {
            diff += 360.0F;
        }
        return diff;
    }

    /** Computes sorted non-zero item count deltas ({@code after - before}) for container open/close logging. */
    public static Map<String, Integer> diffInventoryCounts(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> deltas = new TreeMap<>();
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            int diff = after.getOrDefault(e.getKey(), 0) - e.getValue();
            if (diff != 0) {
                deltas.put(e.getKey(), diff);
            }
        }
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            if (!before.containsKey(e.getKey()) && e.getValue() != 0) {
                deltas.put(e.getKey(), e.getValue());
            }
        }
        return deltas;
    }

    /** Formats a JSON string literal with proper escaping. */
    public static String jsonString(@Nullable String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Formats a finite double to {@code decimals} places using {@link Locale#ROOT}; non-finite becomes {@code 0}. */
    public static String fmt(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        double scale = Math.pow(10.0, decimals);
        double rounded = Math.round(value * scale) / scale;
        if (rounded == 0.0) {
            rounded = 0.0; // avoid "-0.0"
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", rounded);
    }

    /** Builds a JSON object from a map of string keys to integer values. */
    public static String intMapJson(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(jsonString(e.getKey())).append(':').append(e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    /** Formats a {@code "meta"} header line written at recording start. */
    public static String buildMetaLine(long gameTime, Instant startedAt, String label, String playerName,
                                       String playerUuid, String dimension, String gameMode,
                                       double startX, double startY, double startZ) {
        return "{\"t\":\"meta\",\"v\":" + FORMAT_VERSION
                + ",\"ms\":0,\"gt\":" + gameTime
                + ",\"startedAt\":" + jsonString(startedAt.toString())
                + ",\"label\":" + jsonString(label)
                + ",\"player\":" + jsonString(playerName)
                + ",\"playerUuid\":" + jsonString(playerUuid)
                + ",\"dimension\":" + jsonString(dimension)
                + ",\"gameMode\":" + jsonString(gameMode)
                + ",\"tickRateHz\":20"
                + ",\"startPos\":[" + fmt(startX, 4) + ',' + fmt(startY, 4) + ',' + fmt(startZ, 4) + "]}";
    }

    /** Snapshot of one client tick's movement, look, input, hotbar, and crosshair state. */
    public record TickFrame(
            long ms,
            long gameTime,
            int seq,
            double x, double y, double z,
            double vx, double vy, double vz,
            double speedH,
            float yaw, float pitch,
            float dyaw, float dpitch,
            float forward, float strafe,
            boolean jumping, boolean sneaking, boolean sprinting, boolean onGround,
            boolean attackDown, boolean useDown,
            int hotbarSlot,
            String heldItem,
            int heldCount,
            @Nullable String screen,
            @Nullable String aimJson
    ) {
    }

    /** Formats a {@code "tick"} telemetry line. */
    public static String buildTickLine(TickFrame f) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"t\":\"tick\",\"ms\":").append(f.ms())
                .append(",\"gt\":").append(f.gameTime())
                .append(",\"seq\":").append(f.seq())
                .append(",\"pos\":[").append(fmt(f.x(), 4)).append(',').append(fmt(f.y(), 4)).append(',').append(fmt(f.z(), 4)).append(']')
                .append(",\"vel\":[").append(fmt(f.vx(), 4)).append(',').append(fmt(f.vy(), 4)).append(',').append(fmt(f.vz(), 4)).append(']')
                .append(",\"speedH\":").append(fmt(f.speedH(), 4))
                .append(",\"yaw\":").append(fmt(f.yaw(), 2))
                .append(",\"pitch\":").append(fmt(f.pitch(), 2))
                .append(",\"dyaw\":").append(fmt(f.dyaw(), 2))
                .append(",\"dpitch\":").append(fmt(f.dpitch(), 2))
                .append(",\"in\":{\"f\":").append(fmt(f.forward(), 2))
                .append(",\"s\":").append(fmt(f.strafe(), 2))
                .append(",\"jmp\":").append(f.jumping())
                .append(",\"snk\":").append(f.sneaking())
                .append(",\"spr\":").append(f.sprinting())
                .append(",\"gnd\":").append(f.onGround())
                .append(",\"atk\":").append(f.attackDown())
                .append(",\"use\":").append(f.useDown())
                .append('}')
                .append(",\"slot\":").append(f.hotbarSlot())
                .append(",\"held\":").append(jsonString(f.heldItem()))
                .append(",\"heldN\":").append(f.heldCount());
        if (f.screen() != null) {
            sb.append(",\"screen\":").append(jsonString(f.screen()));
        }
        if (f.aimJson() != null) {
            sb.append(",\"aim\":").append(f.aimJson());
        }
        sb.append('}');
        return sb.toString();
    }

    /** Formats a discrete {@code "event"} line wrapping a pre-built JSON object payload {@code dataJson}. */
    public static String buildEventLine(long ms, long gameTime, String source, String kind, String dataJson) {
        String safeData = (dataJson == null || dataJson.isBlank()) ? "{}" : dataJson;
        return "{\"t\":\"event\",\"ms\":" + ms
                + ",\"gt\":" + gameTime
                + ",\"src\":" + jsonString(source)
                + ",\"kind\":" + jsonString(kind)
                + ",\"data\":" + safeData + '}';
    }

    /** Running session statistics written into the closing {@code "summary"} line and chat status. */
    public static final class SummaryTracker {

        private int ticks;
        private int idleTicks;
        private int sprintTicks;
        private int sneakTicks;
        private int jumpTicks;
        private int screenOpenTicks;
        private double horizontalDistance;
        private final Map<String, Integer> eventCounts = new TreeMap<>();

        public void recordTick(double speedH, boolean sprinting, boolean sneaking, boolean jumping, boolean screenOpen) {
            ticks++;
            if (Double.isFinite(speedH) && speedH >= 0.0) {
                horizontalDistance += speedH;
                if (speedH < IDLE_SPEED_THRESHOLD) {
                    idleTicks++;
                }
            } else {
                idleTicks++;
            }
            if (sprinting) {
                sprintTicks++;
            }
            if (sneaking) {
                sneakTicks++;
            }
            if (jumping) {
                jumpTicks++;
            }
            if (screenOpen) {
                screenOpenTicks++;
            }
        }

        public void recordEvent(String kind) {
            if (kind != null && !kind.isBlank()) {
                eventCounts.merge(kind, 1, Integer::sum);
            }
        }

        public int ticks() {
            return ticks;
        }

        public int idleTicks() {
            return idleTicks;
        }

        public double idleFraction() {
            return ticks > 0 ? (double) idleTicks / ticks : 0.0;
        }

        public double horizontalDistance() {
            return horizontalDistance;
        }

        public int totalEvents() {
            int total = 0;
            for (int c : eventCounts.values()) {
                total += c;
            }
            return total;
        }

        public Map<String, Integer> eventCounts() {
            return eventCounts;
        }

        public String buildSummaryLine(long durationMs, long gameTime, String stopReason) {
            return "{\"t\":\"summary\",\"ms\":" + durationMs
                    + ",\"gt\":" + gameTime
                    + ",\"reason\":" + jsonString(stopReason)
                    + ",\"ticks\":" + ticks
                    + ",\"idleTicks\":" + idleTicks
                    + ",\"idleFraction\":" + fmt(idleFraction(), 4)
                    + ",\"sprintTicks\":" + sprintTicks
                    + ",\"sneakTicks\":" + sneakTicks
                    + ",\"jumpTicks\":" + jumpTicks
                    + ",\"screenOpenTicks\":" + screenOpenTicks
                    + ",\"horizontalDist\":" + fmt(horizontalDistance, 3)
                    + ",\"totalEvents\":" + totalEvents()
                    + ",\"events\":" + intMapJson(eventCounts) + '}';
        }
    }

    private MotionTraceJson() {
    }
}
