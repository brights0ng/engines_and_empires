package dev.brights0ng.meteodyne.sable;

import dev.brights0ng.meteodyne.heat.world.HeatShips;
import dev.brights0ng.meteodyne.sable.gametest.HeatShipGameTests;
import dev.brights0ng.meteodyne.sable.gametest.ShipLightningGameTests;
import dev.brights0ng.meteodyne.sable.gametest.ShipPersonClimateGameTests;
import dev.brights0ng.meteodyne.sable.gametest.ShipWeatherGameTests;
import dev.brights0ng.meteodyne.sable.gametest.WindGameTests;
import dev.brights0ng.meteodyne.ships.Ships;
import dev.brights0ng.paradynelib.sable.wind.WindCommand;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Meteodyne with Sable (split step 2c): ships as cover, ships' rooms in the heat simulation, weather on their decks,
 * the library's wind push command under {@code /meteodyne wind}, and the game tests that need ships. Sable is optional, so every class that names Sable's is in this package, and
 * {@code Meteodyne} calls {@link #register} only when Sable is loaded; without it {@link Ships#NONE} and
 * {@link HeatShips#NONE} answer (no ships).
 */
public final class MeteodyneSable {

    private MeteodyneSable() {
    }

    public static void register(IEventBus modEventBus) {
        Ships.use(SableShips.INSTANCE);
        HeatShips.use(SableHeatShips.INSTANCE);
        NeoForge.EVENT_BUS.addListener(ShipWeather::onLevelTick);
        NeoForge.EVENT_BUS.addListener(MeteodyneSable::registerCommands);
        modEventBus.addListener(MeteodyneSable::registerGameTests);
    }

    /** {@code /meteodyne wind}: the wind and its push on the nearest physics object (the library's command). */
    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("meteodyne").requires(source -> source.hasPermission(2))
                .then(WindCommand.node("/meteodyne wind")));
    }

    private static void registerGameTests(RegisterGameTestsEvent event) {
        event.register(HeatShipGameTests.class);
        event.register(ShipLightningGameTests.class);
        event.register(ShipPersonClimateGameTests.class);
        event.register(ShipWeatherGameTests.class);
        event.register(WindGameTests.class);
    }
}
