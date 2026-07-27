package com.yumelium.yumelium;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Coremod entry point. On Cleanroom, mixin configs are registered via the
 * {@code MixinConfigs} manifest attribute (build) and the {@code crl.dev.mixin}
 * system property (dev runs), so this plugin only needs to exist as the coremod
 * marker. Keep the methods returning null unless a real ASM transformer is added.
 */
@IFMLLoadingPlugin.Name("Yumelium")
@IFMLLoadingPlugin.MCVersion("1.12.2")
public class YumeliumLoadingPlugin implements IFMLLoadingPlugin {
    // NvidiaShaderCacheGuard.install() used to run here while the load-time nvoglv64 crash was blamed on the
    // driver's shader disk cache. The hs_err stack later proved the REAL cause: drawPlayerShadowCaster rendered the
    // vanilla player model (display-list recording through CLIENT vertex arrays) with Sodium's region VBO/VAO still
    // bound — fixed at the call site in SodiumWorldRenderer. The guard is kept in-tree (proven able to disable the
    // cache in-process) but no longer engaged; the cache is innocent and useful.


    @Override
    public @Nullable String[] getASMTransformerClass() {
        return null;
    }

    @Override
    public @Nullable String getModContainerClass() {
        return null;
    }

    @Override
    public @Nullable String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> map) {
        // Auto-correct known-incompatible third-party config values (VintageFix entity_disappearing, FermiumASM
        // loader-surgery toggles) as early as possible — before mixin configs are applied, so VintageFix's mixin
        // plugin reads the corrected file in this same launch. See ThirdPartyConfigEnforcer for the rules.
        Object mcLocation = map.get("mcLocation");
        com.yumelium.yumelium.compat.ThirdPartyConfigEnforcer.enforceAll(
                mcLocation instanceof java.io.File ? (java.io.File) mcLocation : null);
    }

    @Override
    public @Nullable String getAccessTransformerClass() {
        return null;
    }
}
