package com.yumelium.yumelium.proxy;

public interface IProxy {
    /** Called from the mod's pre-init; client-only setup (event handlers, etc.) lives in {@link ClientProxy}. */
    default void preInit() {
    }
}
