// Written by Claude.
package dev.brights0ng.empire.god;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import dev.brights0ng.empire.rules.DemandLine;
import dev.brights0ng.empire.rules.DemandOption;
import dev.brights0ng.empire.rules.SacrificeCurve;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/**
 * A god an empire may be founded under, from {@code data/<namespace>/empire/god/<path>.json}:
 * <pre>
 * {"accepts_rotting_meat": false,
 *  "tiers": {"1": [opt], "2": [opt, opt], ..., "5": [opt, opt]}}
 * </pre>
 * where an option is {@code {"item": id}}, {@code {"items": [ids]}}, {@code {"tag": id}} or {@code {"fluid": id}}, each
 * with a {@code "multiplier"} from 0 to 1. Tier 1's option is the god's founding meat. Its name is the lang key
 * {@code god.<namespace>.<path>}.
 *
 * @param tiers the options of tiers 1-5, in order
 */
public record GodDefinition(ResourceLocation id, boolean acceptsRottingMeat, List<List<DemandOption>> tiers) {

    public GodDefinition {
        List<List<DemandOption>> copy = new ArrayList<>(tiers.size());
        for (List<DemandOption> tier : tiers) {
            copy.add(List.copyOf(tier));
        }
        tiers = List.copyOf(copy);
    }

    public Component displayName() {
        return Component.translatable(nameKey(id));
    }

    public static String nameKey(ResourceLocation id) {
        return "god." + id.getNamespace() + "." + id.getPath();
    }

    /** Tier 1's options: the meat the god is founded (and adopted) with. */
    public List<DemandOption> foundingMeat() {
        return tiers.isEmpty() ? List.of() : tiers.get(0);
    }

    /** Reads a god's file; throws {@link JsonParseException} (with what is wrong) on a malformed one. */
    public static GodDefinition parse(ResourceLocation id, JsonElement json) {
        JsonObject root = GsonHelper.convertToJsonObject(json, "god");
        boolean rotting = GsonHelper.getAsBoolean(root, "accepts_rotting_meat", false);
        JsonObject tiersJson = GsonHelper.getAsJsonObject(root, "tiers");
        List<List<DemandOption>> tiers = new ArrayList<>(SacrificeCurve.TIERS);
        for (int tier = 1; tier <= SacrificeCurve.TIERS; tier++) {
            List<DemandOption> options = new ArrayList<>();
            if (tiersJson.has(Integer.toString(tier))) {
                JsonArray array = GsonHelper.getAsJsonArray(tiersJson, Integer.toString(tier));
                for (JsonElement element : array) {
                    options.add(option(GsonHelper.convertToJsonObject(element, "tier " + tier + " option")));
                }
            }
            tiers.add(options);
        }
        if (tiers.get(0).isEmpty()) {
            throw new JsonParseException("tier 1 (the founding meat) has no option");
        }
        return new GodDefinition(id, rotting, tiers);
    }

    private static DemandOption option(JsonObject json) {
        double multiplier = GsonHelper.getAsDouble(json, "multiplier");
        DemandLine.Kind kind;
        List<String> ids = new ArrayList<>();
        if (json.has("item")) {
            kind = DemandLine.Kind.ITEM;
            ids.add(id(GsonHelper.getAsString(json, "item")));
        } else if (json.has("items")) {
            kind = DemandLine.Kind.ITEMS;
            for (JsonElement element : GsonHelper.getAsJsonArray(json, "items")) {
                ids.add(id(GsonHelper.convertToString(element, "item")));
            }
        } else if (json.has("tag")) {
            kind = DemandLine.Kind.TAG;
            ids.add(id(GsonHelper.getAsString(json, "tag")));
        } else if (json.has("fluid")) {
            kind = DemandLine.Kind.FLUID;
            ids.add(id(GsonHelper.getAsString(json, "fluid")));
        } else {
            throw new JsonParseException("an option needs one of item, items, tag or fluid: " + json);
        }
        try {
            return new DemandOption(kind, ids, multiplier);
        } catch (IllegalArgumentException e) {
            throw new JsonParseException(e.getMessage());
        }
    }

    /** A valid resource location, written in full ({@code minecraft:} added when there is no namespace). */
    private static String id(String raw) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) {
            throw new JsonParseException("not a valid id: " + raw);
        }
        return id.toString();
    }
}
