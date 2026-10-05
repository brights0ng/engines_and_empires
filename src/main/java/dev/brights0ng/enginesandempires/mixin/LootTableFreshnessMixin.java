package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.brights0ng.enginesandempires.food.SourceFreshness;
import net.minecraft.world.Container;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Food a loot table puts into a container (chests, barrels and chest minecarts in structures, when first opened) is stamped by
 * the table's freshness bucket. Other loot (mob drops, fishing, archaeology) does not go through here, so it stays fresh.
 */
@Mixin(LootTable.class)
public abstract class LootTableFreshnessMixin {

    @WrapMethod(method = "fill")
    private void engines_and_empires$stampLoot(Container container, LootParams params, long seed, Operation<Void> original) {
        original.call(container, params, seed);
        LootTable self = (LootTable) (Object) this;
        SourceFreshness.stampLoot(container, self.getLootTableId(), params.getLevel().getRandom());
    }
}
