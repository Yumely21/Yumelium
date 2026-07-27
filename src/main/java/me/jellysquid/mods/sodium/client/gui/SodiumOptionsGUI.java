package me.jellysquid.mods.sodium.client.gui;

import com.yumelium.yumelium.shaders.gui.YumeliumShaderScreen;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.options.*;
import me.jellysquid.mods.sodium.client.gui.options.control.Control;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlElement;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import me.jellysquid.mods.sodium.client.gui.utils.Drawable;
import me.jellysquid.mods.sodium.client.gui.utils.Element;
import me.jellysquid.mods.sodium.client.gui.widgets.FlatButtonWidget;
import me.jellysquid.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

public class SodiumOptionsGUI extends GuiScreen {

    // Modern layout: a left navigation sidebar (branding + page list) with the option content to its right.
    private static final int SIDEBAR_WIDTH = 125;
    // Top/bottom of the scrollable option content viewport.
    private static final int CONTENT_TOP = 28;
    private static final int CONTENT_BOTTOM_MARGIN = 32;

    private int scrollOffset;
    private int maxScroll;

    private final List<Element> children = new CopyOnWriteArrayList<>();

    private final List<OptionPage> pages = new ArrayList<>();

    private final List<ControlElement<?>> controls = new ArrayList<>();
    private final List<Drawable> drawable = new ArrayList<>();

    public final GuiScreen prevScreen;

    private OptionPage currentPage;

    private FlatButtonWidget applyButton, closeButton, undoButton;
    private FlatButtonWidget shadersButton;

    private boolean hasPendingChanges;
    private ControlElement<?> hoveredElement;

    public SodiumOptionsGUI(GuiScreen prevScreen) {
        super();

        this.prevScreen = prevScreen;

        this.pages.add(SodiumGameOptionPages.general());
        this.pages.add(SodiumGameOptionPages.quality());
        this.pages.add(SodiumGameOptionPages.advanced());
        this.pages.add(SodiumGameOptionPages.performance());
        this.pages.add(SodiumGameOptionPages.yumeliumPlus());
        this.pages.add(SodiumGameOptionPages.nvidium());
    }

    public void setPage(OptionPage page) {
        this.currentPage = page;
        this.scrollOffset = 0;

        this.rebuildGUI();
    }

    @Override
    public void initGui() {
        super.initGui();

        this.rebuildGUI();
    }

    private void rebuildGUI() {
        this.controls.clear();
        this.children.clear();
        this.drawable.clear();

        if (this.currentPage == null) {
            if (this.pages.isEmpty()) {
                throw new IllegalStateException("No pages are available?!");
            }

            // Just use the first page for now
            this.currentPage = this.pages.get(0);
        }

        this.rebuildGUIPages();
        this.rebuildGUIOptions();

        this.undoButton = new FlatButtonWidget(new Dim2i(this.width - 211, this.height - 26, 65, 20), new TextComponentTranslation("sodium.options.buttons.undo").getFormattedText(), this::undoChanges);
        this.applyButton = new FlatButtonWidget(new Dim2i(this.width - 142, this.height - 26, 65, 20), new TextComponentTranslation("sodium.options.buttons.apply").getFormattedText(), this::applyChanges);
        this.closeButton = new FlatButtonWidget(new Dim2i(this.width - 73, this.height - 26, 65, 20), new TextComponentTranslation("gui.done").getFormattedText(), this::onClose);
        // "Shaders..." sits in the top-right corner (where the donation button used to be); opens the shader screen.
        this.shadersButton = new FlatButtonWidget(new Dim2i(this.width - 128, 6, 100, 20), "Shaders...", this::openShaderScreen);

        this.children.add(this.undoButton);
        this.children.add(this.applyButton);
        this.children.add(this.closeButton);
        this.children.add(this.shadersButton);

        for (Element element : this.children) {
            if (element instanceof Drawable) {
                this.drawable.add((Drawable) element);
            }
        }
    }

    private void rebuildGUIPages() {
        // Page navigation runs vertically down the left sidebar, below the branding header.
        int x = 6;
        int y = 40;
        int width = SIDEBAR_WIDTH - 12;

        for (OptionPage page : this.pages) {
            FlatButtonWidget button = new FlatButtonWidget(new Dim2i(x, y, width, 18), page.getNewName(), () -> this.setPage(page));
            button.setSelected(this.currentPage == page);

            y += 20;

            this.children.add(button);
        }
    }

