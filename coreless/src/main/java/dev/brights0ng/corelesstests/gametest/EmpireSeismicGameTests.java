// Written by Gemini.
package dev.brights0ng.corelesstests.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.brights0ng.corelesstests.CorelessTests;
import dev.brights0ng.empire.compat.seismic.ClaimOverlay;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.map.ClaimMapCache;
import dev.brights0ng.empire.map.ClaimMapNetwork;
import dev.brights0ng.empire.map.ClaimMapPayloads;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.ClaimGrid;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.seismicsurveyance.client.MapLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CorelessTests.MODID)
@PrefixGameTestTemplate(false)
public final class EmpireSeismicGameTests {

    @GameTest(template = GameTestStructures.EMPTY)
    public static void claimMapPayloadBuildsCorrectAnswer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);

        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        Player stranger = helper.makeMockPlayer(GameType.SURVIVAL);

        BlockPos centerPos = helper.absolutePos(new BlockPos(8, 1, 8));
        int cx = centerPos.getX() >> 4;
        int cz = centerPos.getZ() >> 4;
        long centerChunk = ChunkKeys.pack(cx, cz);

        EmpireRecord record = data.createEmpire(founder.getUUID(), centerChunk, centerPos, helper.getLevel().getGameTime());

        try {
            int radius = 2;
            ClaimMapPayloads.Answer founderAnswer = ClaimMapNetwork.build(helper.getLevel(), founder.getUUID(), cx, cz, radius);
            helper.assertTrue(founderAnswer.size() == 2 * radius + 1, "Answer size should be 2*radius+1");

            byte[] unpackedFounder = ClaimGrid.unpack(founderAnswer.packed(), founderAnswer.size() * founderAnswer.size());
            int centerIndex = radius * founderAnswer.size() + radius;
            helper.assertTrue(unpackedFounder[centerIndex] == ClaimGrid.OUR_EMPIRE, "Center chunk must be OUR_EMPIRE for founder");

            helper.assertTrue(!founderAnswer.bonfires().isEmpty(), "Bonfire list must not be empty");
            ClaimMapPayloads.BonfireMarker marker = founderAnswer.bonfires().get(0);
            helper.assertTrue(marker.ourEmpire(), "Bonfire must belong to our empire for founder");
            helper.assertTrue(marker.x() == centerPos.getX(), "Bonfire X mismatch");
            helper.assertTrue(marker.z() == centerPos.getZ(), "Bonfire Z mismatch");

            // Stranger's view
            ClaimMapPayloads.Answer strangerAnswer = ClaimMapNetwork.build(helper.getLevel(), stranger.getUUID(), cx, cz, radius);
            byte[] unpackedStranger = ClaimGrid.unpack(strangerAnswer.packed(), strangerAnswer.size() * strangerAnswer.size());
            helper.assertTrue(unpackedStranger[centerIndex] == ClaimGrid.OTHER_EMPIRE, "Center chunk must be OTHER_EMPIRE for stranger");

            ClaimMapPayloads.BonfireMarker strangerMarker = strangerAnswer.bonfires().get(0);
            helper.assertTrue(!strangerMarker.ourEmpire(), "Bonfire must belong to other empire for stranger");
        } finally {
            data.disbandEmpire(record.id(), server);
        }

        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void claimOverlayRendersColumnsAndMarkersFromCache(GameTestHelper helper) {
        ClaimMapCache.clear();

        byte[] states = new byte[]{
                0, 0, 0,
                0, 1, 0,
                0, 0, 0
        };
        ClaimMapPayloads.Answer answer = new ClaimMapPayloads.Answer(
                10, 10, 3,
                ClaimGrid.pack(states),
                List.of(new ClaimMapPayloads.BonfireMarker(168, 168, true))
        );
        ClaimMapCache.accept(answer);

        List<Integer> drawnX = new ArrayList<>();
        List<Integer> drawnZ = new ArrayList<>();
        List<Integer> drawnSides = new ArrayList<>();

        ClaimOverlay.INSTANCE.columns(10, 10, 12, 12, (cx, cz, fill, edge, edges) -> {
            drawnX.add(cx);
            drawnZ.add(cz);
            drawnSides.add(edges);
            helper.assertTrue(fill == ClaimOverlay.fill(ClaimGrid.OUR_EMPIRE), "Fill colour mismatch");
            helper.assertTrue(edge == ClaimOverlay.edge(ClaimGrid.OUR_EMPIRE), "Edge colour mismatch");
        });

        helper.assertTrue(drawnX.size() == 1, "Exactly one chunk should be drawn");
        helper.assertTrue(drawnX.get(0) == 11 && drawnZ.get(0) == 11, "Chunk coords mismatch");
        helper.assertTrue(drawnSides.get(0) == (MapLayer.NORTH | MapLayer.SOUTH | MapLayer.WEST | MapLayer.EAST), "All 4 edges should be active");

        List<MapLayer.Marker> markers = ClaimOverlay.INSTANCE.markers();
        helper.assertTrue(markers.size() == 1, "Exactly one bonfire marker should exist");
        MapLayer.Marker m = markers.get(0);
        helper.assertTrue(m.x() == 168 && m.z() == 168, "Marker pos mismatch");
        helper.assertTrue(m.large(), "Own empire bonfire should be large");
        helper.assertTrue(m.colour() == ClaimOverlay.MARKER_BONFIRE, "Marker colour mismatch");

        List<MapLayer.LegendRow> legend = ClaimOverlay.INSTANCE.legend();
        helper.assertTrue(legend.size() == 3, "Legend must have 3 rows");
        helper.assertTrue(!legend.get(0).marker(), "Territory row must not be marker");
        helper.assertTrue(!legend.get(1).marker(), "Foreign row must not be marker");
        helper.assertTrue(legend.get(2).marker(), "Bonfire row must be marker");

        ClaimMapCache.clear();
        helper.succeed();
    }
}
