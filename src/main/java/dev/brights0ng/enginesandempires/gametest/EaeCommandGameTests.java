package dev.brights0ng.enginesandempires.gametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;

import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.enginesandempires.debug.EaeCommand;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The core's {@code /eae} redirects (split step 2f): each feature node leads to that mod's own root, a whole command
 * parses through it, and the removed {@code /eae weather|heat|clouds|wind} stay removed.
 */
@GameTestHolder(EnginesAndEmpires.MODID)
@PrefixGameTestTemplate(false)
public final class EaeCommandGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    @GameTest(template = SCRATCH)
    public static void eaeRedirectsToEachFeatureModsRoot(GameTestHelper helper) {
        CommandDispatcher<CommandSourceStack> dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        CommandSourceStack op = helper.getLevel().getServer().createCommandSourceStack();
        CommandNode<CommandSourceStack> eae = dispatcher.getRoot().getChild(EaeCommand.ROOT);
        helper.assertTrue(eae != null, "/eae exists");
        for (String feature : EaeCommand.FEATURES) {
            CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild(feature);
            helper.assertTrue(root != null, "/" + feature + " exists");
            helper.assertTrue(eae.getChild(feature) != null && eae.getChild(feature).getRedirect() == root,
                    "/eae " + feature + " redirects to /" + feature);
        }
        for (String command : new String[]{"eae frontier tier", "eae seismic shot", "eae sparseores phantoms",
                "eae spoilage inspect"}) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(command, op);
            helper.assertTrue(!parse.getReader().canRead() && parse.getExceptions().isEmpty()
                    && parse.getContext().getLastChild().getCommand() != null, "/" + command + " parses to a command");
        }
        for (String removed : new String[]{"weather", "heat", "clouds", "wind", "food"}) {
            helper.assertTrue(eae.getChild(removed) == null, "/eae " + removed + " stays removed");
        }
        helper.succeed();
    }
}
