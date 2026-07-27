package com.yumelium.yumelium.client.light;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Drives {@link DynamicLightManager#update} once per client tick.
 */
public class DynamicLightsHandler {
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.isGamePaused()) {
            return;
        }

        DynamicLightManager.INSTANCE.update(mc);
    }
}
