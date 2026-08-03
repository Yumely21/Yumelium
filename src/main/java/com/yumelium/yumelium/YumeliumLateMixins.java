package com.yumelium.yumelium;

import net.minecraftforge.fml.common.Loader;
import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Collections;
import java.util.List;

/**
 * MixinBooter LATE mixin loader — the sanctioned way to mixin ANOTHER MOD's classes on Cleanroom.
 *
 * <p>The project's classloader invariant forbids EARLY mixin configs (the three registered via
 * {@code crl.dev.mixin} / the {@code MixinConfigs} manifest) from ever naming other mods' classes: Foundation
 * negative-caches the failed target probe at coremod time and crashes the mod later. LATE configs apply after mod
 * loading, when the target classes exist, so none of that applies — this is the same mechanism compat mods (Fugue,
 * UniversalTweaks) use. MixinBooter (bundled with Cleanroom) discovers implementors of {@link ILateMixinLoader}
 * through FML's ASM data table; no registration call is needed.</p>
 *
 * <p>Deliberately NOT added to the two early registration sites in build.gradle — this config must never load
 * early.</p>
 */
public class YumeliumLateMixins implements ILateMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("yumelium-late.mixins.json");
    }

    @Override
    public boolean shouldMixinConfigQueue(String mixinConfig) {
        // Every mixin in the late config targets Betweenlands renderers; without the mod there is nothing to patch.
        return Loader.isModLoaded("thebetweenlands");
    }
}
