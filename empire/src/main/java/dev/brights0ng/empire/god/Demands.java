// Written by Claude.
package dev.brights0ng.empire.god;

import java.util.List;

import javax.annotation.Nullable;

import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.DemandLine;
import dev.brights0ng.empire.rules.DemandRoller;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/** An empire's demand, between its record, its god and the roller. */
public final class Demands {

    /** Rolls the empire's next demand from its god, its dominion and the demands it has paid so far. */
    public static void rollNext(EmpireRecord record, GodDefinition god) {
        record.setCurrentDemand(DemandRoller.roll(record.id().getMostSignificantBits(), record.id().getLeastSignificantBits(),
                record.sacrificeCount(), record.state().dominion(), god.tiers()));
    }

    /** Whether an empire other than {@code self} already has this god. */
    public static boolean heldByAnother(EmpireSavedData data, ResourceLocation god, @Nullable EmpireRecord self) {
        for (EmpireRecord record : data.allEmpires()) {
            if (record != self && god.equals(record.god())) {
                return true;
            }
        }
        return false;
    }

    /** "&lt;god&gt;: &lt;paid&gt;/&lt;required&gt; &lt;item&gt; · ...", fluids as "&lt;paid&gt;/&lt;required&gt; mB &lt;fluid&gt;". */
    public static Component status(Component godName, List<DemandLine> lines) {
        MutableComponent joined = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                joined.append(" · ");
            }
            joined.append(line(lines.get(i)));
        }
        return Component.translatable("message.empire.demand.status", godName, joined);
    }

    public static Component line(DemandLine line) {
        String key = line.kind() == DemandLine.Kind.FLUID ? "message.empire.demand.fluid_line" : "message.empire.demand.line";
        return Component.translatable(key, line.paid(), line.required(), Offerings.name(line.kind(), line.ids()));
    }

    private Demands() {
    }
}
