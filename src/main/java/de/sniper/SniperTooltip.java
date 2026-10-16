package de.sniper;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Haengt an jedes Angebot im Auktionshaus den Preis pro Stueck und den Vergleich zum Markt an.
 * Bei Kompilierfehlern in dieser Datei: Datei loeschen und in SniperMod die Zeile
 * "SniperTooltip.register();" entfernen.
 */
public final class SniperTooltip {
    private SniperTooltip() {}

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (!Sniper.ahOpen) return;
            Sniper.Listing l = Sniper.parseStack(stack);
            if (l == null) return;

            lines.add(Component.literal(""));
            lines.add(Component.literal("Pro Stück: " + Sniper.money(l.unit())).withStyle(ChatFormatting.AQUA));

            double ref = Sniper.reference(l.key());
            if (l.special() || Double.isNaN(ref) || ref <= 0) return;

            long pct = Math.round((1 - l.unit() / ref) * 100);
            String diff = pct >= 0 ? "-" + pct + "%" : "+" + (-pct) + "%";
            ChatFormatting color = pct >= Sniper.threshold ? ChatFormatting.GREEN
                    : pct >= 0 ? ChatFormatting.GRAY : ChatFormatting.RED;
            lines.add(Component.literal("Markt: " + Sniper.money(ref) + "  (" + diff + ")").withStyle(color));
        });
    }
}
