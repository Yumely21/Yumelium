package me.jellysquid.mods.sodium.mixin.features.options;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import net.minecraft.client.settings.GameSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Makes vanilla's cloud rendering honour Yumelium's "Clouds" option ({@code quality.enableClouds}) and cloud-quality
 * setting. {@code shouldRenderClouds()} is what the world renderer consults (0 = off, 1 = fast, 2 = fancy).
 */
@Mixin(GameSettings.class)
public class MixinGameOptions {
    @Shadow
    public int renderDistanceChunks;

    @Shadow
    public boolean fancyGraphics;

    /**
     * @author Yumelium
     * @reason Implement the cloud rendering toggle / quality option
     */
    @Overwrite
    public int shouldRenderClouds() {
        SodiumGameOptions options = SodiumClientMod.options();

        if (this.renderDistanceChunks < 4 || !options.quality.enableClouds) {
            return 0;
        }

        return options.quality.cloudQuality.isFancy(this.fancyGraphics) ? 2 : 1;
    }
}
