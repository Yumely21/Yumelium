package me.jellysquid.mods.sodium.client.gl.device;

import me.jellysquid.mods.sodium.client.gl.functions.DeviceFunctions;
import org.lwjgl.opengl.GLCapabilities;

public interface RenderDevice {
    RenderDevice INSTANCE = new GLRenderDevice();

    CommandList createCommandList();

    static void enterManagedCode() {
        RenderDevice.INSTANCE.makeActive();
    }

    static void exitManagedCode() {
        RenderDevice.INSTANCE.makeInactive();
    }

    void makeActive();
    void makeInactive();

    /**
     * Tells the device that vertex-array / buffer bindings were changed with RAW GL calls behind the
     * {@link me.jellysquid.mods.sodium.client.gl.state.GlStateTracker}'s back, so its cached bindings must be
     * forgotten — otherwise the next {@code bindVertexArray}/{@code bindBuffer} with the cached handle SKIPS the real
     * {@code glBindVertexArray} and the following draw runs on whatever state the raw code left (found as the END
     * "entity shadows corrupt the terrain shadow map" bug: the entity shadow casters unbind the VAO raw — the
     * display-list-compile crash guard — mid shadow pass).
     */
    void notifyExternalStateReset();

    GLCapabilities getCapabilities();

    DeviceFunctions getDeviceFunctions();
}
