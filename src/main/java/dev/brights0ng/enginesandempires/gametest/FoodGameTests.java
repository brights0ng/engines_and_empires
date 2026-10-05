package dev.brights0ng.enginesandempires.gametest;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.food.Cohort;
import dev.brights0ng.enginesandempires.food.FoodFreshness;
import dev.brights0ng.enginesandempires.food.FoodNetwork;
import dev.brights0ng.enginesandempires.food.SourceFreshness;
import dev.brights0ng.enginesandempires.food.SpoilClock;
import dev.brights0ng.enginesandempires.food.SpoilTimes;
import dev.brights0ng.enginesandempires.food.Spoilage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * In-game checks of food stacking: that food of any freshness stacks, and that every way items move (menu clicks, dragging,
 * shift-clicking, picking up, item entities, hoppers, item handlers, splitting) keeps each unit's freshness, with the top
 * units leaving first. Stage lengths are the defaults (5 days each) unless a world config says otherwise.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class FoodGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    /** Ages well inside each stage, whatever the configured lengths. */
    private static long freshAge() {
        return times().freshTicks() / 20;
    }

    private static long oldFreshAge() {
        return times().freshTicks() * 17 / 20;
    }

    private static long staleAge() {
        return times().freshTicks() + times().ripeTicks() + times().staleTicks() / 2;
    }

    private static long rottingAge() {
        return times().freshTicks() + times().ripeTicks() + times().staleTicks() + SpoilTimes.DAY;
    }

    private static final int MAIN = InventoryMenu.INV_SLOT_START;
    private static final int HOTBAR = InventoryMenu.USE_ROW_SLOT_START;

    // ---- Comparing ----

    @GameTest(template = SCRATCH)
    public static void foodOfAnyFreshnessStacks(GameTestHelper helper) {
        ItemStack fresh = bread(5, freshAge());
        ItemStack stale = bread(5, staleAge());
        helper.assertTrue(ItemStack.isSameItemSameComponents(fresh, stale), "fresh and stale bread should stack");
        helper.assertFalse(ItemStack.matches(fresh, stale), "but they are not exactly equal, so menus still sync the difference");
        helper.assertTrue(ItemStack.hashItemAndComponents(fresh) == ItemStack.hashItemAndComponents(stale), "hashes should agree");
        ItemStack named = bread(5, freshAge());
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Loaf"));
        helper.assertFalse(ItemStack.isSameItemSameComponents(fresh, named), "other components still matter");
        helper.assertFalse(ItemStack.isSameItemSameComponents(new ItemStack(Items.WHEAT), new ItemStack(Items.BREAD)), "sanity");
        helper.succeed();
    }

    // ---- Menu clicks ----

    /** 21 fresh placed on 9 stale: one stack of 30, two groups. */
    @GameTest(template = SCRATCH)
    public static void placingKeepsBothGroups(GameTestHelper helper) {
        Player player = player(helper);
        AbstractContainerMenu menu = player.inventoryMenu;
        menu.getSlot(MAIN).set(bread(9, staleAge()));
        menu.setCarried(bread(21, freshAge()));
        menu.clicked(MAIN, 0, ClickType.PICKUP, player);
        expect(helper, menu.getSlot(MAIN).getItem(), "fresh 21, stale 9");
        helper.assertTrue(menu.getCarried().isEmpty(), "the cursor should be empty");
        helper.succeed();
    }

    /** Within a stage, everything takes the older age. */
    @GameTest(template = SCRATCH)
    public static void sameStageTakesTheOlderAge(GameTestHelper helper) {
        Player player = player(helper);
        AbstractContainerMenu menu = player.inventoryMenu;
        long now = SpoilClock.now();
        menu.getSlot(MAIN).set(bread(5, freshAge()));
        menu.setCarried(bread(7, oldFreshAge()));
        menu.clicked(MAIN, 0, ClickType.PICKUP, player);
        FoodFreshness f = Spoilage.stored(menu.getSlot(MAIN).getItem());
        helper.assertTrue(f != null && f.cohorts().equals(List.of(new Cohort(now - oldFreshAge(), 12))),
                "expected 12 fresh at the older age, got " + f);
        helper.succeed();
    }

    /** Right-click picks up half, from the top. */
    @GameTest(template = SCRATCH)
    public static void pickingUpHalfTakesTheTop(GameTestHelper helper) {
        Player player = player(helper);
        AbstractContainerMenu menu = player.inventoryMenu;
        menu.getSlot(MAIN).set(mixed(21, freshAge(), 9, staleAge()));
        menu.clicked(MAIN, 1, ClickType.PICKUP, player);
        expect(helper, menu.getCarried(), "fresh 15");
        expect(helper, menu.getSlot(MAIN).getItem(), "fresh 6, stale 9");
        helper.succeed();
    }

    /** Double-clicking collects matching food into the cursor, groups and all. */
    @GameTest(template = SCRATCH)
    public static void doubleClickCollectsWithGroups(GameTestHelper helper) {
        Player player = player(helper);
        AbstractContainerMenu menu = player.inventoryMenu;
        menu.getSlot(MAIN).set(bread(4, rottingAge()));
        menu.setCarried(bread(10, freshAge()));
        // The double-click lands on the (empty) slot the cursor's stack was just picked up from
        menu.clicked(MAIN + 5, 0, ClickType.PICKUP_ALL, player);
        expect(helper, menu.getCarried(), "fresh 10, rotting 4");
        helper.assertTrue(menu.getSlot(MAIN).getItem().isEmpty(), "the slot should have been emptied into the cursor");
        helper.succeed();
    }

    /** Dragging fresh food over a slot of stale food cannot make the stale food fresh. */
    @GameTest(template = SCRATCH)
    public static void draggingCannotLaunderStaleFood(GameTestHelper helper) {
        Player player = player(helper);
        AbstractContainerMenu menu = player.inventoryMenu;
        menu.getSlot(MAIN).set(bread(5, staleAge()));
        menu.setCarried(bread(10, freshAge()));
        menu.clicked(-999, AbstractContainerMenu.getQuickcraftMask(0, 0), ClickType.QUICK_CRAFT, player);
        menu.clicked(MAIN, AbstractContainerMenu.getQuickcraftMask(1, 0), ClickType.QUICK_CRAFT, player);
        menu.clicked(MAIN + 1, AbstractContainerMenu.getQuickcraftMask(1, 0), ClickType.QUICK_CRAFT, player);
        menu.clicked(-999, AbstractContainerMenu.getQuickcraftMask(2, 0), ClickType.QUICK_CRAFT, player);
        expect(helper, menu.getSlot(MAIN).getItem(), "fresh 5, stale 5");
        expect(helper, menu.getSlot(MAIN + 1).getItem(), "fresh 5");
        helper.assertTrue(menu.getCarried().isEmpty(), "the cursor should be empty");
        helper.succeed();
    }

    /** Shift-clicking food from the hotbar into a matching stack. */
    @GameTest(template = SCRATCH)
    public static void shiftClickKeepsGroups(GameTestHelper helper) {
        Player player = player(helper);
        AbstractContainerMenu menu = player.inventoryMenu;
        menu.getSlot(MAIN).set(bread(5, staleAge()));
        menu.getSlot(HOTBAR).set(bread(10, freshAge()));
        menu.clicked(HOTBAR, 0, ClickType.QUICK_MOVE, player);
        expect(helper, menu.getSlot(MAIN).getItem(), "fresh 10, stale 5");
        helper.assertTrue(menu.getSlot(HOTBAR).getItem().isEmpty(), "the hotbar slot should be empty");
        helper.succeed();
    }

    // ---- Other routes ----

    @GameTest(template = SCRATCH)
    public static void pickingUpJoinsTheStack(GameTestHelper helper) {
        Player player = player(helper);
        player.getInventory().setItem(0, bread(10, freshAge()));
        ItemStack incoming = bread(4, staleAge());
        player.getInventory().add(incoming);
        expect(helper, player.getInventory().getItem(0), "fresh 10, stale 4");
        helper.assertTrue(incoming.isEmpty(), "everything should have been picked up");
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void itemEntitiesMergeWithGroups(GameTestHelper helper) {
        ItemStack destination = bread(10, freshAge());
        ItemStack origin = bread(4, staleAge());
        ItemStack merged = ItemEntity.merge(destination, origin, 64);
        expect(helper, merged, "fresh 10, stale 4");
        helper.assertTrue(origin.isEmpty(), "the origin should be used up");
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void hoppersKeepGroups(GameTestHelper helper) {
        SimpleContainer chest = new SimpleContainer(1);
        chest.setItem(0, bread(10, freshAge()));
        ItemStack left = HopperBlockEntity.addItem(null, chest, bread(5, staleAge()), null);
        expect(helper, chest.getItem(0), "fresh 10, stale 5");
        helper.assertTrue(left.isEmpty(), "everything should have gone in");
        helper.succeed();
    }

    /** Feeding fresh food into a chest through an item handler (as Create does) must not make the rotten food there fresh. */
    @GameTest(template = SCRATCH)
    public static void itemHandlersCannotLaunderRottenFood(GameTestHelper helper) {
        SimpleContainer chest = new SimpleContainer(1);
        chest.setItem(0, bread(5, rottingAge()));
        ItemStack left = new InvWrapper(chest).insertItem(0, bread(10, freshAge()), false);
        expect(helper, chest.getItem(0), "fresh 10, rotting 5");
        helper.assertTrue(left.isEmpty(), "everything should have gone in");

        ItemStackHandler handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, bread(5, rottingAge()));
        handler.insertItem(0, bread(10, freshAge()), false);
        expect(helper, handler.getStackInSlot(0), "fresh 10, rotting 5");
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void extractingTakesTheTop(GameTestHelper helper) {
        ItemStackHandler handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, mixed(21, freshAge(), 9, staleAge()));
        ItemStack out = handler.extractItem(0, 25, false);
        expect(helper, out, "fresh 21, stale 4");
        expect(helper, handler.getStackInSlot(0), "stale 5");
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void splittingTakesTheTop(GameTestHelper helper) {
        ItemStack stack = mixed(21, freshAge(), 9, staleAge());
        ItemStack taken = stack.split(10);
        expect(helper, taken, "fresh 10");
        expect(helper, stack, "fresh 11, stale 9");
        helper.succeed();
    }

    /** Count changes nothing accounted for can only make food staler. */
    @GameTest(template = SCRATCH)
    public static void unknownRoutesOnlyMakeFoodStaler(GameTestHelper helper) {
        ItemStack stack = mixed(10, freshAge(), 5, staleAge());
        stack.grow(3);
        expect(helper, stack, "fresh 10, stale 8");
        stack.shrink(4);
        expect(helper, stack, "fresh 6, stale 8");
        helper.succeed();
    }

    /** Unstamped food merged into stamped food is stamped as born now. */
    @GameTest(template = SCRATCH)
    public static void unstampedFoodCountsAsNew(GameTestHelper helper) {
        Player player = player(helper);
        AbstractContainerMenu menu = player.inventoryMenu;
        menu.getSlot(MAIN).set(new ItemStack(Items.BREAD, 3));
        menu.setCarried(bread(2, staleAge()));
        menu.clicked(MAIN, 0, ClickType.PICKUP, player);
        expect(helper, menu.getSlot(MAIN).getItem(), "fresh 3, stale 2");
        helper.succeed();
    }

    /** Groups that age into the same stage become one when the stack is next tidied. */
    @GameTest(template = SCRATCH)
    public static void groupsThatAgeTogetherMerge(GameTestHelper helper) {
        long now = SpoilClock.now();
        long fresh = times().freshTicks();
        // Just before and just after the fresh/ripe boundary, stored as two groups
        ItemStack stack = new ItemStack(Items.BREAD, 10);
        Spoilage.write(stack, new FoodFreshness(List.of(new Cohort(now - (fresh - 100), 4), new Cohort(now - (fresh + 100), 6)), 0));
        expect(helper, stack, "fresh 4, ripe 6");
        // A little later both are ripe
        Spoilage.write(stack, Spoilage.stored(stack).aged(200));
        helper.assertTrue(Spoilage.normalize(stack), "tidying should have merged the groups");
        expect(helper, stack, "ripe 10");
        helper.assertTrue(Spoilage.stored(stack).cohorts().get(0).born() == now - (fresh + 300), "the merged group takes the older age");
        helper.succeed();
    }

    // ---- Helpers ----

    // ---- Where food comes from ----

    @GameTest(template = SCRATCH)
    public static void rottenFleshIsAlwaysRotting(GameTestHelper helper) {
        ItemStack unstamped = new ItemStack(Items.ROTTEN_FLESH, 5);
        Spoilage.normalize(unstamped);
        expect(helper, unstamped, "rotting 5");
        ItemStack stampedFresh = new ItemStack(Items.ROTTEN_FLESH, 3);
        Spoilage.write(stampedFresh, FoodFreshness.born(SpoilClock.now(), 3));
        expect(helper, stampedFresh, "rotting 3");
        helper.succeed();
    }

    /** Each bucket hands out its two stages, and never anything else. */
    @GameTest(template = SCRATCH)
    public static void lootChestsStampByBucket(GameTestHelper helper) {
        expectLoot(helper, "minecraft:chests/village/village_plains_house", "fresh", "ripe");
        expectLoot(helper, "minecraft:chests/village/village_taiga_house", "fresh", "ripe");
        expectLoot(helper, "minecraft:chests/pillager_outpost", "ripe", "stale");
        expectLoot(helper, "minecraft:chests/woodland_mansion", "ripe", "stale");
        expectLoot(helper, "minecraft:chests/bastion_other", "ripe", "stale");
        expectLoot(helper, "minecraft:chests/ruined_portal", "stale", "rotting");
        expectLoot(helper, "minecraft:chests/simple_dungeon", "stale", "rotting");
        expectLoot(helper, "somemod:chests/farmhouse", "stale", "rotting");
        helper.succeed();
    }

    /** A real village chest filling itself goes through the loot hook. */
    @GameTest(template = SCRATCH)
    public static void realVillageChestsAreStamped(GameTestHelper helper) {
        LootTable table = helper.getLevel().getServer().reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.withDefaultNamespace("chests/village/village_plains_house")));
        int food = 0;
        for (long seed = 1; seed <= 64 && food == 0; seed++) {
            SimpleContainer chest = new SimpleContainer(27);
            table.fill(chest, new LootParams.Builder(helper.getLevel()).withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                    .create(LootContextParamSets.CHEST), seed);
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack stack = chest.getItem(i);
                if (Spoilage.isSpoilable(stack)) {
                    food++;
                    String stage = describe(stack).split(" ")[0];
                    helper.assertTrue(Spoilage.stored(stack) != null && (stage.equals("fresh") || stage.equals("ripe")),
                            "village chest food should be fresh or ripe, got " + describe(stack) + " stored " + Spoilage.stored(stack));
                }
            }
        }
        helper.assertTrue(food > 0, "no food in 64 village chests?");
        helper.succeed();
    }

    /** Villager trades are fresh or ripe, and picking the same trade again does not re-roll it. */
    @GameTest(template = SCRATCH)
    public static void villagerTradesAreStamped(GameTestHelper helper) {
        Villager villager = breadSeller(helper);
        MerchantContainer trade = new MerchantContainer(villager);
        trade.setItem(0, new ItemStack(Items.EMERALD, 5));
        ItemStack result = trade.getItem(2);
        String stage = describe(result).split(" ")[0];
        helper.assertTrue(stage.equals("fresh") || stage.equals("ripe"), "a trade should be fresh or ripe, got " + describe(result));
        FoodFreshness first = Spoilage.stored(result);
        trade.updateSellItem();
        helper.assertTrue(first.equals(Spoilage.stored(trade.getItem(2))), "picking the trade again should not re-roll it");

        // Shift-clicking the result trades until the emeralds run out, and every loaf keeps a trade's freshness
        Player player = player(helper);
        MerchantMenu menu = new MerchantMenu(1, player.getInventory(), villager);
        menu.getSlot(0).set(new ItemStack(Items.EMERALD, 3));
        menu.clicked(2, 0, ClickType.QUICK_MOVE, player);
        int bread = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.BREAD)) {
                bread += stack.getCount();
                helper.assertTrue(describe(stack).matches("(fresh \\d+|ripe \\d+)(, (fresh|ripe) \\d+)?"), "got " + describe(stack));
                // Every loaf came from a stamped trade (somewhere inside its stage), none was just born now
                for (Cohort cohort : Spoilage.stored(stack).cohorts()) {
                    helper.assertTrue(cohort.born() < SpoilClock.now(), "a shift-clicked trade came out born now: " + Spoilage.stored(stack));
                }
            }
        }
        helper.assertTrue(bread == 18, "three trades of 6 bread should give 18, got " + bread);
        helper.succeed();
    }

    // ---- Choosing the top group ----

    @GameTest(template = SCRATCH)
    public static void scrollingChoosesTheTopGroup(GameTestHelper helper) {
        Player player = player(helper);
        AbstractContainerMenu menu = player.inventoryMenu;
        menu.getSlot(MAIN).set(mixed(21, freshAge(), 9, staleAge()));
        helper.assertTrue(FoodNetwork.rotateTop(player, menu.containerId, MAIN, true), "should have rotated");
        expect(helper, menu.getSlot(MAIN).getItem(), "stale 9, fresh 21");
        // The top group is what leaves first
        menu.clicked(MAIN, 1, ClickType.PICKUP, player);
        expect(helper, menu.getCarried(), "stale 9, fresh 6");
        expect(helper, menu.getSlot(MAIN).getItem(), "fresh 15");
        helper.assertFalse(FoodNetwork.rotateTop(player, menu.containerId, MAIN, true), "one group has nothing to scroll to");
        helper.assertFalse(FoodNetwork.rotateTop(player, menu.containerId + 1, MAIN, true), "another menu's id is ignored");
        helper.succeed();
    }

    /** Eating takes from the top group: the scrolled-to one, or the freshest by default. */
    @GameTest(template = SCRATCH)
    public static void eatingTakesTheTopGroup(GameTestHelper helper) {
        Player player = player(helper);
        player.getFoodData().setFoodLevel(0);
        player.getInventory().setItem(0, mixed(21, freshAge(), 9, staleAge()));
        ItemStack stack = player.getInventory().getItem(0);
        player.eat(helper.getLevel(), stack, stack.get(net.minecraft.core.component.DataComponents.FOOD));
        expect(helper, stack, "fresh 20, stale 9");
        helper.assertTrue(FoodNetwork.rotateTop(player, player.inventoryMenu.containerId, HOTBAR, true), "should have rotated");
        player.eat(helper.getLevel(), stack, stack.get(net.minecraft.core.component.DataComponents.FOOD));
        expect(helper, stack, "stale 8, fresh 20");
        helper.succeed();
    }

    /** What a loaf of bread (5 food, 6 saturation) gives at each stage, and the rotting effects. */
    @GameTest(template = SCRATCH)
    public static void eatingGivesTheStagesValues(GameTestHelper helper) {
        expectEating(helper, freshAge(), 5, false);
        expectEating(helper, times().freshTicks() + times().ripeTicks() / 2, 5, false);
        expectEating(helper, staleAge(), 4, false);
        expectEating(helper, rottingAge(), 3, true);

        net.minecraft.world.food.FoodProperties bread = new ItemStack(Items.BREAD).get(net.minecraft.core.component.DataComponents.FOOD);
        ItemStack stale = bread(1, staleAge());
        ItemStack rotting = bread(1, rottingAge());
        helper.assertTrue(Math.abs(dev.brights0ng.enginesandempires.food.EatingEffects.adjust(stale, bread).saturation() - 4.5f) < 1e-4,
                "stale bread should give 4.5 saturation");
        helper.assertTrue(Math.abs(dev.brights0ng.enginesandempires.food.EatingEffects.adjust(rotting, bread).saturation() - 3f) < 1e-4,
                "rotting bread should give 3 saturation");
        helper.succeed();
    }

    private static void expectEating(GameTestHelper helper, long age, int food, boolean sick) {
        Player player = player(helper);
        player.getFoodData().setFoodLevel(0);
        player.getFoodData().setSaturation(0);
        ItemStack stack = bread(3, age);
        String stage = describe(stack);
        player.eat(helper.getLevel(), stack, stack.get(net.minecraft.core.component.DataComponents.FOOD));
        helper.assertTrue(player.getFoodData().getFoodLevel() == food,
                stage + " bread should give " + food + " food, gave " + player.getFoodData().getFoodLevel());
        for (var effect : List.of(net.minecraft.world.effect.MobEffects.POISON, net.minecraft.world.effect.MobEffects.WEAKNESS,
                net.minecraft.world.effect.MobEffects.CONFUSION)) {
            helper.assertTrue(player.hasEffect(effect) == sick, stage + " bread: " + effect.getRegisteredName()
                    + (sick ? " missing" : " should not be there"));
        }
        if (sick) {
            helper.assertTrue(player.getEffect(net.minecraft.world.effect.MobEffects.POISON).getDuration() == 60, "poison should last 3 s");
            helper.assertTrue(player.getEffect(net.minecraft.world.effect.MobEffects.WEAKNESS).getDuration() == 400, "weakness should last 20 s");
        }
    }

    /** AppleSkin's previews get the same values eating gives (the dev runs always have AppleSkin). */
    @GameTest(template = SCRATCH)
    public static void appleSkinPreviewsSpoiledValues(GameTestHelper helper) {
        helper.assertTrue(net.neoforged.fml.ModList.get().isLoaded("appleskin"), "AppleSkin should be in the dev runtime");
        Player player = player(helper);
        net.minecraft.world.food.FoodProperties bread = new ItemStack(Items.BREAD).get(net.minecraft.core.component.DataComponents.FOOD);
        expectPreview(helper, player, bread(1, freshAge()), bread, 5, 6f);
        expectPreview(helper, player, bread(1, staleAge()), bread, 4, 4.5f);
        expectPreview(helper, player, bread(1, rottingAge()), bread, 3, 3f);
        helper.succeed();
    }

    private static void expectPreview(GameTestHelper helper, Player player, ItemStack stack,
                                      net.minecraft.world.food.FoodProperties bread, int food, float saturation) {
        squeek.appleskin.api.event.FoodValuesEvent event = new squeek.appleskin.api.event.FoodValuesEvent(player, stack, bread, bread);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
        helper.assertTrue(event.modifiedFoodProperties.nutrition() == food
                        && Math.abs(event.modifiedFoodProperties.saturation() - saturation) < 1e-4,
                describe(stack) + " bread should preview " + food + "/" + saturation + ", previewed "
                        + event.modifiedFoodProperties.nutrition() + "/" + event.modifiedFoodProperties.saturation());
    }

    // ---- The ice chest ----

    private static dev.brights0ng.enginesandempires.food.icechest.IceChestBlockEntity iceChest(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos, dev.brights0ng.enginesandempires.food.FoodContent.ICE_CHEST.get());
        return (dev.brights0ng.enginesandempires.food.icechest.IceChestBlockEntity) helper.getBlockEntity(pos);
    }

    /** One block of ice keeps food 90% fresher for its 2.5 minutes, then is gone. */
    @GameTest(template = SCRATCH)
    public static void iceChestSlowsSpoiling(GameTestHelper helper) {
        var chest = iceChest(helper);
        long t0 = SpoilClock.now();
        chest.settleAt(t0);
        chest.inventory().insertItem(0, new ItemStack(Items.ICE), false);
        chest.inventory().insertItem(1, bread(5, freshAge()), false);
        long born = Spoilage.stored(chest.inventory().getStackInSlot(1)).cohorts().get(0).born();
        // Ten minutes later: the ice cooled for 3000 ticks of it
        chest.settleAt(t0 + 6000);
        long bornAfter = Spoilage.stored(chest.inventory().getStackInSlot(1)).cohorts().get(0).born();
        helper.assertTrue(bornAfter - born == 2700, "the bread should be 2700 ticks younger, is " + (bornAfter - born));
        helper.assertTrue(chest.inventory().getStackInSlot(0).isEmpty(), "the ice should be used up");
        helper.assertFalse(chest.isCooling(), "nothing should be burning");
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void iceChestBurnsIceOneAtATime(GameTestHelper helper) {
        var chest = iceChest(helper);
        long t0 = SpoilClock.now();
        chest.settleAt(t0);
        chest.inventory().insertItem(0, new ItemStack(Items.PACKED_ICE, 3), false);
        chest.inventory().insertItem(1, bread(1, freshAge()), false);
        chest.settleAt(t0 + 15000);
        helper.assertTrue(chest.inventory().getStackInSlot(0).getCount() == 1, "the second packed ice should be burning, so one left");
        helper.assertTrue(chest.isCooling(), "ice should still be burning");
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void iceChestDoesNotMeltWithoutFood(GameTestHelper helper) {
        var chest = iceChest(helper);
        long t0 = SpoilClock.now();
        chest.settleAt(t0);
        chest.inventory().insertItem(0, new ItemStack(Items.ICE, 2), false);
        chest.settleAt(t0 + 100_000);
        helper.assertTrue(chest.inventory().getStackInSlot(0).getCount() == 2, "an empty chest should not melt its ice");
        helper.succeed();
    }

    /** Ice goes in the top, food in the sides, food comes out the bottom; each slot takes only its own kind. */
    @GameTest(template = SCRATCH)
    public static void iceChestSides(GameTestHelper helper) {
        iceChest(helper);
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        var top = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, net.minecraft.core.Direction.UP);
        var side = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, net.minecraft.core.Direction.NORTH);
        var bottom = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, net.minecraft.core.Direction.DOWN);
        helper.assertTrue(top != null && side != null && bottom != null, "every side should have a handler");
        helper.assertTrue(top.insertItem(0, bread(1, freshAge()), false).getCount() == 1, "food should not go in the top");
        helper.assertTrue(top.insertItem(0, new ItemStack(Items.BLUE_ICE, 4), false).isEmpty(), "ice should go in the top");
        helper.assertTrue(insertAnywhere(side, new ItemStack(Items.ICE)).getCount() == 1, "ice should not go in the sides");
        helper.assertTrue(insertAnywhere(side, new ItemStack(Items.WHEAT)).getCount() == 1, "only food goes in the sides");
        helper.assertTrue(insertAnywhere(side, bread(6, freshAge())).isEmpty(), "food should go in the sides");
        helper.assertTrue(insertAnywhere(bottom, bread(1, freshAge())).getCount() == 1, "nothing goes in the bottom");
        ItemStack out = ItemStack.EMPTY;
        for (int i = 0; i < bottom.getSlots() && out.isEmpty(); i++) {
            out = bottom.extractItem(i, 64, false);
        }
        helper.assertTrue(out.is(Items.BREAD) && out.getCount() == 6, "the bread should come out the bottom, got " + out);
        helper.assertTrue(top.extractItem(0, 64, false).isEmpty(), "ice cannot be pulled back out the top");
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void iceChestRecipeLoads(GameTestHelper helper) {
        helper.assertTrue(helper.getLevel().getRecipeManager()
                .byKey(ResourceLocation.fromNamespaceAndPath("engines_and_empires", "ice_chest")).isPresent(), "no ice chest recipe");
        helper.succeed();
    }

    private static ItemStack insertAnywhere(net.neoforged.neoforge.items.IItemHandler handler, ItemStack stack) {
        ItemStack left = stack;
        for (int i = 0; i < handler.getSlots() && !left.isEmpty(); i++) {
            left = handler.insertItem(i, left, false);
        }
        return left;
    }

    private static Villager breadSeller(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setNoAi(true);
        MerchantOffers offers = villager.getOffers();
        offers.clear();
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD, 6), 12, 1, 0.05F));
        return villager;
    }

    /** A container of 20 single loaves, stamped as loot from {@code table}: only the two stages, and both of them. */
    private static void expectLoot(GameTestHelper helper, String table, String first, String second) {
        SimpleContainer chest = new SimpleContainer(20);
        for (int i = 0; i < 20; i++) {
            chest.setItem(i, new ItemStack(Items.BREAD));
        }
        SourceFreshness.stampLoot(chest, ResourceLocation.parse(table), RandomSource.create(table.hashCode()));
        java.util.Set<String> seen = new java.util.TreeSet<>();
        for (int i = 0; i < 20; i++) {
            ItemStack stack = chest.getItem(i);
            helper.assertTrue(Spoilage.stored(stack) != null, table + " left a loaf unstamped");
            seen.add(describe(stack).split(" ")[0]);
        }
        java.util.Set<String> expected = new java.util.TreeSet<>(List.of(first, second));
        helper.assertTrue(seen.equals(expected), table + " should give " + expected + ", gave " + seen);
    }

    private static SpoilTimes times() {
        return Spoilage.times();
    }

    private static Player player(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().clearContent();
        return player;
    }

    /** {@code count} bread, {@code age} ticks old. */
    private static ItemStack bread(int count, long age) {
        ItemStack stack = new ItemStack(Items.BREAD, count);
        Spoilage.write(stack, FoodFreshness.born(SpoilClock.now() - age, count));
        return stack;
    }

    private static ItemStack mixed(int firstCount, long firstAge, int secondCount, long secondAge) {
        long now = SpoilClock.now();
        ItemStack stack = new ItemStack(Items.BREAD, firstCount + secondCount);
        Spoilage.write(stack, new FoodFreshness(List.of(new Cohort(now - firstAge, firstCount), new Cohort(now - secondAge, secondCount)), 0)
                .settle(now, times()));
        return stack;
    }

    /** The stack's groups, top first, like "fresh 21, stale 9". */
    private static String describe(ItemStack stack) {
        if (stack.isEmpty()) {
            return "(empty)";
        }
        long now = SpoilClock.now();
        FoodFreshness view = Spoilage.view(stack, now);
        List<String> parts = new ArrayList<>();
        int size = view.cohorts().size();
        for (int step = 0; step < size; step++) {
            Cohort cohort = view.cohorts().get((view.top() + step) % size);
            parts.add(times().stage(now - cohort.born()).id() + " " + cohort.count());
        }
        String described = String.join(", ", parts);
        return view.total() == stack.getCount() ? described : described + " (but the stack holds " + stack.getCount() + ")";
    }

    private static void expect(GameTestHelper helper, ItemStack stack, String expected) {
        String actual = describe(stack);
        FoodFreshness stored = Spoilage.stored(stack);
        helper.assertTrue(expected.equals(actual) && stored != null && stored.total() == stack.getCount(),
                "expected " + expected + ", got " + actual + " (stored " + stored + ")");
    }

    private FoodGameTests() {
    }
}
