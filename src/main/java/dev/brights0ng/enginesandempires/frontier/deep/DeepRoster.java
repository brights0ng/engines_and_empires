package dev.brights0ng.enginesandempires.frontier.deep;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;

/**
 * Which mobs come up from the Deep: a data-pack registry, so rosters can be changed without code. Each file is a weighted
 * list of mobs, with how many come together and how tough they are:
 *
 * <pre>
 * data/engines_and_empires/engines_and_empires/deep_roster/&lt;name&gt;.json
 * { "entries": [ { "entity": "minecraft:silverfish", "weight": 3, "min": 2, "max": 3, "health": 1.0 } ] }
 * </pre>
 *
 * Entries whose mob does not exist (a mod that is not installed) are skipped, so rosters can name Deeper and Darker's mobs
 * alongside vanilla stand-ins. Rosters so far: {@code thumper} (brought up by a shot), {@code stirred} (around a stirred
 * deposit), and the incursions' waves: {@code lesser} (every lesser incursion's waves) and {@code greater_1} to
 * {@code greater_10}.
 *
 * <p>Two more optional fields, for waves: {@code packs}, how many packs are picked from the entries (default 1), and
 * {@code fixed}, mobs that always come, either exactly {@code count} or between {@code min} and {@code max}:
 * {@code "fixed": [ { "entity": "minecraft:warden", "count": 1 }, { "entity": "deeperdarker:shattered", "min": 4, "max": 6 } ]}.
 */
public record DeepRoster(List<Entry> entries, int packs, List<Fixed> fixed) {

    public static final ResourceKey<Registry<DeepRoster>> KEY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "deep_roster"));

    public static final ResourceLocation THUMPER = ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "thumper");
    public static final ResourceLocation STIRRED = ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "stirred");
    public static final ResourceLocation LESSER = ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "lesser");

    /** The roster of a greater incursion's wave {@code wave} (1 to 10). */
    public static ResourceLocation greater(int wave) {
        return ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "greater_" + wave);
    }

    /**
     * @param entity the mob
     * @param weight how likely it is to be picked, against the others
     * @param min    fewest that come together
     * @param max    most that come together
     * @param health its maximum health, as a multiple of its usual
     */
    public record Entry(ResourceLocation entity, int weight, int min, int max, double health) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("entity").forGetter(Entry::entity),
                Codec.intRange(1, 10_000).optionalFieldOf("weight", 1).forGetter(Entry::weight),
                Codec.intRange(1, 64).optionalFieldOf("min", 1).forGetter(Entry::min),
                Codec.intRange(1, 64).optionalFieldOf("max", 1).forGetter(Entry::max),
                Codec.doubleRange(0.05, 100.0).optionalFieldOf("health", 1.0).forGetter(Entry::health)
        ).apply(instance, Entry::new));

        public Optional<EntityType<?>> type() {
            return BuiltInRegistries.ENTITY_TYPE.getOptional(entity);
        }
    }

    /** Mobs that always come with a wave: {@code count} of {@code entity}, or {@code min} to {@code max} when those are given. */
    public record Fixed(ResourceLocation entity, int count, int min, int max, double health) {
        public static final Codec<Fixed> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("entity").forGetter(Fixed::entity),
                Codec.intRange(1, 64).optionalFieldOf("count", 1).forGetter(Fixed::count),
                Codec.intRange(0, 64).optionalFieldOf("min", -1).forGetter(Fixed::min),
                Codec.intRange(0, 64).optionalFieldOf("max", -1).forGetter(Fixed::max),
                Codec.doubleRange(0.05, 100.0).optionalFieldOf("health", 1.0).forGetter(Fixed::health)
        ).apply(instance, Fixed::new));

        public Optional<EntityType<?>> type() {
            return BuiltInRegistries.ENTITY_TYPE.getOptional(entity);
        }

        /** How many come this time. */
        public int roll(RandomSource random) {
            if (min < 0 && max < 0) {
                return count;
            }
            int low = min < 0 ? count : min;
            int high = Math.max(low, max < 0 ? low : max);
            return low + random.nextInt(high - low + 1);
        }
    }

    public static final Codec<DeepRoster> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Entry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(DeepRoster::entries),
            Codec.intRange(0, 64).optionalFieldOf("packs", 1).forGetter(DeepRoster::packs),
            Fixed.CODEC.listOf().optionalFieldOf("fixed", List.of()).forGetter(DeepRoster::fixed)
    ).apply(instance, DeepRoster::new));

    /** The roster called {@code name}, if the loaded data packs have one. */
    public static Optional<DeepRoster> get(ServerLevel level, ResourceLocation name) {
        return level.registryAccess().registry(KEY).flatMap(registry -> registry.getOptional(name));
    }

    /** Picks an entry by weight, among those whose mob exists. */
    public Optional<Entry> pick(RandomSource random) {
        int total = 0;
        for (Entry entry : entries) {
            if (entry.type().isPresent()) {
                total += entry.weight();
            }
        }
        if (total <= 0) {
            return Optional.empty();
        }
        int roll = random.nextInt(total);
        for (Entry entry : entries) {
            if (entry.type().isEmpty()) {
                continue;
            }
            roll -= entry.weight();
            if (roll < 0) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }
}
