package de.sniper;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import java.util.List;

/** Deal-Liste, Markierungen im Menue und Knoepfe fuers Auktionshaus. */
public final class SniperUi {
    private SniperUi() {}

    private static final int GOLD = 0xFFFBBF24;
    private static final int GREEN = 0xFF4ADE80;
    private static final int GRAY = 0xFF9A9AB0;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int RED = 0xFFF87171;

    private static final int PANEL_W = 172;
    private static final int PANEL_H = 112;
    private static final int ROWS = 6;

    private static Button toggleBtn, thresholdBtn, buyBtn;
    private static AbstractContainerScreen<?> owner;

    // ---------------------------------------------------------------- Geometrie
    // Normales Kisten-Menue: 176 breit, 114 + 18 * Reihen hoch

    private static int left(AbstractContainerScreen<?> cs) {
        return (cs.width - 176) / 2;
    }

    private static int top(AbstractContainerScreen<?> cs) {
        int rows = (cs.getMenu().slots.size() - 36) / 9;
        return (cs.height - (114 + rows * 18)) / 2;
    }

    /** Panel links neben dem Menue, bei wenig Platz rechts daneben. */
    private static int panelX(AbstractContainerScreen<?> cs) {
        int l = left(cs);
        return l >= PANEL_W + 8 ? l - PANEL_W - 4 : l + 180;
    }

    private static int alpha(int rgb, float a) {
        int al = Math.max(0, Math.min(255, (int) (a * 255)));
        return (al << 24) | (rgb & 0xFFFFFF);
    }

