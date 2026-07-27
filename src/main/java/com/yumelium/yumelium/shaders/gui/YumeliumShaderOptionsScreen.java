package com.yumelium.yumelium.shaders.gui;

import com.yumelium.yumelium.shaders.ShaderController;
import com.yumelium.yumelium.shaders.ShaderPackManager;
import com.yumelium.yumelium.shaders.pack.ShaderOptionSet;
import com.yumelium.yumelium.shaders.pack.ShaderOptionSet.Entry;
import com.yumelium.yumelium.shaders.pack.ShaderOptionSet.Option;
import com.yumelium.yumelium.shaders.pack.ShaderOptionSet.Screen;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.widgets.FlatButtonWidget;
import me.jellysquid.mods.sodium.client.util.Dim2i;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Yumelium/Iris (M8 v2) — the "Shader Pack Settings" screen for a full OptiFine/Iris option tree: boolean toggles,
 * value/enum options that cycle through allowed values, and {@code [sub-screen]} navigation, with lang-file labels +
 * tooltips. Changes are staged in memory and committed (persist + recompile) on Apply/Done, so cycling a value doesn't
 * recompile on every click. Import/Export use a native file dialog. Styled like {@link YumeliumShaderScreen} — all
 * controls use the flat purple {@link FlatButtonWidget}, not vanilla buttons.
 */
public class YumeliumShaderOptionsScreen extends GuiScreen {
    private static final int ACCENT = 0xFFB4A8F0;
    private static final int PANEL = 0x90281E46;
    private static final int PANEL_HOVER = 0xC0322459;
    private static final int LINK_BG = 0xA0342A5A;
    private static final int FRAME = 0x60000000;
    private static final int ON = 0xFF3F9C5C;
    private static final int OFF = 0xFF5A5270;
    private static final int VALUE_BG = 0xFF463A78;

    private static final int ROW_HEIGHT = 24;

    private final GuiScreen parent;
    private final String packName;
    private ShaderOptionSet options;
    private final List<Screen> nav = new ArrayList<>();

    private boolean dirty;
    private int scrollOffset;
    private int maxScroll;
    private int listX, listW, listTop, listBottom;
    private final List<FlatButtonWidget> buttons = new ArrayList<>();
    private FlatButtonWidget applyButton;

    public YumeliumShaderOptionsScreen(GuiScreen parent) {
        this.parent = parent;
        this.packName = ShaderController.activePack();
    }

    private Screen current() {
        return this.nav.get(this.nav.size() - 1);
    }

    @Override
    public void initGui() {
        this.options = ShaderController.shaderOptions();
        if (this.nav.isEmpty()) {
            this.nav.add(this.options.rootScreen());
        }

        int panelW = Math.min(360, this.width - 40);
        this.listX = (this.width - panelW) / 2;
        this.listW = panelW;
        this.listTop = 46;
        this.listBottom = this.height - 40;

        int contentHeight = current().entries.size() * ROW_HEIGHT;
        this.maxScroll = Math.max(0, contentHeight - (this.listBottom - this.listTop));
        this.scrollOffset = Math.min(this.scrollOffset, this.maxScroll);

        this.buttons.clear();
        int by = this.height - 28;
        int bw = 66;
        // Left group: Back (in a sub-page) or Import/Export (at the root, when the pack has options).
        if (this.nav.size() > 1) {
            this.buttons.add(new FlatButtonWidget(new Dim2i(this.listX, by, bw, 20), "< Back", this::back));
        } else if (!this.options.isEmpty()) {
            this.buttons.add(new FlatButtonWidget(new Dim2i(this.listX, by, bw, 20), "Import", this::importSettings));
            this.buttons.add(new FlatButtonWidget(new Dim2i(this.listX + bw + 4, by, bw, 20), "Export", this::exportSettings));
        }
        // Right group: Apply + Done.
        this.applyButton = new FlatButtonWidget(new Dim2i(this.listX + this.listW - 2 * bw - 4, by, bw, 20), "Apply", this::commit);
        this.buttons.add(this.applyButton);
        this.buttons.add(new FlatButtonWidget(new Dim2i(this.listX + this.listW - bw, by, bw, 20), I18n.format("gui.done"), this::done));
    }

    private void back() {
        this.nav.remove(this.nav.size() - 1);
        this.scrollOffset = 0;
        this.initGui();
    }

    private void done() {
        commit();
        this.mc.displayGuiScreen(this.parent);
    }

    private void commit() {
        if (this.dirty) {
            ShaderController.applyOptionChanges();
            this.dirty = false;
        }
    }

    /** Imports this pack's settings from a text file the user picks, then applies + recompiles (like Iris's Import). */
    private void importSettings() {
        pickFile(FileDialog.LOAD, "Import shader settings", null, file -> {
            ShaderController.shaderOptions().load(file.toPath());
            ShaderController.applyOptionChanges(); // persist to the pack's own file + recompile
            this.dirty = false;
        });
    }

    /** Exports this pack's current settings to a text file the user picks (OptiFine/Iris {@code NAME=value} format). */
    private void exportSettings() {
        pickFile(FileDialog.SAVE, "Export shader settings", ShaderController.activePack() + ".txt",
                file -> ShaderController.shaderOptions().save(file.toPath()));
    }

