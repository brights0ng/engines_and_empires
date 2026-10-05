package dev.brights0ng.enginesandempires.oregen.shape;

import dev.brights0ng.enginesandempires.oregen.Noise3D;

/**
 * A thin, wide, wavy slab of rock: the building block of seams and veins.
 *
 * <p>It is an ellipsoid with two long semi-axes lying in its plane (along strike and dip) and a short
 * one across it, distorted three ways so it never looks like a clean lens: the whole slab undulates
 * (waves), its outline is ragged (edge) and its thickness pinches and swells.
 *
 * <p>Immutable and thread-safe.
 */
final class Plate {

    /** How strongly a plate is distorted. Amplitudes are fractions, except the wave, which is in blocks. */
    record Style(double waveAmplitude, double edgeAmplitude, double swellAmplitude) {
    }

    private final double ox;
    private final double oy;
    private final double oz;
    private final Orientation frame;
    private final double ra;
    private final double rb;
    private final double rc;
    private final Style style;
    private final double waveWavelength;
    private final double edgeWavelength;
    private final double swellWavelength;
    private final Noise3D waveNoise;
    private final Noise3D edgeNoise;
    private final Noise3D swellNoise;

    /**
     * @param ox    the plate's centre, as an offset from the deposit's centre
     * @param frame the plate's axes
     * @param ra    semi-axis along strike
     * @param rb    semi-axis along dip
     * @param rc    semi-thickness, across the plate
     */
    Plate(double ox, double oy, double oz, Orientation frame, double ra, double rb, double rc,
          Style style, long noiseSeed) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.frame = frame;
        this.ra = ra;
        this.rb = rb;
        this.rc = rc;
        this.style = style;
        double size = Math.max(ra, rb);
        this.waveWavelength = Math.max(9.0, 0.8 * size);
        this.edgeWavelength = Math.max(6.0, 0.5 * size);
        this.swellWavelength = Math.max(7.0, 0.6 * size);
        this.waveNoise = new Noise3D(noiseSeed ^ 0x11L);
        this.edgeNoise = new Noise3D(noiseSeed ^ 0x22L);
        this.swellNoise = new Noise3D(noiseSeed ^ 0x33L);
    }

    Orientation frame() {
        return frame;
    }

    /** Coordinate along strike of the point, measured from the plate's centre. */
    double strike(double x, double y, double z) {
        return frame.a(x - ox, y - oy, z - oz);
    }

    /** Coordinate along dip of the point, measured from the plate's centre. */
    double dip(double x, double y, double z) {
        return frame.b(x - ox, y - oy, z - oz);
    }

    /** Distance across the plate of the point from the plate's (wavy) midsurface. */
    double acrossFromMidsurface(double x, double y, double z) {
        double a = frame.a(x - ox, y - oy, z - oz);
        double b = frame.b(x - ox, y - oy, z - oz);
        double c = frame.n(x - ox, y - oy, z - oz);
        return c - style.waveAmplitude() * waveNoise.noise(a / waveWavelength, b / waveWavelength, 0.5);
    }

    /** Above 0 inside the plate, reaching 1 at its centre and 0 at its edge. At or below 0 outside. */
    double envelope(double x, double y, double z) {
        double px = x - ox;
        double py = y - oy;
        double pz = z - oz;
        double a = frame.a(px, py, pz);
        double b = frame.b(px, py, pz);
        double c = frame.n(px, py, pz);

        double edgeMax = 1.0 + style.edgeAmplitude();
        double swellMax = 1.0 + style.swellAmplitude();
        if (Math.abs(a) > ra * edgeMax || Math.abs(b) > rb * edgeMax
                || Math.abs(c) > rc * swellMax + style.waveAmplitude()) {
            return -1.0; // beyond anything the distortions can reach; skip the noise
        }

        double wave = style.waveAmplitude() * waveNoise.noise(a / waveWavelength, b / waveWavelength, 0.5);
        double edge = 1.0 + style.edgeAmplitude() * edgeNoise.noise(a / edgeWavelength, b / edgeWavelength, 0.5);
        double swell = 1.0 + style.swellAmplitude() * swellNoise.noise(a / swellWavelength, b / swellWavelength, 0.5);

        double across = (c - wave) / (rc * swell);
        double outline = ((a / ra) * (a / ra) + (b / rb) * (b / rb)) / (edge * edge);
        return 1.0 - outline - across * across;
    }

    /** How far the plate can reach from the deposit's centre along a world axis (0 = x, 1 = y, 2 = z). */
    double reach(int axis) {
        double offset = switch (axis) {
            case 0 -> Math.abs(ox);
            case 1 -> Math.abs(oy);
            default -> Math.abs(oz);
        };
        double edgeMax = 1.0 + style.edgeAmplitude();
        double swellMax = 1.0 + style.swellAmplitude();
        return offset + frame.ellipsoidReach(axis, ra * edgeMax, rb * edgeMax, rc * swellMax + style.waveAmplitude());
    }
}
