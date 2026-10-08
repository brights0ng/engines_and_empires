package dev.brights0ng.enginesandempires.weather.debug.client;

import dev.brights0ng.enginesandempires.weather.debug.WeatherMapPayloads;
import net.minecraft.client.Minecraft;

/** Client side of the weather debug map: opens the screen and hands it the server's answers. Client only. */
public final class WeatherMapClient {

    public static void open() {
        Minecraft.getInstance().setScreen(new WeatherMapScreen());
    }

    public static void accept(WeatherMapPayloads.Answer answer) {
        if (Minecraft.getInstance().screen instanceof WeatherMapScreen screen) {
            screen.accept(answer);
        }
    }

    private WeatherMapClient() {
    }
}
