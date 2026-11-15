package com.mrvoxel.opstools.modules;

import com.mrvoxel.opstools.OpsTools;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * GUI Particles - animated particles behind the Meteor ClickGUI and HUD editor.
 *
 * Where it draws: Render2DEvent fires with the in-game HUD, and vanilla draws the HUD BEFORE the open screen. So
 * particles drawn there appear in front of the world and behind every GUI widget, with no mixin needed.
 *
 * Resizing: particle positions are stored as fractions of the screen (0..1), so a window resize or GUI scale change
 * just rescales them. Particles wrap around the edges.
 *
 * Drawing goes through GuiGraphics#fill, which the game batches into BufferBuilder quads itself.
 */
public class GuiParticles extends Module {
    public enum ParticleType { Snow, Rain, Stars, Bubbles, Fireflies, Embers, Confetti }
    public enum ColorMode { Static, Rainbow, Gradient }
    public enum MouseMode { None, Repel, Attract }
    public enum Screens { MeteorGui, AnyScreen }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColor = settings.createGroup("Color");
    private final SettingGroup sgMouse = settings.createGroup("Mouse");
    private final SettingGroup sgExtras = settings.createGroup("Extras");

    // ------------------------------------------------------------------ general

    private final Setting<ParticleType> particleType = sgGeneral.add(new EnumSetting.Builder<ParticleType>()
        .name("particle-type")
        .description("Which particles to draw.")
        .defaultValue(ParticleType.Snow)
        .build()
    );
    private final Setting<Screens> screens = sgGeneral.add(new EnumSetting.Builder<Screens>()
        .name("screens")
        .description("MeteorGui: only the ClickGUI, HUD editor and other Meteor screens. AnyScreen: every screen opened in a world.")
        .defaultValue(Screens.MeteorGui)
        .build()
    );
    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Movement speed multiplier.")
        .defaultValue(1.0)
        .min(0.1).max(5.0).sliderRange(0.1, 3.0)
        .build()
    );
    private final Setting<Integer> density = sgGeneral.add(new IntSetting.Builder()
        .name("density")
        .description("Number of particles on screen.")
        .defaultValue(120)
        .min(10).max(600).sliderRange(10, 400)
        .build()
    );
    private final Setting<Double> size = sgGeneral.add(new DoubleSetting.Builder()
        .name("size")
        .description("Particle size multiplier.")
        .defaultValue(1.0)
        .min(0.5).max(3.0).sliderRange(0.5, 3.0)
        .build()
    );
    private final Setting<Double> wind = sgGeneral.add(new DoubleSetting.Builder()
        .name("wind")
        .description("Sideways push on every particle. Negative blows left, positive right.")
        .defaultValue(0.0)
        .min(-2.0).max(2.0).sliderRange(-1.0, 1.0)
        .build()
    );

    // ------------------------------------------------------------------ color

    private final Setting<ColorMode> colorMode = sgColor.add(new EnumSetting.Builder<ColorMode>()
        .name("color-mode")
        .description("Static: one color. Rainbow: cycling hues. Gradient: blends from color to color-2 from top to bottom. Confetti always uses random colors.")
        .defaultValue(ColorMode.Static)
        .build()
    );
    private final Setting<SettingColor> color = sgColor.add(new ColorSetting.Builder()
        .name("color")
        .description("Particle color (its alpha sets the maximum opacity of every mode).")
        .defaultValue(new SettingColor(255, 255, 255, 180))
        .build()
    );
    private final Setting<SettingColor> color2 = sgColor.add(new ColorSetting.Builder()
        .name("color-2")
        .description("Bottom color of the gradient.")
        .defaultValue(new SettingColor(80, 160, 255, 180))
        .visible(() -> colorMode.get() == ColorMode.Gradient)
        .build()
    );
    private final Setting<Double> rainbowSpeed = sgColor.add(new DoubleSetting.Builder()
        .name("rainbow-speed")
        .description("How fast the rainbow cycles.")
        .defaultValue(0.1)
        .min(0.01).max(1.0).sliderRange(0.01, 0.5)
        .visible(() -> colorMode.get() == ColorMode.Rainbow)
        .build()
    );

    // ------------------------------------------------------------------ mouse

    private final Setting<MouseMode> mouseMode = sgMouse.add(new EnumSetting.Builder<MouseMode>()
        .name("mouse")
        .description("Repel: particles flee your cursor. Attract: they are pulled towards it.")
        .defaultValue(MouseMode.None)
        .build()
    );
    private final Setting<Integer> mouseRadius = sgMouse.add(new IntSetting.Builder()
        .name("mouse-radius")
        .description("Distance from the cursor (in GUI pixels) at which particles react.")
        .defaultValue(90)
        .min(20).max(300).sliderRange(30, 200)
        .visible(() -> mouseMode.get() != MouseMode.None)
        .build()
    );
    private final Setting<Double> mouseStrength = sgMouse.add(new DoubleSetting.Builder()
        .name("mouse-strength")
        .description("How strongly particles react to the cursor.")
        .defaultValue(1.0)
        .min(0.1).max(3.0).sliderRange(0.1, 3.0)
        .visible(() -> mouseMode.get() != MouseMode.None)
        .build()
    );

    // ------------------------------------------------------------------ extras

    private final Setting<Integer> backgroundDim = sgExtras.add(new IntSetting.Builder()
        .name("background-dim")
        .description("Darkens the world behind the particles (0 = off).")
        .defaultValue(0)
        .min(0).max(200).sliderRange(0, 150)
        .build()
    );
    private final Setting<Boolean> links = sgExtras.add(new BoolSetting.Builder()
        .name("links")
        .description("Draw lines between particles that are close to each other (not for rain, and only up to 250 particles).")
        .defaultValue(false)
        .build()
    );
    private final Setting<Integer> linkDistance = sgExtras.add(new IntSetting.Builder()
        .name("link-distance")
        .description("Maximum distance (in GUI pixels) for a link.")
        .defaultValue(60)
        .min(20).max(150).sliderRange(20, 120)
        .visible(links::get)
        .build()
    );

    // ------------------------------------------------------------------ state

    private static final int MAX_LINK_PARTICLES = 250;
    private static final int MAX_LINKS = 300;

    private static final class Particle {
        float x, y;     // 0..1 of the screen
        float size;     // 1..3
        float speed;    // 0.6..1.4 individual speed factor
        float phase;    // sway / twinkle phase
        float hue;      // 0..1, for rainbow and confetti
        int px, py;     // pixel position of the last frame (for links)
        int rgb;        // color of the last frame (for links)
    }

    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private long lastNanos = 0;
    private ParticleType lastType = null;

    // per-frame values
    private int frameW, frameH;
    private double mouseX, mouseY;

    public GuiParticles() {
        super(OpsTools.CATEGORY, "gui-particles", "Draws snow, rain, stars, bubbles, fireflies, embers or confetti behind the Meteor ClickGUI and HUD editor.");
    }

    @Override
    public void onDeactivate() {
        particles.clear();
        lastNanos = 0;
        lastType = null;
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        boolean active = screens.get() == Screens.AnyScreen ? mc.screen != null : mc.screen instanceof WidgetScreen;
        if (!active) {
            lastNanos = 0; // avoid a huge time step when a screen is opened again
            return;
        }

        frameW = mc.getWindow().getGuiScaledWidth();
        frameH = mc.getWindow().getGuiScaledHeight();
        if (frameW <= 0 || frameH <= 0) return;

        // cursor in GUI pixels
        mouseX = mc.mouseHandler.xpos() * frameW / Math.max(1, mc.getWindow().getScreenWidth());
        mouseY = mc.mouseHandler.ypos() * frameH / Math.max(1, mc.getWindow().getScreenHeight());

        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min((now - lastNanos) / 1.0E9f, 0.1f);
        lastNanos = now;
        float seconds = now / 1.0E9f;

        ParticleType type = particleType.get();
        if (type != lastType) {
            particles.clear();
            lastType = type;
        }
        syncCount();

        GuiGraphics graphics = event.drawContext;

        if (backgroundDim.get() > 0) {
            graphics.fill(0, 0, frameW, frameH, backgroundDim.get() << 24);
        }

        float speedMul = speed.get().floatValue();
        SettingColor base = color.get();

        for (Particle p : particles) {
            update(p, type, dt, speedMul);
            p.px = (int) (p.x * frameW);
            p.py = (int) (p.y * frameH);
            p.rgb = rgbFor(p, type, base, seconds);
            draw(graphics, p, type, base.a);
        }

        if (links.get() && type != ParticleType.Rain && particles.size() <= MAX_LINK_PARTICLES) {
            drawLinks(graphics, base.a);
        }
    }

    // ------------------------------------------------------------------ simulation

    private void syncCount() {
        int target = density.get();
        while (particles.size() < target) particles.add(spawn());
        while (particles.size() > target) particles.remove(particles.size() - 1);
    }

    private Particle spawn() {
        Particle p = new Particle();
        p.x = random.nextFloat();
        p.y = random.nextFloat();
        p.size = 1f + random.nextFloat() * 2f;
        p.speed = 0.6f + random.nextFloat() * 0.8f;
        p.phase = random.nextFloat() * (float) (Math.PI * 2);
        p.hue = random.nextFloat();
        return p;
    }

    private void update(Particle p, ParticleType type, float dt, float sp) {
        switch (type) {
            case Snow -> {
                p.phase += dt * 1.5f;
                p.y += p.speed * 0.07f * sp * dt;
                p.x += (float) Math.sin(p.phase) * 0.012f * sp * dt;
                if (p.y > 1f) respawnTop(p);
            }
            case Rain -> {
                p.y += p.speed * 0.9f * sp * dt;
                if (p.y > 1f) respawnTop(p);
            }
            case Stars -> {
                p.phase += dt * p.speed * 2f * sp;
                p.x += 0.004f * sp * dt;
            }
            case Bubbles -> {
                p.phase += dt * 2f;
                p.y -= p.speed * 0.08f * sp * dt;
                p.x += (float) Math.sin(p.phase) * 0.012f * sp * dt;
                if (p.y < -0.05f) respawnBottom(p);
            }
            case Fireflies -> {
                p.phase += dt * p.speed * 1.5f * sp;
                p.x += (float) Math.cos(p.phase * 0.7f) * 0.03f * sp * dt;
                p.y += (float) Math.sin(p.phase * 0.9f) * 0.03f * sp * dt;
                if (p.y > 1f) p.y -= 1f;
                else if (p.y < 0f) p.y += 1f;
            }
            case Embers -> {
                p.phase += dt * 6f;
                p.y -= p.speed * 0.10f * sp * dt;
                p.x += (float) Math.sin(p.phase * 0.4f) * 0.02f * sp * dt;
                if (p.y < -0.05f) respawnBottom(p);
            }
            case Confetti -> {
                p.phase += dt * 5f;
                p.y += p.speed * 0.12f * sp * dt;
                p.x += (float) Math.sin(p.phase * 0.5f) * 0.03f * sp * dt;
                if (p.y > 1f) respawnTop(p);
            }
        }

        // wind
        p.x += wind.get().floatValue() * 0.05f * sp * dt;

        // cursor
        if (mouseMode.get() != MouseMode.None && dt > 0f) pushFromMouse(p, dt);

        // wrap around the left and right edges
        if (p.x > 1f) p.x -= 1f;
        else if (p.x < 0f) p.x += 1f;
    }

    private void pushFromMouse(Particle p, float dt) {
        double dx = p.x * frameW - mouseX;
        double dy = p.y * frameH - mouseY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        double radius = mouseRadius.get();
        if (dist >= radius || dist < 3.0) return;

        double force = (1.0 - dist / radius) * 250.0 * mouseStrength.get(); // GUI pixels per second
        if (mouseMode.get() == MouseMode.Attract) force = -force;

        p.x += (float) (dx / dist * force * dt / frameW);
        p.y += (float) (dy / dist * force * dt / frameH);
    }

    private void respawnTop(Particle p) {
        p.y = 0f;
        p.x = random.nextFloat();
    }

    private void respawnBottom(Particle p) {
        p.y = 1.05f;
        p.x = random.nextFloat();
    }

    // ------------------------------------------------------------------ drawing

    private void draw(GuiGraphics g, Particle p, ParticleType type, int maxAlpha) {
        int x = p.px, y = p.py;
        float sz = p.size * size.get().floatValue();
        int s = Math.max(1, Math.round(sz));

        switch (type) {
            case Snow -> g.fill(x, y, x + s, y + s, argb(p.rgb, maxAlpha, 0.55f + 0.15f * p.size));
            case Rain -> g.fill(x, y, x + 1, y + 6 + Math.round(sz * 4f), argb(p.rgb, maxAlpha, 0.6f));
            case Stars -> {
                float twinkle = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(p.phase));
                int c = argb(p.rgb, maxAlpha, twinkle);
                g.fill(x, y, x + 1, y + 1, c);
                if (sz > 1.8f) { // bigger stars get a small cross
                    int arm = Math.max(1, Math.round(sz - 1f));
                    g.fill(x - arm, y, x + arm + 1, y + 1, c);
                    g.fill(x, y - arm, x + 1, y + arm + 1, c);
                }
            }
            case Bubbles -> ring(g, x, y, Math.round(2f + sz * 1.5f), argb(p.rgb, maxAlpha, 0.8f));
            case Fireflies -> {
                float pulse = 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(p.phase * 2f));
                int halo = s + 1;
                g.fill(x - halo, y - halo, x + s + halo, y + s + halo, argb(p.rgb, maxAlpha, 0.18f * pulse)); // glow
                g.fill(x, y, x + s, y + s, argb(p.rgb, maxAlpha, pulse));
            }
            case Embers -> {
                float flicker = 0.4f + 0.6f * (0.5f + 0.5f * (float) Math.sin(p.phase * 1.7f + p.hue * 10f));
                g.fill(x, y, x + s, y + s, argb(p.rgb, maxAlpha, flicker));
            }
            case Confetti -> {
                int w = Math.max(1, Math.round(sz * (0.6f + 0.8f * Math.abs((float) Math.sin(p.phase)))));
                int h = Math.max(2, Math.round(sz * 2f));
                g.fill(x, y, x + w, y + h, argb(p.rgb, maxAlpha, 0.9f));
            }
        }
    }

    /** Lines between nearby particles, fading out with distance. */
    private void drawLinks(GuiGraphics g, int maxAlpha) {
        int maxDist = linkDistance.get();
        int maxDist2 = maxDist * maxDist;
        int drawn = 0;

        for (int i = 0; i < particles.size() && drawn < MAX_LINKS; i++) {
            Particle a = particles.get(i);
            for (int j = i + 1; j < particles.size() && drawn < MAX_LINKS; j++) {
                Particle b = particles.get(j);
                int dx = b.px - a.px, dy = b.py - a.py;
                int d2 = dx * dx + dy * dy;
                if (d2 > maxDist2 || d2 < 4) continue;

                double dist = Math.sqrt(d2);
                int c = argb(a.rgb, maxAlpha, (float) (1.0 - dist / maxDist) * 0.5f);
                int steps = Math.max(1, (int) (dist / 2));
                for (int s = 0; s <= steps; s++) {
                    int x = a.px + dx * s / steps;
                    int y = a.py + dy * s / steps;
                    g.fill(x, y, x + 1, y + 1, c);
                }
                drawn++;
            }
        }
    }

    /** A hollow circle, one scanline at a time. */
    private static void ring(GuiGraphics g, int cx, int cy, int r, int c) {
        int innerR = r - 1;
        for (int dy = -r; dy <= r; dy++) {
            int outer = (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy)));
            int y = cy + dy;
            if (Math.abs(dy) < innerR) {
                int inner = Math.min((int) Math.round(Math.sqrt(innerR * innerR - dy * dy)), outer - 1);
                g.fill(cx - outer, y, cx - inner, y + 1, c);
                g.fill(cx + inner + 1, y, cx + outer + 1, y + 1, c);
            } else {
                g.fill(cx - outer, y, cx + outer + 1, y + 1, c); // top and bottom caps
            }
        }
    }

    // ------------------------------------------------------------------ color helpers

    /** RGB (no alpha) for this particle according to the color mode. */
    private int rgbFor(Particle p, ParticleType type, SettingColor base, float seconds) {
        if (type == ParticleType.Confetti) return hsv(p.hue, 0.75f, 1f);

        return switch (colorMode.get()) {
            case Static -> (base.r << 16) | (base.g << 8) | base.b;
            case Rainbow -> hsv(seconds * rainbowSpeed.get().floatValue() + p.hue * 0.3f, 0.7f, 1f);
            case Gradient -> {
                SettingColor other = color2.get();
                float t = Math.max(0f, Math.min(1f, p.y));
                int r = Math.round(base.r + (other.r - base.r) * t);
                int gr = Math.round(base.g + (other.g - base.g) * t);
                int b = Math.round(base.b + (other.b - base.b) * t);
                yield (r << 16) | (gr << 8) | b;
            }
        };
    }

    private static int argb(int rgb, int maxAlpha, float alphaMul) {
        int a = Math.max(0, Math.min(255, Math.round(maxAlpha * alphaMul)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private static int hsv(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        float scaled = h * 6f;
        int i = (int) scaled;
        float f = scaled - i;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }
}