    /** Farbe je Deal-Stufe: gruen, gold oder MEGA = Regenbogen. */
    private static int color(Sniper.Deal d, long now) {
        int t = Sniper.tier(d);
        if (t == 0) return GREEN;
        if (t == 1) return GOLD;
        double x = now / 400.0;
        int r = (int) (180 + 75 * Math.sin(x));
        int g = (int) (130 + 125 * Math.sin(x + 2.1));
        int b = (int) (200 + 55 * Math.sin(x + 4.2));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void panel(GuiGraphics g, int x1, int y1, int x2, int y2, int accent) {
        g.fill(x1 + 1, y1, x2 - 1, y2, 0xFF2A2A44);
        g.fill(x1, y1 + 1, x2, y2 - 1, 0xFF2A2A44);
        g.fillGradient(x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0xF0181830, 0xF00A0A14);
        g.fill(x1 + 8, y1, x2 - 8, y1 + 2, accent);
    }

    // ---------------------------------------------------------------- Knoepfe

    public static void ensureButtons(AbstractContainerScreen<?> cs) {
        List<AbstractWidget> list = Screens.getButtons(cs);
        if (owner == cs && toggleBtn != null && list.contains(toggleBtn)) {
            refresh();
            return;
        }
        owner = cs;
        int x = panelX(cs);
        int y = top(cs) + PANEL_H + 4;

        toggleBtn = Button.builder(Component.literal(""), b -> Sniper.toggle())
                .bounds(x, y, PANEL_W, 20).build();
        thresholdBtn = Button.builder(Component.literal(""), b -> Sniper.cycleThreshold())
                .bounds(x, y + 24, PANEL_W, 20).build();
        buyBtn = Button.builder(Component.literal(""), b -> Sniper.buySelected(Minecraft.getInstance()))
                .bounds(x, y + 48, PANEL_W, 20).build();

        list.add(toggleBtn);
        list.add(thresholdBtn);
        list.add(buyBtn);
        refresh();
    }

    private static void refresh() {
        if (toggleBtn == null) return;
        toggleBtn.setMessage(Component.literal("Sniper: " + (Sniper.enabled ? "AN" : "AUS")
                + "  [" + SniperKeys.name(SniperKeys.toggle) + "]"));
        thresholdBtn.setMessage(Component.literal("Schwelle: " + Sniper.threshold + "%"
                + "  [" + SniperKeys.name(SniperKeys.threshold) + "]"));
        buyBtn.setMessage(Component.literal("Gewählten Deal kaufen  [" + SniperKeys.name(SniperKeys.buy) + "]"));
        buyBtn.active = Sniper.selectedDeal() != null;
    }

    // ---------------------------------------------------------------- Zeichnen

    public static void render(AbstractContainerScreen<?> cs, GuiGraphics g) {
        if (!Sniper.ahOpen) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        long now = System.currentTimeMillis();
        int left = left(cs);
        int top = top(cs);
        var menu = cs.getMenu();
        int px = panelX(cs);
        Sniper.Deal sel = Sniper.selectedDeal();

        // ----- Panel -----
        panel(g, px, top, px + PANEL_W, top + PANEL_H, GOLD);
        g.drawString(font, "AH-SNIPER", px + 6, top + 6, GOLD);
        String state = Sniper.enabled ? "AN" : "AUS";
        g.drawString(font, state, px + PANEL_W - 6 - font.width(state), top + 6, Sniper.enabled ? GREEN : RED);
        g.drawString(font, Sniper.listings.size() + " Angebote · " + Sniper.deals.size() + " Deals",
                px + 6, top + 18, GRAY);

        int ry = top + 32;
        if (!Sniper.enabled) {
            g.drawString(font, "Sniper ist aus", px + 6, ry, GRAY);
        } else if (Sniper.deals.isEmpty()) {
            g.drawString(font, "Keine Deals auf dieser Seite", px + 6, ry, GRAY);
            g.drawString(font, "Blättere durch die Seiten", px + 6, ry + 11, GRAY);
        } else {
            int n = Math.min(ROWS, Sniper.deals.size());
            for (int i = 0; i < n; i++) {
                Sniper.Deal d = Sniper.deals.get(i);
                boolean isSel = d.equals(sel);
                int col = color(d, now);
                if (isSel) g.fill(px + 3, ry - 1, px + PANEL_W - 3, ry + 10, alpha(col, 0.22f));
                String label = (isSel ? "> " : "  ") + (i + 1) + ". " + d.l().count() + "x "
                        + font.plainSubstrByWidth(d.l().name(), 54);
                g.drawString(font, label, px + 5, ry, isSel ? WHITE : GRAY);
                String right = "-" + Math.round(d.discount() * 100) + "% " + Sniper.shortMoney(d.profit());
                g.drawString(font, right, px + PANEL_W - 5 - font.width(right), ry, col);
                ry += 11;
            }
            if (Sniper.deals.size() > ROWS) {
                g.drawString(font, "+ " + (Sniper.deals.size() - ROWS) + " weitere", px + 6, ry, GRAY);
            }
        }
        String stats = "Käufe: " + Sniper.sessionBuys + " · Gewinn ca. " + Sniper.shortMoney(Sniper.sessionProfit);
        g.drawString(font, stats, px + 6, top + PANEL_H - 12, GRAY);

        if (!Sniper.enabled) return;

        // ----- Markierungen im Menue -----
        float pulse = 0.5f + 0.5f * (float) Math.abs(Math.sin(now / 250.0));
        for (Sniper.Deal d : Sniper.deals) {
            int idx = d.l().slot();
            if (idx >= menu.slots.size()) continue;
            Slot s = menu.slots.get(idx);
            int x = left + s.x;
            int y = top + s.y;
            int col = color(d, now);
            boolean isSel = d.equals(sel);

            g.fill(x, y, x + 16, y + 16, alpha(col, (isSel ? 0.20f : 0.10f) + 0.15f * pulse));
            border(g, x - 1, y - 1, 18, col);
            if (isSel) border(g, x - 2, y - 2, 20, WHITE);
            g.drawString(font, "-" + Math.round(d.discount() * 100) + "%", x + 1, y + 1, col);
        }

        // ----- MEGA-Deal-Banner -----
        for (Sniper.Deal d : Sniper.deals) {
            if (Sniper.tier(d) < 2) continue;
            String banner = "MEGA-DEAL: " + d.l().count() + "x " + d.l().name()
                    + "  -" + Math.round(d.discount() * 100) + "%";
            int bx = cs.width / 2 - font.width(banner) / 2;
            int by = Math.max(4, top - 16);
            g.fill(bx - 6, by - 3, bx + font.width(banner) + 6, by + 11, alpha(0x000000, 0.65f));
            g.drawString(font, banner, bx, by, color(d, now));
            break;
        }
    }

    private static void border(GuiGraphics g, int x, int y, int size, int col) {
        g.fill(x, y, x + size, y + 1, col);
        g.fill(x, y + size - 1, x + size, y + size, col);
        g.fill(x, y + 1, x + 1, y + size - 1, col);
        g.fill(x + size - 1, y + 1, x + size, y + size - 1, col);
    }
}
