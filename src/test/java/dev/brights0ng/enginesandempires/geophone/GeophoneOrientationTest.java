package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeophoneOrientationTest {

    private static final int[][] FACES = {
            {0, 1, 0},   // top
            {0, -1, 0},  // underside
            {0, 0, -1},  // north
            {0, 0, 1},   // south
            {1, 0, 0},   // east
            {-1, 0, 0}   // west
    };

    /** The whole point: an upright rod, once tipped, points straight out of whichever face it was staked into. */
    @Test
    void theTiltTurnsTheUprightRodToPointOutOfEveryFace() {
        for (int[] normal : FACES) {
            GeophoneOrientation.Tilt tilt = GeophoneOrientation.tiltFor(normal[0], normal[1], normal[2]);
            double[] pointing = GeophoneOrientation.rotate(tilt, 0, 1, 0);
            assertArrayEquals(new double[]{normal[0], normal[1], normal[2]}, pointing, 1.0e-9,
                    "face (" + normal[0] + ", " + normal[1] + ", " + normal[2] + ")");
        }
    }

    @Test
    void aTiltNeverTurnsAboutTwoAxesAtOnce() {
        for (int[] normal : FACES) {
            GeophoneOrientation.Tilt tilt = GeophoneOrientation.tiltFor(normal[0], normal[1], normal[2]);
            assertTrue(tilt.xDegrees() == 0.0 || tilt.zDegrees() == 0.0, "the renderer applies them in either order");
        }
    }

    @Test
    void aTiltKeepsLengthsSoTheModelIsNotStretched() {
        for (int[] normal : FACES) {
            GeophoneOrientation.Tilt tilt = GeophoneOrientation.tiltFor(normal[0], normal[1], normal[2]);
            double[] turned = GeophoneOrientation.rotate(tilt, 0.3, 0.8, -0.5);
            assertEquals(Math.sqrt(0.3 * 0.3 + 0.8 * 0.8 + 0.5 * 0.5),
                    Math.sqrt(turned[0] * turned[0] + turned[1] * turned[1] + turned[2] * turned[2]), 1.0e-9);
        }
    }

    @Test
    void anythingButAFaceNormalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GeophoneOrientation.tiltFor(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> GeophoneOrientation.tiltFor(1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> GeophoneOrientation.tiltFor(2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> GeophoneOrientation.bounds(0, 0, 0, 0, 2, 0));
    }

    @Test
    void theHitboxStartsAtTheStakingPointAndReachesOutAlongTheRod() {
        double x = 10.3;
        double y = 64.0;
        double z = 5.7;
        double half = GeophoneOrientation.THICKNESS / 2.0;
        double length = GeophoneOrientation.VISIBLE_LENGTH;

        // Staked in the top of a block: a box standing on the point.
        assertArrayEquals(new double[]{x - half, y, z - half, x + half, y + length, z + half},
                GeophoneOrientation.bounds(x, y, z, 0, 1, 0), 1.0e-9);
        // In the underside: hanging from the point.
        assertArrayEquals(new double[]{x - half, y - length, z - half, x + half, y, z + half},
                GeophoneOrientation.bounds(x, y, z, 0, -1, 0), 1.0e-9);
        // In a north face, the rod runs towards -z.
        assertArrayEquals(new double[]{x - half, y - half, z - length, x + half, y + half, z},
                GeophoneOrientation.bounds(x, y, z, 0, 0, -1), 1.0e-9);
        // In an east face, the rod runs towards +x.
        assertArrayEquals(new double[]{x, y - half, z - half, x + length, y + half, z + half},
                GeophoneOrientation.bounds(x, y, z, 1, 0, 0), 1.0e-9);
    }

    @Test
    void everyHitboxIsAValidBoxContainingTheStakingPoint() {
        for (int[] normal : FACES) {
            double[] box = GeophoneOrientation.bounds(3.25, -12.5, 100.75, normal[0], normal[1], normal[2]);
            assertTrue(box[0] <= box[3] && box[1] <= box[4] && box[2] <= box[5], "min is not above max");
            assertTrue(box[0] <= 3.25 && 3.25 <= box[3] && box[1] <= -12.5 && -12.5 <= box[4] && box[2] <= 100.75 && 100.75 <= box[5]);
        }
    }

    @Test
    void theHitboxTipMatchesWhereTheTiltedModelEnds() {
        // The visible part of the model, from the face to the tip of the cap, turned by the tilt, must end at the box's far side.
        for (int[] normal : FACES) {
            GeophoneOrientation.Tilt tilt = GeophoneOrientation.tiltFor(normal[0], normal[1], normal[2]);
            double[] tip = GeophoneOrientation.rotate(tilt, 0, GeophoneOrientation.VISIBLE_LENGTH, 0);
            double[] box = GeophoneOrientation.bounds(0, 0, 0, normal[0], normal[1], normal[2]);
            for (int axis = 0; axis < 3; axis++) {
                assertTrue(tip[axis] >= box[axis] - 1.0e-9 && tip[axis] <= box[axis + 3] + 1.0e-9,
                        "the tip is outside the hitbox on axis " + axis);
            }
        }
    }

    @Test
    void theSpikeIsEightPixelsAndTheVisiblePartTwelve() {
        assertEquals(8.0 / 16.0, GeophoneOrientation.SPIKE_LENGTH, 0.0);
        assertEquals(12.0 / 16.0, GeophoneOrientation.VISIBLE_LENGTH, 0.0);
    }
}
