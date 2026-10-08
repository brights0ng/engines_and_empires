package dev.brights0ng.enginesandempires.weather.sim.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import dev.brights0ng.enginesandempires.weather.field.FieldTile;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.sim.CloudSim;
import dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud;
import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The weather systems saved with the Overworld ({@code data/engines_and_empires_weather.dat}): every low and high,
 * the next id, which areas have had weather recently (so a returning player finds young waves and a newcomer finds
 * weather already under way), whether the one-time spin-up has run, and how far {@code /eae weather step} has moved
 * the simulation ahead of the game clock. Phase 3 adds the atmosphere field's tiles (temperatures, moisture, recent
 * precipitation, terrain heights); phase 4a the clouds near the players (so the sky is still there after a restart).
 */
public final class WeatherSimData extends SavedData {

    public static final String NAME = "engines_and_empires_weather";
    private static final int VERSION = 1;

    final List<WeatherSystem> systems = new ArrayList<>();
    long nextId;
    /** Coverage cell key -> simulation time it was last inside a player's zone. */
    final Map<Long, Long> cells = new HashMap<>();
    boolean spunUp;
    /** Simulation time minus game time, ticks. */
    long offset;
    final AtmosphereField field = new AtmosphereField();
    final CloudSim clouds = new CloudSim();

    public static SavedData.Factory<WeatherSimData> factory() {
        return new SavedData.Factory<>(WeatherSimData::new, WeatherSimData::load, null);
    }

    static WeatherSimData load(CompoundTag tag, HolderLookup.Provider registries) {
        WeatherSimData d = new WeatherSimData();
        if (tag.getInt("version") != VERSION) {
            return d;
        }
        d.nextId = tag.getLong("nextId");
        d.spunUp = tag.getBoolean("spunUp");
        d.offset = tag.getLong("offset");
        ListTag list = tag.getList("systems", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag s = list.getCompound(i);
            WeatherSystem w = new WeatherSystem(s.getLong("id"),
                    s.getBoolean("high") ? WeatherSystem.Kind.HIGH : WeatherSystem.Kind.LOW,
                    s.getDouble("x"), s.getDouble("z"), s.getInt("track"), s.getInt("hem"), s.getLong("age"),
                    s.getLong("lifetime"), s.getDouble("peak"), s.getDouble("radius"), s.getBoolean("blocking"));
            w.nudge = s.contains("nudge") ? s.getDouble("nudge") : 1;
            d.systems.add(w);
        }
        long[] keys = tag.getLongArray("cellKeys");
        long[] times = tag.getLongArray("cellTimes");
        for (int i = 0; i < Math.min(keys.length, times.length); i++) {
            d.cells.put(keys[i], times[i]);
        }
        ListTag tiles = tag.getList("tiles", Tag.TAG_COMPOUND);
        int n = AtmosphereField.SIZE * AtmosphereField.SIZE;
        for (int i = 0; i < tiles.size(); i++) {
            CompoundTag t = tiles.getCompound(i);
            FieldTile tile = new FieldTile(t.getInt("tx"), t.getInt("tz"));
            tile.lastActive = t.getLong("active");
            if (!readFloats(t, "t", tile.t, n) || !readFloats(t, "a", tile.a, n) || !readFloats(t, "q", tile.q, n)
                    || !readFloats(t, "p", tile.p, n) || !readFloats(t, "base", tile.base, n)) {
                continue;
            }
            readFloats(t, "elevation", tile.elevation, n);
            readFloats(t, "humidity", tile.humidity, n);
            readFloats(t, "r", tile.r, n);
            byte[] surface = t.getByteArray("surface");
            if (surface.length == n) {
                System.arraycopy(surface, 0, tile.surface, 0, n);
            }
            d.field.put(tile);
        }
        ListTag clouds = tag.getList("clouds", Tag.TAG_COMPOUND);
        for (int i = 0; i < clouds.size(); i++) {
            SimCloud c = readCloud(clouds.getCompound(i));
            if (c != null) {
                d.clouds.add(c);
            }
        }
        return d;
    }

    private static SimCloud readCloud(CompoundTag c) {
        CloudType type = CloudType.of(c.getString("type"));
        int[] bits = c.getIntArray("domes");
        if (type == null || bits.length == 0 || bits.length % 5 != 0) {
            return null;
        }
        List<SimCloud.Dome> list = new ArrayList<>();
        for (int i = 0; i < bits.length; i += 5) {
            list.add(new SimCloud.Dome(Float.intBitsToFloat(bits[i]), Float.intBitsToFloat(bits[i + 1]),
                    Float.intBitsToFloat(bits[i + 2]), bits[i + 3], Float.intBitsToFloat(bits[i + 4])));
        }
        SimCloud s = new SimCloud(c.getUUID("id"), type, c.getDouble("x"), c.getDouble("z"), c.getDouble("vx"),
                c.getDouble("vz"), c.getLong("ref"), c.getLong("birth"), c.getLong("end"),
                (float) dev.brights0ng.enginesandempires.weather.cloud.CloudScale.floorBase(c.getFloat("base")),
                c.getFloat("thickness"), c.getFloat("coverage"), c.getFloat("precipitation"), c.getFloat("lightning"),
                list, 0, c.getBoolean("dissolving"));
        s.rainBottom = c.contains("rainBottom") ? c.getFloat("rainBottom") : Float.NEGATIVE_INFINITY;
        if (c.contains("tvx")) {
            s.tvx = c.getDouble("tvx");
            s.tvz = c.getDouble("tvz");
        }
        return s;
    }

