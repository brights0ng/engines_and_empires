// Edited by Claude: skips Encumbered's weight drawing while its server config isn't loaded (crash on quit/rejoin).
package dev.brights0ng.enginesandempires.mixin.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.khofonyx.encumbered.ServerConfig;
import com.khofonyx.encumbered.client.WeightOverlay;

import dev.brights0ng.enginesandempires.weight.ItemWeights;

/**
 * Two fixes to Encumbered's weight display:
 * <ul>
 *   <li>Tidies the numbers it writes above the inventory: "Weight: 212.34999", "TH1: 100.0" and "TH2: 200.0" become
 *       "Weight: 212.35 kpg", "TH1: 100 kpg" and "TH2: 200 kpg". Encumbered builds each line as one string and passes
 *       it to {@code Component.literal}, so this rewrites that string.</li>
 *   <li>Skips both drawings (the HUD anvil and the inventory lines) while Encumbered's SERVER config isn't loaded.
 *       Both read its weight thresholds from that config every frame, and a frame drawn while a world is starting or
 *       stopping (quitting, rejoining) threw "Cannot get config value before config is loaded" and crashed the client
 *       (Bright, 2026-11-15). Nothing is drawn for those frames.</li>
 * </ul>
 */
@Mixin(value = WeightOverlay.class, remap = false)
public abstract class EncumberedOverlayMixin {

    @Unique
    private static final Pattern ENGINES_AND_EMPIRES$LINE = Pattern.compile("^(Weight|TH1|TH2): (-?[0-9.]+(?:E-?[0-9]+)?)$");

    @Inject(method = "onRenderHUD(Lnet/neoforged/neoforge/client/event/RenderGuiLayerEvent$Post;)V", at = @At("HEAD"),
            cancellable = true)
    private static void engines_and_empires$skipHudWithoutConfig(CallbackInfo ci) {
        if (!ServerConfig.SPEC.isLoaded()) {
            ci.cancel();
        }
    }

    @Inject(method = "onRenderHUD(Lnet/neoforged/neoforge/client/event/ScreenEvent$Render$Post;)V", at = @At("HEAD"),
            cancellable = true)
    private static void engines_and_empires$skipScreenWithoutConfig(CallbackInfo ci) {
        if (!ServerConfig.SPEC.isLoaded()) {
            ci.cancel();
        }
    }

    @ModifyArg(method = "onRenderHUD(Lnet/neoforged/neoforge/client/event/ScreenEvent$Render$Post;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;literal(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"))
    private static String engines_and_empires$kpgLine(String text) {
        Matcher m = ENGINES_AND_EMPIRES$LINE.matcher(text);
        if (!m.matches()) {
            return text;
        }
        try {
            return m.group(1) + ": " + ItemWeights.format(Double.parseDouble(m.group(2))) + " kpg";
        } catch (NumberFormatException e) {
            return text;
        }
    }
}
