package dev.brights0ng.enginesandempires.mixin.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.khofonyx.encumbered.client.WeightOverlay;

import dev.brights0ng.enginesandempires.weight.ItemWeights;

/**
 * Tidies the numbers Encumbered writes above the inventory: "Weight: 212.34999", "TH1: 100.0" and "TH2: 200.0" become
 * "Weight: 212.35 kpg", "TH1: 100 kpg" and "TH2: 200 kpg". Encumbered builds each line as one string and passes it to
 * {@code Component.literal}, so this rewrites that string.
 */
@Mixin(value = WeightOverlay.class, remap = false)
public abstract class EncumberedOverlayMixin {

    @Unique
    private static final Pattern ENGINES_AND_EMPIRES$LINE = Pattern.compile("^(Weight|TH1|TH2): (-?[0-9.]+(?:E-?[0-9]+)?)$");

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
