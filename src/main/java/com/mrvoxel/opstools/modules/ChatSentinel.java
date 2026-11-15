package com.mrvoxel.opstools.modules;

import com.mrvoxel.opstools.OpsTools;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Chat Sentinel - chat filter, keyword alerts and a movable vanilla chat.
 *
 *  - Mute rules: messages that match are removed (exceptions rescue a message from being muted).
 *  - Highlight rules: matching messages are colored.
 *  - Alert keywords: names / words that play a sound and/or pop a toast. Your own name can be added automatically.
 *  - Every rule is a regex (or a plain word when "regex" is off).
 *  - The normal chat can be moved and resized by dragging it while the chat is open, and its black background
 *    can be removed. Moving needs the ChatComponentMixin that comes with this module.
 *
 * Chat controls (chat input open):
 *    drag with the left mouse button on the chat          move the chat anywhere on the screen
 *    Shift + drag on the chat                             resize the chat
 *    right click on the chat                              put the chat back in its default place
 *  If "require-drag-key" is on, the drag key (Left Alt) must be held for all of the above.
 */
public class ChatSentinel extends Module {
    public enum AlertSound {
        Ping(SoundEvents.EXPERIENCE_ORB_PICKUP),
        LevelUp(SoundEvents.PLAYER_LEVELUP),
        Bell(SoundEvents.BELL_BLOCK),
        Anvil(SoundEvents.ANVIL_LAND),
        Chime(SoundEvents.AMETHYST_BLOCK_CHIME);

        final SoundEvent event;
        AlertSound(SoundEvent event) { this.event = event; }
    }

    public enum HighlightMode {
        Line,       // the whole message gets the color (keeps names, hover and click events)
        MatchOnly   // only the matched text is colored (rebuilds the message as plain text)
    }

    private enum DragMode { NONE, MOVE, RESIZE }

    /** The running module, read by the chat mixin. */
    private static ChatSentinel instance;

    /** Set to true by ChatComponentMixin the first time its render hook runs. */
    public static volatile boolean mixinHit = false;
    private boolean warnedMixin = false;

    /** Horizontal chat offset in GUI pixels (0 when the module is off). Used by the mixin. */
    public static float chatDx() {
        return instance != null && instance.isActive() ? instance.chatX.get() : 0f;
    }

    /** Vertical chat offset in GUI pixels (0 when the module is off). Used by the mixin. */
    public static float chatDy() {
        return instance != null && instance.isActive() ? instance.chatY.get() : 0f;
    }

    // ------------------------------------------------------------------ settings

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgChat = settings.createGroup("Chat Window");
    private final SettingGroup sgMute = settings.createGroup("Mute");
    private final SettingGroup sgHighlight = settings.createGroup("Highlight");
    private final SettingGroup sgAlerts = settings.createGroup("Alerts");

    private final Setting<Boolean> regex = sgGeneral.add(new BoolSetting.Builder()
        .name("regex")
        .description("Treat the rules as regular expressions. Off: they are plain words.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> ignoreCase = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-case")
        .description("Ignore upper and lower case when matching.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> ignoreOwn = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-own-messages")
        .description("Do not alert on messages you sent yourself (detected by '<you>' or 'you:' at the start).")
        .defaultValue(true)
        .build()
    );

