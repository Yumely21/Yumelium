package com.yumelium.yumelium.client.cloud;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Installs {@link YumeliumCloudRenderer} onto the client world's provider so it replaces vanilla's fancy-cloud rendering
 * (Forge routes clouds to a provider's {@code IRenderHandler} when one is set — {@code FMLClientHandler.renderClouds}
 * then returns true and vanilla's {@code renderCloudsFancy} is skipped entirely).
 */
public class CloudRendererRegistrar {
    private static final YumeliumCloudRenderer RENDERER = new YumeliumCloudRenderer();
    private static boolean logged = false;

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.getWorld().isRemote && event.getWorld().provider != null) {
            event.getWorld().provider.setCloudRenderer(RENDERER);
            if (!logged) {
                logged = true;
                SodiumClientMod.logger().info("[Yumelium] custom cloud renderer installed (replaces vanilla fancy clouds)");
            }
        }
    }
}
