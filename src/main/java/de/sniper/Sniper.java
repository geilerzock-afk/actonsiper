package de.sniper;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die Angebote im HugoSMP-Auktionshaus (/ah) aus den Item-Beschreibungen
 * ("Preis: $1.998", "Verkaeufer: .Name", "Laeuft ab in: ...") und vergleicht den
 * Preis pro Stueck mit dem Marktpreis.
 */
public final class Sniper {
    private Sniper() {}

    /** Ein Angebot im Auktionshaus. */
    public record Listing(int slot, String key, String name, int count, double price,
                          String seller, String expires, boolean special) {
        public double unit() {
            return price / Math.max(1, count);
        }
    }

    /** Ein Angebot, das deutlich unter dem Marktpreis liegt. */
    public record Deal(Listing l, double ref, double discount, double profit) {}

    public static final int[] STEPS = {10, 20, 30, 40, 50, 60};

    // ---- Einstellungen (werden gespeichert) ----
    public static boolean enabled = true;
    public static int threshold = 30;       // Prozent unter Marktpreis
    public static double minProfit = 0;     // Mindest-Gewinn pro Angebot
    public static final Map<String, Double> manual = new TreeMap<>(); // eigene Preise pro Stueck
    public static final Map<String, Double> market = new TreeMap<>(); // gelernte Marktpreise

    // ---- Laufender Zustand ----
    public static boolean ahOpen = false;
    public static List<Listing> listings = List.of();
    public static List<Deal> deals = List.of();
    public static Deal best = null;
    public static String selectedSig = null;

    // ---- Statistik dieser Sitzung ----
    public static int sessionBuys = 0;
    public static double sessionProfit = 0;

    private static final Map<String, ArrayDeque<Double>> samples = new HashMap<>();
    private static final Set<String> seen = new HashSet<>();
    private static final Set<String> notified = new HashSet<>();
    private static int ticks = 0;
    private static boolean wasAh = false;
    private static long lastBuyAt = 0;

    private static final Pattern PRICE = Pattern.compile("Preis:\\s*\\$?\\s*([\\d.,]+[kKmMbB]?)");
    private static final Pattern SELLER = Pattern.compile("Verk\\S*ufer:\\s*(\\S+)");
    private static final Pattern EXPIRES = Pattern.compile("ab in:\\s*(.+)");

    // ------------------------------------------------------------ Tick

