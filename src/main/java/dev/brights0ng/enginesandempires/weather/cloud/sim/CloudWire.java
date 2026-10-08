package dev.brights0ng.enginesandempires.weather.cloud.sim;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;

/**
 * The cloud sync's wire format ({@link CloudSyncPayload}), over plain {@link DataOutput}/{@link DataInput} so it is
 * pure Java and unit-tested; the payload adapts Netty's buffers to it.
 */
public final class CloudWire {

    public static void write(DataOutput out, boolean reset, List<SimCloud> upserts, List<UUID> removed) {
        try {
            out.writeBoolean(reset);
            out.writeShort(upserts.size());
            for (SimCloud c : upserts) {
                writeCloud(out, c);
            }
            out.writeShort(removed.size());
            for (UUID id : removed) {
                out.writeLong(id.getMostSignificantBits());
                out.writeLong(id.getLeastSignificantBits());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {reset (Boolean), upserts (List of SimCloud), removed (List of UUID)} */
    public static Object[] read(DataInput in) {
        try {
            boolean reset = in.readBoolean();
            int n = in.readUnsignedShort();
            List<SimCloud> upserts = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                SimCloud c = readCloud(in);
                if (c != null) {
                    upserts.add(c);
                }
            }
            int m = in.readUnsignedShort();
            List<UUID> removed = new ArrayList<>(m);
            for (int i = 0; i < m; i++) {
                removed.add(new UUID(in.readLong(), in.readLong()));
            }
            return new Object[]{reset, upserts, removed};
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void writeCloud(DataOutput out, SimCloud c) throws IOException {
        out.writeLong(c.id.getMostSignificantBits());
        out.writeLong(c.id.getLeastSignificantBits());
        out.writeByte(c.type.ordinal());
        out.writeDouble(c.x);
        out.writeDouble(c.z);
        out.writeFloat((float) c.vx);
        out.writeFloat((float) c.vz);
        out.writeLong(c.refTick);
        out.writeLong(c.birth);
        out.writeLong(c.end);
        out.writeFloat(c.baseY);
        out.writeFloat(c.thickness);
        out.writeFloat(c.coverage);
        out.writeFloat(c.precipitation);
        out.writeFloat(c.lightning);
        out.writeInt(c.version);
        out.writeBoolean(c.dissolving);
        out.writeByte(c.domes.size());
        for (SimCloud.Dome d : c.domes) {
            out.writeFloat(d.dx());
            out.writeFloat(d.dz());
            out.writeFloat(d.radius());
            out.writeInt(d.seed());
            out.writeFloat(d.tower());
        }
        out.writeFloat(c.rainBottom);
        out.writeFloat((float) c.tvx);
        out.writeFloat((float) c.tvz);
    }

    /** One cloud, or null if its type is unknown here (read past it all the same). */
    static SimCloud readCloud(DataInput in) throws IOException {
        UUID id = new UUID(in.readLong(), in.readLong());
        int type = in.readUnsignedByte();
        double x = in.readDouble(), z = in.readDouble();
        double vx = in.readFloat(), vz = in.readFloat();
        long ref = in.readLong(), birth = in.readLong(), end = in.readLong();
        float base = in.readFloat(), thickness = in.readFloat(), coverage = in.readFloat();
        float precipitation = in.readFloat(), lightning = in.readFloat();
        int version = in.readInt();
        boolean dissolving = in.readBoolean();
        int n = in.readUnsignedByte();
        List<SimCloud.Dome> domes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            domes.add(new SimCloud.Dome(in.readFloat(), in.readFloat(), in.readFloat(), in.readInt(), in.readFloat()));
        }
        float rainBottom = in.readFloat();
        double tvx = in.readFloat(), tvz = in.readFloat();
        CloudType[] types = CloudType.values();
        if (type >= types.length || domes.isEmpty()) {
            return null;
        }
        SimCloud c = new SimCloud(id, types[type], x, z, vx, vz, ref, birth, end, base, thickness, coverage, precipitation,
                lightning, domes, version, dissolving);
        c.rainBottom = rainBottom;
        c.tvx = tvx;
        c.tvz = tvz;
        return c;
    }

    private CloudWire() {
    }
}