    private static CompoundTag writeCloud(SimCloud s) {
        CompoundTag c = new CompoundTag();
        c.putUUID("id", s.id);
        c.putString("type", s.type.id);
        c.putDouble("x", s.x);
        c.putDouble("z", s.z);
        c.putDouble("vx", s.vx);
        c.putDouble("vz", s.vz);
        c.putDouble("tvx", s.tvx);
        c.putDouble("tvz", s.tvz);
        c.putLong("ref", s.refTick);
        c.putLong("birth", s.birth);
        c.putLong("end", s.end);
        c.putFloat("base", s.baseY);
        c.putFloat("thickness", s.thickness);
        c.putFloat("coverage", s.coverage);
        c.putFloat("precipitation", s.precipitation);
        c.putFloat("lightning", s.lightning);
        c.putFloat("rainBottom", s.rainBottom);
        c.putBoolean("dissolving", s.dissolving);
        int[] bits = new int[s.domes.size() * 5];
        for (int i = 0; i < s.domes.size(); i++) {
            SimCloud.Dome d = s.domes.get(i);
            bits[i * 5] = Float.floatToRawIntBits(d.dx());
            bits[i * 5 + 1] = Float.floatToRawIntBits(d.dz());
            bits[i * 5 + 2] = Float.floatToRawIntBits(d.radius());
            bits[i * 5 + 3] = d.seed();
            bits[i * 5 + 4] = Float.floatToRawIntBits(d.tower());
        }
        c.putIntArray("domes", bits);
        return c;
    }

    private static boolean readFloats(CompoundTag tag, String key, float[] into, int n) {
        int[] bits = tag.getIntArray(key);
        if (bits.length != n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            into[i] = Float.intBitsToFloat(bits[i]);
        }
        return true;
    }

    private static void writeFloats(CompoundTag tag, String key, float[] values) {
        int[] bits = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Float.floatToRawIntBits(values[i]);
        }
        tag.putIntArray(key, bits);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", VERSION);
        tag.putLong("nextId", nextId);
        tag.putBoolean("spunUp", spunUp);
        tag.putLong("offset", offset);
        ListTag list = new ListTag();
        for (WeatherSystem w : systems) {
            CompoundTag s = new CompoundTag();
            s.putLong("id", w.id);
            s.putBoolean("high", w.kind == WeatherSystem.Kind.HIGH);
            s.putDouble("x", w.x);
            s.putDouble("z", w.z);
            s.putInt("track", w.track);
            s.putInt("hem", w.hemisphere);
            s.putLong("age", w.age);
            s.putLong("lifetime", w.lifetime);
            s.putDouble("peak", w.peak);
            s.putDouble("radius", w.maxRadius);
            s.putBoolean("blocking", w.blocking);
            s.putDouble("nudge", w.nudge);
            list.add(s);
        }
        tag.put("systems", list);
        long[] keys = new long[cells.size()];
        long[] times = new long[cells.size()];
        int i = 0;
        for (Map.Entry<Long, Long> e : cells.entrySet()) {
            keys[i] = e.getKey();
            times[i++] = e.getValue();
        }
        tag.put("cellKeys", new LongArrayTag(keys));
        tag.put("cellTimes", new LongArrayTag(times));
        ListTag tiles = new ListTag();
        for (FieldTile tile : field.tiles().values()) {
            CompoundTag t = new CompoundTag();
            t.putInt("tx", tile.tx);
            t.putInt("tz", tile.tz);
            t.putLong("active", tile.lastActive);
            writeFloats(t, "t", tile.t);
            writeFloats(t, "a", tile.a);
            writeFloats(t, "q", tile.q);
            writeFloats(t, "p", tile.p);
            writeFloats(t, "r", tile.r);
            writeFloats(t, "base", tile.base);
            writeFloats(t, "elevation", tile.elevation);
            writeFloats(t, "humidity", tile.humidity);
            t.putByteArray("surface", tile.surface);
            tiles.add(t);
        }
        tag.put("tiles", tiles);
        ListTag cloudList = new ListTag();
        for (SimCloud s : clouds.clouds()) {
            cloudList.add(writeCloud(s));
        }
        tag.put("clouds", cloudList);
        return tag;
    }
}
