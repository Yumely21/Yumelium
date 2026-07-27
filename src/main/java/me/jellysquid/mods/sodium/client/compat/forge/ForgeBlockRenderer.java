package me.jellysquid.mods.sodium.client.compat.forge;

/**
 * NOTE(yumelium): STUB. Upstream ForgeBlockRenderer bridged Forge's custom block model rendering. The base
 * 1.12.2 port renders via the standard IBakedModel/getQuads path (see BlockRenderer), so init() is a no-op.
 * TODO(yumelium): port the real ForgeBlockRenderer if Forge models needing special handling are required.
 */
public class ForgeBlockRenderer {
    public static void init() {
    }
}
