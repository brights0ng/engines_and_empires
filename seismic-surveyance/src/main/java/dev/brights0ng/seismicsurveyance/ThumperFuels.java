// Written by Claude; updated by Gemini.
package dev.brights0ng.seismicsurveyance;

import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.datamaps.AdvancedDataMapType;
import net.neoforged.neoforge.registries.datamaps.DataMapValueRemover;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * What the combustive thumper burns, from data (split step 4c): the fluid data map {@code seismic_surveyance:thumper_fuels},
 * which gives a fluid a {@link Grade}. Any mod or data pack adds fuels with an entry in its own
 * {@code data/seismic_surveyance/data_maps/fluid/thumper_fuels.json}, by fluid id or by fluid tag; how is in
 * {@code docs/history/standalone-audit.md}. Seismic's own entries ({@code SeismicDataGen}) give its three fluid tags their grades
 * ({@link ThumperFluids#PREMIUM_FUEL}, {@link ThumperFluids#FUEL}, {@link ThumperFluids#COMBUSTIBLE}, which other mods
 * can add their fluids to instead) and name Create: Diesel Generators' fluids, behind a condition on that mod.
 *
 * <p>A fluid that two entries grade differently takes the better grade (premium, then standard, then volatile), so one
 * mod's entry never downgrades another's fuel; an entry with {@code "replace": true} sets the grade outright. With no
 * fuel at all registered (Seismic with Create alone), the thumper can be built but never fires: every fluid is inert,
 * and its tooltip and goggles say it needs a fuel source ({@link #anyRegistered}).
 *
 * <p>The map is synced to clients, which read it for the tooltip and the goggles.
 */
public final class ThumperFuels {

    /** How a fuel fires, from best to worst: what {@link CombustiveFiring} does with it. */
    public enum Grade implements StringRepresentable {
        /** Diesel: the furthest shot, and the cheapest. */
        PREMIUM("premium", CombustiveFiring.Charge.PREMIUM_FUEL),
        /** Gasoline, biodiesel: a shorter shot. */
        STANDARD("standard", CombustiveFiring.Charge.FUEL),
        /** Anything else that burns (crude oil, plant oil, ethanol): fires, then explodes. */
        VOLATILE("volatile", CombustiveFiring.Charge.VOLATILE);

        public static final Codec<Grade> CODEC = StringRepresentable.fromEnum(Grade::values);

        private final String name;
        private final CombustiveFiring.Charge charge;

        Grade(String name, CombustiveFiring.Charge charge) {
            this.name = name;
            this.charge = charge;
        }

        /** What the thumper does when it fires on a fluid of this grade. */
        public CombustiveFiring.Charge charge() {
            return charge;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        /** The better of two grades. */
        public static Grade best(Grade a, Grade b) {
            return a.ordinal() <= b.ordinal() ? a : b;
        }
    }

    /** One fluid's entry: its grade. A record, so the entry can grow fields without breaking existing files. */
    public record Fuel(Grade grade) {
        public static final Codec<Fuel> CODEC = RecordCodecBuilder.create(in -> in.group(
                Grade.CODEC.fieldOf("grade").forGetter(Fuel::grade)).apply(in, Fuel::new));
    }

    public static final AdvancedDataMapType<Fluid, Fuel, DataMapValueRemover.Default<Fuel, Fluid>> FUELS =
            AdvancedDataMapType.builder(SeismicSurveyance.id("thumper_fuels"), Registries.FLUID, Fuel.CODEC)
                    .synced(Fuel.CODEC, false)
                    .merger((registry, first, firstValue, second, secondValue) ->
                            new Fuel(Grade.best(firstValue.grade(), secondValue.grade())))
                    .build();

    /** Call from the mod constructor with the mod event bus. */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener((RegisterDataMapTypesEvent event) -> event.register(FUELS));
    }

    /** The fuel entry of a fluid, or null if the thumper cannot burn it. */
    public static Fuel of(Fluid fluid) {
        return fluid.builtInRegistryHolder().getData(FUELS);
    }

    /** Whether any fluid at all is a thumper fuel in this game: without one, the thumper can never fire. */
    public static boolean anyRegistered() {
        return !all().isEmpty();
    }

    /** Every fuel entry this game has loaded, by fluid. */
    public static Map<ResourceKey<Fluid>, Fuel> all() {
        return BuiltInRegistries.FLUID.getDataMap(FUELS);
    }

    private ThumperFuels() {
    }
}
