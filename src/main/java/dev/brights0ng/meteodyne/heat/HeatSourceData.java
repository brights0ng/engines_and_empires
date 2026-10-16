package dev.brights0ng.meteodyne.heat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * What a block does to the air around it for snow, ice and water (weather phase 8e-3; Bright, 2026-10-15): the value of
 * the {@code engines_and_empires:heat_sources} block data map
 * ({@code data/<ns>/data_maps/block/heat_sources.json}). Its numbers started as Cold Sweat's own block temperatures,
 * converted to C, but the pack owns them: they work without Cold Sweat, a datapack can retune or add sources, and the
 * later heat simulation (8f) reads the same table.
 *
 * <p>A value is one variant or a list of them (the first whose {@code state} matches the block wins):
 * <ul>
 *   <li>{@code state}: block state properties that must match, e.g. {@code {"lit": "true"}} (optional)</li>
 *   <li>{@code change}: C added to the air at the source, negative for cold</li>
 *   <li>{@code range}: blocks; the effect fades linearly from full strength at half a block to nothing at the range</li>
 *   <li>{@code max_effect}: C; the most that all blocks of this one kind can add up to, however many are near</li>
 *   <li>{@code limit}: C, optional; a heat source stops warming the air once it reaches this, a cold one stops cooling
 *       it there (so a stock icebox, limit 0, never takes air below freezing, while blue ice reaches -17.8)</li>
 * </ul>
 */
public record HeatSourceData(List<Variant> variants) {

    /** One state of a source. {@code limit} is NaN for none. */
    public record Variant(Map<String, String> state, double change, double range, double maxEffect, double limit) {

        public static final Codec<Variant> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("state", Map.of())
                        .forGetter(Variant::state),
                Codec.doubleRange(-200, 200).fieldOf("change").forGetter(Variant::change),
                Codec.doubleRange(0.5, 16).fieldOf("range").forGetter(Variant::range),
                Codec.doubleRange(0, 500).fieldOf("max_effect").forGetter(Variant::maxEffect),
                Codec.doubleRange(-273, 3000).optionalFieldOf("limit")
                        .forGetter(v -> Double.isNaN(v.limit) ? Optional.empty() : Optional.of(v.limit))
        ).apply(i, (state, change, range, max, limit) -> new Variant(state, change, range, max,
                limit.orElse(Double.NaN))));

        /** Whether {@code block} is in the state this variant is for. */
        public boolean matches(BlockState block) {
            for (Map.Entry<String, String> wanted : state.entrySet()) {
                Property<?> property = block.getBlock().getStateDefinition().getProperty(wanted.getKey());
                if (property == null || !valueName(block, property).equals(wanted.getValue())) {
                    return false;
                }
            }
            return true;
        }

        private static <T extends Comparable<T>> String valueName(BlockState block, Property<T> property) {
            return property.getName(block.getValue(property));
        }
    }

    public static final Codec<HeatSourceData> CODEC = Codec.withAlternative(Variant.CODEC.listOf(), Variant.CODEC,
            List::of).xmap(HeatSourceData::new, HeatSourceData::variants);

    /** The first variant that fits {@code block}, or null. */
    public Variant find(BlockState block) {
        for (Variant v : variants) {
            if (v.matches(block)) {
                return v;
            }
        }
        return null;
    }
}
