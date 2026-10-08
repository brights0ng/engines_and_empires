package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A snapshot of the sky for benchmarking the mesher offline ({@code /eae clouds dump}, 2026-10-08): every formation's
 * domes, where the camera was relative to each, and the cloud settings ({@link CloudTuning}). Plain text, one line per
 * formation and dome; {@link CloudShape}'s fields are written in record order, so it keeps working as fields are added
 * (old dumps then fail to load, rather than load wrong). Pure Java.
 */
final class CloudDump {

    /** One formation and where the camera was from its anchor (anchor-local x, z; world y). */
    record Entry(CloudFormation formation, double camX, double camY, double camZ) {
    }

    /** The sky at game time {@code time}, at configured voxel size {@code voxel} and draw distance. */
    record Snapshot(double time, int voxel, double drawDistance, List<Entry> entries) {
    }

    static void write(Path path, Snapshot s) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("sky\t" + s.time() + "\t" + s.voxel() + "\t" + s.drawDistance());
        StringBuilder tuning = new StringBuilder("tuning");
        for (Field f : tuningFields()) {
            try {
                Object v = f.get(null);
                tuning.append('\t').append(f.getName()).append('=')
                        .append(v instanceof double[] d ? join(d) : String.valueOf(v));
            } catch (IllegalAccessException e) {
                throw new IOException(e);
            }
        }
        out.add(tuning.toString());
        for (Entry e : s.entries()) {
            CloudFormation f = e.formation();
            out.add("formation\t" + f.regionId() + "\t" + f.time() + "\t" + e.camX() + "\t" + e.camY() + "\t"
                    + e.camZ());
            for (CloudShape m : f.members()) {
                StringBuilder line = new StringBuilder("dome");
                for (RecordComponent c : CloudShape.class.getRecordComponents()) {
                    try {
                        line.append('\t').append(c.getAccessor().invoke(m));
                    } catch (ReflectiveOperationException ex) {
                        throw new IOException(ex);
                    }
                }
                out.add(line.toString());
            }
        }
        Files.write(path, out, StandardCharsets.UTF_8);
    }

    /** Reads a dump, and sets {@link CloudTuning} to the settings it was taken with. */
    static Snapshot read(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        RecordComponent[] comps = CloudShape.class.getRecordComponents();
        Class<?>[] types = Arrays.stream(comps).map(RecordComponent::getType).toArray(Class<?>[]::new);
        double time = 0, draw = 0;
        int voxel = 4;
        List<Entry> entries = new ArrayList<>();
        String[] pending = null;
        List<CloudShape> domes = new ArrayList<>();
        for (String line : lines) {
            String[] t = line.split("\t");
            switch (t[0]) {
                case "sky" -> {
                    time = Double.parseDouble(t[1]);
                    voxel = Integer.parseInt(t[2]);
                    draw = Double.parseDouble(t[3]);
                }
                case "tuning" -> applyTuning(t);
                case "formation" -> {
                    flush(pending, domes, entries);
                    pending = t;
                    domes = new ArrayList<>();
                }
                case "dome" -> {
                    if (t.length - 1 != comps.length) {
                        throw new IOException("dump has " + (t.length - 1) + " dome fields, CloudShape has "
                                + comps.length + ": take a new dump");
                    }
                    Object[] args = new Object[comps.length];
                    for (int i = 0; i < comps.length; i++) {
                        args[i] = parse(types[i], t[i + 1]);
                    }
                    try {
                        domes.add(CloudShape.class.getDeclaredConstructor(types).newInstance(args));
                    } catch (ReflectiveOperationException ex) {
                        throw new IOException(ex);
                    }
                }
                default -> {
                }
            }
        }
        flush(pending, domes, entries);
        return new Snapshot(time, voxel, draw, entries);
    }

    private static void flush(String[] f, List<CloudShape> domes, List<Entry> entries) {
        if (f == null || domes.isEmpty()) {
            return;
        }
        CloudFormation formation = CloudFormation.of(UUID.fromString(f[1]), domes, Double.parseDouble(f[2]));
        entries.add(new Entry(formation, Double.parseDouble(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5])));
    }

    private static Object parse(Class<?> type, String s) {
        if (type == UUID.class) {
            return UUID.fromString(s);
        } else if (type == String.class) {
            return s;
        } else if (type == double.class) {
            return Double.parseDouble(s);
        } else if (type == float.class) {
            return Float.parseFloat(s);
        } else if (type == long.class) {
            return Long.parseLong(s);
        } else if (type == int.class) {
            return Integer.parseInt(s);
        } else if (type == boolean.class) {
            return Boolean.parseBoolean(s);
        }
        throw new IllegalArgumentException("can't read a " + type + " from a dump");
    }

    /** The settings: every non-final static field of {@link CloudTuning} but its version counter. */
    private static List<Field> tuningFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : CloudTuning.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) && !Modifier.isFinal(mod) && !f.getName().equals("version")) {
                out.add(f);
            }
        }
        return out;
    }

    private static void applyTuning(String[] t) throws IOException {
        for (int i = 1; i < t.length; i++) {
            int eq = t[i].indexOf('=');
            String name = t[i].substring(0, eq), value = t[i].substring(eq + 1);
            try {
                Field f = CloudTuning.class.getDeclaredField(name);
                Class<?> type = f.getType();
                f.set(null, type == double[].class ? Arrays.stream(value.split(",")).mapToDouble(Double::parseDouble)
                        .toArray() : parse(type, value));
            } catch (NoSuchFieldException e) {
                // A setting since removed: ignore.
            } catch (IllegalAccessException e) {
                throw new IOException(e);
            }
        }
        CloudTuning.version++;
    }

    private static String join(double[] d) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            b.append(i == 0 ? "" : ",").append(d[i]);
        }
        return b.toString();
    }

    private CloudDump() {
    }
}
