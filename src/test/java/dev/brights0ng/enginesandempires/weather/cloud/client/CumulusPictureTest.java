package dev.brights0ng.enginesandempires.weather.cloud.client;

import org.junit.jupiter.api.Test;

/** Prints cumulus as ASCII (a side view and a slice) for checking shapes by eye. Always passes. */
class CumulusPictureTest {

    static String side(CloudVoxelizer.Cropped c) {
        CloudVoxelizer.Grid g = c.grid();
        StringBuilder sb = new StringBuilder();
        for (int v = g.ny() - 1; v >= 0; v--) {
            for (int i = 0; i < g.nx(); i++) {
                int count = 0;
                for (int j = 0; j < g.nz(); j++) {
                    if (c.solid()[(v * g.nz() + j) * g.nx() + i] != 0) {
                        count++;
                    }
                }
                sb.append(count == 0 ? ' ' : count < 4 ? '.' : count < 12 ? '+' : '#');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String slice(CloudVoxelizer.Cropped c) {
        CloudVoxelizer.Grid g = c.grid();
        int j = g.nz() / 2;
        StringBuilder sb = new StringBuilder();
        for (int v = g.ny() - 1; v >= 0; v--) {
            for (int i = 0; i < g.nx(); i++) {
                sb.append(c.solid()[(v * g.nz() + j) * g.nx() + i] != 0 ? '#' : ' ');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    void pictures() {
        String[][] cases = {{"cumulus_humilis", "80", "110"}, {"cumulus_mediocris", "140", "260"}};
        for (String[] k : cases) {
            for (int seed = 1; seed <= 2; seed++) {
                for (boolean puffs : new boolean[]{false, true}) {
                    CloudField f = CumulusShapeTest.field(CumulusShapeTest.cumulus(k[0], Float.parseFloat(k[1]),
                            Float.parseFloat(k[2]), seed));
                    CloudVoxelizer.Cropped c = CumulusShapeTest.voxels(f, 4);
                    System.out.println("==== " + k[0] + " seed " + seed + (puffs ? " with puffs" : "") + " (grid "
                            + c.grid().nx() + "x" + c.grid().ny() + "x" + c.grid().nz() + ") side view:");
                    System.out.print(side(c));
                    System.out.println("---- slice through the middle:");
                    System.out.print(slice(c));
                }
            }
        }
    }
}
