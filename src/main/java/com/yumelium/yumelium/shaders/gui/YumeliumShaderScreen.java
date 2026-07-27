package com.yumelium.yumelium.shaders.gui;

import com.yumelium.yumelium.shaders.ShaderController;
import com.yumelium.yumelium.shaders.ShaderPackManager;
import me.jellysquid.mods.sodium.client.gui.URLUtils;
import me.jellysquid.mods.sodium.client.gui.widgets.FlatButtonWidget;
import me.jellysquid.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Yumelium/Iris (M8) — a modern shader-pack selection screen, styled to match the engine's Sodium options GUI (dark
 * purple panels + {@code 0xB4A8F0} accent) and laid out like Iris's own {@code ShaderPackScreen}: a scrollable list of
 * available packs with hover/selection/active states, plus Open-Folder / Apply / Done actions.
 *
 * <p>Iris's real screen can't be ported verbatim (it targets 1.16.5+ and its modern widget framework), so this recreates
 * the look + flow on 1.12.2 using the engine's existing widgets. Row 0 is a "Shaders: Off" entry; the rest are the packs
 * ({@link ShaderPackManager#BUILTIN_NAME} + anything in {@code shaderpacks/}). Selecting a row highlights it; Apply
 * activates the selection via {@link ShaderController} (rebuild pipeline + reload renderer).</p>
 */
public class YumeliumShaderScreen extends GuiScreen {
    // Palette (matches SodiumOptionsGUI).
    private static final int ACCENT = 0xFFB4A8F0;
    private static final int PANEL = 0x90281E46;
    private static final int PANEL_HOVER = 0xC0322459;
    private static final int PANEL_SELECTED = 0xF0413472;
    private static final int FRAME = 0x60000000;

    private static final int ROW_HEIGHT = 24;

    private final GuiScreen parent;

    private List<String> packs = new ArrayList<>();
    private int entryCount;          // packs + 1 (row 0 = "off")
    private int selected;            // currently-highlighted row
    private int scrollOffset;
    private int maxScroll;

    private int listX, listW, listTop, listBottom;

    private final List<FlatButtonWidget> buttons = new ArrayList<>();
    private FlatButtonWidget applyButton;

    public YumeliumShaderScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.packs = ShaderController.availablePacks();
        this.entryCount = this.packs.size() + 1;
        this.selected = appliedIndex();

        int panelW = Math.min(360, this.width - 40);
        this.listX = (this.width - panelW) / 2;
        this.listW = panelW;
        this.listTop = 44;
        this.listBottom = this.height - 40;

        int contentHeight = this.entryCount * ROW_HEIGHT;
        this.maxScroll = Math.max(0, contentHeight - (this.listBottom - this.listTop));
        this.scrollOffset = Math.min(this.scrollOffset, this.maxScroll);

        // Bottom action bar.
        this.buttons.clear();
        int by = this.height - 30;
        this.buttons.add(new FlatButtonWidget(new Dim2i(this.listX, by, 92, 20),
                TextFormatting.RESET + "Open Folder", this::openFolder));
        this.buttons.add(new FlatButtonWidget(new Dim2i(this.listX + 96, by, 90, 20),
                TextFormatting.RESET + "Settings...", this::openSettings));
        this.applyButton = new FlatButtonWidget(new Dim2i(this.listX + this.listW - 128, by, 62, 20),
                TextFormatting.RESET + "Apply", this::applySelection);
        this.buttons.add(this.applyButton);
        this.buttons.add(new FlatButtonWidget(new Dim2i(this.listX + this.listW - 62, by, 62, 20),
                I18n.format("gui.done"), this::close));
    }

    /** The list row that reflects the live state: 0 when shaders are off, else 1 + the active pack's index. */
    private int appliedIndex() {
        if (!ShaderController.isEnabled()) {
            return 0;
        }
        int i = this.packs.indexOf(ShaderController.activePack());
        return i >= 0 ? i + 1 : 0;
    }

    private String rowLabel(int row) {
        if (row == 0) {
            return "Shaders: Off";
        }
        String name = this.packs.get(row - 1);
        return ShaderPackManager.BUILTIN_NAME.equals(name) ? "Built-in (default)" : name;
    }

    private void applySelection() {
        if (this.selected == 0) {
            ShaderController.setEnabled(false);
        } else {
            ShaderController.setPack(this.packs.get(this.selected - 1));
            ShaderController.setEnabled(true);
        }
    }

    private void openFolder() {
        URLUtils.open(ShaderPackManager.instance().shaderpacksDir().toAbsolutePath().toString());
    }

    private void openSettings() {
        this.mc.displayGuiScreen(new YumeliumShaderOptionsScreen(this));
    }

    private void close() {
        this.mc.displayGuiScreen(this.parent);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        // Header.
        this.drawCenteredString(this.fontRenderer, TextFormatting.BOLD + "Shader Packs", this.width / 2, 16, ACCENT);
        int applied = appliedIndex();
        String status = applied == 0 ? TextFormatting.GRAY + "Shaders are off"
                : TextFormatting.GRAY + "Active: " + TextFormatting.WHITE + rowLabel(applied);
        this.drawCenteredString(this.fontRenderer, status, this.width / 2, 30, 0xFFFFFF);

        // List frame.
        drawRect(this.listX - 4, this.listTop - 4, this.listX + this.listW + 4, this.listBottom + 4, FRAME);

        // Rows, clipped to the list viewport so scrolled entries don't spill over the header/buttons.
        boolean inList = mouseX >= this.listX && mouseX <= this.listX + this.listW
                && mouseY >= this.listTop && mouseY <= this.listBottom;
        enableScissor(this.listX, this.listTop, this.listW, this.listBottom - this.listTop);
        for (int i = 0; i < this.entryCount; i++) {
            int y0 = this.listTop + i * ROW_HEIGHT - this.scrollOffset;
            int y1 = y0 + ROW_HEIGHT - 2;
            if (y1 < this.listTop || y0 > this.listBottom) {
                continue;
            }
            boolean hovered = inList && mouseY >= y0 && mouseY < y1;
            int bg = this.selected == i ? PANEL_SELECTED : (hovered ? PANEL_HOVER : PANEL);
            drawRect(this.listX, y0, this.listX + this.listW, y1, bg);
            if (i == applied) {
                drawRect(this.listX, y0, this.listX + 3, y1, ACCENT); // active accent bar
                this.fontRenderer.drawString(TextFormatting.ITALIC + "active",
                        this.listX + this.listW - this.fontRenderer.getStringWidth("active") - 8, y0 + 8, 0xFF9C8FE0);
            }
            int textColor = i == 0 && applied != 0 ? 0xFFBFBFBF : 0xFFFFFFFF;
            this.fontRenderer.drawString(rowLabel(i), this.listX + 12, y0 + 8, textColor);
        }
        disableScissor();

        drawScrollbar();

        // Buttons (unclipped). Apply is only enabled when the selection differs from what's applied.
        this.applyButton.setEnabled(this.selected != applied);
        for (FlatButtonWidget button : this.buttons) {
            button.render(mouseX, mouseY, partialTicks);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawScrollbar() {
        if (this.maxScroll <= 0) {
            return;
        }
        int trackHeight = this.listBottom - this.listTop;
        int contentHeight = trackHeight + this.maxScroll;
        int thumbHeight = Math.max(16, trackHeight * trackHeight / contentHeight);
        int thumbY = this.listTop + (int) ((long) (trackHeight - thumbHeight) * this.scrollOffset / this.maxScroll);
        int barX = this.listX + this.listW + 1;
        drawRect(barX, this.listTop, barX + 3, this.listBottom, 0x40000000);
        drawRect(barX, thumbY, barX + 3, thumbY + thumbHeight, ACCENT);
    }

    private void enableScissor(int x, int y, int w, int h) {
        ScaledResolution sr = new ScaledResolution(this.mc);
        int scale = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * scale, this.mc.displayHeight - ((y + h) * scale), w * scale, h * scale);
    }

    private void disableScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        for (FlatButtonWidget button : this.buttons) {
            if (button.mouseClicked(mouseX, mouseY, mouseButton)) {
                return;
            }
        }

        if (mouseButton == 0 && mouseX >= this.listX && mouseX <= this.listX + this.listW
                && mouseY >= this.listTop && mouseY <= this.listBottom) {
            int row = (mouseY - this.listTop + this.scrollOffset) / ROW_HEIGHT;
            if (row >= 0 && row < this.entryCount) {
                this.selected = row;
            }
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && this.maxScroll > 0) {
            this.scrollOffset = clamp(this.scrollOffset - Integer.signum(wheel) * ROW_HEIGHT, 0, this.maxScroll);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            close();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