    private void rebuildGUIOptions() {
        // Options fill the panel to the right of the sidebar, below the page-title header, offset by the scroll amount.
        int x = SIDEBAR_WIDTH + 8;
        int y = CONTENT_TOP - this.scrollOffset;

        int optionWidth = Math.min(300, this.width - x - 8);

        for (OptionGroup group : this.currentPage.getGroups()) {
            // Add each option's control element
            for (Option<?> option : group.getOptions()) {
                Control<?> control = option.getControl();
                ControlElement<?> element = control.createElement(new Dim2i(x, y, optionWidth, 18));

                this.controls.add(element);
                this.children.add(element);

                // Move down to the next option
                y += 18;
            }

            // Add padding beneath each option group
            y += 4;
        }

        // Total content height (undoing the scroll offset) vs. the visible viewport height determines the scroll range.
        int contentHeight = (y + this.scrollOffset) - CONTENT_TOP;
        int visibleHeight = (this.height - CONTENT_BOTTOM_MARGIN) - CONTENT_TOP;
        this.maxScroll = Math.max(0, contentHeight - visibleHeight);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float delta) {
        super.drawDefaultBackground();

        // Left navigation sidebar with the Yumelium branding
        this.drawRect(0, 0, SIDEBAR_WIDTH, this.height, 0x90281E46);
        this.fontRenderer.drawStringWithShadow(TextFormatting.BOLD + SodiumClientMod.MODNAME, 8, 12, 0xFFB4A8F0);
        this.fontRenderer.drawString("v" + SodiumClientMod.getVersion(), 8, 24, 0xFF8778B8);

        // Title of the currently-selected page, at the top of the content panel
        this.fontRenderer.drawStringWithShadow(TextFormatting.BOLD + this.currentPage.getNewName().getFormattedText(),
                SIDEBAR_WIDTH + 8, 12, 0xFFB4A8F0);

        this.updateControls();

        // Non-option widgets (page nav, apply/close/shaders buttons) render without clipping.
        for (Drawable drawable : this.drawable) {
            if (!(drawable instanceof ControlElement)) {
                drawable.render(mouseX, mouseY, delta);
            }
        }

        // Option controls are clipped to the scrollable content viewport so scrolled rows don't spill over the header.
        this.enableContentScissor();
        for (ControlElement<?> control : this.controls) {
            control.render(mouseX, mouseY, delta);
        }
        this.disableContentScissor();

        this.renderScrollbar();

        if (this.hoveredElement != null) {
            this.renderOptionTooltip(this.hoveredElement);
        }
    }

    private void enableContentScissor() {
        ScaledResolution sr = new ScaledResolution(this.mc);
        int scale = sr.getScaleFactor();

        int top = CONTENT_TOP;
        int bottom = this.height - CONTENT_BOTTOM_MARGIN;

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        // glScissor is in window pixels with a bottom-left origin, hence the Y flip against the real display height.
        GL11.glScissor(SIDEBAR_WIDTH * scale, this.mc.displayHeight - (bottom * scale),
                (this.width - SIDEBAR_WIDTH) * scale, (bottom - top) * scale);
    }

    private void disableContentScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void renderScrollbar() {
        if (this.maxScroll <= 0) {
            return;
        }

        int top = CONTENT_TOP;
        int bottom = this.height - CONTENT_BOTTOM_MARGIN;
        int trackHeight = bottom - top;
        int contentHeight = trackHeight + this.maxScroll;

        int thumbHeight = Math.max(16, trackHeight * trackHeight / contentHeight);
        int thumbY = top + (int) ((long) (trackHeight - thumbHeight) * this.scrollOffset / this.maxScroll);
        int barX = this.width - 5;

        this.drawRect(barX, top, barX + 3, bottom, 0x40000000);
        this.drawRect(barX, thumbY, barX + 3, thumbY + thumbHeight, 0xFFB4A8F0);
    }

    private void updateControls() {
        ControlElement<?> hovered = this.getActiveControls()
                .filter(ControlElement::isHovered)
                .findFirst()
                .orElse(null);

        boolean hasChanges = this.getAllOptions()
                .anyMatch(Option::hasChanged);

        for (OptionPage page : this.pages) {
            for (Option<?> option : page.getOptions()) {
                if (option.hasChanged()) {
                    hasChanges = true;
                }
            }
        }

        this.applyButton.setEnabled(hasChanges);
        this.undoButton.setVisible(hasChanges);
        this.closeButton.setEnabled(!hasChanges);

        this.hasPendingChanges = hasChanges;
        this.hoveredElement = hovered;
    }

