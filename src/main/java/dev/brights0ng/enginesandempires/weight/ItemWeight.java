package dev.brights0ng.enginesandempires.weight;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One entry of the {@code engines_and_empires:item_weight} data map: how much an item weighs in kpg, the unit Create
 * Aeronautics uses for block mass. Written the same way as Encumbered's own entries: {@code {"weight": 0.5}}.
 */
public record ItemWeight(float weight) {

    public static final Codec<ItemWeight> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.fieldOf("weight").forGetter(ItemWeight::weight)
    ).apply(instance, ItemWeight::new));
}
