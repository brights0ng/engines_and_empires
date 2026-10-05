package dev.brights0ng.enginesandempires.food.compat;

import dev.brights0ng.enginesandempires.food.EatingEffects;
import net.neoforged.neoforge.common.NeoForge;
import squeek.appleskin.api.event.FoodValuesEvent;

/**
 * AppleSkin's food previews (the tooltip bar and the flashing hunger and saturation on the HUD) show what eating the top
 * group of a spoiled stack really gives, the same values {@link EatingEffects} hands to vanilla when it is eaten.
 *
 * <p>Only loaded when AppleSkin is installed (see {@link #registerIfLoaded}); nothing else touches its classes.
 */
public final class AppleSkinCompat {

    public static final String MOD_ID = "appleskin";

    /** Hooks into AppleSkin if it is present. Safe to call either way. */
    public static void registerIfLoaded() {
        if (net.neoforged.fml.ModList.get().isLoaded(MOD_ID)) {
            Hook.register();
        }
    }

    /** Kept separate so that AppleSkin's classes are only looked up once it is known to be there. */
    private static final class Hook {

        static void register() {
            NeoForge.EVENT_BUS.addListener(Hook::onFoodValues);
        }

        private static void onFoodValues(FoodValuesEvent event) {
            if (event.modifiedFoodProperties != null) {
                event.modifiedFoodProperties = EatingEffects.adjust(event.itemStack, event.modifiedFoodProperties);
            }
        }
    }

    private AppleSkinCompat() {
    }
}
