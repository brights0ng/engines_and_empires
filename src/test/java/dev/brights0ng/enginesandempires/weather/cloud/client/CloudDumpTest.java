package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A dumped sky reads back the same, settings included, and plans like the original. */
class CloudDumpTest {

    private static final UUID REGION = UUID.randomUUID();

    private static CloudShape c(int id, double x, double z, float r, float base, float top) {
        return new CloudShape(new UUID(0, id), REGION, "minecraft:overworld", x, z, 0.3, 0, 0, r, base, top, 0.8f,
                0.88f, 0.5f, 1, 0, 0, "cumulonimbus_capillatus", 0.9f, 0.8f, 0.4f, 0.82f, 0.8f, 0.6f, 101 + id,
                Float.NEGATIVE_INFINITY);
    }

    @Test
    void roundTrips(@TempDir Path dir) throws Exception {
        CloudFormation f = CloudFormation.of(REGION, List.of(c(1, 0, 0, 260, 180, 520), c(2, -220, 120, 180, 185, 380)),
                1234.5);
        Path path = dir.resolve("dump.txt");
        double noise = CloudTuning.noiseScale;
        double[] lod = CloudTuning.lodDistances;
        try {
            CloudTuning.noiseScale = 1.25;
            CloudDump.write(path, new CloudDump.Snapshot(1300, 4, 3072,
                    List.of(new CloudDump.Entry(f, 10, 200, -30))));
            CloudTuning.noiseScale = 1.0;
            CloudDump.Snapshot back = CloudDump.read(path);
            assertEquals(1.25, CloudTuning.noiseScale, 1e-12, "settings restored");
            assertEquals(1, back.entries().size());
            CloudDump.Entry e = back.entries().getFirst();
            assertEquals(f, e.formation(), "domes, ids and offsets time intact");
            assertEquals(200, e.camY(), 1e-12);
            assertEquals(4, back.voxel());
            // And it plans as the original would (without Minecraft).
            CloudField field = CloudField.of(e.formation());
            assertFalse(RebuildSchedule.plan(field.bounds(), e.camX(), e.camY(), e.camZ(), 512, 4, 3072).isEmpty());
            assertFalse(Files.readString(path).isBlank());
        } finally {
            CloudTuning.noiseScale = noise;
            CloudTuning.lodDistances = lod;
        }
    }
}
