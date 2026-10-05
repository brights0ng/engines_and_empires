package dev.brights0ng.enginesandempires.frontier;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

/** The data-pack tags Frontier reads, so what counts can be changed without code. */
public final class FrontierTags {

    /** Blocks that anchor a settlement: beds, lanterns, workstations, hay bales. */
    public static final TagKey<Block> SETTLES = block("settles");

    /** Blocks that burn out outside Settled land (used from phase 3). */
    public static final TagKey<Block> BURNS_OUT = block("burns_out");

    /** People whose presence makes land inhabited, besides players: villagers, illagers, colonists. */
    public static final TagKey<EntityType<?>> INHABITANTS = entity("inhabitants");

    /** Mobs that count as guards for Civilized land (used from phase 4). */
    public static final TagKey<EntityType<?>> GUARDS = entity("guards");

    /** Mobs that hunt by day and are kept out of Settled land (used from phase 2). */
    public static final TagKey<EntityType<?>> DAYTIME_HOSTILE = entity("daytime_hostile");

    /** Neutral mobs that are never provoked in Frontier land (used from phase 2). */
    public static final TagKey<EntityType<?>> NEVER_PROVOKED = entity("never_provoked");

    /**
     * Mobs that attack players on sight anywhere when they come up from the Deep (stirred deposits, thumper shots), whatever
     * the tier: Deeper and Darker's sculk snapper and sculk centipede. Tamed ones never are.
     */
    public static final TagKey<EntityType<?>> PROVOKED_FROM_BELOW = entity("provoked_from_below");

    /**
     * Deep mobs that cannot walk (Deeper and Darker's shriek worm): an incursion brings them up near a player instead of
     * with the rest of the wave, and keeps them from sinking back into the ground until it is over.
     */
    public static final TagKey<EntityType<?>> STATIONARY = entity("stationary");

    private static TagKey<Block> block(String name) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, name));
    }

    private static TagKey<EntityType<?>> entity(String name) {
        return TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, name));
    }

    private FrontierTags() {
    }
}