    private Stream<Option<?>> getAllOptions() {
        return this.pages.stream()
                .flatMap(s -> s.getOptions().stream());
    }

    private Stream<ControlElement<?>> getActiveControls() {
        return this.controls.stream();
    }

    private void renderOptionTooltip(ControlElement<?> element) {
        Dim2i dim = element.getDimensions();

        int textPadding = 3;
        int boxPadding = 3;

        int boxWidth = 200;

        int boxY = dim.getOriginY();
        int boxX = dim.getLimitX() + boxPadding;

        // Flip the tooltip to the left of the option if it would run off the right edge of the screen
        if (boxX + boxWidth > this.width) {
            boxX = dim.getOriginX() - boxPadding - boxWidth;
        }

        Option<?> option = element.getOption();
        List<String> tooltip = new ArrayList<>(this.fontRenderer.listFormattedStringToWidth(option.getTooltip().getFormattedText(), boxWidth - (textPadding * 2)));

        OptionImpact impact = option.getImpact();

        if (impact != null) {
            tooltip.add(TextFormatting.GRAY + I18n.format("sodium.options.performance_impact_string", impact.toDisplayString()));
        }

        int boxHeight = (tooltip.size() * 12) + boxPadding;
        int boxYLimit = boxY + boxHeight;
        int boxYCutoff = this.height - 40;

        // If the box is going to be cutoff on the Y-axis, move it back up the difference
        if (boxYLimit > boxYCutoff) {
            boxY -= boxYLimit - boxYCutoff;
        }

        this.drawGradientRect(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xE0281E46, 0xE0281E46);

        for (int i = 0; i < tooltip.size(); i++) {
            this.fontRenderer.drawString(tooltip.get(i), boxX + textPadding, boxY + textPadding + (i * 12), 0xFFFFFFFF);
        }
    }
    
    private void applyChanges() {
        final HashSet<OptionStorage<?>> dirtyStorages = new HashSet<>();
        final EnumSet<OptionFlag> flags = EnumSet.noneOf(OptionFlag.class);

        this.getAllOptions().forEach((option -> {
            if (!option.hasChanged()) {
                return;
            }

            option.applyChanges();

            flags.addAll(option.getFlags());
            dirtyStorages.add(option.getStorage());
        }));

        if (flags.contains(OptionFlag.REQUIRES_RENDERER_RELOAD)) {    	
            this.mc.renderGlobal.loadRenderers();
        }

        if (flags.contains(OptionFlag.REQUIRES_ASSET_RELOAD)) {
            this.mc.getTextureMapBlocks().setMipmapLevels(this.mc.gameSettings.mipmapLevels);
            this.mc.refreshResources();
        }

        for (OptionStorage<?> storage : dirtyStorages) {
            storage.save();
        }
    }

    private void undoChanges() {
        this.getAllOptions()
                .forEach(Option::reset);
    }

    private void openShaderScreen() {
        this.mc.displayGuiScreen(new YumeliumShaderScreen(this));
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        if(keyCode == Keyboard.KEY_ESCAPE && !shouldCloseOnEsc()) {
            return;
        } else if (keyCode == Keyboard.KEY_ESCAPE) {
            onClose();
            return;
        }

        if (keyCode == Keyboard.KEY_P && isShiftKeyDown()) {
            this.mc.displayGuiScreen(new GuiVideoSettings(this.prevScreen, this.mc.gameSettings));
        }
    }

    public boolean shouldCloseOnEsc() {
        return !this.hasPendingChanges;
    }

    // We can't override onGuiClosed due to StackOverflow
    public void onClose() {
        this.mc.displayGuiScreen(this.prevScreen);
        super.onGuiClosed();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        // Ignore option clicks outside the content viewport (rows scrolled under the header/into the button area).
        boolean inContent = mouseY >= CONTENT_TOP && mouseY <= this.height - CONTENT_BOTTOM_MARGIN;

        for (Element element : this.children) {
            if (element instanceof ControlElement && !inContent) {
                continue;
            }
            element.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);

        this.children.forEach(element -> element.mouseDragged(mouseX, mouseY));
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();

        int wheel = Mouse.getEventDWheel();

        if (wheel != 0 && this.maxScroll > 0) {
            int newOffset = MathHelper.clamp(this.scrollOffset - Integer.signum(wheel) * 15, 0, this.maxScroll);

            if (newOffset != this.scrollOffset) {
                this.scrollOffset = newOffset;
                this.rebuildGUI();
            }
        }
    }
}
