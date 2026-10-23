// Written by Claude.
package dev.brights0ng.empire.client;

import java.util.function.Function;

import dev.brights0ng.empire.Empire;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * The bonfire's GeckoLib model (ChatGPT's "grand folksy bonfire", 3x3x3 blocks, origin at the bottom centre of the core
 * block). The flames flicker through the texture's eight frames ({@code bonfire.png.mcmeta}, which GeckoLib plays by
 * itself); the model only decides which flames show: none when out, as modelled when lit, {@link #ROARING_SCALE} times
 * taller and wider while roaring (each flame grows from its base, its bone's pivot).
 *
 * <p>The baked model is shared by every bonfire and the item, so every flame bone is set on every render.
 *
 * @param <T> the block entity or the item
 */
public class BonfireModel<T extends GeoAnimatable> extends GeoModel<T> {

    public enum Flames { OUT, LIT, ROARING }

    public static final float ROARING_SCALE = 1.4F;

    private static final ResourceLocation MODEL = Empire.id("geo/grand_folksy_bonfire_no_embers.geo.json");
    private static final ResourceLocation TEXTURE = Empire.id("textures/entity/bonfire.png");
    private static final ResourceLocation ANIMATION = Empire.id("animations/grand_folksy_bonfire_no_embers.animation.json");
    private static final String[] FLAME_BONES = {"flame_1", "flame_2", "flame_3"};

    private final Function<T, Flames> flames;

    public BonfireModel(Function<T, Flames> flames) {
        this.flames = flames;
    }

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(T animatable, long instanceId, AnimationState<T> animationState) {
        Flames state = flames.apply(animatable);
        float scale = state == Flames.ROARING ? ROARING_SCALE : 1.0F;
        for (String name : FLAME_BONES) {
            getBone(name).ifPresent(bone -> {
                bone.setHidden(state == Flames.OUT);
                bone.updateScale(scale, scale, scale);
            });
        }
    }
}
