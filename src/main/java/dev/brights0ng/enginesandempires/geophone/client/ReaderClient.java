package dev.brights0ng.enginesandempires.geophone.client;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.geophone.ReaderCompass;
import dev.brights0ng.enginesandempires.geophone.ReaderReading;
import dev.brights0ng.enginesandempires.geophone.SeismicContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Client-side registration for the wind-up reader: the renderer for the placed reader and the models it is drawn from, and
 * the item properties that make the held reader a compass.
 *
 * <p>The held reader's picture is chosen by its item model, which reads three properties, all worked out here from the reading
 * the item carries and from whoever is holding it (or, in an inventory, looking at it):
 * <ul>
 *   <li>{@code reading}: 1 if it has a reading in this dimension to point at, otherwise 0, and it is drawn as a dead dial.</li>
 *   <li>{@code angle}: which way the needle points, as a fraction of a turn clockwise from straight up.</li>
 *   <li>{@code height}: what the arrow says, see {@link ReaderCompass.HeightBand}.</li>
 * </ul>
 *
 * <p>This class is only ever loaded on a client.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID, value = Dist.CLIENT)
public final class ReaderClient {

    /** The body of the placed reader, and the lamp on top of it dark and lit. */
    public static final ModelResourceLocation BODY = model("block/windup_reader_body");
    public static final ModelResourceLocation LAMP = model("block/windup_reader_lamp");
    public static final ModelResourceLocation LAMP_LIT = model("block/windup_reader_lamp_lit");

    private static ModelResourceLocation model(String path) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, path));
    }

    private static ResourceLocation property(String name) {
        return ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, name);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SeismicContent.WINDUP_READER_ENTITY.get(), WindupReaderRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterModels(ModelEvent.RegisterAdditional event) {
        event.register(BODY);
        event.register(LAMP);
        event.register(LAMP_LIT);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            Item reader = SeismicContent.WINDUP_READER.get();
            ItemProperties.register(reader, property("reading"),
                    (stack, level, holder, seed) -> usableReading(stack, level, holder) != null ? 1.0F : 0.0F);
            ItemProperties.register(reader, property("angle"), (stack, level, holder, seed) -> {
                ReaderReading reading = usableReading(stack, level, holder);
                Entity viewer = viewer(stack, holder);
                if (reading == null || viewer == null) {
                    return 0.0F;
                }
                return (float) ReaderCompass.needleFraction(viewer.getYRot(), viewer.getX(), viewer.getZ(),
                        reading.x() + 0.5, reading.z() + 0.5);
            });
            ItemProperties.register(reader, property("height"), (stack, level, holder, seed) -> {
                ReaderReading reading = usableReading(stack, level, holder);
                Entity viewer = viewer(stack, holder);
                if (reading == null || viewer == null) {
                    return ReaderCompass.HeightBand.NONE.value();
                }
                return ReaderCompass.heightBand(reading.y() + 0.5 - viewer.getY(), reading.hasHeight()).value();
            });
        });
    }

    /** Who the needle is turned relative to: whoever holds the item, or is looking at it, or the item itself if it is lying about. */
    private static Entity viewer(ItemStack stack, LivingEntity holder) {
        if (holder != null) {
            return holder;
        }
        Entity represented = stack.getEntityRepresentation();
        return represented != null ? represented : Minecraft.getInstance().player;
    }

    /** The item's reading, if it has one and it is in the dimension the viewer is in. A reading points nowhere in another dimension. */
    private static ReaderReading usableReading(ItemStack stack, ClientLevel level, LivingEntity holder) {
        ReaderReading reading = stack.get(SeismicContent.READER_READING.get());
        if (reading == null) {
            return null;
        }
        Entity viewer = viewer(stack, holder);
        net.minecraft.world.level.Level where = level != null ? level : viewer != null ? viewer.level() : null;
        if (where == null || !where.dimension().location().toString().equals(reading.dimension())) {
            return null;
        }
        return reading;
    }

    private ReaderClient() {
    }
}
