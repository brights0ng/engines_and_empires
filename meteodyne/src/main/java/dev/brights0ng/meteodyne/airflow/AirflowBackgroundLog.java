// Written by Claude (GreedyFluids M8c c0).
package dev.brights0ng.meteodyne.airflow;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import dev.brights0ng.greedyfluids.grid.Material;

/**
 * The recorder's background dump (GreedyFluids M8c c0; Bright, 2026-11-18): what each region was built from and every
 * input sent to it afterwards, so a GreedyFluids bench can replay an in-game night on the real geometry, background and
 * sky. Pure Java. The server thread only hands over immutable values (specs, section codes, sent lattices, fresh tops
 * arrays); a single writer thread encodes and gzips them in order, so a recording costs the server thread nothing
 * measurable.
 *
 * <p><b>Format</b> ({@code background.gfbg}): a gzip stream of big-endian {@code DataOutput} values.
 * <ul>
 *   <li>Header: int {@link #MAGIC} ("GFBG"), int {@link #VERSION}, long the game tick the recording started.</li>
 *   <li>Records: byte tag, long game tick, int region id, then by tag:
 *     <ul>
 *       <li>{@link #REGION}: int x0, y0, z0 (world blocks), nx, ny, nz (cells); double κ (m²/s) and the surface film
 *           (s/m); boolean restored (built from saved temperatures, which are not in the dump); boolean snapshot (a
 *           region already running when the recording started: its cells are as built, before any later edits);
 *           int palette size, then per code: byte kind ({@link AirflowCells.Kind} ordinal: 0 air, 1 solid, 2 door
 *           shut, 3 door open), double conductance per block (m/s), heat capacity relative to air, fixed wall
 *           temperature (C, NaN for none); the codes, nx·ny·nz bytes in region-cell order (y·nz + z)·nx + x; int
 *           source count, then per source int cell, double watts, double limit (C, NaN for none); then a weather
 *           body.</li>
 *       <li>{@link #WEATHER}: a weather body.</li>
 *       <li>{@link #EDIT}: int x, y, z (region cells), then the new cell as one palette entry.</li>
 *       <li>{@link #SOURCE}: int x, y, z (region cells), double watts, double limit.</li>
 *       <li>{@link #BODIES}: int count, then per body double x, y, z (region metres) and watts. Written when the count
 *           changes or a body moves by more than {@link #BODY_STEP} blocks.</li>
 *       <li>{@link #RETIRE}: nothing more.</li>
 *       <li>{@link #END}: last; tick 0 and region 0.</li>
 *     </ul></li>
 *   <li>A weather body: double background (C; the uniform value, or the lattice's first point), wind x and z (m/s);
 *       int lattice length (−1: unchanged since this region's last weather; 0: none, a uniform background), then the
 *       floats ({@link AirflowField} lattice, C, ordered (j·lz + k)·lx + i); int tops length (−1 unchanged, 0 none),
 *       then each column's first open height (region cells, ordered z·nx + x).</li>
 * </ul>
 * {@link #read} decodes a file, filling in unchanged lattices and tops.
 */
public final class AirflowBackgroundLog implements AutoCloseable {

    public static final int MAGIC = 0x47464247, VERSION = 1;
    public static final byte END = 0, REGION = 1, WEATHER = 2, EDIT = 3, SOURCE = 4, BODIES = 5, RETIRE = 6;
    /** A body that moved less than this since the last bodies written isn't written again, blocks. */
    public static final double BODY_STEP = 0.25;
    static final int UNCHANGED = -1;

    private final ExecutorService writer;
    private final DataOutputStream out;
    // writer thread only
    private final Map<Integer, float[]> lastLattice = new HashMap<>();
    private final Map<Integer, int[]> lastTops = new HashMap<>();
    private final Map<Integer, double[]> lastBodies = new HashMap<>();
    private volatile IOException error;
    private volatile long regions, weathers, edits, sources, bodies;
    private boolean closed;

