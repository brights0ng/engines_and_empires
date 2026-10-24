// Written by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class CivilianLinesTest {

    @Test
    void pickNeverRepeatsTheLastLine() {
        for (CivilianLines.Category c : CivilianLines.Category.values()) {
            SplittableRandom random = new SplittableRandom(c.ordinal() + 1L);
            int last = -1;
            for (int i = 0; i < 1_000; i++) {
                int next = CivilianLines.pick(c, random, last);
                assertTrue(next >= 0 && next < c.count(), c + " picked " + next);
                assertNotEquals(last, next, c.name());
                last = next;
            }
        }
    }

    @Test
    void keysHaveTheExpectedShape() {
        assertEquals("civilian.empire.line.greeting.3", CivilianLines.key(CivilianLines.Category.GREETING, 3));
        assertEquals("civilian.empire.talk.2.1", CivilianLines.talkKey(2, 1));
    }

    @Test
    void everyKeyIsInTheLangFile() throws Exception {
        JsonObject lang;
        try (InputStream in = CivilianLinesTest.class.getResourceAsStream("/assets/empire/lang/en_us.json")) {
            assertNotNull(in, "en_us.json on the classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (CivilianLines.Category c : CivilianLines.Category.values()) {
            for (int i = 0; i < c.count(); i++) {
                String key = CivilianLines.key(c, i);
                assertTrue(lang.has(key), key);
            }
        }
        for (int e = 0; e < CivilianLines.TALK_LENGTHS.length; e++) {
            for (int l = 0; l < CivilianLines.TALK_LENGTHS[e]; l++) {
                String key = CivilianLines.talkKey(e, l);
                assertTrue(lang.has(key), key);
            }
        }
    }
}
