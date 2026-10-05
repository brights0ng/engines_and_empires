package dev.brights0ng.enginesandempires.food.debug;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.food.Cohort;
import dev.brights0ng.enginesandempires.food.FoodFreshness;
import dev.brights0ng.enginesandempires.food.SpoilClock;
import dev.brights0ng.enginesandempires.food.SpoilTimes;
import dev.brights0ng.enginesandempires.food.Spoilage;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Debug commands for food spoilage, under the pack's {@code /eae} root. They need operator permission and act on the food
 * in your main hand.
 *
 * <ul>
 *   <li>{@code /eae food inspect}: each cohort in the stack, top first: count, stage, hidden substage, and age.</li>
 *   <li>{@code /eae food age <ticks>}: makes the whole stack older by that much (24000 = a day).</li>
 * </ul>
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class FoodCommand {

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("eae")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("food")
                        .then(Commands.literal("inspect").executes(context -> inspect(context.getSource())))
                        .then(Commands.literal("age")
                                .then(Commands.argument("ticks", LongArgumentType.longArg(0))
                                        .executes(context -> age(context.getSource(), LongArgumentType.getLong(context, "ticks")))))));
    }

    private static int inspect(CommandSourceStack source) throws CommandSyntaxException {
        ItemStack held = heldFood(source);
        if (held == null) {
            return 0;
        }
        long now = SpoilClock.now();
        SpoilTimes times = Spoilage.times();
        FoodFreshness stored = Spoilage.stored(held);
        FoodFreshness view = Spoilage.view(held, now);
        source.sendSuccess(() -> Component.literal(held.getHoverName().getString() + " x" + held.getCount()
                + (stored == null ? " (not stamped yet)" : "")), false);
        int size = view.cohorts().size();
        for (int step = 0; step < size; step++) {
            int index = (view.top() + step) % size;
            Cohort cohort = view.cohorts().get(index);
            long age = now - cohort.born();
            String line = String.format("  %s%d %s, substage %d/10 (step %d/30), %.2f days old, born at tick %d",
                    step == 0 ? "top: " : "", cohort.count(), times.stage(age).id(), times.substage(age) + 1, times.step(age),
                    age / (double) SpoilTimes.DAY, cohort.born());
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return size;
    }

    private static int age(CommandSourceStack source, long ticks) throws CommandSyntaxException {
        ItemStack held = heldFood(source);
        if (held == null) {
            return 0;
        }
        long now = SpoilClock.now();
        Spoilage.write(held, Spoilage.view(held, now).aged(ticks).settle(now, Spoilage.times()));
        source.sendSuccess(() -> Component.literal("Aged the stack by " + ticks + " ticks."), false);
        return inspect(source);
    }

    private static ItemStack heldFood(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (!Spoilage.isSpoilable(held)) {
            source.sendFailure(Component.literal("Hold some food that spoils."));
            return null;
        }
        return held;
    }

    private FoodCommand() {
    }
}
