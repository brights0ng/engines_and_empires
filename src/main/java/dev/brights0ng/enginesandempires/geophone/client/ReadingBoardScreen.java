package dev.brights0ng.enginesandempires.geophone.client;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

import dev.brights0ng.enginesandempires.geophone.Logbook;
import dev.brights0ng.enginesandempires.geophone.LogbookEntry;
import dev.brights0ng.enginesandempires.geophone.ReaderAccuracy;
import dev.brights0ng.enginesandempires.geophone.ReaderReading;
import dev.brights0ng.enginesandempires.geophone.ReadingBoardActionPayload;
import dev.brights0ng.enginesandempires.geophone.ReadingBoardMenu;
import dev.brights0ng.enginesandempires.geophone.ReadingOres;
import dev.brights0ng.enginesandempires.geophone.ReadingSummary;
import dev.brights0ng.enginesandempires.geophone.SeismicContent;
import dev.brights0ng.enginesandempires.geophone.WindupReaderItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The screen every board of readings shares: the logbook's, the smart logger's reader screen, and the portable record
 * display's list. A board of rows like a split-flap display, one per saved reading, with its ore swatch and name on the
 * left and, on the right, how far away it is and which way from where you stand. Beside it, a reader slot and five
 * buttons: Save, Load, Delete (asks for a second click), Rename, and Clear (wipes the reader's reading; also asks for a
 * second click if that would lose a reading). If the board holds more readings than it has rows, it scrolls, with the
 * mouse wheel or by dragging the bar on its right. Hovering over a row shows the reading in full.
 *
 * <p>To rename a reading, select its row, then type in the name field along the top and press Enter or Rename. Clearing
 * it, or typing the default name back, puts the row back to being called by its number.
 *
 * <p>The selection is kept by entry number, so it stays on the same reading while readings arrive or are deleted. It
 * decides nothing: it draws what the board holds and sends what was pressed ({@link ReadingBoardActionPayload}). It is
 * drawn entirely in code, so it needs no textures.
 */
public abstract class ReadingBoardScreen<M extends ReadingBoardMenu> extends AbstractContainerScreen<M> {

    /**
     * How a board is laid out.
     *
     * @param width        the whole screen's width
     * @param boardWidth   the board's width
     * @param rows         how many rows the board shows at once
     * @param buttonX      where the buttons are, from the screen's left
     * @param buttonWidth  how wide they are
     * @param nameBoxX     where the name field starts, from the screen's left
     * @param oreNames     whether each row names its ore as well as showing its swatch (the wider boards have room)
     * @param frame        the colours of the frame: outer edge, rim, body
     */
    public record Layout(int width, int boardWidth, int rows, int buttonX, int buttonWidth, int nameBoxX, boolean oreNames,
                         int[] frame) {
    }

    protected static final int BOARD_X = 8;
    protected static final int BOARD_Y = 20;
    protected static final int ROW_HEIGHT = 10;
    private static final int SCROLL_WIDTH = 5;

    private static final long CONFIRM_MILLIS = 3000L;

    protected static final int AMBER = 0xFFF2B742;
    protected static final int DIM = 0xFF6A6D75;

    protected final Layout layout;

    private Button save;
    private Button load;
    private Button delete;
    private Button rename;
    private Button clear;
    private EditBox nameBox;

    /** The selected entry's number, or -1. */
    private int selected = -1;
    private int nameBoxFor = -2;

    /** The first row shown. */
    private int scroll;
    private boolean draggingScrollbar;

    private int armedNumber = -1;
    private long armedUntil;
    private boolean clearArmed;
    private long clearArmedUntil;

    protected ReadingBoardScreen(M menu, Inventory inventory, Component title, Layout layout) {
        super(menu, inventory, title);
        this.layout = layout;
        this.imageWidth = layout.width();
        this.imageHeight = 256;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos + layout.buttonX();
        int width = layout.buttonWidth();
        save = addRenderableWidget(Button.builder(Component.translatable("gui.engines_and_empires.logbook.save"),
                button -> send(ReadingBoardMenu.SAVE, 0, "")).bounds(x, topPos + 62, width, 18).build());
        load = addRenderableWidget(Button.builder(Component.translatable("gui.engines_and_empires.logbook.load"),
                button -> send(ReadingBoardMenu.LOAD, selected, "")).bounds(x, topPos + 84, width, 18).build());
        delete = addRenderableWidget(Button.builder(Component.translatable("gui.engines_and_empires.logbook.delete"),
                button -> pressDelete()).bounds(x, topPos + 106, width, 18).build());
        rename = addRenderableWidget(Button.builder(Component.translatable("gui.engines_and_empires.logbook.rename"),
                button -> applyRename()).bounds(x, topPos + 128, width, 18).build());
        clear = addRenderableWidget(Button.builder(Component.translatable("gui.engines_and_empires.logbook.clear"),
                button -> pressClear()).bounds(x, topPos + 150, width, 18).build());

        nameBox = new EditBox(font, leftPos + layout.nameBoxX(), topPos + 4, BOARD_X + layout.boardWidth() - layout.nameBoxX(), 12,
                Component.translatable("gui.engines_and_empires.logbook.rename"));
        nameBox.setMaxLength(Logbook.MAX_NAME_LENGTH);
        nameBox.setHint(Component.translatable("gui.engines_and_empires.logbook.name_hint").withStyle(style -> style.withColor(DIM & 0xFFFFFF)));
        nameBox.setEditable(false);
        addRenderableWidget(nameBox);
        nameBoxFor = -2;
    }

