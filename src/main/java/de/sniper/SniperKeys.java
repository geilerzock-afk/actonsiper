package de.sniper;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/**
 * Tasten fuer den Sniper. Sie erscheinen in den Minecraft-Steuerungsoptionen unter "AH-Sniper"
 * und koennen dort geaendert werden. Weil normale Tasten in Menues nicht ankommen, werden sie
 * hier direkt per GLFW abgefragt - so funktionieren sie auch im geoeffneten Auktionshaus.
 */
public final class SniperKeys {
    private SniperKeys() {}

    public static KeyMapping buy, next, prev, toggle, threshold, openAh;

    private static final Map<KeyMapping, Boolean> wasDown = new HashMap<>();

    public static void register() {
        // Bei Kompilierfehlern in der naechsten Zeile: "Identifier" gegen "ResourceLocation" tauschen
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("ahsniper", "sniper"));
        buy = reg("buy", GLFW.GLFW_KEY_B, cat);
        next = reg("next", GLFW.GLFW_KEY_DOWN, cat);
        prev = reg("prev", GLFW.GLFW_KEY_UP, cat);
        toggle = reg("toggle", GLFW.GLFW_KEY_V, cat);
        threshold = reg("threshold", GLFW.GLFW_KEY_G, cat);
        openAh = reg("open", GLFW.GLFW_KEY_H, cat);
    }

    private static KeyMapping reg(String id, int key, KeyMapping.Category cat) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.ahsniper." + id, InputConstants.Type.KEYSYM, key, cat));
    }

    /** Liefert true genau einmal, nachdem die Taste gedrueckt wurde. */
    private static boolean pressed(KeyMapping km) {
        if (km == null || km.isUnbound()) return false;
        InputConstants.Key k = km.getKey();
        if (k.getType() != InputConstants.Type.KEYSYM) return false;
        long window = GLFW.glfwGetCurrentContext();
        boolean now = window != 0 && GLFW.glfwGetKey(window, k.getValue()) == GLFW.GLFW_PRESS;
        boolean was = wasDown.getOrDefault(km, false);
        wasDown.put(km, now);
        return now && !was;
    }

    /** Name der aktuell belegten Taste, z. B. "B". */
    public static String name(KeyMapping km) {
        return km == null ? "?" : km.getTranslatedKeyMessage().getString();
    }

    public static void tick(Minecraft mc) {
        // Alle Tasten jeden Tick abfragen, damit der Zustand aktuell bleibt
        boolean pBuy = pressed(buy);
        boolean pNext = pressed(next);
        boolean pPrev = pressed(prev);
        boolean pToggle = pressed(toggle);
        boolean pThreshold = pressed(threshold);
        boolean pOpen = pressed(openAh);
        if (mc.player == null) return;

        boolean noScreen = mc.screen == null;
        boolean inAh = Sniper.ahOpen && mc.screen instanceof AbstractContainerScreen<?>;

        if (pToggle && (noScreen || inAh)) Sniper.toggle();
        if (pThreshold && (noScreen || inAh)) Sniper.cycleThreshold();
        if (pOpen && noScreen) mc.player.connection.sendCommand("ah");

        if (inAh) {
            if (pNext) Sniper.select(1);
            if (pPrev) Sniper.select(-1);
            if (pBuy) Sniper.buySelected(mc);
        }
    }
}
