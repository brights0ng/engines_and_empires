// Written by Gemini.
package dev.brights0ng.empire.ledger;

import net.minecraft.world.item.Item;

/**
 * The Administrative Ledger: held to visualize an empire's 3D regions and used to manage settlements and regions
 * (starting with right-clicking a bed to scan and register an {@code AUTO} {@code HOME} region via
 * {@link LedgerInteractions}).
 */
public class AdministrativeLedgerItem extends Item {

    public AdministrativeLedgerItem(Properties properties) {
        super(properties);
    }
}
