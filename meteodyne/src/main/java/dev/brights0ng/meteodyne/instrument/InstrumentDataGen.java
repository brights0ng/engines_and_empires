// Written by ChatGPT; updated by Gemini.
package dev.brights0ng.meteodyne.instrument;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.brights0ng.meteodyne.Meteodyne;
import net.minecraft.Util;
import net.minecraft.core.Direction;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/** Generates the thermometer's models, textures, and initial survival recipes. See docs/meteodyne/weather-instruments.md. */
public final class InstrumentDataGen implements DataProvider {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final PackOutput output;
    private final boolean client, server;

    private InstrumentDataGen(PackOutput output, boolean client, boolean server) {
        this.output = output;
        this.client = client;
        this.server = server;
    }

    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeClient() || event.includeServer(), new InstrumentDataGen(
                event.getGenerator().getPackOutput(), event.includeClient(), event.includeServer()));
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (client) { assets(cache); }
                if (server) { data(cache); }
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }, Util.backgroundExecutor());
    }

    private void assets(CachedOutput cache) throws IOException {
        Path root = output.getOutputFolder(PackOutput.Target.RESOURCE_PACK).resolve(Meteodyne.MODID);
        png(cache, root.resolve("textures/block/thermometer_face.png"), ThermometerTextures.face());
        for (InstrumentMetalTextures.Panel panel : InstrumentMetalTextures.Panel.values()) {
            png(cache, root.resolve("textures/block/" + panel.path() + ".png"), InstrumentMetalTextures.panel(panel));
        }
        png(cache, root.resolve("textures/block/instrument_white.png"), ThermometerTextures.white());
        png(cache, root.resolve("textures/item/thermometer_unknown.png"), ThermometerTextures.item(Double.NaN));
        JsonObject held = itemModel("thermometer_unknown");
        JsonArray overrides = new JsonArray();
        for (int frame = 0; frame <= ThermometerTextures.STEPS; frame++) {
            String name = "thermometer_" + frame;
            png(cache, root.resolve("textures/item/" + name + ".png"), ThermometerTextures.item(frame / (double) ThermometerTextures.STEPS));
            write(cache, root.resolve("models/item/" + name + ".json"), itemModel(name));
            overrides.add(object("predicate", object("meteodyne:temperature", frame + 1), "model", "meteodyne:item/" + name));
        }
        held.add("overrides", overrides);
        write(cache, root.resolve("models/item/thermometer.json"), held);
        windAssets(cache, root);
        JsonObject variants = new JsonObject();
        for (AttachFace face : AttachFace.values()) {
            String name = face.name().toLowerCase(Locale.ROOT);
            write(cache, root.resolve("models/block/temperature_sensor_" + name + ".json"), sensorModel(face));
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                int rotation = switch (direction) { case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0; };
                variants.add("face=" + name + ",facing=" + direction.getSerializedName(),
                        object("model", "meteodyne:block/temperature_sensor_" + name, "y", rotation));
            }
        }
        write(cache, root.resolve("blockstates/temperature_sensor.json"), object("variants", variants));
        write(cache, root.resolve("models/item/temperature_sensor.json"), object("parent", "meteodyne:block/temperature_sensor_floor"));
    }

    static JsonObject sensorModel(AttachFace face) { return sensorModel(face, false); }

    private static JsonObject sensorModel(AttachFace face, boolean wind) {
        JsonArray elements = new JsonArray();
        JsonObject textures = object("face", wind ? "meteodyne:block/wind_sensor_face" : "meteodyne:block/thermometer_face", "particle", "meteodyne:block/instrument_metal");
        for (SensorShape.Box box : wind ? WindSensorShape.boxes(face) : SensorShape.boxes(face)) {
            JsonObject faces = new JsonObject();
            for (String side : new String[]{"north", "south", "east", "west", "up", "down"}) {
                int width = modelPixels(side.equals("east") || side.equals("west") ? box.z1() - box.z0() : box.x1() - box.x0());
                int height = modelPixels(side.equals("up") || side.equals("down") ? box.z1() - box.z0() : box.y1() - box.y0());
                InstrumentMetalTextures.Panel panel = InstrumentMetalTextures.Panel.forFace(width, height);
                textures.addProperty(panel.path(), "meteodyne:block/" + panel.path());
                JsonObject material = object("texture", "#" + panel.path(), "uv", array(0, 0, panel.width(), panel.height()));
                if (width < height) { material.addProperty("rotation", 90); }
                faces.add(side, material);
            }
            if (wind && box.equals(WindSensorShape.head(face))) {
                faces.add("north", object("texture", "#face", "uv", array(0, 0, 6, 6)));
            } else if (!wind && box.equals(SensorShape.head(face))) {
                faces.add("north", object("texture", "#face", "uv", array(SensorShape.FACE_LEFT / 2.0,
                        SensorShape.FACE_TOP / 2.0, SensorShape.FACE_RIGHT / 2.0, SensorShape.FACE_BOTTOM / 2.0)));
            }
            elements.add(object("from", array(box.x0(), box.y0(), box.z0()), "to", array(box.x1(), box.y1(), box.z1()), "faces", faces));
        }
        if (wind) {
            textures.addProperty("rotor", "meteodyne:block/instrument_metal");
            // The live rotor is rendered separately; its stationary inventory silhouette uses identical boxes.
        }
        return object("parent", "minecraft:block/block", "textures", textures, "elements", elements);
    }

    private void windAssets(CachedOutput cache, Path root) throws IOException {
        png(cache, root.resolve("textures/block/wind_sensor_face.png"), WindGaugeTextures.face());
        png(cache, root.resolve("textures/item/wind_gauge_unknown.png"), WindGaugeTextures.item(-1, 0, false));
        png(cache, root.resolve("textures/item/wind_gauge_calm.png"), WindGaugeTextures.item(-1, 0, true));
        write(cache, root.resolve("models/item/wind_gauge_calm.json"), itemModel("wind_gauge_calm"));
        JsonObject held = itemModel("wind_gauge_unknown");
        JsonArray overrides = new JsonArray();
        overrides.add(object("predicate", object("meteodyne:wind", 1), "model", "meteodyne:item/wind_gauge_calm"));
        for (int direction = 0; direction < WindGaugeTextures.DIRECTIONS; direction++) {
            for (int rotor = 0; rotor < WindGaugeTextures.ROTOR_FRAMES; rotor++) {
                String name = "wind_gauge_" + direction + "_" + rotor;
                png(cache, root.resolve("textures/item/" + name + ".png"), WindGaugeTextures.item(direction, rotor, true));
                write(cache, root.resolve("models/item/" + name + ".json"), itemModel(name));
                overrides.add(object("predicate", object("meteodyne:wind", 2 + direction * WindGaugeTextures.ROTOR_FRAMES + rotor),
                        "model", "meteodyne:item/" + name));
            }
        }
        held.add("overrides", overrides);
        write(cache, root.resolve("models/item/wind_gauge.json"), held);
        JsonObject variants = new JsonObject();
        for (AttachFace face : AttachFace.values()) {
            String name = face.name().toLowerCase(Locale.ROOT);
            JsonObject model = sensorModel(face, true);
            write(cache, root.resolve("models/block/wind_sensor_" + name + ".json"), model);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                int rotation = switch (direction) { case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0; };
                variants.add("face=" + name + ",facing=" + direction.getSerializedName(),
                        object("model", "meteodyne:block/wind_sensor_" + name, "y", rotation));
            }
        }
        write(cache, root.resolve("blockstates/wind_sensor.json"), object("variants", variants));
        JsonObject inventory = sensorModel(AttachFace.FLOOR, true);
        JsonArray elements = inventory.getAsJsonArray("elements");
        for (SensorShape.Box box : WindSensorShape.rotorBoxes(AttachFace.FLOOR)) {
            JsonObject faces = new JsonObject();
            for (String side : new String[]{"north", "south", "east", "west", "up", "down"}) {
                faces.add(side, object("texture", "#rotor", "uv", array(0, 0, 1, 1)));
            }
            elements.add(object("from", array(box.x0(), box.y0(), box.z0()), "to", array(box.x1(), box.y1(), box.z1()), "faces", faces));
        }
        write(cache, root.resolve("models/item/wind_sensor.json"), inventory);
    }

    private static int modelPixels(double length) {
        int pixels = (int) Math.round(length);
        if (pixels < 1 || Math.abs(length - pixels) > 1e-9) {
            throw new IllegalArgumentException("Metal faces need whole model pixels or an explicit new UV layout: " + length);
        }
        return pixels;
    }

    private static JsonObject itemModel(String texture) {
        return object("parent", "minecraft:item/generated", "textures", object("layer0", "meteodyne:item/" + texture));
    }

    private void data(CachedOutput cache) throws IOException {
        Path root = output.getOutputFolder(PackOutput.Target.DATA_PACK);
        write(cache, root.resolve("meteodyne/recipe/thermometer.json"), object("type", "minecraft:crafting_shaped",
                "category", "equipment", "pattern", array(" I ", " G ", "IRI"),
                "key", object("I", object("item", "minecraft:iron_ingot"), "G", object("item", "minecraft:glass"),
                        "R", object("item", "minecraft:redstone")), "result", object("id", "meteodyne:thermometer", "count", 1)));
        write(cache, root.resolve("meteodyne/recipe/temperature_sensor.json"), object("type", "minecraft:crafting_shaped",
                "category", "redstone", "pattern", array(" T ", "IRI"),
                "key", object("I", object("item", "minecraft:iron_ingot"), "T", object("item", "meteodyne:thermometer"),
                        "R", object("item", "minecraft:redstone")), "result", object("id", "meteodyne:temperature_sensor", "count", 1)));
        for (String item : new String[]{"thermometer", "temperature_sensor", "wind_gauge", "wind_sensor"}) {
            String ingredient = switch (item) { case "thermometer", "wind_gauge" -> "minecraft:iron_ingot"; case "wind_sensor" -> "meteodyne:wind_gauge"; default -> "meteodyne:thermometer"; };
            write(cache, root.resolve("meteodyne/advancement/recipes/tools/" + item + ".json"),
                    object("parent", "minecraft:recipes/root", "criteria", object("has_material", object("trigger", "minecraft:inventory_changed",
                            "conditions", object("items", array(object("items", ingredient))))),
                            "requirements", array(array("has_material")), "rewards", object("recipes", array("meteodyne:" + item))));
        }
        write(cache, root.resolve("meteodyne/loot_table/blocks/temperature_sensor.json"), object("type", "minecraft:block",
                "pools", array(object("rolls", 1, "entries", array(object("type", "minecraft:item", "name", "meteodyne:temperature_sensor")),
                        "conditions", array(object("condition", "minecraft:survives_explosion"))))));
        write(cache, root.resolve("meteodyne/recipe/wind_gauge.json"), object("type", "minecraft:crafting_shaped",
                "category", "equipment", "pattern", array(" N ", "NCN", " I "),
                "key", object("N", object("item", "minecraft:iron_nugget"), "C", object("item", "minecraft:compass"),
                        "I", object("item", "minecraft:iron_ingot")), "result", object("id", "meteodyne:wind_gauge", "count", 1)));
        write(cache, root.resolve("meteodyne/recipe/wind_sensor.json"), object("type", "minecraft:crafting_shaped",
                "category", "redstone", "pattern", array(" W ", "IRI"),
                "key", object("W", object("item", "meteodyne:wind_gauge"), "I", object("item", "minecraft:iron_ingot"),
                        "R", object("item", "minecraft:redstone")), "result", object("id", "meteodyne:wind_sensor", "count", 1)));
        write(cache, root.resolve("meteodyne/loot_table/blocks/wind_sensor.json"), object("type", "minecraft:block",
                "pools", array(object("rolls", 1, "entries", array(object("type", "minecraft:item", "name", "meteodyne:wind_sensor")),
                        "conditions", array(object("condition", "minecraft:survives_explosion"))))));
        write(cache, root.resolve("minecraft/tags/block/mineable/pickaxe.json"), object("replace", false, "values", array("meteodyne:temperature_sensor", "meteodyne:wind_sensor")));
    }

    private static JsonObject object(Object... entries) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < entries.length; i += 2) { object.add((String) entries[i], GSON.toJsonTree(entries[i + 1])); }
        return object;
    }

    private static JsonArray array(Object... values) {
        JsonArray array = new JsonArray();
        for (Object value : values) { array.add(GSON.toJsonTree(value)); }
        return array;
    }

    private static void write(CachedOutput cache, Path path, JsonElement json) throws IOException {
        write(cache, path, (GSON.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void png(CachedOutput cache, Path path, BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        write(cache, path, bytes.toByteArray());
    }

    private static void write(CachedOutput cache, Path path, byte[] data) throws IOException {
        cache.writeIfNeeded(path, data, Hashing.sha1().hashBytes(data));
    }

    @Override public String getName() { return "Meteodyne weather instruments"; }
}