    /** Opens {@code file} and writes the header; {@code startTick} is the game tick now. */
    public AirflowBackgroundLog(Path file, long startTick) throws IOException {
        out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file), 1 << 16)));
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeLong(startTick);
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "meteodyne-airflow-background-log");
            t.setDaemon(true);
            return t;
        });
    }

    /** A region built from {@code spec} (or, with {@code snapshot}, already running when the recording started). */
    void region(long tick, int id, AirflowRegionModel.Spec spec, boolean snapshot) {
        submit(() -> {
            head(REGION, tick, id);
            int nx = spec.nx(), ny = spec.ny(), nz = spec.nz();
            out.writeInt(spec.x0());
            out.writeInt(spec.y0());
            out.writeInt(spec.z0());
            out.writeInt(nx);
            out.writeInt(ny);
            out.writeInt(nz);
            out.writeDouble(spec.kappa());
            out.writeDouble(spec.table().filmResistance());
            out.writeBoolean(spec.saved() != null);
            out.writeBoolean(snapshot);
            AirflowCells.Table table = spec.table();
            out.writeInt(table.size());
            for (int c = 0; c < table.size(); c++) {
                cell(table.cell((byte) c));
            }
            out.write(codes(spec));
            int[] at = spec.sourceCells();
            out.writeInt(at.length);
            for (int i = 0; i < at.length; i++) {
                out.writeInt(at[i]);
                out.writeDouble(spec.sourceWatts()[i]);
                out.writeDouble(spec.sourceLimits() == null ? Double.NaN : spec.sourceLimits()[i]);
            }
            lastLattice.remove(id);
            lastTops.remove(id);
            lastBodies.remove(id);
            weatherBody(id, spec.backgroundT(), spec.windX(), spec.windZ(), spec.lattice(), spec.tops());
            regions++;
        });
    }

    /** Weather sent to a region. */
    void weather(long tick, int id, AirflowRegionModel.Weather w) {
        submit(() -> {
            head(WEATHER, tick, id);
            weatherBody(id, w.backgroundT(), w.windX(), w.windZ(), w.lattice(), w.tops());
            weathers++;
        });
    }

    /** A cell edit sent to a region (region cells). */
    void edit(long tick, int id, int x, int y, int z, AirflowCells.Cell after) {
        submit(() -> {
            head(EDIT, tick, id);
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            cell(after);
            edits++;
        });
    }

    /** A source change sent to a region (region cells). */
    void source(long tick, int id, int x, int y, int z, double watts, double limit) {
        submit(() -> {
            head(SOURCE, tick, id);
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            out.writeDouble(watts);
            out.writeDouble(limit);
            sources++;
        });
    }

    /** Body heat sent to a region: x, y, z (region metres) and watts, packed; written only when it moved. */
    void bodies(long tick, int id, double[] xyzw) {
        double[] copy = xyzw.clone();
        submit(() -> {
            double[] last = lastBodies.get(id);
            if (last == null ? copy.length == 0 : !moved(last, copy)) {
                return;
            }
            lastBodies.put(id, copy);
            head(BODIES, tick, id);
            out.writeInt(copy.length / 4);
            for (double v : copy) {
                out.writeDouble(v);
            }
            bodies++;
        });
    }

    /** A region retired. */
    void retire(long tick, int id) {
        submit(() -> head(RETIRE, tick, id));
    }

    /** Writes the end, closes the file and stops the writer (waits up to 30 s for what is queued). */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        submit(() -> head(END, 0, 0));
        writer.submit(() -> {
            try {
                out.close();
            } catch (IOException e) {
                fail(e);
            }
        });
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                fail(new IOException("background log: writer did not finish in 30 s"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(new IOException("background log: interrupted while closing", e));
        }
        if (error != null) {
            throw error;
        }
    }

    /** The first write error, or null. */
    public IOException error() {
        return error;
    }

    /** Records written so far: regions, weather, edits, sources, bodies. */
    public long[] counts() {
        return new long[] {regions, weathers, edits, sources, bodies};
    }

    private interface Write {
        void run() throws IOException;
    }

    private synchronized void submit(Write w) {
        if (writer.isShutdown()) {
            return;
        }
        writer.submit(() -> {
            if (error != null) {
                return;
            }
            try {
                w.run();
            } catch (IOException | RuntimeException e) {
                fail(e instanceof IOException io ? io : new IOException(e));
            }
        });
    }

    private void fail(IOException e) {
        if (error == null) {
            error = e;
        }
    }

    private void head(byte tag, long tick, int id) throws IOException {
        out.writeByte(tag);
        out.writeLong(tick);
        out.writeInt(id);
    }

    private void cell(AirflowCells.Cell c) throws IOException {
        out.writeByte(c.kind().ordinal());
        Material m = c.material();
        out.writeDouble(m == null ? 0 : m.conductancePerBlock());
        out.writeDouble(m == null ? 0 : m.relHeatCapacity());
        out.writeDouble(c.wall());
    }

    private void weatherBody(int id, double background, double windX, double windZ, float[] lattice, int[] tops)
            throws IOException {
        out.writeDouble(background);
        out.writeDouble(windX);
        out.writeDouble(windZ);
        float[] lastL = lastLattice.get(id);
        if (lattice == null) {
            out.writeInt(0);
            lastLattice.remove(id);
        } else if (lastL != null && (lastL == lattice || Arrays.equals(lastL, lattice))) {
            out.writeInt(UNCHANGED);
        } else {
            out.writeInt(lattice.length);
            for (float f : lattice) {
                out.writeFloat(f);
            }
            lastLattice.put(id, lattice);
        }
        int[] lastT = lastTops.get(id);
        if (tops == null) {
            out.writeInt(0);
            lastTops.remove(id);
        } else if (lastT != null && Arrays.equals(lastT, tops)) {
            out.writeInt(UNCHANGED);
        } else {
            out.writeInt(tops.length);
            for (int t : tops) {
                out.writeInt(t);
            }
            lastTops.put(id, tops);
        }
    }

    /** The spec's codes in region-cell order. */
    static byte[] codes(AirflowRegionModel.Spec spec) {
        int nx = spec.nx(), ny = spec.ny(), nz = spec.nz(), nsx = nx / 16, nsz = nz / 16;
        byte[] out = new byte[nx * ny * nz];
        for (int y = 0; y < ny; y++) {
            for (int z = 0; z < nz; z++) {
                for (int x = 0; x < nx; x++) {
                    AirflowSections.Codes s = spec.sections()[((y >> 4) * nsz + (z >> 4)) * nsx + (x >> 4)];
                    out[(y * nz + z) * nx + x] = s.code(x & 15, y & 15, z & 15);
                }
            }
        }
        return out;
    }

    private static boolean moved(double[] last, double[] now) {
        if (last.length != now.length) {
            return true;
        }
        for (int i = 0; i < now.length; i += 4) {
            double dx = now[i] - last[i], dy = now[i + 1] - last[i + 1], dz = now[i + 2] - last[i + 2];
            if (dx * dx + dy * dy + dz * dz > BODY_STEP * BODY_STEP || now[i + 3] != last[i + 3]) {
                return true;
            }
        }
        return false;
    }

    // --- Reading ---

    /** One record of a dump. */
    public sealed interface Entry permits Region, Weather, Edit, Source, Bodies, Retire {
        long tick();

        int id();
    }

    /** A palette entry: kind ({@link AirflowCells.Kind} ordinal), conductance per block, relative capacity, wall. */
    public record CellInfo(byte kind, double conductance, double capacity, double wall) {
    }

    /** A region's build; {@code weather} is the weather it was built with. */
    public record Region(long tick, int id, int x0, int y0, int z0, int nx, int ny, int nz, double kappa, double film,
                         boolean restored, boolean snapshot, CellInfo[] palette, byte[] cells, int[] sourceCells,
                         double[] sourceWatts, double[] sourceLimits, Weather weather) implements Entry {
    }

    /** Weather; {@code lattice} and {@code tops} are filled in when unchanged (null: none). */
    public record Weather(long tick, int id, double backgroundT, double windX, double windZ, float[] lattice,
                          int[] tops) implements Entry {
    }

    public record Edit(long tick, int id, int x, int y, int z, CellInfo cell) implements Entry {
    }

    public record Source(long tick, int id, int x, int y, int z, double watts, double limit) implements Entry {
    }

    public record Bodies(long tick, int id, double[] xyzw) implements Entry {
    }

    public record Retire(long tick, int id) implements Entry {
    }

    /** A decoded dump: the tick it started at and its records in order. */
    public record Dump(long startTick, List<Entry> entries) {
    }

    /** Decodes a dump (a file cut short ends at its last whole record). */
    public static Dump read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(file), 1 << 16)))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("not a background dump");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("unknown background dump version " + version);
            }
            long start = in.readLong();
            List<Entry> entries = new ArrayList<>();
            Map<Integer, float[]> lattices = new HashMap<>();
            Map<Integer, int[]> tops = new HashMap<>();
            try {
                while (true) {
                    byte tag = in.readByte();
                    long tick = in.readLong();
                    int id = in.readInt();
                    switch (tag) {
                        case END -> {
                            return new Dump(start, List.copyOf(entries));
                        }
                        case REGION -> {
                            int x0 = in.readInt(), y0 = in.readInt(), z0 = in.readInt();
                            int nx = in.readInt(), ny = in.readInt(), nz = in.readInt();
                            double kappa = in.readDouble(), film = in.readDouble();
                            boolean restored = in.readBoolean(), snapshot = in.readBoolean();
                            CellInfo[] palette = new CellInfo[in.readInt()];
                            for (int i = 0; i < palette.length; i++) {
                                palette[i] = readCell(in);
                            }
                            byte[] cells = new byte[nx * ny * nz];
                            in.readFully(cells);
                            int n = in.readInt();
                            int[] at = new int[n];
                            double[] watts = new double[n], limits = new double[n];
                            for (int i = 0; i < n; i++) {
                                at[i] = in.readInt();
                                watts[i] = in.readDouble();
                                limits[i] = in.readDouble();
                            }
                            lattices.remove(id);
                            tops.remove(id);
                            Weather w = readWeather(in, tick, id, lattices, tops);
                            entries.add(new Region(tick, id, x0, y0, z0, nx, ny, nz, kappa, film, restored, snapshot,
                                    palette, cells, at, watts, limits, w));
                        }
                        case WEATHER -> entries.add(readWeather(in, tick, id, lattices, tops));
                        case EDIT -> entries.add(new Edit(tick, id, in.readInt(), in.readInt(), in.readInt(),
                                readCell(in)));
                        case SOURCE -> entries.add(new Source(tick, id, in.readInt(), in.readInt(), in.readInt(),
                                in.readDouble(), in.readDouble()));
                        case BODIES -> {
                            double[] xyzw = new double[4 * in.readInt()];
                            for (int i = 0; i < xyzw.length; i++) {
                                xyzw[i] = in.readDouble();
                            }
                            entries.add(new Bodies(tick, id, xyzw));
                        }
                        case RETIRE -> entries.add(new Retire(tick, id));
                        default -> throw new IOException("unknown background dump record " + tag);
                    }
                }
            } catch (EOFException cut) {
                return new Dump(start, List.copyOf(entries)); // a recording that never closed
            }
        }
    }

    private static CellInfo readCell(DataInputStream in) throws IOException {
        return new CellInfo(in.readByte(), in.readDouble(), in.readDouble(), in.readDouble());
    }

    private static Weather readWeather(DataInputStream in, long tick, int id, Map<Integer, float[]> lattices,
            Map<Integer, int[]> tops) throws IOException {
        double bg = in.readDouble(), wx = in.readDouble(), wz = in.readDouble();
        int n = in.readInt();
        float[] lattice;
        if (n == UNCHANGED) {
            lattice = lattices.get(id);
        } else if (n == 0) {
            lattice = null;
            lattices.remove(id);
        } else {
            lattice = new float[n];
            for (int i = 0; i < n; i++) {
                lattice[i] = in.readFloat();
            }
            lattices.put(id, lattice);
        }
        int m = in.readInt();
        int[] t;
        if (m == UNCHANGED) {
            t = tops.get(id);
        } else if (m == 0) {
            t = null;
            tops.remove(id);
        } else {
            t = new int[m];
            for (int i = 0; i < m; i++) {
                t[i] = in.readInt();
            }
            tops.put(id, t);
        }
        return new Weather(tick, id, bg, wx, wz, lattice, t);
    }
}
