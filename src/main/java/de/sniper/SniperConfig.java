package de.sniper;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** Speichert Einstellungen und Preise in config/ahsniper.properties. */
public final class SniperConfig {
    private SniperConfig() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("ahsniper.properties");
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) return;
        Properties p = new Properties();
        try (InputStreamReader in = new InputStreamReader(Files.newInputStream(f), StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            return;
        }

        Sniper.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
        Sniper.threshold = (int) num(p.getProperty("threshold"), 30);
        Sniper.minProfit = num(p.getProperty("minProfit"), 0);

        Sniper.manual.clear();
        Sniper.market.clear();
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith("manual.")) {
                Sniper.manual.put(name.substring(7), num(p.getProperty(name), 0));
            } else if (name.startsWith("market.")) {
                Sniper.market.put(name.substring(7), num(p.getProperty(name), 0));
            }
        }
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("enabled", String.valueOf(Sniper.enabled));
        p.setProperty("threshold", String.valueOf(Sniper.threshold));
        p.setProperty("minProfit", String.valueOf(Sniper.minProfit));
        for (Map.Entry<String, Double> e : Sniper.manual.entrySet()) {
            p.setProperty("manual." + e.getKey(), String.valueOf(e.getValue()));
        }
        for (Map.Entry<String, Double> e : Sniper.market.entrySet()) {
            p.setProperty("market." + e.getKey(), String.valueOf(e.getValue()));
        }
        try (OutputStreamWriter out = new OutputStreamWriter(Files.newOutputStream(file()), StandardCharsets.UTF_8)) {
            p.store(out, "AH-Sniper");
        } catch (IOException ignored) {
            // Speichern ist nur Komfort - ein Fehler soll das Spiel nicht stoeren
        }
    }

    private static double num(String s, double def) {
        try {
            return s == null ? def : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
