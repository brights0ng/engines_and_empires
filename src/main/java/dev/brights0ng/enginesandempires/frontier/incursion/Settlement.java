package dev.brights0ng.enginesandempires.frontier.incursion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.brights0ng.enginesandempires.frontier.FrontierConfig;
import dev.brights0ng.enginesandempires.frontier.FrontierTags;
import dev.brights0ng.enginesandempires.frontier.Tier;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import dev.brights0ng.enginesandempires.frontier.tier.SectionKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * One settlement: a connected group of Settled land (see {@link SettlementClusters}). Civilized if any of it is.
 *
 * @param sections its sections
 * @param columns  the chunk columns they stand in
 * @param civilized whether any of it is Civilized
 */
public record Settlement(Set<Long> sections, Set<Long> columns, boolean civilized) {

    /** Every settlement in a level, biggest first. */
    public static List<Settlement> all(FrontierLevel frontier) {
        List<Settlement> all = new ArrayList<>();
        for (Set<Long> cluster : SettlementClusters.cluster(frontier.settledSections())) {
            boolean civilized = false;
            for (long section : cluster) {
                if (frontier.baseTier(section) == Tier.CIVILIZED) {
                    civilized = true;
                    break;
                }
            }
            all.add(new Settlement(cluster, SettlementClusters.columns(cluster), civilized));
        }
        return all;
    }

    /** The settlement {@code pos} is in, if any. */
    public static Settlement at(FrontierLevel frontier, BlockPos pos) {
        for (Settlement settlement : all(frontier)) {
            if (settlement.contains(pos)) {
                return settlement;
            }
        }
        return null;
    }

    /** Whether {@code pos} is inside: in one of its sections, or the ones just above or below them. */
    public boolean contains(BlockPos pos) {
        return contains(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    public boolean contains(long blockPos) {
        return contains(BlockPos.getX(blockPos) >> 4, BlockPos.getY(blockPos) >> 4, BlockPos.getZ(blockPos) >> 4);
    }

    private boolean contains(int sx, int sy, int sz) {
        return sections.contains(SectionKey.of(sx, sy, sz)) || sections.contains(SectionKey.of(sx, sy - 1, sz))
                || sections.contains(SectionKey.of(sx, sy + 1, sz));
    }

    /** Whether {@code pos}'s chunk column is one of the settlement's (for the boss bar and rewards: loosely inside). */
    public boolean inColumns(BlockPos pos) {
        return columns.contains(SettlementClusters.column(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** The players inside. */
    public List<ServerPlayer> players(ServerLevel level) {
        List<ServerPlayer> inside = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && contains(player.blockPosition())) {
                inside.add(player);
            }
        }
        return inside;
    }

    /** The block box around it all. */
    public AABB bounds() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long s : sections) {
            minX = Math.min(minX, SectionKey.x(s));
            minY = Math.min(minY, SectionKey.y(s));
            minZ = Math.min(minZ, SectionKey.z(s));
            maxX = Math.max(maxX, SectionKey.x(s));
            maxY = Math.max(maxY, SectionKey.y(s));
            maxZ = Math.max(maxZ, SectionKey.z(s));
        }
        return new AABB(minX << 4, (minY - 1) << 4, minZ << 4, (maxX + 1) << 4, (maxY + 2) << 4, (maxZ + 1) << 4);
    }

    /** The middle of it, on the ground: where an incursion heads when it has found nobody to hunt. */
    public BlockPos centre(ServerLevel level) {
        double x = 0;
        double z = 0;
        for (long c : columns) {
            x += SettlementClusters.columnX(c) * 16 + 8;
            z += SettlementClusters.columnZ(c) * 16 + 8;
        }
        int bx = (int) Math.floor(x / columns.size());
        int bz = (int) Math.floor(z / columns.size());
        return new BlockPos(bx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz), bz);
    }

    /** How much it draws the Deep's attention right now (see {@link IncursionOdds}). */
    public Score score(ServerLevel level) {
        FrontierConfig.IncursionParams params = FrontierConfig.incursions();
        long now = level.getGameTime();
        int players = players(level).size();
        int inhabitants = level.getEntities((Entity) null, bounds(),
                e -> !(e instanceof Player) && e.isAlive() && e.getType().is(FrontierTags.INHABITANTS)
                        && contains(e.blockPosition())).size();
        IncursionData data = IncursionData.of(level);
        double shots = data.shotScore(this::contains, now);
        double machines = data.machineScore(this::contains, now);
        return new Score(players, inhabitants, shots, machines,
                players * params.playerWeight() + inhabitants * params.inhabitantWeight() + shots + machines);
    }

    /** A settlement's score and what went into it. */
    public record Score(int players, int inhabitants, double shots, double machines, double total) {
    }

    /** Tonight's chance for this settlement. */
    public double chance(double score) {
        FrontierConfig.IncursionParams params = FrontierConfig.incursions();
        return civilized
                ? IncursionOdds.chance(score, params.maxScore(), params.civilizedMinChance(), params.civilizedMaxChance())
                : IncursionOdds.chance(score, params.maxScore(), params.settledMinChance(), params.settledMaxChance());
    }
}
