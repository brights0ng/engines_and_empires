package dev.brights0ng.enginesandempires.food;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** How {@link FoodFreshness} is saved with the item and sent to clients. */
public final class FoodCodecs {

    public static final Codec<Cohort> COHORT = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("born").forGetter(Cohort::born),
            Codec.INT.fieldOf("count").forGetter(Cohort::count)
    ).apply(instance, Cohort::new));

    public static final Codec<FoodFreshness> FRESHNESS = RecordCodecBuilder.create(instance -> instance.group(
            COHORT.listOf().fieldOf("cohorts").forGetter(FoodFreshness::cohorts),
            Codec.INT.optionalFieldOf("top", 0).forGetter(FoodFreshness::top)
    ).apply(instance, FoodFreshness::new));

    public static final StreamCodec<ByteBuf, Cohort> COHORT_STREAM = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, Cohort::born,
            ByteBufCodecs.VAR_INT, Cohort::count,
            Cohort::new);

    public static final StreamCodec<ByteBuf, FoodFreshness> FRESHNESS_STREAM = StreamCodec.composite(
            COHORT_STREAM.apply(ByteBufCodecs.list(8)), FoodFreshness::cohorts,
            ByteBufCodecs.VAR_INT, FoodFreshness::top,
            FoodFreshness::new);

    private FoodCodecs() {
    }
}
