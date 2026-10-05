package dev.brights0ng.enginesandempires.geophone;

import java.util.List;
import java.util.Locale;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.generators.BlockModelBuilder;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ItemModelBuilder;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;

/**
 * Generates the models of the wind-up reader: the small models the placed reader is drawn from, and the model of the held
 * item, which is the compass.
 *
 * <p>The held item's picture depends on three properties (see {@code ReaderClient}): whether it has a reading, which way the
 * needle points, and what the height arrow says. The game chooses a picture by going down a list of overrides, each of which
 * says "if these properties are at least this much, use this model", and using the <em>last</em> one that fits. So the list is
 * written in an order that makes that come out right:
 * <ul>
 *   <li>The model itself is the dead dial, used when the reading property is not 1.</li>
 *   <li>Then one group of overrides for each height, from no arrow up through up, level and down. Each group needs at least its
 *       own height value, so a group further down the list wins over the ones before it whenever it fits.</li>
 *   <li>Inside a group, one override per needle position, in order of angle, so the last that fits is the nearest position; then
 *       a last one that brings the needle back to the first picture as it comes round to a full turn.</li>
 * </ul>
 * There are 32 positions and 4 heights, so 132 overrides and 128 small models. It is a lot of files, but it is all generated, and
 * a test checks that every combination of the properties really does choose the picture it should.
 */
final class ReaderDataGen {

    private static final String MODID = EnginesAndEmpiresMod.MODID;

    /** The name of a property the item model reads: {@code engines_and_empires:<name>}. */
    private static ResourceLocation property(String name) {
        return ResourceLocation.fromNamespaceAndPath(MODID, name);
    }

    static void registerModels(BlockStateProvider provider) {
        // The placed reader: a brass case with a crank on one side, and a lamp on top. Drawn standing with its foot in the
        // middle of the bottom of one block, and turned by the renderer.
        ResourceLocation casing = provider.modLoc("block/windup_reader");
        BlockModelBuilder body = provider.models().getBuilder("windup_reader_body")
                .texture("particle", casing)
                .texture("body", casing);
        SeismicDataGen.box(body, "#body", 4, 0, 4, 12, 5, 12);   // the case
        SeismicDataGen.box(body, "#body", 12, 1, 7, 14, 3, 9);   // the crank's axle
        SeismicDataGen.box(body, "#body", 13, 3, 7, 15, 8, 9);   // the crank's grip
        for (String lamp : List.of("windup_reader_lamp", "windup_reader_lamp_lit")) {
            ResourceLocation texture = provider.modLoc("block/" + lamp);
            BlockModelBuilder model = provider.models().getBuilder(lamp)
                    .texture("particle", texture)
                    .texture("lamp", texture);
            SeismicDataGen.box(model, "#lamp", 6, 5, 6, 10, 7, 10); // the lamp, on top of the case
        }

        // The held reader: every needle position with every height arrow, then the list that chooses between them.
        ItemModelProvider items = provider.itemModels();
        ReaderCompass.HeightBand[] bands = ReaderCompass.HeightBand.values();
        ItemModelBuilder[][] pictures = new ItemModelBuilder[bands.length][ReaderCompass.FRAMES];
        for (int b = 0; b < bands.length; b++) {
            for (int frame = 0; frame < ReaderCompass.FRAMES; frame++) {
                ItemModelBuilder picture = items.withExistingParent(ReaderCompass.pictureName(frame, bands[b]), "item/generated")
                        .texture("layer0", provider.modLoc("item/windup_reader_" + frame));
                if (bands[b] != ReaderCompass.HeightBand.NONE) {
                    picture.texture("layer1", provider.modLoc("item/windup_reader_arrow_" + bands[b].name().toLowerCase(Locale.ROOT)));
                }
                pictures[b][frame] = picture;
            }
        }

        ItemModelBuilder reader = items.withExistingParent("windup_reader", "item/generated")
                .texture("layer0", provider.modLoc("item/windup_reader_idle"));
        for (int b = 0; b < bands.length; b++) {
            for (int frame = 0; frame < ReaderCompass.FRAMES; frame++) {
                override(reader, bands[b], ReaderCompass.frameThreshold(frame), frame == 0, pictures[b][frame]);
            }
            override(reader, bands[b], ReaderCompass.wrapThreshold(), false, pictures[b][0]);
        }
    }

    /**
     * One override: use {@code picture} if there is a reading, the height is at least this band's, and the angle is at least
     * {@code angleThreshold} (which is not asked at all for the first position, which fits from angle 0).
     */
    private static void override(ItemModelBuilder reader, ReaderCompass.HeightBand band, double angleThreshold,
                                 boolean anyAngle, ItemModelBuilder picture) {
        ItemModelBuilder.OverrideBuilder override = reader.override().predicate(property("reading"), 1.0F);
        if (band != ReaderCompass.HeightBand.NONE) {
            override.predicate(property("height"), band.value());
        }
        if (!anyAngle) {
            override.predicate(property("angle"), (float) angleThreshold);
        }
        override.model(picture).end();
    }

    private ReaderDataGen() {
    }
}
