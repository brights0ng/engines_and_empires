// Written by Gemini; rewritten by Claude (geometry moved to TalcBorderGeometry, mesh cached, colours and claim rules kept).
package dev.brights0ng.empire.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.brights0ng.empire.map.ClaimMapCache;
import dev.brights0ng.empire.map.ClaimMapPayloads;
import dev.brights0ng.empire.rules.ClaimGrid;
import dev.brights0ng.empire.talc.BlessedTalcItem;
import dev.brights0ng.empire.talc.TarnishedTalcItem;
import dev.brights0ng.paradynelib.client.ClientBlockChanges;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Draws claim borders while the player holds Blessed or Tarnished Talc: chalk lines draped over the terrain and
 * shimmering light veils above them. The geometry (see {@link TalcBorderGeometry}) is built from the claim cache and
 * the world, cached, and rebuilt when the claims around the player change, the player changes chunk, a block changes in
 * a column the mesh was built from ({@link ClientBlockChanges}), a chunk loads nearby, or every
 * {@link #FALLBACK_REFRESH_TICKS} ticks in case anything slipped past those. Each frame only re-colours the cached quads.
 */
public final class TalcBorderRenderer {

    /** Chunk radius around the player to ask the server about. */
    private static final int QUERY_RADIUS = 7;
    /** Chunk radius around the player whose borders are drawn. */
    private static final int RENDER_RADIUS = 5;
    /** Lines and veils fade out between these horizontal distances from the camera. */
    private static final float FADE_START = 55.0F;
    private static final float FADE_END = 80.0F;
    /** Border edges farther than this from the player when the mesh is built are left out. */
    private static final double BUILD_DISTANCE = FADE_END + 24.0;
    /** A rebuild at least this often (5 s) even without a block change, as a safety net. */
    private static final int FALLBACK_REFRESH_TICKS = 100;

    private static TalcBorderGeometry.Mesh mesh;
    private static ClientLevel meshLevel;
    private static long meshChunk;
    private static long meshClaims;
    private static long meshTick;
    /** A block or chunk the mesh depends on changed; rebuilt at most once a tick. */
    private static boolean dirty;

    /** Hooks the renderer up: drawing, block changes and chunk loads. Called once from the client entry point. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(TalcBorderRenderer::onRenderStage);
        NeoForge.EVENT_BUS.addListener(TalcBorderRenderer::onChunkLoad);
        ClientBlockChanges.register(TalcBorderRenderer::onBlockChanged);
    }

    private static void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        TalcBorderGeometry.Mesh m = mesh;
        if (oldState != newState && m != null && m.readsColumn(pos.getX(), pos.getZ())) {
            dirty = true;
        }
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (mesh == null || !event.getLevel().isClientSide()) {
            return;
        }
        ChunkPos pos = event.getChunk().getPos();
        ChunkPos centre = new ChunkPos(meshChunk);
        if (Math.abs(pos.x - centre.x) <= RENDER_RADIUS + 1 && Math.abs(pos.z - centre.z) <= RENDER_RADIUS + 1) {
            dirty = true;
        }
    }

    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || !level.dimension().equals(Level.OVERWORLD)) {
            mesh = null;
            meshLevel = null;
            return;
        }

        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        boolean holdingBlessed = main.getItem() instanceof BlessedTalcItem || off.getItem() instanceof BlessedTalcItem;
        boolean holdingTarnished = main.getItem() instanceof TarnishedTalcItem || off.getItem() instanceof TarnishedTalcItem;
        if (!holdingBlessed && !holdingTarnished) {
            mesh = null;
            return;
        }

        int pcx = player.blockPosition().getX() >> 4;
        int pcz = player.blockPosition().getZ() >> 4;
        long now = System.currentTimeMillis();
        if (ClaimMapCache.shouldAsk("talc_borders", pcx, pcz, QUERY_RADIUS, now)) {
            PacketDistributor.sendToServer(new ClaimMapPayloads.Request(pcx, pcz, QUERY_RADIUS));
        }

        long gameTime = level.getGameTime();
        long chunk = ChunkPos.asLong(pcx, pcz);
        long claims = claimSignature(pcx, pcz);
        if (mesh == null || meshLevel != level || meshChunk != chunk || meshClaims != claims
                || (dirty && gameTime != meshTick)
                || gameTime - meshTick >= FALLBACK_REFRESH_TICKS || gameTime < meshTick) {
            mesh = buildMesh(level, player, pcx, pcz);
            meshLevel = level;
            meshChunk = chunk;
            meshClaims = claims;
            meshTick = gameTime;
            dirty = false;
        }
        if (mesh.quadCount() == 0) {
            return;
        }

        float time = (gameTime + event.getPartialTick().getGameTimeDeltaPartialTick(false)) * 0.06F;
        draw(event, mc, mesh, time, holdingTarnished);
    }

    /** A hash of the claim states the mesh depends on. */
    private static long claimSignature(int pcx, int pcz) {
        long h = 1;
        int r = RENDER_RADIUS + 1;
        for (int cz = pcz - r; cz <= pcz + r; cz++) {
            for (int cx = pcx - r; cx <= pcx + r; cx++) {
                h = h * 31 + ClaimMapCache.claim(cx, cz);
            }
        }
        return h;
    }

    /** Whether the chunk in {@code state} draws its side facing a chunk in {@code neighbour}. */
    private static boolean drawsBorder(int state, int neighbour) {
        // A rival's border with our empire is drawn once, from our side.
        return state != ClaimGrid.UNCLAIMED && neighbour != state
                && !(state == ClaimGrid.OTHER_EMPIRE && neighbour == ClaimGrid.OUR_EMPIRE);
    }

    private static TalcBorderGeometry.Mesh buildMesh(ClientLevel level, LocalPlayer player, int pcx, int pcz) {
        TalcBorderGeometry.Borders borders = new TalcBorderGeometry.Borders();
        double px = player.getX();
        double pz = player.getZ();
        double maxSq = BUILD_DISTANCE * BUILD_DISTANCE;
        for (int cz = pcz - RENDER_RADIUS; cz <= pcz + RENDER_RADIUS; cz++) {
            for (int cx = pcx - RENDER_RADIUS; cx <= pcx + RENDER_RADIUS; cx++) {
                int state = ClaimMapCache.claim(cx, cz);
                if (state == ClaimGrid.UNCLAIMED || !level.hasChunk(cx, cz)) {
                    continue;
                }
                int x0 = cx << 4;
                int z0 = cz << 4;
                boolean north = drawsBorder(state, ClaimMapCache.claim(cx, cz - 1));
                boolean south = drawsBorder(state, ClaimMapCache.claim(cx, cz + 1));
                boolean west = drawsBorder(state, ClaimMapCache.claim(cx - 1, cz));
                boolean east = drawsBorder(state, ClaimMapCache.claim(cx + 1, cz));
                for (int i = 0; i < 16; i++) {
                    if (north && near(x0 + i + 0.5, z0, px, pz, maxSq)) {
                        borders.addZEdge(x0 + i, z0, state);
                    }
                    if (south && near(x0 + i + 0.5, z0 + 16, px, pz, maxSq)) {
                        borders.addZEdge(x0 + i, z0 + 16, state);
                    }
                    if (west && near(x0, z0 + i + 0.5, px, pz, maxSq)) {
                        borders.addXEdge(x0, z0 + i, state);
                    }
                    if (east && near(x0 + 16, z0 + i + 0.5, px, pz, maxSq)) {
                        borders.addXEdge(x0 + 16, z0 + i, state);
                    }
                }
            }
        }
        return TalcBorderGeometry.build(new LevelTerrain(level), borders, pcx << 4, pcz << 4);
    }

    private static boolean near(double x, double z, double px, double pz, double maxSq) {
        double dx = x - px;
        double dz = z - pz;
        return dx * dx + dz * dz <= maxSq;
    }

    private static void draw(RenderLevelStageEvent event, Minecraft mc, TalcBorderGeometry.Mesh m, float time,
                             boolean tarnished) {
        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(m.originX() - cam.x, -cam.y, m.originZ() - cam.z);
        Matrix4f matrix = poseStack.last().pose();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(EmpireRenderTypes.BORDER_VEIL);

        int[] ours = new int[6];
        int[] rival = new int[6];
        colors(ClaimGrid.OUR_EMPIRE, tarnished, ours);
        colors(ClaimGrid.OTHER_EMPIRE, tarnished, rival);
        // Camera position relative to the mesh origin, so distances stay in small floats.
        float camX = (float) (cam.x - m.originX());
        float camZ = (float) (cam.z - m.originZ());
        float[] fade = new float[4];

        for (int q = 0; q < m.quadCount(); q++) {
            float maxFade = 0.0F;
            for (int v = 0; v < 4; v++) {
                float dx = m.x(q, v) - camX;
                float dz = m.z(q, v) - camZ;
                float d = Mth.sqrt(dx * dx + dz * dz);
                fade[v] = 1.0F - Mth.clamp((d - FADE_START) / (FADE_END - FADE_START), 0.0F, 1.0F);
                maxFade = Math.max(maxFade, fade[v]);
            }
            if (maxFade <= 0.0F) {
                continue;
            }
            int[] c = m.style(q) == ClaimGrid.OUR_EMPIRE ? ours : rival;
            boolean veil = m.kind(q) == TalcBorderGeometry.Mesh.VEIL;
            for (int v = 0; v < 4; v++) {
                float x = m.x(q, v);
                float y = m.y(q, v);
                float z = m.z(q, v);
                int alpha;
                if (!veil) {
                    alpha = (int) (225 * fade[v]);
                    consumer.addVertex(matrix, x, y, z).setColor(c[0], c[1], c[2], alpha);
                } else {
                    if (v == 1 || v == 2) {
                        alpha = 0;
                    } else {
                        float wave = Mth.sin((x + m.originX() + z + m.originZ()) * 0.4F + time * 3.0F);
                        alpha = (int) (105 * (0.75F + 0.25F * wave) * fade[v]);
                    }
                    consumer.addVertex(matrix, x, y, z).setColor(c[3], c[4], c[5], alpha);
                }
            }
        }

        buffers.endBatch(EmpireRenderTypes.BORDER_VEIL);
        poseStack.popPose();
    }

    /** Line colour in out[0..2], veil colour in out[3..5]. */
    private static void colors(int state, boolean tarnished, int[] out) {
        if (state == ClaimGrid.OUR_EMPIRE) {
            if (tarnished) {
                // Smoky silver-white for tarnished talc
                set(out, 225, 230, 235, 220, 225, 235);
            } else {
                // Pure luminous chalk white
                set(out, 255, 255, 255, 255, 255, 255);
            }
        } else {
            // Rival empires: smoldering crimson
            set(out, 220, 53, 69, 255, 82, 82);
        }
    }

    private static void set(int[] out, int... values) {
        System.arraycopy(values, 0, out, 0, values.length);
    }

    /**
     * The world as {@link TalcBorderGeometry} sees it: a block is terrain if it has collision, is powder snow, or is a
     * low non-colliding cover (snow layer, pressure plate, rail: outline at most a quarter block tall); its outline
     * shape is used. Fluids count up to their surface. Air and leaves never count, so lines run under canopies.
     */
    private record LevelTerrain(ClientLevel level, BlockPos.MutableBlockPos pos) implements TalcBorderGeometry.Terrain {

        LevelTerrain(ClientLevel level) {
            this(level, new BlockPos.MutableBlockPos());
        }

        @Override
        public int scanTop(int x, int z) {
            if (!level.hasChunk(x >> 4, z >> 4)) {
                return UNLOADED;
            }
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            return Math.min(level.getMaxBuildHeight() - 1, top + 1);
        }

        @Override
        public int scanBottom() {
            return level.getMinBuildHeight();
        }

        @Override
        public void boxes(int x, int y, int z, TalcBorderGeometry.BoxSink sink) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.is(BlockTags.LEAVES)) {
                return;
            }
            VoxelShape outline = state.getShape(level, pos);
            if (!outline.isEmpty() && (!state.getCollisionShape(level, pos).isEmpty()
                    || state.is(Blocks.POWDER_SNOW)
                    || outline.max(Direction.Axis.Y) <= 0.25)) {
                outline.forAllBoxes(sink::box);
            }
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                float height = fluid.getHeight(level, pos);
                if (height > 0.0F) {
                    sink.box(0, 0, 0, 1, height, 1);
                }
            }
        }
    }

    private TalcBorderRenderer() {
    }
}
