package com.yumelium.yumelium;

import com.yumelium.yumelium.proxy.IProxy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Reference.MOD_ID, name = Reference.MOD_NAME, version = Reference.VERSION)
public class YumeliumMod {

    public static final Logger LOGGER = LogManager.getLogger(Reference.MOD_NAME);

    /**
     * Kill switch: floor near-zero alpha (1–8 out of 255) to exactly 0 on BLOCK-ATLAS sprite data at load time
     * (MixinTextureUtilMipmap.yumelium$floorNearZeroAlpha). Fixes Betweenlands "transparent" texels shipped at
     * alpha 1–8/255 (algae_N.png has NO true zeros at all) rendering as a translucent film — and punching depth
     * holes in late depth-tested draws (BL RenderWorldLast gas clouds) — under the shader pipeline's tiny discard
     * thresholds (terrain a &lt;= 0.00001, gbuffers_water GREATER 0.0001). Vanilla's fixed-function alpha test
     * (0.1) never displayed these texels anywhere, so flooring them is vanilla-appearance-exact. Audited
     * 2026-08-04 over 902 BL + 500 vanilla block textures: no legitimate texture depends on alpha 1–8 being drawn.
     */
    public static boolean ATLAS_ALPHA_FLOOR = true;

    /**
     * True while {@code Minecraft.textureMapBlocks} is inside {@code loadSprites} (set by
     * MixinTextureMapAtlasScope). Volatile because VintageFix runs sprite-frame loads — and therefore the alpha
     * floor — on ForkJoinPool worker threads inside that bracket.
     */
    public static volatile boolean BLOCK_ATLAS_LOADING = false;

    @SidedProxy(modId = Reference.MOD_ID,
            clientSide = "com.yumelium.yumelium.proxy.ClientProxy",
            serverSide = "com.yumelium.yumelium.proxy.CommonProxy")
    public static IProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Hello From {}!", Reference.MOD_NAME);
        proxy.preInit();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        // FermiumASM registers its SRG-hardcoded F3 stats handler in loadComplete (AFTER postInit) — install a
        // one-shot first-tick fixer that pulls it off the bus in dev runtimes (production is left untouched).
        if (net.minecraftforge.fml.common.FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            com.yumelium.yumelium.compat.FermiumAsmCompat.install();
        }
    }

}
