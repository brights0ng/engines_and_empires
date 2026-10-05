package dev.brights0ng.enginesandempires.geophone;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A heavy hammer for striking the ground. Right-click stone to strike it and send a vibration into the ground, or a
 * {@link StrikePlateBlock} to send a much stronger one. Anything else is not solid enough to carry a blow: the hammer
 * does nothing, and the click passes on as if the hammer were not there.
 *
 * <p>What counts as stone is the {@link #STRIKABLE} block tag, so a datapack can change it.
 *
 * <p>Striking has a short cooldown, so one blow's vibration has mostly passed before the next is made.
 */
public class SledgehammerItem extends Item {

    /** The blocks a hammer can strike without a plate: natural stone of every dimension, cobblestone, and ore in the rock. */
    public static final TagKey<Block> STRIKABLE = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "strikable"));

    /** How long before the hammer can strike again, in ticks. */
    public static final int COOLDOWN_TICKS = 20;

    public SledgehammerItem(Properties properties) {
        super(properties);
    }

    /**
     * How far a blow on this block carries, or 0 if the block cannot be struck at all.
     */
    public static int rangeFor(BlockState struck) {
        if (struck.getBlock() instanceof StrikePlateBlock) {
            return SeismicShots.PLATE_RANGE;
        }
        return struck.is(STRIKABLE) ? SeismicShots.HAMMER_RANGE : 0;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState struck = level.getBlockState(pos);
        int range = rangeFor(struck);
        if (range == 0) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel server) {
            SeismicShots.strike(server, pos, struck, range);
            Player player = context.getPlayer();
            if (player != null) {
                player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