    protected Logbook records() {
        return menu.records();
    }

    private LogbookEntry selectedEntry() {
        Logbook records = records();
        int row = selected < 0 ? -1 : records.indexOfNumber(selected);
        return row < 0 ? null : records.get(row);
    }

    private static void send(int action, int number, String name) {
        PacketDistributor.sendToServer(new ReadingBoardActionPayload(action, number, name));
    }

    private void pressDelete() {
        if (selectedEntry() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (armedNumber == selected && now < armedUntil) {
            send(ReadingBoardMenu.DELETE, selected, "");
            armedNumber = -1;
            selected = -1;
        } else {
            armedNumber = selected;
            armedUntil = now + CONFIRM_MILLIS;
        }
    }

    /**
     * Clears the reader in the slot. If it holds a reading, the first click only arms the button, and a second within a few
     * seconds confirms it, since that reading is lost unless it was already saved somewhere. An empty reader is cleared on
     * the first click, since there is nothing to lose (the server just says so).
     */
    private void pressClear() {
        ItemStack reader = menu.readerStack();
        boolean hasReading = reader.getItem() instanceof WindupReaderItem && reader.get(SeismicContent.READER_READING.get()) != null;
        if (!hasReading) {
            send(ReadingBoardMenu.CLEAR_READER, 0, "");
            return;
        }
        long now = System.currentTimeMillis();
        if (clearArmed && now < clearArmedUntil) {
            send(ReadingBoardMenu.CLEAR_READER, 0, "");
            clearArmed = false;
        } else {
            clearArmed = true;
            clearArmedUntil = now + CONFIRM_MILLIS;
        }
    }

    private void applyRename() {
        LogbookEntry entry = selectedEntry();
        if (entry == null) {
            return;
        }
        String typed = nameBox.getValue().strip();
        String defaultName = ReadingBoardMenu.displayName(entry.withName("")).getString();
        send(ReadingBoardMenu.RENAME, entry.number(), typed.equals(defaultName) ? "" : typed);
        nameBox.setFocused(false);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        LogbookEntry entry = selectedEntry();
        if (entry == null) {
            selected = -1;
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());

        ItemStack reader = menu.readerStack();
        boolean hasReader = reader.getItem() instanceof WindupReaderItem;
        save.active = hasReader && reader.get(SeismicContent.READER_READING.get()) != null;
        load.active = hasReader && entry != null;
        delete.active = entry != null;
        rename.active = entry != null;
        clear.active = hasReader;

        boolean armed = armedNumber == selected && armedNumber >= 0 && System.currentTimeMillis() < armedUntil;
        delete.setMessage(Component.translatable(armed ? "gui.engines_and_empires.logbook.confirm" : "gui.engines_and_empires.logbook.delete"));
        boolean clearArmedNow = clearArmed && System.currentTimeMillis() < clearArmedUntil;
        clear.setMessage(Component.translatable(clearArmedNow ? "gui.engines_and_empires.logbook.confirm" : "gui.engines_and_empires.logbook.clear"));
        if (!hasReader) {
            clearArmed = false; // nothing to confirm if the reader has left the slot
        }

        // The name field follows the selection: it shows the selected reading's name, and cannot be typed in when there is none.
        if (selected != nameBoxFor) {
            nameBoxFor = selected;
            nameBox.setEditable(entry != null);
            nameBox.setValue(entry != null ? ReadingBoardMenu.displayName(entry).getString() : "");
            nameBox.moveCursorToEnd(false);
            nameBox.setFocused(false);
        }
    }

    private int maxScroll() {
        return Math.max(0, records().size() - layout.rows());
    }

    /** Whether the board has a scroll bar: only if it can hold more readings than it has rows. */
    private boolean scrolls() {
        return records().capacity() > layout.rows();
    }

    // ---- drawing ----

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        int row = rowAt(mouseX, mouseY);
        Logbook records = records();
        if (row >= 0 && records.has(scroll + row)) {
            graphics.renderComponentTooltip(font, details(records.get(scroll + row)), mouseX, mouseY);
        }
    }

