// Written by Claude.
package dev.brights0ng.empire.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.god.GodDefinition;
import dev.brights0ng.empire.god.Gods;
import dev.brights0ng.empire.god.Offerings;
import dev.brights0ng.empire.rules.DemandOption;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The gods' data pack: every god loads, and everything they ask for exists in this game. */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class GodDataGameTests {

    private static final Set<String> NINE_GODS = Set.of("iron_aurochs", "sunblessed_ram", "pale_stag", "deep_boar",
            "marsh_harrier", "silt_dredger", "silver_current", "carrion_crow", "sun_scale");

    @GameTest(template = GameTestStructures.EMPTY)
    public static void allNineGodsLoad(GameTestHelper helper) {
        Set<String> loaded = new TreeSet<>();
        for (GodDefinition god : Gods.all()) {
            if (god.id().getNamespace().equals(Empire.MODID)) {
                loaded.add(god.id().getPath());
            }
        }
        helper.assertTrue(loaded.equals(new TreeSet<>(NINE_GODS)), "Empire's gods should be " + new TreeSet<>(NINE_GODS)
                + ", loaded " + loaded);
        for (String path : NINE_GODS) {
            GodDefinition god = Gods.get(Empire.id(path));
            for (int tier = 1; tier <= 5; tier++) {
                int expected = tier == 1 ? 1 : 2;
                helper.assertTrue(god.tiers().get(tier - 1).size() == expected,
                        path + " should have " + expected + " option(s) in tier " + tier + ", has " + god.tiers().get(tier - 1));
            }
            helper.assertTrue(god.acceptsRottingMeat() == path.equals("carrion_crow"),
                    path + ": only the Carrion Crow accepts rotting meat");
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void everyItemTagAndFluidTheGodsAskForExists(GameTestHelper helper) {
        List<String> missing = new ArrayList<>();
        for (GodDefinition god : Gods.all()) {
            for (List<DemandOption> tier : god.tiers()) {
                for (DemandOption option : tier) {
                    for (String id : option.ids()) {
                        boolean found = switch (option.kind()) {
                            case ITEM, ITEMS -> Offerings.item(id).isPresent();
                            case TAG -> BuiltInRegistries.ITEM.getTag(Offerings.tag(id))
                                    .map(set -> set.size() > 0).orElse(false);
                            case FLUID -> Offerings.fluid(id) != null;
                        };
                        if (!found) {
                            missing.add(god.id() + ": " + option.kind() + " " + id);
                        }
                    }
                }
            }
        }
        helper.assertTrue(missing.isEmpty(), "unknown (or empty) ids in the gods' demands: " + missing);
        helper.succeed();
    }

    /** The server loads every mod's en_us (NeoForge's LanguageHook), so a god without a name shows up here. */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void everyGodHasAName(GameTestHelper helper) {
        List<String> unnamed = new ArrayList<>();
        for (GodDefinition god : Gods.all()) {
            if (!Language.getInstance().has(GodDefinition.nameKey(god.id()))) {
                unnamed.add(GodDefinition.nameKey(god.id()));
            }
        }
        helper.assertTrue(unnamed.isEmpty(), "gods without a lang name: " + unnamed);
        helper.assertTrue(Gods.get(Empire.id("sun_scale")).displayName().getString().equals("The Sun-Scale"),
                "the Sun-Scale's name should read The Sun-Scale");
        helper.succeed();
    }
}
