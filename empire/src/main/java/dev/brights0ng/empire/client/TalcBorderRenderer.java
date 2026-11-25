// Written by Gemini; rewritten by Claude (geometry moved to TalcBorderGeometry, mesh cached, colours and claim rules kept); golden protected land by Claude; claims now asked for by ClaimCacheKeeper; edited by Gemini (delegated quad rendering to shared VeilMeshRenderer).
package dev.brights0ng.empire.client;

import dev.brights0ng.empire.ledger.AdministrativeLedgerItem;
import dev.brights0ng.empire.map.ClaimMapCache;
import dev.brights0ng.empire.rules.ClaimGrid;
import dev.brights0ng.empire.talc.BlessedTalcItem;
import dev.brights0ng.empire.talc.TarnishedTalcItem;
import dev.brights0ng.paradynelib.client.ClientBlockChanges;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Draws claim borders while the player holds Blessed or Tarnished Talc: chalk lines draped over the terrain and
 * shimmering light veils above them. Protected land (the 3x3 around an empire's main bonfire) has its own golden
 * outline, which takes the place of a claim border where the two meet. The claims come from the cache, which
 * {@link ClaimCacheKeeper} keeps fresh around the player. The geometry (see {@link TalcBorderGeometry}) is built from
 * the claim cache and
 * the world, cached, and rebuilt when the claims around the player change, the player changes chunk, a block changes in
 * a column the mesh was built from ({@link ClientBlockChanges}), a chunk loads nearby, or every
 * {@link #FALLBACK_REFRESH_TICKS} ticks in case anything slipped past those. Each frame only re-colours the cached quads.
 */
public final class TalcBorderRenderer {

    /** Chunk radius around the player whose borders are drawn. */
    private static final int RENDER_RADIUS = 5;
    /** Border edges farther than this from the player when the mesh is built are left out. */
    private static final double BUILD_DISTANCE = VeilMeshRenderer.FADE_END + 24.0;
    /** A rebuild at least this often (5 s) even without a block change, as a safety net. */
    private static final int FALLBACK_REFRESH_TICKS = 100;

    private static VeilMesh mesh;
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
        VeilMesh m = mesh;
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
        boolean holdingLedger = main.getItem() instanceof AdministrativeLedgerItem || off.getItem() instanceof AdministrativeLedgerItem;
        if (!holdingBlessed && !holdingTarnished && !holdingLedger) {
            mesh = null;
            return;
        }

        int pcx = player.blockPosition().getX() >> 4;
        int pcz = player.blockPosition().getZ() >> 4;
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

        float time = VeilMeshRenderer.animationTime(gameTime, event);
        boolean tarnished = holdingTarnished;
        VeilMeshRenderer.draw(event, mc, mesh, time, (style, out) -> colors(style, tarnished, out));
    }

    /** A hash of the claim states the mesh depends on. */
    private static long claimSignature(int pcx, int pcz) {
        long h = 1;
        int r = RENDER_RADIUS + 1;
        for (int cz = pcz - r; cz <= pcz + r; cz++) {
            for (int cx = pcx - r; cx <= pcx + r; cx++) {
                h = h * 31 + ClaimMapCache.claim(cx, cz) + 4L * ClaimMapCache.protection(cx, cz);
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

    /**
     * The style a chunk draws its side with, facing a neighbour, or 0 for none. Where the protection differs the side is
     * a golden protected border, drawn once (from the protected side; between two empires' protected land, from the
     * lower side value) and in place of any claim border there; otherwise the claim rule applies.
     */
    private static int sideStyle(int state, int protection, int neighbour, int neighbourProtection) {
        if (protection != neighbourProtection) {
            boolean drawsHere = protection != ClaimGrid.UNCLAIMED
                    && (neighbourProtection == ClaimGrid.UNCLAIMED || protection < neighbourProtection);
            return drawsHere ? TalcBorderGeometry.STYLE_PROTECTED : 0;
        }
        return drawsBorder(state, neighbour) ? state : 0;
    }

    private static VeilMesh buildMesh(ClientLevel level, LocalPlayer player, int pcx, int pcz) {
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
                int prot = ClaimMapCache.protection(cx, cz);
                int north = sideStyle(state, prot, ClaimMapCache.claim(cx, cz - 1), ClaimMapCache.protection(cx, cz - 1));
                int south = sideStyle(state, prot, ClaimMapCache.claim(cx, cz + 1), ClaimMapCache.protection(cx, cz + 1));
                int west = sideStyle(state, prot, ClaimMapCache.claim(cx - 1, cz), ClaimMapCache.protection(cx - 1, cz));
                int east = sideStyle(state, prot, ClaimMapCache.claim(cx + 1, cz), ClaimMapCache.protection(cx + 1, cz));
                for (int i = 0; i < 16; i++) {
                    if (north != 0 && near(x0 + i + 0.5, z0, px, pz, maxSq)) {
                        borders.addZEdge(x0 + i, z0, north);
                    }
                    if (south != 0 && near(x0 + i + 0.5, z0 + 16, px, pz, maxSq)) {
                        borders.addZEdge(x0 + i, z0 + 16, south);
                    }
                    if (west != 0 && near(x0, z0 + i + 0.5, px, pz, maxSq)) {
                        borders.addXEdge(x0, z0 + i, west);
                    }
                    if (east != 0 && near(x0 + 16, z0 + i + 0.5, px, pz, maxSq)) {
                        borders.addXEdge(x0 + 16, z0 + i, east);
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

    /** Line colour in out[0..2], veil colour in out[3..5]. */
    private static void colors(int state, boolean tarnished, int[] out) {
        if (state == TalcBorderGeometry.STYLE_PROTECTED) {
            // Protected land: burnished gold, a little dulled under tarnished talc
            if (tarnished) {
                set(out, 225, 180, 70, 235, 195, 95);
            } else {
                set(out, 255, 200, 55, 255, 215, 90);
            }
        } else if (state == ClaimGrid.OUR_EMPIRE) {
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