    /** A reading in full: its name, ore, where it is, and how good a fix it is. Also used for the map's hover. */
    public static List<Component> details(LogbookEntry entry) {
        ReaderReading reading = entry.reading();
        return List.of(
                ReadingBoardMenu.displayName(entry).copy().withStyle(ChatFormatting.GOLD),
                ReadingOres.colouredName(reading.ore()),
                Component.translatable("engines_and_empires.smart_logger.position", reading.x(),
                        reading.hasHeight() ? String.valueOf(reading.y()) : "?", reading.z()).withStyle(ChatFormatting.WHITE),
                Component.translatable("item.engines_and_empires.windup_reader.confidence_"
                        + reading.confidence().name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.GRAY));
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        int[] frame = layout.frame();
        graphics.fill(x, y, x + imageWidth, y + imageHeight, frame[0]);
        graphics.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, frame[1]);
        graphics.fill(x + 2, y + 2, x + imageWidth - 2, y + imageHeight - 2, frame[2]);

        int rows = layout.rows();
        int boardWidth = layout.boardWidth();
        int boardTop = y + BOARD_Y;
        int scrollX = BOARD_X + boardWidth + 1;
        int boardRight = scrolls() ? scrollX + SCROLL_WIDTH : BOARD_X + boardWidth;
        graphics.fill(x + BOARD_X - 2, boardTop - 2, x + boardRight + 2, boardTop + rows * ROW_HEIGHT + 2, 0xFF0C0D0F);

        Logbook records = records();
        int hovered = rowAt(mouseX, mouseY);
        for (int row = 0; row < rows; row++) {
            int index = scroll + row;
            int rowTop = boardTop + row * ROW_HEIGHT;
            int left = x + BOARD_X;
            boolean has = records.has(index);
            boolean isSelected = has && records.get(index).number() == selected;
            int background = isSelected ? 0xFF5A4614 : row == hovered && has ? 0xFF2B2F37 : (index % 2 == 0 ? 0xFF16181C : 0xFF1C1F24);
            graphics.fill(left, rowTop, left + boardWidth, rowTop + ROW_HEIGHT, background);
            // The seam across the middle of each flap.
            graphics.fill(left, rowTop + ROW_HEIGHT / 2, left + boardWidth, rowTop + ROW_HEIGHT / 2 + 1, 0x66000000);
            if (has) {
                LogbookEntry entry = records.get(index);
                ReaderReading reading = entry.reading();
                Component where = layout.oreNames()
                        ? ReadingOres.colouredName(reading.ore()).append(Component.literal("  ")).append(describe(reading))
                        : describe(reading);
                int whereWidth = font.width(where);
                graphics.drawString(font, where, left + boardWidth - 3 - whereWidth, rowTop + 1, AMBER, false);
                // The ore's swatch, in its map colour, then the name, cut short if it does not fit.
                graphics.fill(left + 3, rowTop + 2, left + 8, rowTop + 7, ReadingOres.colour(reading.ore()));
                graphics.drawString(font, fit(ReadingBoardMenu.displayName(entry).getString(), boardWidth - 18 - whereWidth),
                        left + 11, rowTop + 1, AMBER, false);
            } else if (index == records.size()) {
                graphics.drawString(font, "-", left + 3, rowTop + 1, DIM, false);
            }
        }

        if (scrolls()) {
            int trackHeight = rows * ROW_HEIGHT;
            graphics.fill(x + scrollX, boardTop, x + scrollX + SCROLL_WIDTH, boardTop + trackHeight, 0xFF1A1B1F);
            int total = Math.max(records.size(), rows);
            int thumbHeight = Math.max(8, trackHeight * rows / total);
            int thumbTop = boardTop + (maxScroll() == 0 ? 0 : (trackHeight - thumbHeight) * scroll / maxScroll());
            graphics.fill(x + scrollX, thumbTop, x + scrollX + SCROLL_WIDTH, thumbTop + thumbHeight, 0xFFB08A3E);
        }

