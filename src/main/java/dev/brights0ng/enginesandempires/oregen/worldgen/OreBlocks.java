package dev.brights0ng.enginesandempires.oregen.worldgen;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.oregen.OreTypes;
import dev.brights0ng.enginesandempires.oregen.Realm;
import dev.brights0ng.enginesandempires.oregen.rich.RichOreBlocks;
import dev.brights0ng.enginesandempires.oregen.rich.RichOres;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which blocks stand for each ore, its rich ore, and the host rock around a deposit.
 *
 * <p>Every overworld ore has a stone version and a deepslate version, and so does its rich ore; nether
 * ores have one block, which sits in netherrack, basalt and blackstone alike. A deposit's blocks use
 * the version that matches the rock they are in, so deep deposits, which sit in deepslate, are made of
 * deepslate ore and deepslate rich ore.
 *
 * <p>The ordinary blocks' ids come from {@link RichOres}, the same table the rich blocks are built from,
 * so the two cannot disagree. Blocks are looked up by registry id the first time they are needed, not
 * while classes load, because blocks are only safe to touch once the game has started up. That also means
 * a block from another mod, such as Create's zinc ore, needs no compile-time dependency on that mod: if it
 * is missing, a clearly visible placeholder block is used instead and a warning is logged.
 *
 * <p>Crystal has no rich block yet, so rich crystal is ordinary amethyst.
 */
final class OreBlocks {

    /**
     * Below this height the overworld's rock is deepslate. Vanilla terrain is solid deepslate below y=0,
     * blending into stone by y=8. Used to choose the version to build when there is no real rock to look
     * at (sky showcase mode).
     */
    static final int DEEPSLATE_BELOW_Y = 0;

    /**
     * @param stone     ordinary ore in stone-type rock
     * @param deepslate ordinary ore in deepslate-type rock, or null if the same block is used
     * @param fallback  stands in for the ordinary blocks if they are missing, or null if they must exist
     */
    private record Entry(String stone, String deepslate, String fallback) {
    }

    /** Visible stand-ins for blocks from other mods, in case that mod is not loaded. */
    private static final Map<String, String> PLACEHOLDERS = Map.of("zinc", "minecraft:light_blue_concrete");

    private static final Map<String, Entry> ENTRIES = buildEntries();

    private static final Map<String, BlockState> RESOLVED = new ConcurrentHashMap<>();
    private static final Map<String, Set<Block>> ORE_BLOCKS = new ConcurrentHashMap<>();

    private static Map<String, Entry> buildEntries() {
        Map<String, Entry> entries = new HashMap<>();
        for (RichOres.Spec spec : RichOres.ALL) {
            entries.put(spec.oreId(), new Entry(spec.stone(), spec.deepslate(), PLACEHOLDERS.get(spec.oreId())));
        }
        // Crystal is amethyst for now, and has no rich version.
        entries.put("crystal", new Entry("minecraft:amethyst_block", null, null));
        return Map.copyOf(entries);
    }

    /**
     * Every block a deposit of one ore can be made of, in a realm. Each has a stone version and a deepslate
     * version; where an ore has no separate deepslate block the two are the same.
     */
    record Palette(BlockState ore, BlockState deepOre, BlockState rich, BlockState deepRich,
                   BlockState host, BlockState deepHost) {

        BlockState ore(boolean deep) {
            return deep ? deepOre : ore;
        }

        BlockState rich(boolean deep) {
            return deep ? deepRich : rich;
        }

        BlockState host(boolean deep) {
            return deep ? deepHost : host;
        }
    }

    /** The blocks for deposits of this ore in this realm. */
    static Palette paletteFor(String oreId, Realm realm) {
        Entry entry = ENTRIES.get(oreId);
        if (entry == null) {
            throw new IllegalArgumentException("No blocks registered for ore '" + oreId + "'");
        }
        BlockState ore = resolve(entry.stone(), entry.fallback());
        BlockState deepOre = entry.deepslate() == null ? ore : resolve(entry.deepslate(), entry.fallback());

        // Rich ore: its own blocks where the ore has them, otherwise just the ordinary ore.
        RichOres.Spec richSpec = RichOres.forOre(oreId);
        BlockState rich = richSpec == null ? ore : RichOreBlocks.state(richSpec.stoneName());
        BlockState deepRich = richSpec == null ? deepOre
                : richSpec.deepslateName() == null ? rich : RichOreBlocks.state(richSpec.deepslateName());

        return new Palette(ore, deepOre, rich, deepRich, host(realm, false), host(realm, true));
    }

    /**
     * Every block that counts as ore of this kind: its stone and deepslate versions and their rich versions.
     * Used to tell whether a deposit's blocks are still standing.
     */
    static Set<Block> oreBlocks(String oreId) {
        return ORE_BLOCKS.computeIfAbsent(oreId, id -> {
            Palette palette = paletteFor(id, OreTypes.byId(id).realm());
            return Set.copyOf(List.of(palette.ore().getBlock(), palette.deepOre().getBlock(),
                    palette.rich().getBlock(), palette.deepRich().getBlock()));
        });
    }

    /** The rock a deposit sits in, where there is none already (sky showcase mode). */
    private static BlockState host(Realm realm, boolean deep) {
        String id = realm == Realm.NETHER ? "minecraft:netherrack" : deep ? "minecraft:deepslate" : "minecraft:stone";
        return resolve(id, null);
    }

    private static BlockState resolve(String id, String fallbackId) {
        BlockState state = RESOLVED.get(id);
        if (state == null) {
            state = lookup(id, fallbackId);
            RESOLVED.put(id, state);
        }
        return state;
    }

    private static BlockState lookup(String id, String fallbackId) {
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id));
        if (block.isPresent()) {
            return block.get().defaultBlockState();
        }
        if (fallbackId == null) {
            throw new IllegalStateException("Block '" + id + "' does not exist and has no fallback");
        }
        EnginesAndEmpiresMod.LOGGER.warn("Block '{}' is not available; using '{}' as a placeholder", id, fallbackId);
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(fallbackId))
                .orElseThrow(() -> new IllegalStateException("Fallback block '" + fallbackId + "' does not exist"))
                .defaultBlockState();
    }

    private OreBlocks() {
    }
}
