// Written by Claude.
package dev.brights0ng.empire.loot;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/**
 * Levitite and pearlescent levitite drop themselves to a silk touch pickaxe of diamond or better (the gods ask for them).
 * The rule is the loot tables Empire ships for them ({@code data/aeronautics/loot_table/blocks/}), but Create Aeronautics
 * registers both blocks with no loot table at all, so the game never rolls those: this rolls them when the blocks break,
 * for as long as Aeronautics leaves their loot tables off. Blocks are named by id only.
 */
public final class LevititeDrops {

    static final Set<ResourceLocation> BLOCKS = Set.of(
            ResourceLocation.fromNamespaceAndPath("aeronautics", "levitite"),
            ResourceLocation.fromNamespaceAndPath("aeronautics", "pearlescent_levitite"));

    public static void onBlockDrops(BlockDropsEvent event) {
        BlockState state = event.getState();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        // A block whose own loot table is on already rolled it (ours, as it overrides Aeronautics' path).
        if (!BLOCKS.contains(id) || state.getBlock().getLootTable() != BuiltInLootTables.EMPTY) {
            return;
        }
        ServerLevel level = event.getLevel();
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
            return;
        }
        LootTable table = level.getServer().reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id.withPrefix("blocks/")));
        BlockPos pos = event.getPos();
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.TOOL, event.getTool())
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .withOptionalParameter(LootContextParams.THIS_ENTITY, event.getBreaker())
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, event.getBlockEntity())
                .create(LootContextParamSets.BLOCK);
        for (ItemStack stack : table.getRandomItems(params)) {
            ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            entity.setDefaultPickUpDelay();
            event.getDrops().add(entity);
        }
    }

    private LevititeDrops() {
    }
}
