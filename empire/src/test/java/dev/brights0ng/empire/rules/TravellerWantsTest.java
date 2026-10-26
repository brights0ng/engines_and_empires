// Written by Claude; extended with citizen food hand-over language checks by ChatGPT.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class TravellerWantsTest {

    @Test
    void sixWantsInOrder() {
        List<String> ids = TravellerWants.WANTS.stream().map(TravellerWants.Want::itemId).toList();
        assertEquals(List.of("minecraft:bread", "minecraft:baked_potato", "minecraft:cooked_porkchop",
                "minecraft:cooked_mutton", "minecraft:sweet_berries", "minecraft:cooked_cod"), ids);
        List<Integer> counts = TravellerWants.WANTS.stream().map(TravellerWants.Want::count).toList();
        assertEquals(List.of(3, 4, 2, 2, 8, 3), counts);
    }

    @Test
    void countsArePositive() {
        for (TravellerWants.Want want : TravellerWants.WANTS) {
            assertTrue(want.count() > 0, want.toString());
        }
    }

    @Test
    void pickStaysInRange() {
        SplittableRandom random = new SplittableRandom(7);
        boolean[] seen = new boolean[TravellerWants.WANTS.size()];
        for (int i = 0; i < 1_000; i++) {
            int pick = TravellerWants.pick(random);
            assertTrue(pick >= 0 && pick < TravellerWants.WANTS.size(), "picked " + pick);
            seen[pick] = true;
        }
        for (int i = 0; i < seen.length; i++) {
            assertTrue(seen[i], "want " + i + " is picked");
        }
    }

    @Test
    void wantKeys() {
        assertEquals("civilian.empire.traveller.want.bread", TravellerWants.wantKey(0));
        assertEquals("civilian.empire.traveller.want.baked_potato", TravellerWants.wantKey(1));
        assertEquals("civilian.empire.traveller.want.cooked_porkchop", TravellerWants.wantKey(2));
        assertEquals("civilian.empire.traveller.want.cooked_mutton", TravellerWants.wantKey(3));
        assertEquals("civilian.empire.traveller.want.sweet_berries", TravellerWants.wantKey(4));
        assertEquals("civilian.empire.traveller.want.cooked_cod", TravellerWants.wantKey(5));
    }

    @Test
    void everyWantKeyIsInTheLangFile() throws Exception {
        JsonObject lang;
        try (InputStream in = TravellerWantsTest.class.getResourceAsStream("/assets/empire/lang/en_us.json")) {
            assertNotNull(in, "en_us.json on the classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (int i = 0; i < TravellerWants.WANTS.size(); i++) {
            assertTrue(lang.has(TravellerWants.wantKey(i)), TravellerWants.wantKey(i));
        }
        assertEquals("Thank you, that'll keep me going.", lang.get("civilian.empire.citizen.food_given").getAsString());
        assertEquals("I can't carry any more.", lang.get("civilian.empire.citizen.pockets_full").getAsString());
    }
}
