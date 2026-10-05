package dev.brights0ng.enginesandempires.geophone;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The wind-up reader. Held, it is a compass: its needle points at the reading it carries, and an arrow says whether that is
 * above, level with, or below you. The picture for that is chosen by the item model from properties set in
 * {@code ReaderClient}, since it depends on which way the holder is facing.
 *
 * <p>It also has to be wound before it can capture a reading. Right-click and hold, aimed at anything other than a block, to
 * turn the crank: this works exactly like Create's own hand crank, except that here the winding happens while the button is
 * held, rather than needing a click for every turn. A full wind takes {@link ReaderWinding#WIND_TICKS} ticks (ten seconds) and
 * spends {@link ReaderWinding#FOOD_POINTS_SPENT} points of the winder's food, taken the same way sprinting or jumping cost
 * hunger, out of saturation first. Letting go part-way through keeps whatever charge was built up, so a wind can be finished
 * over several goes. The reader only holds enough charge for one reading: capturing one spends it, and it must be wound again
 * for the next. Loading a reading from a logbook, unlike capturing one from the geophones, costs nothing and needs no charge.
 *
 * <p>Right-clicking the top of a solid block, instead, stands it on the ground, where it listens through the geophones around it
 * and records the most recent reading it is wound enough to catch. It is turned to face the player, in steps of a quarter turn,
 * and keeps both the reading and the charge it carried. It only goes on the ground: it needs a level surface to stand on.
 */
public class WindupReaderItem extends Item {

    /**
     * How long the item reports itself as usable for, in ticks: comfortably longer than a full wind, so holding the crank is
     * never cut short by the game's own use-duration bookkeeping. The reader's own {@link ReaderWinding#WIND_TICKS} is what
     * actually decides how long winding takes.
     */
    private static final int USE_DURATION_TICKS = 36000;

    /** How often, in ticks, the crank clicks while it turns. */
    private static final int CLICK_INTERVAL_TICKS = 5;

    public WindupReaderItem(Properties properties) {
        super(properties);
    }

    /**
     * Right-click and hold, anywhere that is not a block (the game only calls this when {@link #useOn} did not fire), turns the
     * crank. Nothing happens if it is already fully wound.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        float charge = stack.getOrDefault(SeismicContent.READER_CHARGE.get(), 0.0F);
        if (ReaderWinding.isFullyWound(charge)) {
            return InteractionResultHolder.pass(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_DURATION_TICKS;
    }

    /** A placeholder: something is needed to show the crank is being worked until there is real art for it. */
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    /**
     * One tick of turning the crank. Only does anything on the server: the charge is a data component, which the game already
     * sends to the client on its own, so there is nothing left for the client side of this call to do.
     */
    @Override
    public void onUseTick(Level level, LivingEntity livingEntity, ItemStack stack, int remainingUseDuration) {
        if (level.isClientSide) {
            return;
        }
        float charge = stack.getOrDefault(SeismicContent.READER_CHARGE.get(), 0.0F);
        if (ReaderWinding.isFullyWound(charge)) {
            livingEntity.stopUsingItem(); // finished on an earlier tick; let go rather than keep holding for nothing
            return;
        }

        float next = ReaderWinding.advance(charge);
        stack.set(SeismicContent.READER_CHARGE.get(), next);
        if (livingEntity instanceof Player player) {
            player.causeFoodExhaustion(ReaderWinding.EXHAUSTION_PER_TICK);
        }

        boolean justFinished = ReaderWinding.isFullyWound(next);
        int elapsed = USE_DURATION_TICKS - remainingUseDuration;
        if (justFinished) {
            level.playSound(null, livingEntity.getX(), livingEntity.getY(), livingEntity.getZ(),
                    SoundEvents.METAL_PLACE, SoundSource.PLAYERS, 0.6F, 0.8F);
            livingEntity.stopUsingItem();
        } else if (elapsed % CLICK_INTERVAL_TICKS == 0) {
            // The click rises in pitch as the spring winds tighter, the same way Create's own crank sounds.
            level.playSound(null, livingEntity.getX(), livingEntity.getY(), livingEntity.getZ(),
                    SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.3F, 1.4F + next * 0.4F);
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (context.getClickedFace() != Direction.UP || !state.isFaceSturdy(level, pos, Direction.UP)) {
            return InteractionResult.FAIL;
        }

        if (level instanceof ServerLevel server) {
            Player player = context.getPlayer();
            Vec3 point = context.getClickLocation();
            // Turned to face the player, to the nearest quarter turn.
            float yaw = player == null ? 0.0F : Math.round((player.getYRot() + 180.0F) / 90.0F) * 90.0F;

            ItemStack heldStack = context.getItemInHand();
            float charge = heldStack.getOrDefault(SeismicContent.READER_CHARGE.get(), 0.0F);
            WindupReaderEntity reader = new WindupReaderEntity(SeismicContent.WINDUP_READER_ENTITY.get(), server);
            reader.stake(point, pos, yaw, heldStack.get(SeismicContent.READER_READING.get()), charge);
            server.addFreshEntity(reader);
            server.playSound(null, point.x, point.y, point.z, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 0.7F, 0.9F);
            if (player == null || !player.getAbilities().instabuild) {
                heldStack.shrink(1);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        float charge = stack.getOrDefault(SeismicContent.READER_CHARGE.get(), 0.0F);
        if (ReaderWinding.isFullyWound(charge)) {
            tooltip.add(Component.translatable("item.engines_and_empires.windup_reader.wound").withStyle(ChatFormatting.GREEN));
        } else {
            tooltip.add(Component.translatable("item.engines_and_empires.windup_reader.winding", Math.round(charge * 100.0F))
                    .withStyle(ChatFormatting.GRAY));
        }
        ReaderReading reading = stack.get(SeismicContent.READER_READING.get());
        if (reading != null) {
            String key = switch (reading.confidence()) {
                case PRECISE -> "item.engines_and_empires.windup_reader.confidence_precise";
                case APPROXIMATE -> "item.engines_and_empires.windup_reader.confidence_approximate";
                case ROUGH -> "item.engines_and_empires.windup_reader.confidence_rough";
                case UNKNOWN -> "item.engines_and_empires.windup_reader.confidence_unknown";
            };
            tooltip.add(Component.translatable(key).withStyle(ChatFormatting.GRAY));
        }
    }
}
