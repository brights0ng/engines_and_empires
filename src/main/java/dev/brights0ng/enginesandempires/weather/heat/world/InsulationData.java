package dev.brights0ng.enginesandempires.weather.heat.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * How a block holds and passes heat in the heat simulation (weather phase 8f-2; Bright, 2026-10-15): the value of the
 * {@code engines_and_empires:insulation} block data map. Both relative to stone = 1; blocks not in the map count as
 * stone.
 *
 * @param conductance heat through one block (lower insulates better: wool 0.1, wood 0.25, glass 2.5)
 * @param capacity    heat one block soaks up (thermal mass)
 */
public record InsulationData(double conductance, double capacity) {

    public static final InsulationData STONE = new InsulationData(1, 1);

    public static final Codec<InsulationData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.doubleRange(0.01, 20).fieldOf("conductance").forGetter(InsulationData::conductance),
            Codec.doubleRange(0, 10).fieldOf("capacity").forGetter(InsulationData::capacity)
    ).apply(i, InsulationData::new));
}
