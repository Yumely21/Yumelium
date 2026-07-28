package com.yumelium.yumelium.proxy;

import com.yumelium.yumelium.client.YumeliumDebugOverlay;
import com.yumelium.yumelium.client.YumeliumHudOverlay;
import com.yumelium.yumelium.client.ctm.CtmStitchHandler;
import com.yumelium.yumelium.client.light.DynamicLightsHandler;
import com.yumelium.yumelium.client.nvidium.NvidiumCapabilityProbe;
import com.yumelium.yumelium.client.shaders.IrisCapabilityProbe;
import com.yumelium.yumelium.client.zoom.ZoomHandler;
import com.yumelium.yumelium.shaders.IrisKeyHandler;
import com.yumelium.yumelium.shaders.ShaderPackManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;

public class ClientProxy implements IProxy {
    /**
     * Vanilla 1.12.2 makes water block THREE light levels per block ({@code BlockLiquid} sets lightOpacity 3), so sky
     * light reaches 0 about five blocks under the surface and everything below renders pitch black once the water casts
     * a shadow (see UNDERWATER_SHAFTS — before that, terrain got undimmed direct sun and the attenuation never showed).
     * 1.13+ — and therefore the 1.20.1 build this port is being matched against — attenuates by 1 per block instead, so
     * deep water stays lit. Match that.
     *
     * <p>NOTE: light levels are baked into chunk data, so ALREADY-GENERATED chunks keep their old (dark) values until
     * they are relit; the change shows immediately only in newly generated/lit terrain.</p>
     *
     * <p>DISABLED — measured against 1.20.1, where deep water darkens ENTITIES but NOT terrain. So the entity dimming is
     * correct vanilla behaviour worth keeping (this flag would wrongly remove it), and our terrain darkening came from
     * somewhere else: the water shadow added with UNDERWATER_SHAFTS replaces direct sun with shadowcolor0. Fixed there
     * instead, by keeping that transmission colour near white.</p>
     */
    private static final boolean MATCH_MODERN_WATER_LIGHT_ATTENUATION = false;

    @Override
    public void preInit() {
        if (MATCH_MODERN_WATER_LIGHT_ATTENUATION) {
            net.minecraft.init.Blocks.WATER.setLightOpacity(1);
            net.minecraft.init.Blocks.FLOWING_WATER.setLightOpacity(1);
        }
        MinecraftForge.EVENT_BUS.register(new YumeliumDebugOverlay());
        MinecraftForge.EVENT_BUS.register(new YumeliumHudOverlay());
        MinecraftForge.EVENT_BUS.register(new DynamicLightsHandler());

        ClientRegistry.registerKeyBinding(ZoomHandler.ZOOM_KEY);
        MinecraftForge.EVENT_BUS.register(ZoomHandler.instance());

        // Nvidium port — M0 go/no-go capability probe (runs once, logs a verdict).
        MinecraftForge.EVENT_BUS.register(new NvidiumCapabilityProbe());

        // Iris/Oculus shader-pipeline port — M0 go/no-go feasibility probe (runs once, logs a verdict).
        MinecraftForge.EVENT_BUS.register(new IrisCapabilityProbe());

        // Iris/Oculus shader-pipeline port — M1 shader-pack loader: discover shaderpacks/ + the built-in pack, log them.
        ShaderPackManager.instance().discoverAndLog();

        // Restore the persisted shader state (enabled + active pack) so a restart keeps the user's selection.
        com.yumelium.yumelium.shaders.ShaderController.restoreState();

        // Iris/Oculus shader-pipeline port — the standard Iris keybinds (rebindable in Options → Controls):
        // K = toggle shaders, R = reload shaders, O = shader pack selection screen, plus N = cycle pack (our extra).
        ClientRegistry.registerKeyBinding(IrisKeyHandler.TOGGLE_KEY);
        ClientRegistry.registerKeyBinding(IrisKeyHandler.RELOAD_KEY);
        ClientRegistry.registerKeyBinding(IrisKeyHandler.PACK_SCREEN_KEY);
        ClientRegistry.registerKeyBinding(IrisKeyHandler.CYCLE_PACK_KEY);
        MinecraftForge.EVENT_BUS.register(IrisKeyHandler.instance());

        // Connected textures + soul fire — TEMPORARILY DISABLED. Both re-texture blocks via the 47-tile CTM set, whose
        // frames sit on the very pixel edge and bleed into a faint seam at mipmap distances (a UV inset can't fix it
        // without also erasing legitimate frames — the tile set itself needs a transparent margin). Not registering the
        // stitch hooks means the tiles are never put in the atlas, so CtmRegistry.isActive()/SoulFireTextures.isAvailable()
        // both stay false and the mesher applies neither. Re-register these (and flip SOUL_FIRE_EMULATION in BlockRenderer)
        // once the tile set is regenerated. All the code is kept.
        // MinecraftForge.EVENT_BUS.register(new CtmStitchHandler());
        // MinecraftForge.EVENT_BUS.register(new com.yumelium.yumelium.client.soulfire.SoulFireStitchHandler());

        // Custom FANCY cloud renderer (replaces vanilla via the provider's IRenderHandler): exposed-face + back-face
        // culled + static-VBO clouds — removes vanilla's diagonal/grid seams and rebuilds nothing per frame.
        MinecraftForge.EVENT_BUS.register(new com.yumelium.yumelium.client.cloud.CloudRendererRegistrar());

        // The Betweenlands: its FBO WorldShader blits over our composite output in its dimension — a per-tick
        // reflection handle yields it while our shaders are ON and restores the user's setting when they go OFF.
        // (Must NOT be a mixin: early configs probing mod classes poison Foundation's classloader — see the class doc.)
        if (net.minecraftforge.fml.common.Loader.isModLoaded("thebetweenlands")) {
            MinecraftForge.EVENT_BUS.register(new com.yumelium.yumelium.compat.BetweenlandsCompat());
        }
    }
}
