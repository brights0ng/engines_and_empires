package dev.brights0ng.enginesandempires.frontier.incursion;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The Squelching Heart, torn from a Warden: eaten at night, it holds the night still for five minutes more (see
 * {@link NightHold}) and leaves the eater in Darkness for fifteen seconds. Each player can eat one a night. By day, or a
 * second time in a night, it will not be eaten at all.
 */
public class SquelchingHeartItem extends Item {

    public static final FoodProperties FOOD = new FoodProperties.Builder().nutrition(2).saturationModifier(0.1F)
            .alwaysEdible().build();
    private static final int DARKNESS_TICKS = 300;

    public SquelchingHeartItem(Properties properties) {
        super(properties.food(FOOD));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        boolean allowed = level instanceof ServerLevel server ? NightHold.of(server).canEat(server, player)
                : NightHold.isNight(level.getDayTime());
        if (!allowed) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable(NightHold.isNight(level.getDayTime())
                        ? "item.engines_and_empires.squelching_heart.already" : "item.engines_and_empires.squelching_heart.day")
                        .withStyle(ChatFormatting.DARK_AQUA), true);
            }
            return InteractionResultHolder.fail(stack);
        }
        return super.use(level, player, hand);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (level instanceof ServerLevel server && entity instanceof ServerPlayer player) {
            NightHold hold = NightHold.of(server);
            if (!hold.canEat(server, player)) {
                return stack; // it turned day (or they ate one) while chewing: nothing happens
            }
            hold.eat(server, player);
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DARKNESS_TICKS, 0, false, true, true));
            server.playSound(null, player.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 2.0F, 0.8F);
            Component message = Component.translatable("item.engines_and_empires.squelching_heart.eaten", player.getDisplayName(),
                    hold.held() / 1200).withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC);
            for (ServerPlayer other : server.getServer().getPlayerList().getPlayers()) {
                other.displayClientMessage(message, false);
            }
        }
        return super.finishUsingItem(stack, level, entity);
    }
}
