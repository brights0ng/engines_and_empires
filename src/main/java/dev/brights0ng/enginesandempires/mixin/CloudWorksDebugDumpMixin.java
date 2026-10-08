package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Switches off CloudWorks API's recipe "debug dump" (CloudWorks is Diet's required library).
 *
 * <p>On every world load CloudWorks 1.0.4 writes every recipe to JSON files under {@code cloudworks/recipe_parser/
 * debug_output/}, re-reading and rewriting a whole multi-megabyte file for each recipe. Measured 2026-10-05
 * ({@code claude/perf-report-2026-10-05.md}): 5 min 13 s at ~70% of a CPU core for 3,615 recipes, 26 MB written, and
 * frequent GC hitches (P99 23 ms) the whole time. The output is debug-only; CloudWorks' recipe indexes, which Diet uses,
 * are built separately and are untouched.
 *
 * <p>{@link Pseudo} and {@code require = 0}: without CloudWorks (or if a later version renames these) this does nothing.
 */
@Pseudo
@Mixin(targets = "com.cloudworks.api.recipeparser.DebugOutputWriter", remap = false)
public abstract class CloudWorksDebugDumpMixin {

    @Inject(method = {"writeDebugOutput", "writeDebugOutputAsync"}, at = @At("HEAD"), cancellable = true, require = 0)
    private static void engines_and_empires$skipDump(CallbackInfo ci) {
        ci.cancel();
    }
}
