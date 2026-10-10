package dev.mineedge.client.render;

import dev.mineedge.client.config.MineEdgeClientConfig;
import dev.mineedge.client.movement.ClientMovement;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

public final class MovementOverlay {
    private static final int MARGIN = 4;
    private static final int PADDING = 5;
    private static final int COLUMN_GAP = 12;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int LABEL_COLOR = 0xFFBBBBBB;
    private static final int DIVIDER_COLOR = 0xFF666666;
    private static final int BACKGROUND_COLOR = 0xC0000000;

    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!MineEdgeClientConfig.DIAGNOSTICS.get()
                || minecraft.player == null || !minecraft.getDebugOverlay().showDebugScreen() || minecraft.options.hideGui) {
            return;
        }
        ClientMovement movement = ClientMovement.INSTANCE;
        Component title = Component.translatable("mineedge.debug.title");
        Component[] labels = {
                Component.translatable("mineedge.debug.status"),
                Component.translatable("mineedge.debug.speed"),
                Component.translatable("mineedge.debug.server_tick"),
                Component.translatable("mineedge.debug.command_ack"),
                Component.translatable("mineedge.debug.pending"),
                Component.translatable("mineedge.debug.correction")
        };
        Component[] values = {
                Component.literal(movement.status()),
                Component.translatable("mineedge.debug.speed_value", String.format(Locale.ROOT, "%.2f", movement.speed())),
                Component.literal(Long.toString(movement.serverTick())),
                Component.literal(movement.sequence() + " / " + movement.acknowledged()),
                Component.literal(Integer.toString(movement.pending())),
                Component.translatable("mineedge.debug.correction_value", String.format(Locale.ROOT, "%.3f", movement.correction()))
        };

        int labelWidth = 0;
        int valueWidth = 0;
        for (int row = 0; row < labels.length; row++) {
            labelWidth = Math.max(labelWidth, minecraft.font.width(labels[row]));
            valueWidth = Math.max(valueWidth, minecraft.font.width(values[row]));
        }
        int rowHeight = minecraft.font.lineHeight + 2;
        int width = Math.max(minecraft.font.width(title), labelWidth + COLUMN_GAP + valueWidth) + PADDING * 2;
        int height = PADDING * 2 + rowHeight * (labels.length + 1) + 4;
        GuiGraphics graphics = event.getGuiGraphics();
        int x = MARGIN;
        int y = Math.max(MARGIN, graphics.guiHeight() - MARGIN - height);
        int textX = x + PADDING;
        int valueX = textX + labelWidth + COLUMN_GAP;

        graphics.fill(x, y, x + width, y + height, BACKGROUND_COLOR);
        graphics.drawString(minecraft.font, title, textX, y + PADDING, TEXT_COLOR, false);
        int dividerY = y + PADDING + rowHeight;
        graphics.fill(textX, dividerY, x + width - PADDING, dividerY + 1, DIVIDER_COLOR);
        int rowY = dividerY + 4;
        for (int row = 0; row < labels.length; row++) {
            graphics.drawString(minecraft.font, labels[row], textX, rowY, LABEL_COLOR, false);
            graphics.drawString(minecraft.font, values[row], valueX, rowY, TEXT_COLOR, false);
            rowY += rowHeight;
        }
    }

    private MovementOverlay() {}
}
