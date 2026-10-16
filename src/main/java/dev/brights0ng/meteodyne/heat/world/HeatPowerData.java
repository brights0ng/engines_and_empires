package dev.brights0ng.meteodyne.heat.world;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * What a block puts into the heat simulation's air (weather phase 8f-2; Bright, 2026-10-15): the value of the
 * {@code engines_and_empires:heat_power} block data map ({@code data/<ns>/data_maps/block/heat_power.json}). Separate
 * from {@code heat_sources} (what snow, ice and water see), so torches and the like can melt snow without heating rooms.
 *
 * <p>A value is one variant or a list of them (the first whose {@code state} matches wins):
 * <ul>
 *   <li>{@code state}: block state properties that must match (optional)</li>
 *   <li>{@code power}: relative to a campfire = 1; negative for cold</li>
 *   <li>{@code radiant}: the share felt by line of sight instead of through the air, 0-1 (1: radiant only, no room
 *       heating at all; ice and glowstone)</li>
 *   <li>{@code natural}: only takes part while a player or ship is near, or inside a region a placed source keeps
 *       alive (lava, magma, fire)</li>
 *   <li>{@code per_face}: the power is per face open to air (lava: a lake's surface, not its volume)</li>
 *   <li>{@code max_offset}: the most this source takes the air above (or, for cold, below) the outside air, C</li>
 *   <li>{@code limit}: an absolute air temperature, C, it stops at (a stock icebox: 0, never below freezing). Absent:
 *       the block's {@code heat_sources} limit, if it has one</li>
 *   <li>{@code driver}: the source is on (and how strongly) by its block entity, not its block state (8f-3): the id of
 *       a {@link HeatDrivers} driver (a hearth heating, an engine running). A block with driven variants is a source
 *       candidate in any state; for each driver the first variant that fits counts, and different drivers count at
 *       once (a hearth heats and cools)</li>
 * </ul>
 */
public record HeatPowerData(List<Variant> variants) {

    /** One state of a source. {@code maxOffset} and {@code limit} are NaN for none; {@code driver} null for none. */
    public record Variant(Map<String, String> state, double power, double radiant, boolean natural, boolean perFace,
                          double maxOffset, double limit, ResourceLocation driver) {

        public static final Codec<Variant> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("state", Map.of()).forGetter(Variant::state),
                Codec.doubleRange(-50, 50).fieldOf("power").forGetter(Variant::power),
                Codec.doubleRange(0, 1).optionalFieldOf("radiant", 0.0).forGetter(Variant::radiant),
                Codec.BOOL.optionalFieldOf("natural", false).forGetter(Variant::natural),
                Codec.BOOL.optionalFieldOf("per_face", false).forGetter(Variant::perFace),
                Codec.doubleRange(0, 500).optionalFieldOf("max_offset")
                        .forGetter(v -> Double.isNaN(v.maxOffset) ? Optional.empty() : Optional.of(v.maxOffset)),
                Codec.doubleRange(-273, 3000).optionalFieldOf("limit")
                        .forGetter(v -> Double.isNaN(v.limit) ? Optional.empty() : Optional.of(v.limit)),
                ResourceLocation.CODEC.optionalFieldOf("driver").forGetter(v -> Optional.ofNullable(v.driver))
        ).apply(i, (state, power, radiant, natural, perFace, maxOffset, limit, driver) -> new Variant(state, power,
                radiant, natural, perFace, maxOffset.orElse(Double.NaN), limit.orElse(Double.NaN),
                driver.orElse(null))));

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

        /** Whether it heats or cools the air at all (not radiant only, not zero). */
        public boolean inAir() {
            return power != 0 && radiant < 1;
        }

        /** Whether its block entity decides when it is on. */
        public boolean driven() {
            return driver != null;
        }

        /** The same source at {@code share} of its power (a driver's answer). */
        public Variant scaled(double share) {
            return share == 1 ? this : new Variant(state, power * share, radiant, natural, perFace, maxOffset, limit,
                    driver);
        }

        private static <T extends Comparable<T>> String valueName(BlockState block, Property<T> property) {
            return property.getName(block.getValue(property));
        }
    }

    public static final Codec<HeatPowerData> CODEC = Codec.withAlternative(Variant.CODEC.listOf(), Variant.CODEC,
            List::of).xmap(HeatPowerData::new, HeatPowerData::variants);

    /** The first variant that fits {@code block}, or null. */
    public Variant find(BlockState block) {
        for (Variant v : variants) {
            if (v.matches(block)) {
                return v;
            }
        }
        return null;
    }

    /** For each driver, the first driven variant that fits {@code block} (empty if it has none). */
    public List<Variant> driven(BlockState block) {
        List<Variant> out = null;
        for (Variant v : variants) {
            if (v.driven() && v.matches(block)) {
                if (out == null) {
                    out = new java.util.ArrayList<>(2);
                }
                boolean seen = false;
                for (Variant o : out) {
                    seen |= o.driver().equals(v.driver());
                }
                if (!seen) {
                    out.add(v);
                }
            }
        }
        return out == null ? List.of() : out;
    }
}