    // --- chat window
    private final Setting<Boolean> moveChat = sgChat.add(new BoolSetting.Builder()
        .name("move-chat")
        .description("Move and resize the chat by dragging it while the chat input is open.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> requireDragKey = sgChat.add(new BoolSetting.Builder()
        .name("require-drag-key")
        .description("Off: just drag the chat with the left mouse button. On: you must also hold the drag key.")
        .defaultValue(false)
        .visible(moveChat::get)
        .build()
    );
    private final Setting<Keybind> dragKey = sgChat.add(new KeybindSetting.Builder()
        .name("drag-key")
        .description("Held together with the mouse when 'require-drag-key' is on. Shift resizes, right click resets.")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_LEFT_ALT))
        .visible(() -> moveChat.get() && requireDragKey.get())
        .build()
    );
    private final Setting<Integer> chatX = sgChat.add(new IntSetting.Builder()
        .name("chat-x")
        .description("Horizontal chat offset in GUI pixels. Changes when you drag the chat.")
        .defaultValue(0)
        .min(-3000).max(3000).sliderRange(-600, 600)
        .build()
    );
    private final Setting<Integer> chatY = sgChat.add(new IntSetting.Builder()
        .name("chat-y")
        .description("Vertical chat offset in GUI pixels (negative = up). Changes when you drag the chat.")
        .defaultValue(0)
        .min(-3000).max(3000).sliderRange(-600, 600)
        .build()
    );
    private final Setting<Boolean> removeBackground = sgChat.add(new BoolSetting.Builder()
        .name("remove-background")
        .description("Removes the dark background behind chat text and the chat input. Your old value comes back when this is turned off.")
        .defaultValue(true)
        .build()
    );

    // --- mute
    private final Setting<List<String>> muteRules = sgMute.add(new StringListSetting.Builder()
        .name("mute-rules")
        .description("Messages that match any of these are removed. Example regex: ^\\[Server\\] .*vote.*")
        .defaultValue(new ArrayList<>())
        .build()
    );
    private final Setting<List<String>> muteExceptions = sgMute.add(new StringListSetting.Builder()
        .name("mute-exceptions")
        .description("Messages that match one of these are never muted.")
        .defaultValue(new ArrayList<>())
        .build()
    );

    // --- highlight
    private final Setting<List<String>> highlightRules = sgHighlight.add(new StringListSetting.Builder()
        .name("highlight-rules")
        .description("Messages that match any of these are colored.")
        .defaultValue(new ArrayList<>())
        .build()
    );
    private final Setting<SettingColor> highlightColor = sgHighlight.add(new ColorSetting.Builder()
        .name("highlight-color")
        .description("Color of highlighted messages.")
        .defaultValue(new SettingColor(255, 215, 0, 255))
        .build()
    );
    private final Setting<HighlightMode> highlightMode = sgHighlight.add(new EnumSetting.Builder<HighlightMode>()
        .name("highlight-mode")
        .description("Line: color the whole message. MatchOnly: color only the matched text.")
        .defaultValue(HighlightMode.Line)
        .build()
    );

    // --- alerts
    private final Setting<List<String>> alertKeywords = sgAlerts.add(new StringListSetting.Builder()
        .name("alert-keywords")
        .description("Names and keywords that trigger an alert.")
        .defaultValue(new ArrayList<>())
        .build()
    );
    private final Setting<Boolean> alertOwnName = sgAlerts.add(new BoolSetting.Builder()
        .name("alert-own-name")
        .description("Also alert when your own name is mentioned.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> sound = sgAlerts.add(new BoolSetting.Builder()
        .name("sound")
        .description("Play a sound on an alert.")
        .defaultValue(true)
        .build()
    );
    private final Setting<AlertSound> alertSound = sgAlerts.add(new EnumSetting.Builder<AlertSound>()
        .name("sound-type")
        .description("Which sound to play.")
        .defaultValue(AlertSound.Ping)
        .visible(sound::get)
        .build()
    );
    private final Setting<Double> volume = sgAlerts.add(new DoubleSetting.Builder()
        .name("volume")
        .description("Alert volume.")
        .defaultValue(1.0)
        .min(0.0).max(2.0).sliderRange(0.0, 2.0)
        .visible(sound::get)
        .build()
    );
    private final Setting<Double> pitch = sgAlerts.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("Alert pitch.")
        .defaultValue(1.0)
        .min(0.5).max(2.0).sliderRange(0.5, 2.0)
        .visible(sound::get)
        .build()
    );
    private final Setting<Boolean> toast = sgAlerts.add(new BoolSetting.Builder()
        .name("toast")
        .description("Show a toast notification on an alert.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> colorAlerts = sgAlerts.add(new BoolSetting.Builder()
        .name("color-alerts")
        .description("Also color messages that triggered an alert.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> alertColor = sgAlerts.add(new ColorSetting.Builder()
        .name("alert-color")
        .description("Color of messages that triggered an alert.")
        .defaultValue(new SettingColor(255, 85, 85, 255))
        .visible(colorAlerts::get)
        .build()
    );
    private final Setting<Integer> cooldown = sgAlerts.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Minimum milliseconds between two alerts, so a spammer cannot flood you.")
        .defaultValue(1000)
        .min(0).max(10000).sliderRange(0, 5000)
        .build()
    );

    // ------------------------------------------------------------------ state

    /** A list of rules compiled to patterns, rebuilt only when the rules or flags change. */
    private final class RuleSet {
        private String key = null;
        private List<Pattern> patterns = List.of();

        List<Pattern> get(List<String> rules) {
            String newKey = regex.get() + "|" + ignoreCase.get() + "|" + String.join("\u0000", rules);
            if (newKey.equals(key)) return patterns;

            key = newKey;
            List<Pattern> out = new ArrayList<>();
            int flags = ignoreCase.get() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            for (String rule : rules) {
                if (rule == null || rule.isBlank()) continue;
                try {
                    out.add(Pattern.compile(regex.get() ? rule : Pattern.quote(rule), flags));
                } catch (PatternSyntaxException e) {
                    warning("Invalid regex '%s': %s", rule, e.getDescription());
                }
            }
            patterns = out;
            return patterns;
        }
    }

    private final RuleSet muteSet = new RuleSet();
    private final RuleSet exceptionSet = new RuleSet();
    private final RuleSet highlightSet = new RuleSet();
    private final RuleSet alertSet = new RuleSet();

    private String ownNameCached = null;
    private Pattern ownNamePattern = null;
    private Pattern ownSenderPattern = null;

    private long lastAlert = 0;
    private int mutedCount = 0;

    // dragging
    private DragMode dragMode = DragMode.NONE;
    private boolean resetLatch = false;
    private boolean lmbWasDown = false;
    private double startMouseX, startMouseY, startWidth, startHeight;
    private int startOffsetX, startOffsetY;

    // background
    private Double savedBackground = null;

    public ChatSentinel() {
        super(OpsTools.CATEGORY, "chat-sentinel", "Chat filter and keyword alerts (regex mute and highlight, sound and toast), plus a movable chat without the black background.");
        instance = this;
    }

    @Override
    public void onActivate() {
        mutedCount = 0;
    }

    @Override
    public void onDeactivate() {
        endDrag();
        restoreBackground();
    }

    @Override
    public String getInfoString() {
        return mutedCount > 0 ? String.valueOf(mutedCount) : null;
    }

    // ------------------------------------------------------------------ chat background

    private void restoreBackground() {
        if (savedBackground != null && mc.options != null) {
            mc.options.textBackgroundOpacity().set(savedBackground);
            savedBackground = null;
        }
    }

    @EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Post event) {
        if (mc.options == null) return;

        if (removeBackground.get()) {
            if (savedBackground == null) savedBackground = mc.options.textBackgroundOpacity().get();
            if (mc.options.textBackgroundOpacity().get() != 0.0) mc.options.textBackgroundOpacity().set(0.0);
        } else {
            restoreBackground();
        }
    }

    // ------------------------------------------------------------------ moving and resizing the chat

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!moveChat.get() || !(mc.screen instanceof ChatScreen)) {
            endDrag();
            resetLatch = false;
            lmbWasDown = false;
            return;
        }

        int guiW = mc.getWindow().getGuiScaledWidth();
        int guiH = mc.getWindow().getGuiScaledHeight();
        double mx = mc.mouseHandler.xpos() * guiW / Math.max(1, mc.getWindow().getScreenWidth());
        double my = mc.mouseHandler.ypos() * guiH / Math.max(1, mc.getWindow().getScreenHeight());

        boolean keyOk = !requireDragKey.get() || dragKey.get().isPressed();
        boolean lmb = Input.isButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean rmb = Input.isButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        boolean shift = Input.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT) || Input.isKeyPressed(GLFW.GLFW_KEY_RIGHT_SHIFT);
        boolean over = overChat(mx, my, guiH);

        // right click on the chat: back to the default place
        if (keyOk && rmb && (over || dragMode != DragMode.NONE)) {
            if (!resetLatch) {
                chatX.set(0);
                chatY.set(0);
                resetLatch = true;
            }
        } else {
            resetLatch = false;
        }

        if (dragMode == DragMode.NONE) {
            // a drag can only start on a fresh click that lands on the chat
            if (keyOk && lmb && !lmbWasDown && over) beginDrag(shift ? DragMode.RESIZE : DragMode.MOVE, mx, my);
        } else if (!(keyOk && lmb)) {
            endDrag();
        } else {
            updateDrag(mx, my);
        }

        lmbWasDown = lmb;
    }

    /** Rough screen rectangle of the chat (with the current offset), used to decide if a click grabs it. */
    private boolean overChat(double mx, double my, int guiH) {
        double scale = Math.max(0.1, mc.options.chatScale().get());
        double w = (mc.options.chatWidth().get() * 280.0 + 40.0 + 8.0) * scale;
        double h = (mc.options.chatHeightFocused().get() * 160.0 + 20.0) * scale;
        double left = chatX.get();
        double bottom = guiH - 40.0 + chatY.get();
        return mx >= left && mx <= left + w && my >= bottom - h - 4.0 && my <= bottom + 8.0;
    }

    /** Called when a move starts: if the mixin never ran, say so and list the real render signatures. */
    private void diagnoseMixin() {
        if (mixinHit || warnedMixin) return;
        warnedMixin = true;
        warning("The chat mixin is not active, so the chat cannot be moved. Check latest.log.");
        OpsTools.LOG.warn("ChatComponentMixin never ran. Methods of {} that look like render:", ChatComponent.class.getName());
        for (Method m : ChatComponent.class.getDeclaredMethods()) {
            if (m.getName().toLowerCase().contains("render")) {
                OpsTools.LOG.warn("  {} {}", m.getName(), Arrays.toString(m.getParameterTypes()));
            }
        }
    }

    private void beginDrag(DragMode mode, double mx, double my) {
        if (mode == DragMode.MOVE) diagnoseMixin();
        dragMode = mode;
        startMouseX = mx;
        startMouseY = my;
        startOffsetX = chatX.get();
        startOffsetY = chatY.get();
        startWidth = mc.options.chatWidth().get();
        startHeight = mc.options.chatHeightFocused().get();
    }

    private void updateDrag(double mx, double my) {
        if (dragMode == DragMode.MOVE) {
            chatX.set(clamp(startOffsetX + (int) Math.round(mx - startMouseX), -3000, 3000));
            chatY.set(clamp(startOffsetY + (int) Math.round(my - startMouseY), -3000, 3000));
        } else if (dragMode == DragMode.RESIZE) {
            // The chat width is 40..320 px and the height 20..180 px (before scaling); the options store them as 0..1.
            // ChatComponent#getScale() is private, so read the chat scale option directly (same value).
            double scale = Math.max(0.1, mc.options.chatScale().get());
            double width = startWidth + (mx - startMouseX) / scale / 280.0;
            double height = startHeight + (startMouseY - my) / scale / 160.0; // the chat grows upwards
            mc.options.chatWidth().set(Math.max(0.0, Math.min(1.0, width)));
            mc.options.chatHeightFocused().set(Math.max(0.0, Math.min(1.0, height)));
        }
    }

    private void endDrag() {
        if (dragMode == DragMode.RESIZE && mc.options != null) mc.options.save();
        dragMode = DragMode.NONE;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------ messages

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (mc.player == null) return;

        Component message = event.getMessage();
        String text = message.getString();
        if (text.isBlank()) return;

        // 1) mute
        if (find(muteSet.get(muteRules.get()), text) != null && find(exceptionSet.get(muteExceptions.get()), text) == null) {
            event.cancel();
            mutedCount++;
            return;
        }

        refreshOwnName();
        boolean own = ignoreOwn.get() && ownSenderPattern != null && ownSenderPattern.matcher(text).lookingAt();

        // 2) highlight
        int rgb = -1;
        Matcher highlight = find(highlightSet.get(highlightRules.get()), text);
        if (highlight != null) rgb = highlightColor.get().getPacked() & 0xFFFFFF;

        // 3) alerts
        if (!own) {
            Matcher alert = find(alertSet.get(alertKeywords.get()), text);
            if (alert == null && alertOwnName.get() && ownNamePattern != null) {
                Matcher m = ownNamePattern.matcher(text);
                if (m.find()) alert = m;
            }
            if (alert != null) {
                trigger(alert.group(), text);
                if (colorAlerts.get()) {
                    rgb = alertColor.get().getPacked() & 0xFFFFFF;
                    highlight = alert;
                }
            }
        }

        // 4) color the message
        if (rgb != -1) {
            final int color = rgb;
            if (highlightMode.get() == HighlightMode.MatchOnly && highlight != null && !highlight.group().isEmpty()) {
                String match = highlight.group();
                int start = highlight.start();
                int end = highlight.end();
                event.setMessage(Component.literal(text.substring(0, start))
                    .append(Component.literal(match).withStyle(s -> s.withColor(color)))
                    .append(Component.literal(text.substring(end))));
            } else {
                event.setMessage(message.copy().withStyle(s -> s.withColor(color)));
            }
        }
    }

    private static Matcher find(List<Pattern> patterns, String text) {
        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            if (m.find()) return m;
        }
        return null;
    }

    private void refreshOwnName() {
        String name = mc.player.getName().getString();
        if (name.equals(ownNameCached)) return;

        ownNameCached = name;
        int flags = Pattern.CASE_INSENSITIVE;
        ownNamePattern = Pattern.compile("\\b" + Pattern.quote(name) + "\\b", flags);
        // "<n>", "name:", "name >", "[Rank] name »" ... at the start of the line
        ownSenderPattern = Pattern.compile("^(?:\\[[^\\]]*\\]\\s*)*(?:\\S+\\s+)?<?" + Pattern.quote(name) + ">?\\s*(?::|»|>)", flags);
    }

    // ------------------------------------------------------------------ alerts

    private void trigger(String matched, String text) {
        long now = System.currentTimeMillis();
        if (now - lastAlert < cooldown.get()) return;
        lastAlert = now;

        if (sound.get()) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(alertSound.get().event, pitch.get().floatValue(), volume.get().floatValue()));
        }
        if (toast.get()) {
            String body = text.length() > 90 ? text.substring(0, 90) + "..." : text;
            SystemToast.addOrUpdate(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Chat alert: " + matched), Component.literal(body));
        }
    }
}
