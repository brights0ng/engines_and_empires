package dev.brights0ng.sablefixes.gametest;

import java.lang.reflect.Method;
import java.util.Arrays;

import dev.brights0ng.sablefixes.SableFixes;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Sable Fixes' mixins are woven into their targets in a real game (split step 2b). A mixin config plugin that wrongly
 * skipped them would leave the game running with Sable's bugs back; unit tests run without Mixin and cannot see it. The
 * mod's own bare game test run (Sable Fixes and Sable, nothing else) runs these, so they also prove the fixes need
 * nothing of ours. Their template is the empty structure in this mod's namespace, {@code sable_fixes:gametest/empty},
 * shipped as a resource (Sable Fixes uses nothing of the library's, including its structure writer).
 */
@GameTestHolder(SableFixes.MODID)
@PrefixGameTestTemplate(false)
public final class SableFixesGameTests {

    private static final String EMPTY = "gametest/empty";

    @GameTest(template = EMPTY)
    public static void theGameEventSectionGuardIsApplied(GameTestHelper helper) {
        helper.assertTrue(woven(GameEventDispatcher.class, "sable_fixes$guardSectionRange"),
                "GameEventDispatcher.post should carry the section range guard");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void thePlotSaveFixIsApplied(GameTestHelper helper) {
        helper.assertTrue(woven(ServerLevelPlot.class, "sable_fixes$attachmentsIntoTheChunk"),
                "ServerLevelPlot.save should write chunk attachments into the chunk's tag");
        helper.assertTrue(woven(ServerLevelPlot.class, "sable_fixes$lightIntoTheChunk"),
                "ServerLevelPlot.save should write light data into the chunk's tag");
        helper.succeed();
    }

    /** Whether a mixin handler named {@code handler} was merged into {@code target} (Mixin may decorate the name). */
    private static boolean woven(Class<?> target, String handler) {
        return Arrays.stream(target.getDeclaredMethods()).map(Method::getName).anyMatch(n -> n.contains(handler));
    }

    private SableFixesGameTests() {
    }
}
