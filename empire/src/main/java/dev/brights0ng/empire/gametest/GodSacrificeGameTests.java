// Written by Claude.
package dev.brights0ng.empire.gametest;

import java.util.List;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireConfig;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.bonfire.BonfireItem;
import dev.brights0ng.empire.compat.frontier.FrontierCompat;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.god.Demands;
import dev.brights0ng.empire.god.GodDefinition;
import dev.brights0ng.empire.god.Gods;
import dev.brights0ng.empire.god.Offerings;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.DawnClock;
import dev.brights0ng.empire.rules.DemandLine;
import dev.brights0ng.empire.rules.DemandRoller;
import dev.brights0ng.empire.tier.TierCheck;
import dev.brights0ng.empire.tier.TierChecks;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Sacrifices to the gods at a bonfire: founding with a god's meat, exclusivity, paying demands (items and fluids, in part
 * and in full), adoption by an empire from before gods, and who may pay where. Every test runs to the end in its first
 * tick, so the empires it makes never meet another test's.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class GodSacrificeGameTests {

    private static final BlockPos SPOT = new BlockPos(3, 1, 3);
    /** A second unclaimed bonfire, far from every test structure. */
    private static final BlockPos FAR = new BlockPos(1632, 64, -1632);

    static final ResourceLocation IRON_AUROCHS = Empire.id("iron_aurochs");

    @GameTest(template = GameTestStructures.EMPTY)
    public static void foundingWithFiveBeefGivesTheIronAurochs(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            placeLitBonfire(helper, SPOT);
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);

            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 4));
            interact(helper.getLevel(), helper.absolutePos(SPOT), player);
            helper.assertTrue(player.getMainHandItem().getCount() == 4, "4 beef are not enough and are not taken");
            helper.assertTrue(data.getEmpireOf(player.getUUID()) == null, "no empire from 4 beef");

            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 7));
            interact(helper.getLevel(), helper.absolutePos(SPOT), player);
            helper.assertTrue(player.getMainHandItem().getCount() == 2, "exactly 5 beef are taken");
            EmpireRecord record = data.getEmpireOf(player.getUUID());
            helper.assertTrue(record != null, "5 beef found an empire");
            try {
                helper.assertTrue(IRON_AUROCHS.equals(record.god()), "beef is the Iron Aurochs' meat, god is " + record.god());
                helper.assertTrue(record.state().dominion() == 10, "founding dominion is 10, is " + record.state().dominion());
                helper.assertTrue(record.state().stakedCount() == 9, "9 chunks staked at founding");
                long day = DawnClock.dayOf(helper.getLevel().getDayTime());
                helper.assertTrue(record.state().available(day) == 1, "1 dominion point available at founding");
                helper.assertTrue(record.sacrificeCount() == 0, "no demand paid yet");

                List<DemandLine> demand = record.currentDemand();
                GodDefinition god = Gods.get(IRON_AUROCHS);
                helper.assertTrue(demand.size() == 2, "D 10 asks for tiers 1 and 2 only: " + demand);
                helper.assertTrue(god.foundingMeat().get(0).sameAs(demand.get(0)), "tier 1 is beef: " + demand.get(0));
                helper.assertTrue(god.tiers().get(1).stream().anyMatch(o -> o.sameAs(demand.get(1))),
                        "tier 2 is wheat or leather: " + demand.get(1));
                helper.assertTrue(demand.stream().allMatch(l -> l.paid() == 0), "nothing paid yet: " + demand);
                helper.assertTrue(demand.equals(DemandRoller.roll(record.id().getMostSignificantBits(),
                        record.id().getLeastSignificantBits(), 0, 10, god.tiers())), "the first demand is the roller's");
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void uniqueGodsRefuseAGodAnotherEmpireHas(GameTestHelper helper) {
        boolean before = EmpireConfig.UNIQUE_GODS_PER_SERVER.get();
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        Level overworld = server.overworld();
        withTierChecksPassing(() -> {
            placeLitBonfire(helper, SPOT);
            overworld.setBlock(FAR, litBonfire(), 3);
            Player first = helper.makeMockPlayer(GameType.SURVIVAL);
            Player second = helper.makeMockPlayer(GameType.SURVIVAL);
            try {
                EmpireConfig.UNIQUE_GODS_PER_SERVER.set(true);
                first.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item("naturalist:lizard_tail"), 5));
                interact(helper.getLevel(), helper.absolutePos(SPOT), first);
                EmpireRecord a = data.getEmpireOf(first.getUUID());
                helper.assertTrue(a != null && Empire.id("sun_scale").equals(a.god()), "lizard tails found under the Sun-Scale");

                second.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item("naturalist:lizard_tail"), 5));
                interact(overworld, FAR, second);
                helper.assertTrue(data.getEmpireOf(second.getUUID()) == null,
                        "with unique gods, a second empire cannot take the Sun-Scale");
                helper.assertTrue(second.getMainHandItem().getCount() == 5, "the refused lizard tails are not taken");

                EmpireConfig.UNIQUE_GODS_PER_SERVER.set(false);
                interact(overworld, FAR, second);
                EmpireRecord b = data.getEmpireOf(second.getUUID());
                helper.assertTrue(b != null && Empire.id("sun_scale").equals(b.god()),
                        "without unique gods, two empires may share the Sun-Scale");
                helper.assertTrue(second.getMainHandItem().isEmpty(), "the second founding took its 5 lizard tails");
            } finally {
                EmpireConfig.UNIQUE_GODS_PER_SERVER.set(before);
                disband(data, first, second);
                overworld.setBlock(FAR, Blocks.AIR.defaultBlockState(), 3);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void aPartialDepositPersistsAcrossSaveAndLoad(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, player);
            try {
                DemandLine beef = record.currentDemand().get(0);
                helper.assertTrue(beef.required() > 3, "the beef line asks for more than 3: " + beef);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 3));
                interact(helper.getLevel(), helper.absolutePos(SPOT), player);
                helper.assertTrue(player.getMainHandItem().isEmpty(), "all 3 beef went towards the line");
                helper.assertTrue(record.currentDemand().get(0).paid() == 3, "3 paid: " + record.currentDemand().get(0));
                helper.assertTrue(record.state().dominion() == 10, "a partial payment adds no dominion");
                helper.assertTrue(data.isDirty(), "a deposit marks the empires to be saved");

                CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
                EmpireRecord loaded = EmpireSavedData.load(saved, helper.getLevel().registryAccess()).getEmpire(record.id());
                helper.assertTrue(loaded != null, "the empire loads back");
                helper.assertTrue(loaded.currentDemand().equals(record.currentDemand()),
                        "the demand loads back with what was paid: " + loaded.currentDemand());
                helper.assertTrue(IRON_AUROCHS.equals(loaded.god()) && loaded.sacrificeCount() == 0,
                        "god and sacrifice count load back");
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void payingTheWholeDemandAddsFiveDominionAndRollsANewOne(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, player);
            try {
                payInFull(helper, helper.getLevel(), helper.absolutePos(SPOT), player, record);

                helper.assertTrue(record.state().dominion() == 15, "dominion 10 + 5, is " + record.state().dominion());
                helper.assertTrue(record.sacrificeCount() == 1, "one demand paid");
                List<DemandLine> next = record.currentDemand();
                helper.assertTrue(!next.isEmpty() && next.stream().allMatch(l -> l.paid() == 0),
                        "a fresh demand, nothing paid: " + next);
                helper.assertTrue(next.equals(DemandRoller.roll(record.id().getMostSignificantBits(),
                        record.id().getLeastSignificantBits(), 1, 15, Gods.get(IRON_AUROCHS).tiers())),
                        "the next demand is rolled at D 15 for the second sacrifice: " + next);
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void aFluidLineIsPaidFromABucketAndACanisterAndTheyAreKept(GameTestHelper helper) {
        clearArea(helper, SPOT);
        placeLitBonfire(helper, SPOT);
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = helper.absolutePos(SPOT);
        ChunkPos chunk = new ChunkPos(pos);
        EmpireRecord record = data.createEmpire(player.getUUID(), ChunkKeys.pack(chunk.x, chunk.z), pos, helper.getLevel().getGameTime());
        try {
            record.setGod(Empire.id("pale_stag"));
            String biodiesel = "createdieselgenerators:biodiesel";
            record.setCurrentDemand(List.of(
                    new DemandLine(DemandLine.Kind.FLUID, List.of(biodiesel), 2000, 0),
                    new DemandLine(DemandLine.Kind.ITEM, List.of("minecraft:sweet_berries"), 1, 0)));
            Fluid fluid = Offerings.fluid(biodiesel);
            helper.assertTrue(fluid != null, "Diesel Generators' biodiesel is registered");

            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(fluid.getBucket()));
            interact(helper.getLevel(), pos, player);
            helper.assertTrue(record.currentDemand().get(0).paid() == 1000, "a bucket pays 1000 mB: " + record.currentDemand());
            helper.assertTrue(player.getMainHandItem().is(Items.BUCKET), "the emptied bucket is kept, hand holds "
                    + player.getMainHandItem());

            ItemStack canister = new ItemStack(item("createdieselgenerators:canister"));
            IFluidHandlerItem handler = canister.getCapability(Capabilities.FluidHandler.ITEM);
            helper.assertTrue(handler != null, "a canister is a fluid container");
            helper.assertTrue(handler.fill(new FluidStack(fluid, 1500), IFluidHandler.FluidAction.EXECUTE) == 1500,
                    "a canister holds 1500 mB");
            player.setItemInHand(InteractionHand.MAIN_HAND, handler.getContainer());
            interact(helper.getLevel(), pos, player);
            helper.assertTrue(record.currentDemand().get(0).isPaid(), "the canister pays the last 1000 mB: "
                    + record.currentDemand());
            ItemStack kept = player.getMainHandItem();
            helper.assertTrue(kept.is(canister.getItem()), "the canister is kept, hand holds " + kept);
            int left = FluidUtil.getFluidContained(kept).map(FluidStack::getAmount).orElse(0);
            helper.assertTrue(left == 500, "only what the line needed was drained; 500 mB left, found " + left);
            helper.assertTrue(record.state().dominion() == 10 && record.sacrificeCount() == 0,
                    "the berries are still owed, so nothing is complete yet");

            String status = Demands.status(Gods.get(record.god()).displayName(), record.currentDemand()).getString();
            helper.assertTrue(status.startsWith("The Pale Stag: 2000/2000 mB ") && status.contains(" · 0/1 "),
                    "the readout shows fluids in mB: " + status);
        } finally {
            data.disbandEmpire(record.id(), null);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void theCarrionCrowIsFoundedWithRottenFlesh(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            placeLitBonfire(helper, SPOT);
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ROTTEN_FLESH, 5));
            interact(helper.getLevel(), helper.absolutePos(SPOT), player);
            EmpireRecord record = data.getEmpireOf(player.getUUID());
            try {
                helper.assertTrue(record != null && Empire.id("carrion_crow").equals(record.god()),
                        "rotten flesh founds under the Carrion Crow, god is " + (record == null ? null : record.god()));
                helper.assertTrue(player.getMainHandItem().isEmpty(), "5 rotten flesh taken");
            } finally {
                if (record != null) {
                    data.disbandEmpire(record.id(), null);
                }
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void anEmpireFromBeforeGodsAdoptsOne(GameTestHelper helper) {
        clearArea(helper, SPOT);
        placeLitBonfire(helper, SPOT);
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = helper.absolutePos(SPOT);
        ChunkPos chunk = new ChunkPos(pos);
        EmpireRecord created = data.createEmpire(player.getUUID(), ChunkKeys.pack(chunk.x, chunk.z), pos, helper.getLevel().getGameTime());
        try {
            // A record saved before gods existed has no god, count or demand
            CompoundTag legacy = created.save();
            legacy.remove("god");
            legacy.remove("sacrifice_count");
            legacy.remove("demand");
            EmpireRecord old = EmpireRecord.load(legacy);
            helper.assertTrue(old.god() == null && old.sacrificeCount() == 0 && old.currentDemand().isEmpty(),
                    "a legacy record loads godless with nothing demanded");
            helper.assertTrue(created.god() == null, "createEmpire alone makes a godless empire");

            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT, 10));
            interact(helper.getLevel(), pos, player);
            helper.assertTrue(player.getMainHandItem().getCount() == 10, "a godless empire has nothing to pay");

            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.MUTTON, 4));
            interact(helper.getLevel(), pos, player);
            helper.assertTrue(created.god() == null && player.getMainHandItem().getCount() == 4,
                    "4 mutton do not adopt a god");

            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.MUTTON, 6));
            interact(helper.getLevel(), pos, player);
            helper.assertTrue(Empire.id("sunblessed_ram").equals(created.god()), "mutton adopts the Sunblessed Ram, god is "
                    + created.god());
            helper.assertTrue(player.getMainHandItem().getCount() == 1, "exactly 5 mutton taken");
            helper.assertTrue(created.state().dominion() == 10, "adoption leaves dominion as it was");
            helper.assertTrue(created.currentDemand().equals(DemandRoller.roll(created.id().getMostSignificantBits(),
                    created.id().getLeastSignificantBits(), 0, 10, Gods.get(created.god()).tiers())),
                    "adoption rolls a demand: " + created.currentDemand());
        } finally {
            data.disbandEmpire(created.id(), null);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void paymentsAreRefusedFromNonFoundersUnlitAndNotMainBonfires(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            MinecraftServer server = helper.getLevel().getServer();
            EmpireSavedData data = EmpireSavedData.of(server);
            Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
            EmpireRecord record = foundWithBeef(helper, founder);
            BlockPos mainPos = helper.absolutePos(SPOT);
            BlockPos secondaryPos = mainPos.offset(5, 0, 0);
            try {
                DemandLine tier2 = record.currentDemand().get(1);
                ItemStack payment = offeringFor(tier2, 3);

                Player stranger = helper.makeMockPlayer(GameType.SURVIVAL);
                stranger.setItemInHand(InteractionHand.MAIN_HAND, payment.copy());
                interact(helper.getLevel(), mainPos, stranger);
                helper.assertTrue(stranger.getMainHandItem().getCount() == 3, "a stranger's payment is not taken");

                helper.setBlock(SPOT, EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, false));
                founder.setItemInHand(InteractionHand.MAIN_HAND, payment.copy());
                interact(helper.getLevel(), mainPos, founder);
                helper.assertTrue(founder.getMainHandItem().getCount() == 3, "an unlit bonfire takes nothing");
                helper.setBlock(SPOT, litBonfire());

                helper.getLevel().setBlock(secondaryPos, litBonfire(), 3);
                data.onBonfirePlaced(secondaryPos, server);
                interact(helper.getLevel(), secondaryPos, founder);
                helper.assertTrue(founder.getMainHandItem().getCount() == 3, "a bonfire that is not the main one takes nothing");

                helper.assertTrue(record.currentDemand().stream().allMatch(l -> l.paid() == 0), "nothing was paid: "
                        + record.currentDemand());

                interact(helper.getLevel(), mainPos, founder);
                helper.assertTrue(founder.getMainHandItem().isEmpty() && record.currentDemand().get(1).paid() == 3,
                        "the founder pays at the lit main bonfire: " + record.currentDemand());
            } finally {
                data.onBonfireRemoved(secondaryPos, server);
                helper.getLevel().setBlock(secondaryPos, Blocks.AIR.defaultBlockState(), 3);
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void anEmptyHandReadsTheDemand(GameTestHelper helper) {
        withTierChecksPassing(() -> {
            EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());
            Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
            Player stranger = helper.makeMockPlayer(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(SPOT);
            placeLitBonfire(helper, SPOT);
            helper.assertTrue(useEmpty(helper.getLevel(), pos, stranger) == InteractionResult.CONSUME,
                    "an empty hand at an unclaimed bonfire is answered");
            EmpireRecord record = foundWithBeef(helper, founder);
            try {
                DemandLine beef = record.currentDemand().get(0);
                String status = Demands.status(Gods.get(record.god()).displayName(), record.currentDemand()).getString();
                helper.assertTrue(status.startsWith("The Iron Aurochs: 0/" + beef.required() + " Raw Beef · 0/"),
                        "readout: " + status);
                helper.assertTrue(useEmpty(helper.getLevel(), pos, stranger) == InteractionResult.CONSUME,
                        "anyone may read the demand");
                stranger.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                helper.assertTrue(useEmpty(helper.getLevel(), pos, stranger) == InteractionResult.PASS,
                        "a full hand does not read the demand");
            } finally {
                data.disbandEmpire(record.id(), null);
            }
        });
        helper.succeed();
    }

    // ---- helpers (shared with SacrificeGameTests) ----

    /** Places a lit bonfire with its parts at the test's spot and founds an empire there with 5 beef. */
    static EmpireRecord foundWithBeef(GameTestHelper helper, Player player) {
        placeLitBonfire(helper, SPOT);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEEF, 5));
        interact(helper.getLevel(), helper.absolutePos(SPOT), player);
        EmpireRecord record = EmpireSavedData.of(helper.getLevel().getServer()).getEmpireOf(player.getUUID());
        helper.assertTrue(record != null && IRON_AUROCHS.equals(record.god()), "5 beef found an empire under the Iron Aurochs");
        return record;
    }

    /** Pays every line of the empire's current demand at {@code pos}, as it stands now. */
    static void payInFull(GameTestHelper helper, Level level, BlockPos pos, Player player, EmpireRecord record) {
        List<DemandLine> demand = record.currentDemand();
        int sacrifices = record.sacrificeCount();
        for (int i = 0; i < demand.size(); i++) {
            for (int guard = 0; guard < 100 && record.sacrificeCount() == sacrifices
                    && !record.currentDemand().get(i).isPaid(); guard++) {
                DemandLine line = record.currentDemand().get(i);
                ItemStack offering = offeringFor(line, line.remaining());
                player.setItemInHand(InteractionHand.MAIN_HAND, offering);
                interact(level, pos, player);
            }
        }
        helper.assertTrue(record.sacrificeCount() == sacrifices + 1, "the whole demand was paid: " + demand);
    }

    /** Something that pays {@code count} of a line (one bucket for a fluid line). */
    static ItemStack offeringFor(DemandLine line, int count) {
        Item item = switch (line.kind()) {
            case ITEM, ITEMS -> item(line.ids().get(0));
            case TAG -> BuiltInRegistries.ITEM.getTag(Offerings.tag(line.ids().get(0))).orElseThrow().get(0).value();
            case FLUID -> Offerings.fluid(line.ids().get(0)).getBucket();
        };
        ItemStack stack = new ItemStack(item);
        stack.setCount(Math.max(1, Math.min(count, stack.getMaxStackSize())));
        return stack;
    }

    static Item item(String id) {
        return Offerings.item(id).orElseThrow(() -> new IllegalStateException("no item " + id));
    }

    static void interact(Level level, BlockPos pos, Player player) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        level.getBlockState(pos).useItemOn(player.getItemInHand(InteractionHand.MAIN_HAND), level, player, InteractionHand.MAIN_HAND, hit);
    }

    static InteractionResult useEmpty(Level level, BlockPos pos, Player player) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        return level.getBlockState(pos).useWithoutItem(level, player, hit);
    }

    static BlockState litBonfire() {
        return EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, true);
    }

    static void placeLitBonfire(GameTestHelper helper, BlockPos spot) {
        clearArea(helper, spot);
        helper.setBlock(spot, litBonfire());
        BonfireItem.placeParts(helper.getLevel(), helper.absolutePos(spot));
    }

    static void clearArea(GameTestHelper helper, BlockPos center) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    helper.setBlock(center.offset(dx, dy, dz), Blocks.AIR);
                }
            }
        }
    }

    /** Runs {@code body} with every column Settled, then puts the real tier check back. */
    static void withTierChecksPassing(Runnable body) {
        TierChecks.install(() -> TierCheck.ALWAYS_PASS);
        try {
            body.run();
        } finally {
            if (ModList.get().isLoaded("frontier")) {
                FrontierCompat.registerIfLoaded();
            } else {
                TierChecks.reset();
            }
        }
    }

    private static void disband(EmpireSavedData data, Player... players) {
        for (Player player : players) {
            EmpireRecord record = data.getEmpireOf(player.getUUID());
            if (record != null) {
                data.disbandEmpire(record.id(), null);
            }
        }
    }
}
