package dev.brights0ng.enginesandempires.debug;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;

import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The core's {@code /eae} root (plan Q4, split step 2f): before the split every debug command of the single mod was
 * under {@code /eae}; each mod of the family now has its own root, and {@code /eae <feature> ...} redirects there, so
 * {@code /eae frontier tier} runs {@code /frontier tier}. One node per feature mod root:
 * <ul>
 *   <li>{@code /eae frontier} to {@code /frontier} (tiers, torches, guards, incursions, the Deep)</li>
 *   <li>{@code /eae seismic} to {@code /seismic} (scan, shot)</li>
 *   <li>{@code /eae sparseores} to {@code /sparseores} (deposits, phantoms, biomes)</li>
 *   <li>{@code /eae spoilage} to {@code /spoilage} (inspect, age)</li>
 * </ul>
 * There is no {@code /eae weather}, {@code heat}, {@code clouds} or {@code wind}: those are gone for good (Bright, after
 * step 2c), and Meteodyne's commands are only under {@code /meteodyne}.
 */
@EventBusSubscriber(modid = EnginesAndEmpires.MODID)
public final class EaeCommand {

    /** The core's command root. */
    public static final String ROOT = "eae";
    /** The roots {@code /eae} redirects to, each under its own name. */
    public static final List<String> FEATURES = List.of("frontier", "seismic", "sparseores", "spoilage");

    private EaeCommand() {
    }

    /** Last, so the feature mods have registered the roots it points at. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> eae = Commands.literal(ROOT);
        for (String feature : FEATURES) {
            CommandNode<CommandSourceStack> target = dispatcher.getRoot().getChild(feature);
            if (target == null) {
                EnginesAndEmpires.LOGGER.warn("/{} {}: there is no /{} to redirect to", ROOT, feature, feature);
                continue;
            }
            // The target's own permission and command (if it runs with no arguments), then everything under it.
            eae.then(Commands.literal(feature)
                    .requires(target.getRequirement())
                    .executes(target.getCommand())
                    .redirect(target));
        }
        dispatcher.register(eae);
    }
}
