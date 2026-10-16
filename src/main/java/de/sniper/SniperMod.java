package de.sniper;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class SniperMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SniperConfig.load();
        SniperKeys.register();
        SniperTooltip.register();

        ClientTickEvents.END_CLIENT_TICK.register(Sniper::tick);
        ClientTickEvents.END_CLIENT_TICK.register(SniperKeys::tick);

        // Markierungen auf jedes Kisten-Menue zeichnen (zeichnet nur, wenn es das Auktionshaus ist)
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof AbstractContainerScreen<?> cs) {
                ScreenEvents.afterRender(screen).register((s, graphics, mouseX, mouseY, tickDelta) ->
                        SniperUi.render(cs, graphics));
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("snipe")
                        .executes(ctx -> {
                            status(ctx.getSource());
                            return 1;
                        })
                        // /snipe preis netherrack 40  -> eigener Preis pro Stueck
                        .then(literal("preis")
                                .then(argument("item", StringArgumentType.word())
                                        .then(argument("betrag", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    setPrice(ctx.getSource(),
                                                            Sniper.normalizeKey(StringArgumentType.getString(ctx, "item")),
                                                            StringArgumentType.getString(ctx, "betrag"));
                                                    return 1;
                                                }))))
                        // /snipe hand 40  -> eigener Preis fuer das Item in der Hand
                        .then(literal("hand")
                                .then(argument("betrag", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ItemStack held = Minecraft.getInstance().player.getMainHandItem();
                                            if (held.isEmpty()) {
                                                ctx.getSource().sendFeedback(Component.literal(
                                                        "[Sniper] Du hältst nichts in der Hand."));
                                                return 0;
                                            }
                                            setPrice(ctx.getSource(),
                                                    BuiltInRegistries.ITEM.getKey(held.getItem()).toString(),
                                                    StringArgumentType.getString(ctx, "betrag"));
                                            return 1;
                                        })))
                        .then(literal("loeschen")
                                .then(argument("item", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String key = Sniper.normalizeKey(StringArgumentType.getString(ctx, "item"));
                                            boolean removed = Sniper.manual.remove(key) != null;
                                            SniperConfig.save();
                                            ctx.getSource().sendFeedback(Component.literal("[Sniper] "
                                                    + (removed ? "Eigener Preis gelöscht: " : "Kein eigener Preis für ") + key));
                                            return 1;
                                        })))
                        .then(literal("schwelle")
                                .then(argument("prozent", IntegerArgumentType.integer(1, 95))
                                        .executes(ctx -> {
                                            Sniper.threshold = IntegerArgumentType.getInteger(ctx, "prozent");
                                            SniperConfig.save();
                                            ctx.getSource().sendFeedback(Component.literal(
                                                    "[Sniper] Schwelle: " + Sniper.threshold + "% unter Marktpreis"));
                                            return 1;
                                        })))
                        .then(literal("minprofit")
                                .then(argument("betrag", StringArgumentType.word())
                                        .executes(ctx -> {
                                            double v = Sniper.parseAmount(StringArgumentType.getString(ctx, "betrag"));
                                            if (v < 0) {
                                                ctx.getSource().sendFeedback(Component.literal("[Sniper] Betrag nicht lesbar."));
                                                return 0;
                                            }
                                            Sniper.minProfit = v;
                                            SniperConfig.save();
                                            ctx.getSource().sendFeedback(Component.literal(
                                                    "[Sniper] Mindest-Gewinn: " + Sniper.money(v)));
                                            return 1;
                                        })))
                        .then(literal("liste")
                                .executes(ctx -> {
                                    Sniper.refreshMarket();
                                    Map<String, String> known = Sniper.knownPrices();
                                    if (known.isEmpty()) {
                                        ctx.getSource().sendFeedback(Component.literal(
                                                "[Sniper] Noch keine Preise bekannt – öffne /ah, dann lernt die Mod mit."));
                                        return 1;
                                    }
                                    int n = 0;
                                    for (Map.Entry<String, String> e : known.entrySet()) {
                                        if (n++ >= 25) {
                                            ctx.getSource().sendFeedback(Component.literal("… und " + (known.size() - 25) + " weitere"));
                                            break;
                                        }
                                        ctx.getSource().sendFeedback(Component.literal(
                                                e.getKey().replace("minecraft:", "") + ": " + e.getValue()));
                                    }
                                    return 1;
                                }))));
    }

    private static void setPrice(FabricClientCommandSource src, String key, String raw) {
        double v = Sniper.parseAmount(raw);
        if (v <= 0) {
            src.sendFeedback(Component.literal("[Sniper] Betrag nicht lesbar: " + raw));
            return;
        }
        Sniper.manual.put(key, v);
        SniperConfig.save();
        src.sendFeedback(Component.literal("[Sniper] Preis pro Stück für " + key.replace("minecraft:", "")
                + ": " + Sniper.money(v)));
    }

    private static void status(FabricClientCommandSource src) {
        src.sendFeedback(Component.literal("[Sniper] " + (Sniper.enabled ? "AN" : "AUS")
                + " · Schwelle " + Sniper.threshold + "% · Mindest-Gewinn " + Sniper.money(Sniper.minProfit)
                + " · " + Sniper.manual.size() + " eigene Preise"));
    }
}
