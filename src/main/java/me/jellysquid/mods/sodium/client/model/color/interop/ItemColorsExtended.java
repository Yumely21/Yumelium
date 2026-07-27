package me.jellysquid.mods.sodium.client.model.color.interop;

import net.minecraft.client.renderer.color.IItemColor;
import net.minecraft.item.ItemStack;

public interface ItemColorsExtended {
    IItemColor sodium$getColorProvider(ItemStack stack);
}
