// Written by Gemini.
package dev.brights0ng.empire.ledger;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.region.HomeRegionScanner;
import dev.brights0ng.empire.region.RegionManager;
import dev.brights0ng.empire.region.RegionNetwork;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.RegionInvalidReason;
import dev.brights0ng.empire.rules.RegionOrigin;
import dev.brights0ng.empire.rules.RegionRules;
import dev.brights0ng.empire.rules.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Right-click interactions for the {@link AdministrativeLedgerItem}.
 *
 * <p>Right-clicking any bed (head or foot) while holding the Administrative Ledger cancels vanilla bed interaction
 * and scans/registers an {@link RegionOrigin#AUTO} {@link RegionType#HOME} region seeded at the bed's head. If the bed
 * already lies inside a {@code HOME} of the player's empire, the existing region is immediately re-validated and its
 * current status is reported on the action bar.
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class LedgerInteractions {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getUseItem() == TriState.FALSE) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof AdministrativeLedgerItem)) {
            return;
        }

        Level level = event.getLevel();
        BlockPos clickedPos = event.getPos();
        BlockState state = level.getBlockState(clickedPos);
        if (!state.is(BlockTags.BEDS)) {
            return;
        }

        // Intercept before BedBlock.useWithoutItem so right-clicking never sleeps, sets spawn, or explodes the bed.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));

        if (level.isClientSide()) {
            return;
        }

        Player player = event.getEntity();
        ServerLevel serverLevel = (ServerLevel) level;
        if (!serverLevel.dimension().equals(Level.OVERWORLD)) {
            player.displayClientMessage(Component.translatable("message.empire.not_overworld"), true);
            return;
        }

        EmpireSavedData data = EmpireSavedData.of(serverLevel);
        EmpireRecord empire = data.getEmpireOf(player.getUUID());
        if (empire == null) {
            player.displayClientMessage(Component.translatable("message.empire.no_empire"), true);
            return;
        }

        BlockPos bedHead = resolveBedHead(serverLevel, clickedPos, state);
        long chunk = ChunkKeys.pack(bedHead.getX() >> 4, bedHead.getZ() >> 4);
        if (!empire.state().staked().contains(chunk)) {
            fail(player, RegionInvalidReason.UNSTAKED);
            return;
        }

        RegionRecord existing = data.regionAt(bedHead);
        if (existing != null) {
            if (!existing.empireId().equals(empire.id()) || existing.type() != RegionType.HOME) {
                fail(player, RegionInvalidReason.OVERLAPS_EXISTING);
                return;
            }
            boolean wasValid = existing.valid();
            RegionManager.updateRegion(serverLevel, data, existing, serverLevel.getGameTime());
            if (data.region(existing.id()) != null) {
                if (existing.valid()) {
                    if (!wasValid) {
                        playPageTurn(serverLevel, bedHead, 1.1F);
                        player.displayClientMessage(homeSummaryMessage("restored", existing), true);
                    } else if (existing.isOutdoorBlock(bedHead)) {
                        player.displayClientMessage(
                                Component.translatable("message.empire.ledger.home.bed_outdoor"), true);
                    } else {
                        playPageTurn(serverLevel, bedHead, 1.0F);
                        player.displayClientMessage(homeStatusMessage(existing), true);
                    }
                } else {
                    player.displayClientMessage(Component.translatable(
                            "message.empire.ledger.home.invalid", reasonComponent(existing.invalidReason())), true);
                }
                return;
            }
        }

        if (HomeRegionScanner.isExposedToSky(serverLevel, bedHead.getX(), bedHead.getY(), bedHead.getZ())) {
            fail(player, RegionInvalidReason.EXPOSED_TO_SKY);
            return;
        }

        HomeRegionScanner.ScanResult scan = HomeRegionScanner.scan(
                serverLevel, data, empire, bedHead, RegionOrigin.AUTO);
        if (!scan.succeeded()) {
            fail(player, scan.failure());
            return;
        }

        RegionRecord created = scan.region();
        if (!created.isIndoorBlock(bedHead)) {
            fail(player, RegionInvalidReason.EXPOSED_TO_SKY);
            return;
        }

        data.addRegion(created);
        RegionManager.clearFailedBedsNear(bedHead);
        RegionNetwork.pushToEmpire(serverLevel, empire.id());
        playPageTurn(serverLevel, bedHead, 1.1F);
        player.displayClientMessage(homeSummaryMessage("created", created), true);
    }

    /** Normalizes a clicked bed block (head or foot) to its {@link BedPart#HEAD} position when present. */
    public static BlockPos resolveBedHead(Level level, BlockPos pos, BlockState state) {
        if (state.hasProperty(BedBlock.PART)
                && state.getValue(BedBlock.PART) == BedPart.FOOT
                && state.hasProperty(BedBlock.FACING)) {
            BlockPos head = pos.relative(state.getValue(BedBlock.FACING));
            if (level.getBlockState(head).is(BlockTags.BEDS)) {
                return head;
            }
        }
        return pos;
    }

    private static void fail(Player player, RegionInvalidReason reason) {
        player.displayClientMessage(
                Component.translatable("message.empire.ledger.home.failed", reasonComponent(reason)), true);
    }

    private static Component reasonComponent(RegionInvalidReason reason) {
        return Component.translatable("message.empire.region.reason." + reason.id());
    }

    private static Component homeStatusMessage(RegionRecord region) {
        int inhabitants = region.inhabitants().size();
        int capacity = RegionRules.homeCapacity(region.effectiveVolume());
        int indoorRooms = indoorRoomCount(region);
        if (indoorRooms == 1) {
            return Component.translatable("message.empire.ledger.home.status_one", inhabitants, capacity);
        }
        return Component.translatable("message.empire.ledger.home.status", inhabitants, capacity, indoorRooms);
    }

    private static Component homeSummaryMessage(String verb, RegionRecord region) {
        int capacity = RegionRules.homeCapacity(region.effectiveVolume());
        int indoorRooms = indoorRoomCount(region);
        if (indoorRooms == 1) {
            return Component.translatable("message.empire.ledger.home." + verb + "_one", capacity);
        }
        return Component.translatable("message.empire.ledger.home." + verb, capacity, indoorRooms);
    }

    private static int indoorRoomCount(RegionRecord region) {
        int count = 0;
        for (RegionRecord.RoomRecord room : region.rooms()) {
            if (!room.isOutdoor()) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private static void playPageTurn(ServerLevel level, BlockPos pos, float pitch) {
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0F, pitch);
    }

    private LedgerInteractions() {
    }
}