        for (Slot slot : menu.slots) {
            drawSlot(graphics, x + slot.x - 1, y + slot.y - 1);
        }
    }

    private String fit(String text, int room) {
        if (font.width(text) <= room) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, room - font.width("..."))) + "...";
    }

    private static void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF0E0F12);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF25272C);
        graphics.fill(x + 1, y + 17, x + 18, y + 18, 0xFF4A4D55);
        graphics.fill(x + 17, y + 1, x + 18, y + 18, 0xFF4A4D55);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, AMBER, false);
        Logbook records = records();
        String count = records.size() + "/" + records.capacity();
        graphics.drawString(font, count, layout.buttonX() + layout.buttonWidth() - font.width(count), 6,
                records.isFull() ? 0xFFE05A4A : 0xFFC8CACF, false);
        graphics.drawString(font, Component.translatable("gui.engines_and_empires.logbook.reader"), layout.buttonX() + 4, 22,
                0xFFC8CACF, false);
    }

    /**
     * What is shown on the right of a row: the confidence tag, then how far away the reading is and which way, and how high
     * if that is known.
     */
    static Component describe(ReaderReading reading) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return Component.empty();
        }
        ReadingSummary summary = ReadingSummary.of(reading, minecraft.level.dimension().location().toString(),
                player.getX(), player.getY(), player.getZ());
        MutableComponent tag = confidenceTag(summary.confidence());
        if (!summary.sameDimension()) {
            return tag.append(Component.literal(" "))
                    .append(Component.translatable("gui.engines_and_empires.logbook.elsewhere", summary.dimensionName()));
        }
        MutableComponent text = tag.append(Component.literal(" ")).append(summary.direction() == ReadingSummary.Compass8.HERE
                ? Component.translatable("gui.engines_and_empires.logbook.here")
                : Component.literal(summary.distance() + " " + summary.direction().name()));
        switch (summary.height()) {
            case UP -> text.append(Component.literal(", ")).append(Component.translatable("gui.engines_and_empires.logbook.up", Math.abs(summary.heightDifference())));
            case DOWN -> text.append(Component.literal(", ")).append(Component.translatable("gui.engines_and_empires.logbook.down", Math.abs(summary.heightDifference())));
            case LEVEL -> text.append(Component.literal(", ")).append(Component.translatable("gui.engines_and_empires.logbook.level"));
            default -> {
            }
        }
        return text;
    }

    /**
     * The word shown for a confidence tier: green for a precise fix, amber for approximate, red for rough, and dim grey for a
     * reading old enough to predate this being tracked at all.
     */
    private static MutableComponent confidenceTag(ReaderAccuracy.Confidence confidence) {
        return switch (confidence) {
            case PRECISE -> Component.translatable("gui.engines_and_empires.logbook.confidence.precise").withStyle(ChatFormatting.GREEN);
            case APPROXIMATE -> Component.translatable("gui.engines_and_empires.logbook.confidence.approximate")
                    .withStyle(style -> style.withColor(AMBER & 0xFFFFFF));
            case ROUGH -> Component.translatable("gui.engines_and_empires.logbook.confidence.rough").withStyle(ChatFormatting.RED);
            case UNKNOWN -> Component.translatable("gui.engines_and_empires.logbook.confidence.unknown")
                    .withStyle(style -> style.withColor(DIM & 0xFFFFFF));
        };
    }

    // ---- the mouse and keyboard ----

    private int rowAt(double mouseX, double mouseY) {
        double localX = mouseX - leftPos - BOARD_X;
        double localY = mouseY - topPos - BOARD_Y;
        if (localX < 0 || localX >= layout.boardWidth() || localY < 0 || localY >= layout.rows() * ROW_HEIGHT) {
            return -1;
        }
        return (int) (localY / ROW_HEIGHT);
    }

    private boolean overScrollbar(double mouseX, double mouseY) {
        if (!scrolls()) {
            return false;
        }
        double localX = mouseX - leftPos - (BOARD_X + layout.boardWidth() + 1);
        double localY = mouseY - topPos - BOARD_Y;
        return localX >= 0 && localX < SCROLL_WIDTH && localY >= 0 && localY < layout.rows() * ROW_HEIGHT;
    }

    private void dragScrollbar(double mouseY) {
        double fraction = (mouseY - topPos - BOARD_Y) / (layout.rows() * ROW_HEIGHT);
        scroll = Mth.clamp((int) Math.round(fraction * maxScroll()), 0, maxScroll());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (overScrollbar(mouseX, mouseY)) {
                draggingScrollbar = true;
                dragScrollbar(mouseY);
                return true;
            }
            int row = rowAt(mouseX, mouseY);
            if (row >= 0) {
                Logbook records = records();
                int index = scroll + row;
                selected = records.has(index) ? records.get(index).number() : -1;
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar) {
            dragScrollbar(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && scrolls()) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY) * 3, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * While the name field has the keyboard, typing goes to it, not to the game: without this, pressing the inventory key while
     * typing a name would close the screen. Enter applies the name, and Escape still closes the screen.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.closeContainer();
            }
            return true;
        }
        if (nameBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                applyRename();
                return true;
            }
            if (nameBox.keyPressed(keyCode, scanCode, modifiers) || nameBox.canConsumeInput()) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
