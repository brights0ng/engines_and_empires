package dev.brights0ng.sablefixes;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.common.Mod;

/**
 * Sable Fixes: fixes for Sable's own behaviour, as mixins (see {@code mixin}). A mod of its own since split step 2b; it
 * requires Sable and nothing of ours, and registers nothing.
 */
@Mod(SableFixes.MODID)
public final class SableFixes {

    public static final String MODID = "sable_fixes";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SableFixes() {
    }
}
