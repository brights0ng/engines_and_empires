// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import java.util.function.ToDoubleFunction;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;
import dev.brights0ng.paradynelib.heat.air.AirParams;
import net.minecraft.world.level.block.state.BlockState;

/** Converts existing driver shares to immutable air-only terms, with unchanged table limits. */
final class LocalAirSourcePower {
    static Heat driven(BlockState state,List<HeatPowerData.Variant> variants,ToDoubleFunction<HeatPowerData.Variant> share) {
        if(variants.size()>Heat.MAX_TERMS) throw new IllegalArgumentException("Driven source term limit exceeded");
        var terms=new ArrayList<HeatTerm>();
        for(var variant:variants) if(variant.inAir()) {
            double fraction=share.applyAsDouble(variant);
            if(!Double.isFinite(fraction) || fraction<0 || fraction>1) throw new IllegalArgumentException("Invalid driven power share");
            if(fraction>0) terms.add(new HeatTerm(AirParams.DEFAULT.gridPower(variant.power()*fraction,variant.radiant()),HeatTables.absoluteLimit(state,variant),variant.maxOffset(),variant.perFace()));
        }
        return terms.isEmpty()?null:new Heat(terms);
    }
    private LocalAirSourcePower() { }
}