    /**
     * Shows a native file dialog on a background thread (AWT can't run on the render thread), then runs {@code onPick} on
     * the client thread with the chosen file. No-op if the user cancels. On Windows the dialog is native; if AWT is
     * unavailable it just logs.
     */
    private void pickFile(int mode, String title, String defaultName, java.util.function.Consumer<File> onPick) {
        final Minecraft mc = this.mc;
        final String startDir = ShaderPackManager.instance().shaderpacksDir().toAbsolutePath().toString();
        Thread thread = new Thread(() -> {
            try {
                FileDialog dialog = new FileDialog((Frame) null, title, mode);
                dialog.setDirectory(startDir);
                if (defaultName != null) {
                    dialog.setFile(defaultName);
                }
                dialog.setVisible(true);
                String dir = dialog.getDirectory();
                String file = dialog.getFile();
                dialog.dispose();
                if (dir != null && file != null) {
                    File picked = new File(dir, file);
                    mc.addScheduledTask(() -> {
                        try {
                            onPick.accept(picked);
                        } catch (Throwable t) {
                            SodiumClientMod.logger().error("[Iris] applying imported settings failed", t);
                        }
                    });
                }
            } catch (Throwable t) {
                SodiumClientMod.logger().error("[Iris] file dialog failed (AWT unavailable?)", t);
            }
        }, "Yumelium-ShaderSettings-FileDialog");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        String title = this.nav.size() > 1 ? current().title : "Shader Pack Settings";
        this.drawCenteredString(this.fontRenderer, TextFormatting.BOLD + title, this.width / 2, 14, ACCENT);
        this.drawCenteredString(this.fontRenderer, TextFormatting.GRAY + prettyName(this.packName), this.width / 2, 30, 0xFFFFFF);

        if (this.options.isEmpty()) {
            this.drawCenteredString(this.fontRenderer, TextFormatting.GRAY + "This pack has no configurable options.",
                    this.width / 2, this.height / 2 - 10, 0xFFFFFF);
            renderButtons(mouseX, mouseY, partialTicks);
            return;
        }

        drawRect(this.listX - 4, this.listTop - 4, this.listX + this.listW + 4, this.listBottom + 4, FRAME);

        List<Entry> entries = current().entries;
        boolean inList = mouseX >= this.listX && mouseX <= this.listX + this.listW
                && mouseY >= this.listTop && mouseY <= this.listBottom;
        Option hoveredOption = null;

        enableScissor(this.listX, this.listTop, this.listW, this.listBottom - this.listTop);
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int y0 = this.listTop + i * ROW_HEIGHT - this.scrollOffset;
            int y1 = y0 + ROW_HEIGHT - 2;
            if (y1 < this.listTop || y0 > this.listBottom) {
                continue;
            }
            boolean hovered = inList && mouseY >= y0 && mouseY < y1;

            if (entry.kind == ShaderOptionSet.Kind.EMPTY) {
                continue;
            } else if (entry.kind == ShaderOptionSet.Kind.LINK) {
                drawRect(this.listX, y0, this.listX + this.listW, y1, hovered ? PANEL_HOVER : LINK_BG);
                this.fontRenderer.drawString(entry.target.title, this.listX + 12, y0 + 8, 0xFFFFFFFF);
                this.fontRenderer.drawString(">", this.listX + this.listW - 16, y0 + 8, ACCENT);
            } else {
                Option opt = entry.option;
                drawRect(this.listX, y0, this.listX + this.listW, y1, hovered ? PANEL_HOVER : PANEL);
                this.fontRenderer.drawString(trim(opt.label, this.listW - 96), this.listX + 12, y0 + 8, 0xFFFFFFFF);

                String state = opt.displayValue();
                int boxColor = opt.bool ? (opt.isOn() ? ON : OFF) : (opt.editable() ? VALUE_BG : OFF);
                int boxW = Math.max(38, this.fontRenderer.getStringWidth(state) + 12);
                int boxX = this.listX + this.listW - boxW - 8;
                drawRect(boxX, y0 + 3, boxX + boxW, y1 - 1, boxColor);
                this.fontRenderer.drawString(state, boxX + (boxW - this.fontRenderer.getStringWidth(state)) / 2, y0 + 8, 0xFFFFFFFF);

                if (hovered) {
                    hoveredOption = opt;
                }
            }
        }
        disableScissor();

        drawScrollbar();
        renderButtons(mouseX, mouseY, partialTicks);

        if (hoveredOption != null && !hoveredOption.comment.isEmpty()) {
            this.drawHoveringText(this.fontRenderer.listFormattedStringToWidth(hoveredOption.comment, 220), mouseX, mouseY);
        }
    }

    private void renderButtons(int mouseX, int mouseY, float partialTicks) {
        this.applyButton.setEnabled(this.dirty);
        for (FlatButtonWidget button : this.buttons) {
            button.render(mouseX, mouseY, partialTicks);
        }
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

        if (mouseButton != 0 || this.options.isEmpty()
                || mouseX < this.listX || mouseX > this.listX + this.listW
                || mouseY < this.listTop || mouseY > this.listBottom) {
            return;
        }
        int i = (mouseY - this.listTop + this.scrollOffset) / ROW_HEIGHT;
        List<Entry> entries = current().entries;
        if (i < 0 || i >= entries.size()) {
            return;
        }
        Entry entry = entries.get(i);
        if (entry.kind == ShaderOptionSet.Kind.LINK) {
            this.nav.add(entry.target);
            this.scrollOffset = 0;
            this.initGui();
        } else if (entry.kind == ShaderOptionSet.Kind.OPTION && entry.option.editable()) {
            entry.option.cycle();
            this.dirty = true;
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
            if (this.nav.size() > 1) {
                back();
            } else {
                done();
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private String trim(String s, int maxWidth) {
        if (this.fontRenderer.getStringWidth(s) <= maxWidth) {
            return s;
        }
        return this.fontRenderer.trimStringToWidth(s, maxWidth - this.fontRenderer.getStringWidth("...")) + "...";
    }

    private static String prettyName(String name) {
        return "(built-in)".equals(name) ? "Built-in (default)" : name;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
