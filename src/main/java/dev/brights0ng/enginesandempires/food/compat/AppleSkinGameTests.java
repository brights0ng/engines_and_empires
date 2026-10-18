package dev.brights0ng.enginesandempires.food.compat;

import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.simplespoilage.FoodFreshness;
import dev.brights0ng.simplespoilage.SpoilClock;
import dev.brights0ng.simplespoilage.SpoilTimes;
import dev.brights0ng.simplespoilage.Spoilage;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import squeek.appleskin.api.event.FoodValuesEvent;

/**
 * In-game check of the AppleSkin hook ({@link AppleSkinCompat}): its previews get the same values eating gives. Split off
 * Simple Spoilage's food game tests in split step 2d (plan section 10), since it needs AppleSkin, which only the core's
 * dev runs load. Stage lengths are the defaults unless a world config says otherwise.
 */
@GameTestHolder(EnginesAndEmpires.MODID)
@PrefixGameTestTemplate(false)
public final class AppleSkinGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    /** AppleSkin's previews get the same values eating gives (the dev runs always have AppleSkin). */
    @GameTest(template = SCRATCH)
    public static void appleSkinPreviewsSpoiledValues(GameTestHelper helper) {
        helper.assertTrue(net.neoforged.fml.ModList.get().isLoaded(AppleSkinCompat.MOD_ID), "AppleSkin should be in the dev runtime");
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().clearContent();
        FoodProperties bread = new ItemStack(Items.BREAD).get(DataComponents.FOOD);
        SpoilTimes times = Spoilage.times();
        expectPreview(helper, player, bread(times.freshTicks() / 20), bread, 5, 6f);
        expectPreview(helper, player, bread(times.freshTicks() + times.ripeTicks() + times.staleTicks() / 2), bread, 4, 4.5f);
        expectPreview(helper, player, bread(times.freshTicks() + times.ripeTicks() + times.staleTicks() + SpoilTimes.DAY),
                bread, 3, 3f);
        helper.succeed();
    }

    private static void expectPreview(GameTestHelper helper, Player player, ItemStack stack, FoodProperties bread,
                                      int food, float saturation) {
        FoodValuesEvent event = new FoodValuesEvent(player, stack, bread, bread);
        NeoForge.EVENT_BUS.post(event);
        helper.assertTrue(event.modifiedFoodProperties.nutrition() == food
                        && Math.abs(event.modifiedFoodProperties.saturation() - saturation) < 1e-4,
                describe(stack) + " bread should preview " + food + "/" + saturation + ", previewed "
                        + event.modifiedFoodProperties.nutrition() + "/" + event.modifiedFoodProperties.saturation());
    }

    /** One bread, {@code age} ticks old. */
    private static ItemStack bread(long age) {
        ItemStack stack = new ItemStack(Items.BREAD, 1);
        Spoilage.write(stack, FoodFreshness.born(SpoilClock.now() - age, 1));
        return stack;
    }

    /** The bread's stage, like "stale 1". */
    private static String describe(ItemStack stack) {
        long now = SpoilClock.now();
        FoodFreshness view = Spoilage.view(stack, now);
        var top = view.cohorts().get(view.top());
        return Spoilage.times().stage(now - top.born()).id() + " " + top.count();
    }

    private AppleSkinGameTests() {
    }
}
