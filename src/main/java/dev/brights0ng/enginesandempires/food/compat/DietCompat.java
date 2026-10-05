package dev.brights0ng.enginesandempires.food.compat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.food.EatingEffects;
import dev.brights0ng.enginesandempires.food.StageEffects;
import net.appleseed.appleseed.AppleSeed;
import net.appleseed.appleseed.api.hook.DietHookRegistry;
import net.appleseed.appleseed.api.hook.IDietItemFoodEatHook;
import net.appleseed.appleseed.api.hook.IDietTooltipFilterHook;
import net.appleseed.appleseed.api.type.IDietGroup;
import net.appleseed.appleseed.common.capability.DietData;
import net.appleseed.appleseed.common.data.group.DietGroups;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * The pack's hooks into Diet - AppleSeed Edition (Bright, 2026-09-29):
 * <ul>
 *   <li>What food adds to the food groups is scaled by its stage ({@link StageEffects#nutritionMultiplier()}): fresh 125%,
 *       ripe 100%, stale 50%, rotting nothing. Diet's own tooltip shows the scaled values for the stack's top group.</li>
 *   <li>Dying resets every group to {@link #DEATH_VALUE} (new players start at the groups' 50%). Coming back from the End
 *       keeps the values, which Diet on its own would reset.</li>
 *   <li>Each world's hunger decay starts at {@link #HUNGER_DECAY}: about 5% of each group per full food bar used.</li>
 * </ul>
 * The group effects themselves are data: {@code data/engines_and_empires/diet/groups}.
 *
 * <p>Uses Diet's hook API plus its internal {@code DietData}, {@code DietGroups} and gamerule keys, as of 3.0.2. Only
 * loaded when Diet is installed (see {@link #registerIfLoaded}).
 */
public final class DietCompat {

    public static final String MOD_ID = "appleseed";

    /** What every group is set to on death. */
    public static final float DEATH_VALUE = 0.25f;

    /**
     * Diet's {@code nutritionDecayByHungerMultiplier} each new world starts with, in millionths per hunger point lost:
     * 2500 = 0.25% a point, 5% per full bar of 20. Diet's default is 5000. Hit decay stays at Diet's default.
     */
    public static final int HUNGER_DECAY = 2500;

    /** Hooks into Diet if it is present. Safe to call either way. */
    public static void registerIfLoaded() {
        if (ModList.get().isLoaded(MOD_ID)) {
            Hook.register();
        }
    }

    /** Kept separate so that Diet's classes are only looked up once it is known to be there. */
    private static final class Hook {

        /** Players coming back from the End: their values, from the old player, until they respawn. */
        private static final Map<UUID, Map<String, Float>> RETURNING = new HashMap<>();

        static void register() {
            DietHookRegistry.registerItemFoodEatHook(new StageGains());
            DietHookRegistry.registerTooltipFilterHook(new StageTooltip());
            NeoForge.EVENT_BUS.addListener(Hook::onClone);
            // After Diet's own respawn handler (NORMAL), which resets every group to its starting value
            NeoForge.EVENT_BUS.addListener(EventPriority.LOW, Hook::onRespawn);
            // Before Diet's own login handler (NORMAL); see keepEmptyGroupsEmpty
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, Hook::keepEmptyGroupsEmpty);
            NeoForge.EVENT_BUS.addListener(Hook::onServerStarted);
        }

        private static void onClone(PlayerEvent.Clone event) {
            if (!event.isWasDeath() && !event.getEntity().level().isClientSide()) {
                RETURNING.put(event.getEntity().getUUID(), DietData.getAllValues(event.getOriginal()));
            }
        }

        private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
            Player player = event.getEntity();
            if (player.level().isClientSide()) {
                return;
            }
            Map<String, Float> kept = RETURNING.remove(player.getUUID());
            if (event.isEndConquered()) {
                if (kept != null) {
                    kept.forEach((group, value) -> DietData.setValue(player, group, value));
                }
            } else if (!player.level().getGameRules().getBoolean(AppleSeed.RULE_KEEPNUTRITIONS)) {
                for (IDietGroup group : DietGroups.getGroups(player.level())) {
                    DietData.setValue(player, group.getName(), DEATH_VALUE);
                }
            } else {
                return;
            }
            DietData.syncToClient(player);
        }

        /**
         * Diet gives any group at exactly 0 its starting value (50%) when a player logs in, meant for new players; that
         * would let a starved group be topped up by logging out and back in. A group that has been set and is at 0 is
         * nudged to the smallest float above 0, which Diet leaves alone and which is 0 for every other purpose.
         */
        private static void keepEmptyGroupsEmpty(PlayerEvent.PlayerLoggedInEvent event) {
            Player player = event.getEntity();
            if (player.level().isClientSide()) {
                return;
            }
            CompoundTag values = DietData.getDietTag(player);
            for (String group : values.getAllKeys()) {
                if (values.getFloat(group) == 0.0f) {
                    values.putFloat(group, Float.MIN_VALUE);
                }
            }
        }

        private static void onServerStarted(ServerStartedEvent event) {
            MinecraftServer server = event.getServer();
            WorldSetup setup = server.overworld().getDataStorage().computeIfAbsent(
                    new SavedData.Factory<>(WorldSetup::new, WorldSetup::load, null), WorldSetup.NAME);
            if (!setup.decaySet) {
                server.getGameRules().getRule(AppleSeed.RULE_DECAY_BY_HUNGER).set(HUNGER_DECAY, server);
                setup.decaySet = true;
                setup.setDirty();
                EnginesAndEmpiresMod.LOGGER.info("Diet hunger decay for this world set to {}", HUNGER_DECAY);
            }
        }
    }

    /** What eating the top group of a stack adds: Diet's gains for the item, scaled by the group's stage. */
    private static final class StageGains implements IDietItemFoodEatHook {

        @Override
        public boolean shouldIntercept(Player player, ItemStack stack) {
            return false;
        }

        @Override
        public Map<String, Float> modifyNutritionGains(Player player, ItemStack stack, Map<String, Float> gains) {
            return scaled(stack, gains);
        }
    }

    /** Diet's tooltip shows what eating the top group would really add. */
    private static final class StageTooltip implements IDietTooltipFilterHook {

        @Override
        public boolean shouldShowTooltip(ItemStack stack, Player player) {
            return true;
        }

        @Override
        public Map<String, Float> modifyTooltipNutrition(ItemStack stack, Player player, Map<String, Float> nutrition) {
            return scaled(stack, nutrition);
        }
    }

    /**
     * Gains scaled by the stage of the unit eaten. Diet calls this from {@code LivingEntityUseItemEvent.Finish}, whose item
     * is the stack as it was before the bite, so the top group is still the one that was eaten.
     */
    static Map<String, Float> scaled(ItemStack stack, Map<String, Float> gains) {
        StageEffects stage = EatingEffects.nutritionStage(stack);
        if (stage.nutritionMultiplier() == 1) {
            return gains;
        }
        Map<String, Float> out = new HashMap<>(gains);
        out.replaceAll((group, gain) -> stage.dietGain(gain));
        return out;
    }

    /** Remembers that a world has had the pack's Diet gamerule values set, so later changes by admins stay. */
    private static final class WorldSetup extends SavedData {

        static final String NAME = "engines_and_empires_diet";

        boolean decaySet;

        static WorldSetup load(CompoundTag tag, HolderLookup.Provider registries) {
            WorldSetup setup = new WorldSetup();
            setup.decaySet = tag.getBoolean("decaySet");
            return setup;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("decaySet", decaySet);
            return tag;
        }
    }

    private DietCompat() {
    }
}