    public static void tick(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> cs) || mc.player == null) {
            reset();
            return;
        }
        if (++ticks % 4 != 0) return;
        scan(cs.getMenu());
        if (ahOpen) SniperUi.ensureButtons(cs);
    }

    private static void reset() {
        if (wasAh) {
            refreshMarket();
            SniperConfig.save();
        }
        wasAh = false;
        ahOpen = false;
        listings = List.of();
        deals = List.of();
        best = null;
    }

    /** Liest alle Angebote aus dem geoeffneten Menue und bewertet sie. */
    public static void scan(AbstractContainerMenu menu) {
        List<Listing> ls = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container instanceof Inventory) continue; // eigenes Inventar ueberspringen
            ItemStack st = slot.getItem();
            if (st.isEmpty()) continue;
            Listing l = parse(i, st);
            if (l != null) ls.add(l);
        }
        listings = ls;
        ahOpen = !ls.isEmpty();
        wasAh = wasAh || ahOpen;
        if (!ahOpen) {
            deals = List.of();
            best = null;
            return;
        }

        learn(ls);

        List<Deal> ds = new ArrayList<>();
        if (enabled) {
            for (Listing l : ls) {
                Deal d = evaluate(l);
                if (d != null) ds.add(d);
            }
        }
        ds.sort(Comparator.comparingDouble(Deal::profit).reversed());
        deals = ds;
        best = ds.isEmpty() ? null : ds.get(0);
        notifyNew(ds);
    }

    // ------------------------------------------------------------ Auswahl & Schalter

    /** Aktuell gewaehlter Deal (Standard: der mit dem hoechsten Gewinn). */
    public static Deal selectedDeal() {
        if (deals.isEmpty()) return null;
        if (selectedSig != null) {
            for (Deal d : deals) {
                if (sig(d.l()).equals(selectedSig)) return d;
            }
        }
        return deals.get(0);
    }

    public static void select(int delta) {
        if (deals.isEmpty()) return;
        int idx = Math.max(0, deals.indexOf(selectedDeal()));
        idx = Math.floorMod(idx + delta, deals.size());
        selectedSig = sig(deals.get(idx).l());
    }

    public static void toggle() {
        enabled = !enabled;
        SniperConfig.save();
        rescan();
        msg("Sniper " + (enabled ? "AN" : "AUS"), enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    public static void cycleThreshold() {
        int next = STEPS[0];
        for (int s : STEPS) {
            if (s > threshold) {
                next = s;
                break;
            }
        }
        threshold = next;
        SniperConfig.save();
        rescan();
    }

    private static void rescan() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractContainerScreen<?> cs) scan(cs.getMenu());
    }

    /** 0 = normal, 1 = stark (ab 50 %), 2 = MEGA (ab 70 %). */
    public static int tier(Deal d) {
        return d.discount() >= 0.70 ? 2 : d.discount() >= 0.50 ? 1 : 0;
    }

    // ------------------------------------------------------------ Parsen

    /** Fuer Tooltips: Angebot aus einem einzelnen Item lesen (Slot -1). */
    public static Listing parseStack(ItemStack st) {
        return st.isEmpty() ? null : parse(-1, st);
    }

    private static Listing parse(int slot, ItemStack st) {
        ItemLore lore = st.get(DataComponents.LORE);
        if (lore == null) return null;

        double price = -1;
        String seller = "?";
        String expires = "";
        for (Component c : lore.lines()) {
            String t = c.getString();
            Matcher m = PRICE.matcher(t);
            if (price < 0 && m.find()) {
                double v = parseAmount(m.group(1));
                if (v > 0) price = v;
            }
            m = SELLER.matcher(t);
            if (m.find()) seller = m.group(1);
            m = EXPIRES.matcher(t);
            if (m.find()) expires = m.group(1).trim();
        }
        if (price < 0) return null;

        String key = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
        boolean renamed = st.has(DataComponents.CUSTOM_NAME)
                && !st.getHoverName().getString().equals(st.getItem().getDescription().getString());
        // Verzauberte/umbenannte Items und Traenke sind nicht vergleichbar -> kein Deal
        boolean special = renamed || st.isEnchanted()
                || st.has(DataComponents.STORED_ENCHANTMENTS) || st.has(DataComponents.POTION_CONTENTS);
        return new Listing(slot, key, st.getHoverName().getString(), st.getCount(), price, seller, expires, special);
    }

    /** Versteht 1998, 1.998, 1,998, 1.5k, 2m, 10.500.000 usw. */
    public static double parseAmount(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[.,]+$", "");
        double mult = 1;
        if (s.endsWith("k")) { mult = 1e3; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 1e6; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("b")) { mult = 1e9; s = s.substring(0, s.length() - 1); }

        if (s.matches("\\d{1,3}(\\.\\d{3})+(,\\d+)?")) {
            s = s.replace(".", "").replace(',', '.');
        } else if (s.matches("\\d{1,3}(,\\d{3})+(\\.\\d+)?")) {
            s = s.replace(",", "");
        } else {
            s = s.replace(',', '.');
        }
        try {
            return Double.parseDouble(s) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------ Preise

    /** Jedes neu gesehene Angebot wird als Stichprobe fuer den Marktpreis gemerkt. */
    private static void learn(List<Listing> ls) {
        for (Listing l : ls) {
            if (l.special()) continue;
            if (!seen.add(sig(l))) continue;
            ArrayDeque<Double> q = samples.computeIfAbsent(l.key(), k -> new ArrayDeque<>());
            q.addLast(l.unit());
            if (q.size() > 60) q.removeFirst();
        }
        if (seen.size() > 5000) seen.clear();
    }

    /** Eigener Preis > Median der gesehenen Angebote (ab 4 Stueck) > zuletzt gelernter Wert. */
    public static double reference(String key) {
        Double m = manual.get(key);
        if (m != null) return m;
        ArrayDeque<Double> q = samples.get(key);
        if (q != null && q.size() >= 4) return median(q);
        Double mk = market.get(key);
        return mk != null ? mk : Double.NaN;
    }

    /** Uebernimmt die aktuellen Mediane in die zu speichernden Marktpreise. */
    public static void refreshMarket() {
        for (Map.Entry<String, ArrayDeque<Double>> e : samples.entrySet()) {
            if (e.getValue().size() >= 4) market.put(e.getKey(), median(e.getValue()));
        }
    }

    private static double median(Collection<Double> c) {
        double[] a = c.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = a.length;
        return n % 2 == 1 ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2;
    }

    private static Deal evaluate(Listing l) {
        if (l.special()) return null;
        double ref = reference(l.key());
        if (Double.isNaN(ref) || ref <= 0) return null;
        double discount = 1 - l.unit() / ref;
        double profit = (ref - l.unit()) * l.count();
        if (discount * 100 < threshold || profit < minProfit) return null;
        return new Deal(l, ref, discount, profit);
    }

    /** Alle bekannten Preise fuer /snipe liste. */
    public static Map<String, String> knownPrices() {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Double> e : market.entrySet()) {
            out.put(e.getKey(), money(e.getValue()) + " (gelernt)");
        }
        for (Map.Entry<String, Double> e : manual.entrySet()) {
            out.put(e.getKey(), money(e.getValue()) + " (eigener Preis)");
        }
        return out;
    }

    // ------------------------------------------------------------ Meldungen & Kaufen

    private static void notifyNew(List<Deal> ds) {
        int sent = 0;
        int maxTier = 0;
        for (Deal d : ds) {
            if (!notified.add(sig(d.l()))) continue;
            maxTier = Math.max(maxTier, tier(d));
            if (sent++ < 3) {
                Listing l = d.l();
                msg((tier(d) == 2 ? "MEGA-DEAL: " : "DEAL: ") + l.count() + "x " + l.name() + " für "
                        + money(l.price()) + " (" + money(l.unit()) + "/Stk) – "
                        + Math.round(d.discount() * 100) + "% unter Markt (" + money(d.ref())
                        + ") – Verkäufer " + l.seller() + " – Gewinn ca. " + money(d.profit()),
                        tier(d) == 2 ? ChatFormatting.LIGHT_PURPLE : tier(d) == 1 ? ChatFormatting.GOLD : ChatFormatting.GREEN);
            }
        }
        if (sent > 0) {
            var sound = maxTier == 2 ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.PLAYER_LEVELUP;
            Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(sound, maxTier == 2 ? 1.0f : 1.8f));
        }
        if (notified.size() > 2000) notified.clear();
    }

    /** Liest die Seite frisch ein und klickt dann den gewaehlten Deal an. */
    public static void buySelected(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> cs) || mc.player == null || mc.gameMode == null) return;
        long now = System.currentTimeMillis();
        if (now - lastBuyAt < 400) return; // Doppel-Klick-Schutz
        scan(cs.getMenu());
        Deal d = selectedDeal();
        if (d == null) {
            msg("Kein Deal gefunden.", ChatFormatting.GRAY);
            return;
        }
        lastBuyAt = now;
        Listing l = d.l();
        msg("Kaufe " + l.count() + "x " + l.name() + " für " + money(l.price()) + " …", ChatFormatting.GOLD);
        clickSlot(cs.getMenu(), l.slot(), mc);
        sessionBuys++;
        sessionProfit += d.profit();
    }

    /** Eigentlicher Klick auf den Slot. Bei Kompilierfehlern hier: Methodenname/Signatur anpassen. */
    private static void clickSlot(AbstractContainerMenu menu, int slot, Minecraft mc) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.PICKUP, mc.player);
    }

    // ------------------------------------------------------------ Hilfen

    public static String sig(Listing l) {
        return l.key() + "|" + l.seller() + "|" + l.price() + "|" + l.count();
    }

    public static String normalizeKey(String s) {
        String k = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return k.contains(":") ? k : "minecraft:" + k;
    }

    public static String money(double v) {
        String f = (v == Math.rint(v)) ? "%,.0f" : "%,.2f";
        return "$" + String.format(Locale.GERMANY, f, v);
    }

    /** Kurzform fuer enge Stellen: $950, $1,2k, $3,4M. */
    public static String shortMoney(double v) {
        if (v >= 1e6) return "$" + String.format(Locale.GERMANY, "%.1fM", v / 1e6);
        if (v >= 1e3) return "$" + String.format(Locale.GERMANY, "%.1fk", v / 1e3);
        return "$" + Math.round(v);
    }

    public static void msg(String s, ChatFormatting color) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal("[Sniper] " + s).withStyle(color), false);
    }
}
