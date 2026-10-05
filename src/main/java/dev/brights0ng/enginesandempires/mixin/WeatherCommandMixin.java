package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.WeatherCommand;

/**
 * {@code /weather} sets vanilla's global weather, which the pack holds clear in the Overworld, so there it would do
 * nothing visible. Instead it says so. The pack's own weather controls will live under {@code /eae weather}
 * ({@code claude/weather-backbone-plan.md}).
 */
@Mixin(WeatherCommand.class)
public abstract class WeatherCommandMixin {

    @Inject(method = {"setClear", "setRain", "setThunder"}, at = @At("HEAD"), cancellable = true)
    private static void engines_and_empires$ownWeather(CommandSourceStack source, int time,
                                                       CallbackInfoReturnable<Integer> cir) {
        if (WeatherOwnership.owns(source.getServer().overworld())) {
            source.sendFailure(Component.literal(
                    "The Overworld's weather is simulated by Engines and Empires, so /weather doesn't change it."));
            cir.setReturnValue(0);
        }
    }
}
