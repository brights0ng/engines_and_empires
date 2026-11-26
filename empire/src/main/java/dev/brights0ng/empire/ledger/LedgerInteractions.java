// Written by Gemini.
package dev.brights0ng.empire.ledger;

import javax.annotation.Nullable;

import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianOccupations;
import dev.brights0ng.empire.civilian.ai.FarmCrops;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.region.FarmRegionScanner;
import dev.brights0ng.empire.region.HomeRegionScanner;
import dev.brights0ng.empire.region.RegionClientCache;
import dev.brights0ng.empire.region.RegionManager;
import dev.brights0ng.empire.region.RegionNetwork;
import dev.brights0ng.empire.region.RegionPayloads;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.CivilianLines;
import dev.brights0ng.empire.rules.Occupation;
import dev.brights0ng.empire.rules.RegionInvalidReason;
import dev.brights0ng.empire.rules.RegionOrigin;
import dev.brights0ng.empire.rules.RegionRules;
import dev.brights0ng.empire.rules.RegionType;
import dev.brights0ng.empire.rules.RoomLabel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Right-click and left-click interactions for the {@link AdministrativeLedgerItem}.
 * <ul>
 *   <li>Right-clicking a region's home block (bed for {@link RegionType#HOME}, composter for {@link RegionType#FARM})
 *       creates or selects that region.</li>
 *   <li>While a {@code FARM} is selected, right-clicking a shed-whitelisted block (composter, chest, trapped chest,
 *       barrel, Create item vault) or field-whitelisted block (farmland, crop) flood-fills and adds/merges a room.</li>
 *   <li>Right-clicking a selected region's own room rescans and displays its status; shift-right-clicking removes that
 *       room (deleting the region if no rooms remain).</li>
 *   <li>Clicking another region's non-home block reports an error while a region is selected; clicking another region's
 *       home block switches selection to that region.</li>
 *   <li>Left-clicking (block or air) while holding the ledger, or unequipping the ledger, exits selection.</li>
 *   <li>Non-home blocks outside regions (or without a region selected) interact normally.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class LedgerInteractions {

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        if (!LedgerSelection.isHoldingLedger(player)) {
            return;
        }
        Level level = event.getLevel();
        if (level.isClientSide()) {
            if (RegionClientCache.selectedRegionId() != null) {
                event.setCanceled(true);
                RegionClientCache.clearSelectionLocally();
                PacketDistributor.sendToServer(RegionPayloads.Deselect.INSTANCE);
            }
            return;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        if (serverLevel.dimension().equals(Level.OVERWORLD) && LedgerSelection.clear(serverLevel, player)) {
            event.setCanceled(true);
            playPageTurn(serverLevel, player.blockPosition(), 0.85F);
            player.displayClientMessage(Component.translatable("message.empire.ledger.unselected"), true);
        }
    }

    /**
     * Right-clicking a citizen while holding the Administrative Ledger with a workplace region selected assigns them
     * to that region's occupation; shift-right-clicking unemploys them if their workplace region is selected (and
     * suppresses the debug stats dump whenever the player holds a ledger).
     */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!(event.getTarget() instanceof CivilianEntity civilian) || !civilian.isAlive()) {
            return;
        }
        Player player = event.getEntity();
        if (!LedgerSelection.isHoldingLedger(player)) {
            return;
        }

        Level level = event.getLevel();
        boolean shiftDown = player.isShiftKeyDown();
        if (level.isClientSide()) {
            if (shiftDown || RegionClientCache.selectedRegionId() != null) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        if (!serverLevel.dimension().equals(Level.OVERWORLD)) {
            if (shiftDown) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                player.displayClientMessage(Component.translatable("message.empire.not_overworld"), true);
            }
            return;
        }

        EmpireSavedData data = EmpireSavedData.of(serverLevel);
        EmpireRecord empire = data.getEmpireOf(player.getUUID());
        RegionRecord selected = empire != null
                ? LedgerSelection.selectedRegion(serverLevel, player, data, empire)
                : null;

        if (shiftDown) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (empire == null) {
                player.displayClientMessage(Component.translatable("message.empire.no_empire"), true);
                return;
            }
            handleUnemployCivilian(serverLevel, data, empire, player, selected, civilian);
            return;
        }

        if (selected == null) {
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        handleAssignCivilian(serverLevel, data, player, selected, civilian);
    }

    private static void handleAssignCivilian(ServerLevel level, EmpireSavedData data, Player player,
                                             RegionRecord selected, CivilianEntity civilian) {
        CivilianOccupations.AssignResult result = CivilianOccupations.assign(level, data, civilian, selected);
        String name = civilian.getName().getString();
        int workers = CivilianOccupations.activeWorkerCount(data, selected);
        int cap = RegionRules.workerCapacity(selected.type());
        switch (result.outcome()) {
            case OK -> {
                playPageTurn(level, civilian.blockPosition(), 1.1F);
                civilian.say(CivilianLines.Category.HIRED);
                Component occName = Component.translatable(result.occupation().langKey());
                player.displayClientMessage(Component.translatable(
                        "message.empire.ledger.worker.assigned", name, occName, workers, cap), true);
            }
            case ALREADY_ASSIGNED_HERE -> player.displayClientMessage(
                    Component.translatable("message.empire.ledger.worker.already_here", name), true);
            case AT_CAPACITY -> player.displayClientMessage(
                    Component.translatable("message.empire.ledger.worker.full", workers, cap), true);
            case REGION_INVALID -> player.displayClientMessage(
                    Component.translatable("message.empire.ledger.worker.region_invalid"), true);
            case UNSUPPORTED_REGION -> player.displayClientMessage(
                    Component.translatable("message.empire.ledger.worker.unsupported"), true);
            case NOT_CITIZEN, WRONG_EMPIRE -> player.displayClientMessage(
                    Component.translatable("message.empire.ledger.worker.not_citizen"), true);
        }
    }

    private static void handleUnemployCivilian(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                               Player player, @Nullable RegionRecord selected,
                                               CivilianEntity civilian) {
        var record = civilian.ownRecord(data);
        if (civilian.status() != CivilianEntity.Status.CITIZEN || record == null
                || !record.empire().equals(empire.id())) {
            player.displayClientMessage(Component.translatable("message.empire.ledger.worker.not_citizen"), true);
            return;
        }
        String name = civilian.getName().getString();
        Occupation currentOcc = CivilianOccupations.occupationOf(civilian);
        UUID workplaceId = CivilianOccupations.workplaceIdOf(civilian);
        if (!currentOcc.isEmployed() || workplaceId == null) {
            player.displayClientMessage(
                    Component.translatable("message.empire.ledger.worker.not_employed", name), true);
            return;
        }
        if (selected == null || !selected.id().equals(workplaceId)) {
            player.displayClientMessage(
                    Component.translatable("message.empire.ledger.worker.select_workplace", name), true);
            return;
        }
        if (CivilianOccupations.unassign(level, data, civilian)) {
            playPageTurn(level, civilian.blockPosition(), 0.85F);
            civilian.say(CivilianLines.Category.UNEMPLOYED);
            int workers = CivilianOccupations.activeWorkerCount(data, selected);
            int cap = RegionRules.workerCapacity(selected.type());
            Component occName = Component.translatable(currentOcc.langKey());
            player.displayClientMessage(Component.translatable(
                    "message.empire.ledger.worker.unassigned", name, occName, workers, cap), true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getUseItem() == TriState.FALSE) {
            return;
        }
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        boolean ledgerInActiveHand = stack.getItem() instanceof AdministrativeLedgerItem;
        boolean ledgerInOffhand = event.getHand() == InteractionHand.MAIN_HAND
                && !ledgerInActiveHand
                && player.getOffhandItem().getItem() instanceof AdministrativeLedgerItem;

        // Offhand Ledger + Selected FARM + FIELD click: set crop override (seed/produce in main hand) or clear (empty hand).
        if (ledgerInOffhand) {
            handleOffhandLedgerFieldCrop(event, player, stack);
            return;
        }
        if (!ledgerInActiveHand) {
            return;
        }

        Level level = event.getLevel();
        BlockPos clickedPos = event.getPos();
        BlockState state = level.getBlockState(clickedPos);
        boolean isBed = state.is(BlockTags.BEDS);
        boolean isComposter = FarmRegionScanner.isComposter(state);
        boolean isHomeBlock = isBed || isComposter;
        RoomLabel farmBlockLabel = FarmRegionScanner.labelForClickedBlock(state);

        if (level.isClientSide()) {
            if (shouldCancelClientClick(clickedPos, event.getFace(), event.getEntity().isShiftKeyDown(),
                    isHomeBlock, farmBlockLabel)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        if (!serverLevel.dimension().equals(Level.OVERWORLD)) {
            if (isHomeBlock) {
                cancelServer(event);
                player.displayClientMessage(Component.translatable("message.empire.not_overworld"), true);
            }
            return;
        }

        EmpireSavedData data = EmpireSavedData.of(serverLevel);
        EmpireRecord empire = data.getEmpireOf(player.getUUID());
        if (empire == null) {
            if (isHomeBlock) {
                cancelServer(event);
                player.displayClientMessage(Component.translatable("message.empire.no_empire"), true);
            }
            return;
        }

        RegionRecord selected = LedgerSelection.selectedRegion(serverLevel, player, data, empire);
        BlockPos targetPos = isBed ? resolveBedHead(serverLevel, clickedPos, state) : clickedPos;
        RegionRecord existing = data.regionAt(targetPos);

        // If a player just placed a composter next to an existing farm and clicked it before the 1-tick deferred
        // auto-reshape ran, flush adjacent dirty farms so the composter attaches to and selects that farm.
        if (existing == null && isComposter) {
            flushAdjacentDirtyFarms(serverLevel, data, empire, targetPos);
            existing = data.regionAt(targetPos);
        }

        // For air-interior regions (such as HOME), clicking the floor or inner wall hits the solid shell block outside
        // region.blocks() while clickedPos.relative(face) is inside the room. Support that face-adjacent lookup when
        // shift-removing a room from the selected region or selecting an invalidated region.
        if (existing == null && !isHomeBlock && event.getFace() != null
                && !(selected != null && selected.type() == RegionType.FARM && farmBlockLabel != null)) {
            BlockPos facePos = clickedPos.relative(event.getFace());
            RegionRecord faceRegion = data.regionAt(facePos);
            if (faceRegion != null && faceRegion.empireId().equals(empire.id())) {
                boolean shiftRemovingSelected = player.isShiftKeyDown()
                        && selected != null && faceRegion.id().equals(selected.id());
                if (shiftRemovingSelected || !faceRegion.valid()) {
                    targetPos = facePos;
                    existing = faceRegion;
                }
            }
        }

        // Case 1: clicked block belongs to an existing region.
        if (existing != null) {
            // 1a. Clicked inside the currently selected region.
            if (selected != null && existing.id().equals(selected.id())) {
                cancelServer(event);
                if (player.isShiftKeyDown()) {
                    if (selected.type() == RegionType.FARM) {
                        handleRemoveFarmRoom(serverLevel, data, empire, player, selected, targetPos);
                    } else if (selected.type() == RegionType.HOME) {
                        handleRemoveHomeRoom(serverLevel, data, empire, player, selected, targetPos);
                    }
                    return;
                }
                handleSelectedRegionClick(serverLevel, data, player, selected, targetPos);
                return;
            }

            // 1b. Clicked inside another empire's region.
            if (!existing.empireId().equals(empire.id())) {
                if (selected != null || isHomeBlock) {
                    cancelServer(event);
                    player.displayClientMessage(Component.translatable("message.empire.ledger.other_region"), true);
                }
                return;
            }

            // 1c. Clicked inside another region of the player's empire.
            if (!existing.valid() || isHomeBlockFor(existing.type(), isBed, isComposter)) {
                cancelServer(event);
                handleSelectExistingRegion(serverLevel, data, player, existing, targetPos);
                return;
            }

            // Non-home block of another region: report error when a region is selected (or when clicking a mismatched
            // home block type like a bed inside a farm); otherwise let vanilla interaction run normally.
            if (selected != null || isHomeBlock) {
                cancelServer(event);
                player.displayClientMessage(Component.translatable("message.empire.ledger.other_region"), true);
            }
            return;
        }

        // Case 2: clicked block is NOT in any existing region.
        // 2a. Selected FARM + shed/field whitelisted block -> add or merge a room into the selected farm.
        if (selected != null && selected.type() == RegionType.FARM && farmBlockLabel != null) {
            cancelServer(event);
            handleAddFarmRoom(serverLevel, data, empire, player, selected, targetPos);
            return;
        }

        // 2b. Unclaimed bed -> scan and register a new AUTO HOME (and select it).
        if (isBed) {
            cancelServer(event);
            handleCreateHome(serverLevel, data, empire, player, targetPos);
            return;
        }

        // 2c. Unclaimed composter (with no farm selected) -> scan and register a new AUTO FARM (and select it).
        if (isComposter) {
            cancelServer(event);
            handleCreateFarm(serverLevel, data, empire, player, targetPos);
        }
        // 2d. Non-home block outside any region (and not a farm room addition): interact normally.
    }

    private static boolean shouldCancelClientClick(BlockPos clickedPos, Direction face, boolean shiftDown,
                                                   boolean isHomeBlock, RoomLabel farmBlockLabel) {
        if (isHomeBlock) {
            return true;
        }
        RegionPayloads.RegionSnapshot clickedSnap = RegionClientCache.regionAt(clickedPos);
        if (clickedSnap != null && !clickedSnap.valid()) {
            return true;
        }
        RegionPayloads.RegionSnapshot selected = RegionClientCache.selectedRegion();
        boolean addingFarmRoom = selected != null && selected.type() == RegionType.FARM && farmBlockLabel != null;
        if (clickedSnap == null && face != null && !addingFarmRoom) {
            RegionPayloads.RegionSnapshot faceSnap = RegionClientCache.regionAt(clickedPos.relative(face));
            if (faceSnap != null) {
                if (!faceSnap.valid()) {
                    return true;
                }
                if (shiftDown && faceSnap.id().equals(RegionClientCache.selectedRegionId())) {
                    return true;
                }
            }
        }
        if (RegionClientCache.selectedRegionId() == null) {
            return false;
        }
        if (clickedSnap != null) {
            return true;
        }
        return addingFarmRoom;
    }

    private static void cancelServer(PlayerInteractEvent.RightClickBlock event) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static boolean isHomeBlockFor(RegionType type, boolean isBed, boolean isComposter) {
        return (type == RegionType.HOME && isBed) || (type == RegionType.FARM && isComposter);
    }

    private static void flushAdjacentDirtyFarms(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                                BlockPos pos) {
        long now = level.getGameTime();
        for (RegionRecord region : java.util.List.copyOf(data.regionsOf(empire.id()))) {
            if (region.type() == RegionType.FARM
                    && region.isDirtyFlag()
                    && region.isWithinInflatedBounds(pos, 1)) {
                RegionManager.updateRegion(level, data, region, now);
            }
        }
    }

    private static void handleSelectExistingRegion(ServerLevel level, EmpireSavedData data, Player player,
                                                   RegionRecord existing, BlockPos pos) {
        boolean wasValid = existing.valid();
        RegionManager.updateRegion(level, data, existing, level.getGameTime());
        if (data.region(existing.id()) == null) {
            return;
        }
        LedgerSelection.select(level, player, existing.id());
        if (existing.type() == RegionType.HOME) {
            reportHomeStatus(level, player, existing, pos, wasValid);
        } else if (existing.type() == RegionType.FARM) {
            reportFarmSelection(level, data, player, existing, pos, wasValid);
        }
    }

    private static void handleOffhandLedgerFieldCrop(PlayerInteractEvent.RightClickBlock event, Player player,
                                                     ItemStack mainHand) {
        if (player.isShiftKeyDown()) {
            return;
        }
        boolean isEmptyHand = mainHand.isEmpty();
        Item canonicalSeed = isEmptyHand ? null : FarmCrops.canonicalSeedForItem(mainHand.getItem());
        if (!isEmptyHand && canonicalSeed == null) {
            return;
        }
        Level level = event.getLevel();
        BlockPos clickedPos = event.getPos();
        if (level.isClientSide()) {
            RegionPayloads.RegionSnapshot selected = RegionClientCache.selectedRegion();
            RegionPayloads.RegionSnapshot clickedSnap = RegionClientCache.regionAt(clickedPos);
            if (selected != null && selected.type() == RegionType.FARM
                    && clickedSnap != null && clickedSnap.id().equals(selected.id())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        if (!serverLevel.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        EmpireSavedData data = EmpireSavedData.of(serverLevel);
        EmpireRecord empire = data.getEmpireOf(player.getUUID());
        if (empire == null) {
            return;
        }
        RegionRecord selected = LedgerSelection.selectedRegion(serverLevel, player, data, empire);
        if (selected == null || selected.type() != RegionType.FARM) {
            return;
        }
        RegionRecord.RoomRecord room = selected.roomAt(clickedPos);
        if (room == null || !room.isField()) {
            return;
        }
        cancelServer(event);
        if (isEmptyHand) {
            selected.setRoomPreferredCrop(room.index(), null);
            data.setDirty();
            playPageTurn(serverLevel, clickedPos, 0.9F);
            player.displayClientMessage(Component.translatable("message.empire.ledger.farm.field_crop_cleared"), true);
        } else {
            String seedId = FarmCrops.itemId(canonicalSeed);
            selected.setRoomPreferredCrop(room.index(), seedId);
            data.setDirty();
            playPageTurn(serverLevel, clickedPos, 1.1F);
            player.displayClientMessage(Component.translatable(
                    "message.empire.ledger.farm.field_crop_set", canonicalSeed.getDescription()), true);
        }
    }

    private static void handleSelectedRegionClick(ServerLevel level, EmpireSavedData data, Player player,
                                                  RegionRecord selected, BlockPos pos) {
        boolean wasValid = selected.valid();
        // Explicit ledger click on a selected farm allows field rooms to shrink to match remaining live farmland/crops.
        RegionManager.updateRegion(level, data, selected, level.getGameTime(), selected.type() == RegionType.FARM);
        if (data.region(selected.id()) == null) {
            LedgerSelection.clearForRegion(level, selected.id());
            return;
        }
        if (selected.type() == RegionType.HOME) {
            reportHomeStatus(level, player, selected, pos, wasValid);
        } else if (selected.type() == RegionType.FARM) {
            reportFarmRoomStatus(level, data, player, selected, pos, wasValid);
        }
    }

    private static void handleCreateHome(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                         Player player, BlockPos bedHead) {
        long chunk = ChunkKeys.pack(bedHead.getX() >> 4, bedHead.getZ() >> 4);
        if (!empire.state().staked().contains(chunk)) {
            failHome(player, RegionInvalidReason.UNSTAKED);
            return;
        }
        if (HomeRegionScanner.isExposedToSky(level, bedHead.getX(), bedHead.getY(), bedHead.getZ())) {
            failHome(player, RegionInvalidReason.EXPOSED_TO_SKY);
            return;
        }

        HomeRegionScanner.ScanResult scan = HomeRegionScanner.scan(
                level, data, empire, bedHead, RegionOrigin.AUTO);
        if (!scan.succeeded()) {
            failHome(player, scan.failure());
            return;
        }

        RegionRecord created = scan.region();
        if (!created.isIndoorBlock(bedHead)) {
            failHome(player, RegionInvalidReason.EXPOSED_TO_SKY);
            return;
        }

        data.addRegion(created);
        RegionManager.clearFailedBedsNear(bedHead);
        LedgerSelection.select(level, player, created.id());
        RegionNetwork.pushToEmpire(level, empire.id());
        playPageTurn(level, bedHead, 1.1F);
        player.displayClientMessage(homeSummaryMessage("created", created), true);
    }

    private static void handleCreateFarm(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                         Player player, BlockPos composterPos) {
        long chunk = ChunkKeys.pack(composterPos.getX() >> 4, composterPos.getZ() >> 4);
        if (!empire.state().staked().contains(chunk)) {
            failFarm(player, RegionInvalidReason.UNSTAKED);
            return;
        }

        FarmRegionScanner.ScanResult scan = FarmRegionScanner.scanNewFarm(
                level, data, empire, composterPos, RegionOrigin.AUTO);
        if (!scan.succeeded()) {
            failFarm(player, scan.failure());
            return;
        }

        RegionRecord created = scan.region();
        data.addRegion(created);
        LedgerSelection.select(level, player, created.id());
        RegionNetwork.pushToEmpire(level, empire.id());
        playPageTurn(level, composterPos, 1.1F);
        player.displayClientMessage(Component.translatable(
                "message.empire.ledger.farm.created",
                created.blockCount(),
                created.storageBlocks().size(),
                CivilianOccupations.activeWorkerCount(data, created),
                RegionRules.workerCapacity(RegionType.FARM)), true);
    }

    private static void handleAddFarmRoom(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                          Player player, RegionRecord farm, BlockPos pos) {
        FarmRegionScanner.AddRoomResult result = FarmRegionScanner.addRoom(level, data, empire, farm, pos);
        if (!result.succeeded()) {
            player.displayClientMessage(Component.translatable(
                    "message.empire.ledger.farm.room_failed", reasonComponent(result.failure())), true);
            return;
        }

        RegionNetwork.pushToEmpire(level, empire.id());
        playPageTurn(level, pos, 1.1F);
        RegionRecord.RoomRecord room = result.room();
        if (room == null) {
            return;
        }
        String action = result.merged() ? "merged" : "added";
        if (room.isShed()) {
            player.displayClientMessage(Component.translatable(
                    "message.empire.ledger.farm.shed_" + action,
                    room.blockCount(),
                    room.storageCount()), true);
        } else {
            player.displayClientMessage(Component.translatable(
                    "message.empire.ledger.farm.field_" + action,
                    room.blockCount()), true);
        }
    }

    private static void handleRemoveFarmRoom(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                             Player player, RegionRecord farm, BlockPos pos) {
        FarmRegionScanner.RemoveRoomResult result = FarmRegionScanner.removeRoomAt(
                level, data, farm, pos, level.getGameTime());
        if (result.outcome() == FarmRegionScanner.RemoveOutcome.NONE) {
            return;
        }
        if (result.outcome() == FarmRegionScanner.RemoveOutcome.DELETED_REGION) {
            LedgerSelection.clearForRegion(level, farm.id());
            RegionNetwork.pushToEmpire(level, empire.id());
            playPageTurn(level, pos, 0.85F);
            player.displayClientMessage(Component.translatable("message.empire.ledger.farm.deleted"), true);
            return;
        }
        RegionNetwork.pushToEmpire(level, empire.id());
        playPageTurn(level, pos, 0.9F);
        String kindKey = result.removedLabel() == RoomLabel.SHED
                ? "message.empire.ledger.farm.room_removed_shed"
                : "message.empire.ledger.farm.room_removed_field";
        player.displayClientMessage(Component.translatable(kindKey, farm.rooms().size()), true);
    }

    private static void handleRemoveHomeRoom(ServerLevel level, EmpireSavedData data, EmpireRecord empire,
                                             Player player, RegionRecord home, BlockPos pos) {
        HomeRegionScanner.RemoveOutcome outcome = HomeRegionScanner.removeRoomAt(
                level, data, home, pos, level.getGameTime());
        if (outcome == HomeRegionScanner.RemoveOutcome.NONE) {
            return;
        }
        if (outcome == HomeRegionScanner.RemoveOutcome.DELETED_REGION) {
            LedgerSelection.clearForRegion(level, home.id());
            RegionNetwork.pushToEmpire(level, empire.id());
            playPageTurn(level, pos, 0.85F);
            player.displayClientMessage(Component.translatable("message.empire.ledger.home.deleted"), true);
            return;
        }
        RegionNetwork.pushToEmpire(level, empire.id());
        playPageTurn(level, pos, 0.9F);
        player.displayClientMessage(Component.translatable(
                "message.empire.ledger.home.room_removed", indoorRoomCount(home)), true);
    }

    private static void reportHomeStatus(ServerLevel level, Player player, RegionRecord home, BlockPos pos,
                                         boolean wasValid) {
        if (home.valid()) {
            if (!wasValid) {
                playPageTurn(level, pos, 1.1F);
                player.displayClientMessage(homeSummaryMessage("restored", home), true);
            } else if (home.isOutdoorBlock(pos)) {
                player.displayClientMessage(
                        Component.translatable("message.empire.ledger.home.bed_outdoor"), true);
            } else {
                playPageTurn(level, pos, 1.0F);
                player.displayClientMessage(homeStatusMessage(home), true);
            }
        } else {
            player.displayClientMessage(Component.translatable(
                    "message.empire.ledger.home.invalid", reasonComponent(home.invalidReason())), true);
        }
    }

    private static void reportFarmSelection(ServerLevel level, EmpireSavedData data, Player player,
                                            RegionRecord farm, BlockPos pos, boolean wasValid) {
        if (farm.valid()) {
            playPageTurn(level, pos, wasValid ? 1.0F : 1.1F);
            String key = wasValid ? "message.empire.ledger.farm.selected" : "message.empire.ledger.farm.restored";
            player.displayClientMessage(Component.translatable(
                    key,
                    farm.roomCountWithLabel(RoomLabel.SHED),
                    farm.roomCountWithLabel(RoomLabel.FIELD),
                    farm.storageBlocks().size(),
                    CivilianOccupations.activeWorkerCount(data, farm),
                    RegionRules.workerCapacity(RegionType.FARM)), true);
        } else {
            playPageTurn(level, pos, 0.9F);
            player.displayClientMessage(Component.translatable(
                    "message.empire.ledger.farm.invalid", reasonComponent(farm.invalidReason())), true);
        }
    }

    private static void reportFarmRoomStatus(ServerLevel level, EmpireSavedData data, Player player,
                                             RegionRecord farm, BlockPos pos, boolean wasValid) {
        if (!farm.valid()) {
            playPageTurn(level, pos, 0.9F);
            player.displayClientMessage(Component.translatable(
                    "message.empire.ledger.farm.invalid", reasonComponent(farm.invalidReason())), true);
            return;
        }
        playPageTurn(level, pos, wasValid ? 1.0F : 1.1F);
        RegionRecord.RoomRecord room = farm.roomAt(pos);
        if (room != null && room.isShed()) {
            player.displayClientMessage(Component.translatable(
                    "message.empire.ledger.farm.room_status_shed",
                    room.blockCount(),
                    room.storageCount()), true);
        } else if (room != null && room.isField()) {
            Item preferred = FarmCrops.seedItemById(room.preferredCropId());
            if (preferred != null) {
                player.displayClientMessage(Component.translatable(
                        "message.empire.ledger.farm.room_status_field_crop",
                        room.blockCount(),
                        preferred.getDescription()), true);
            } else {
                player.displayClientMessage(Component.translatable(
                        "message.empire.ledger.farm.room_status_field",
                        room.blockCount()), true);
            }
        } else {
            player.displayClientMessage(Component.translatable(
                    "message.empire.ledger.farm.selected",
                    farm.roomCountWithLabel(RoomLabel.SHED),
                    farm.roomCountWithLabel(RoomLabel.FIELD),
                    farm.storageBlocks().size(),
                    CivilianOccupations.activeWorkerCount(data, farm),
                    RegionRules.workerCapacity(RegionType.FARM)), true);
        }
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

    private static void failHome(Player player, RegionInvalidReason reason) {
        player.displayClientMessage(
                Component.translatable("message.empire.ledger.home.failed", reasonComponent(reason)), true);
    }

    private static void failFarm(Player player, RegionInvalidReason reason) {
        player.displayClientMessage(
                Component.translatable("message.empire.ledger.farm.failed", reasonComponent(reason)), true);
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
