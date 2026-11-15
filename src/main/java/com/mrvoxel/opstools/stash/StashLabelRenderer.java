package com.mrvoxel.opstools.stash;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Draws floating text in the world that always faces the camera.
 *
 * The text is made of line strokes (a tiny built-in vector font) drawn with Meteor's own line renderer, so it does not
 * depend on any font / text-rendering API that changes between Minecraft versions. Supported characters: A-Z, 0-9 and
 * {@code [ ] + - _ . : / % ! X}; anything else is shown as a gap.
 */
public final class StashLabelRenderer {
    private StashLabelRenderer() {}

    /** Glyph grid: x 0..4, y 0..6 (y up). Polylines are separated by '|', points by spaces. */
    private static final Map<Character, double[][][]> GLYPHS = new HashMap<>();

    private static final double ADVANCE = 6.0;   // glyph width 4 + gap 2
    private static final double HEIGHT = 6.0;
    private static final double UNIT_PER_SCALE = 1.5;

    static {
        glyph('A', "0,0 0,4 2,6 4,4 4,0|0,3 4,3");
        glyph('B', "0,0 0,6 3,6 4,5 4,4 3,3 0,3|3,3 4,2 4,1 3,0 0,0");
        glyph('C', "4,5 3,6 1,6 0,5 0,1 1,0 3,0 4,1");
        glyph('D', "0,0 0,6 3,6 4,5 4,1 3,0 0,0");
        glyph('E', "4,6 0,6 0,0 4,0|0,3 3,3");
        glyph('F', "4,6 0,6 0,0|0,3 3,3");
        glyph('G', "4,5 3,6 1,6 0,5 0,1 1,0 3,0 4,1 4,3 2,3");
        glyph('H', "0,0 0,6|4,0 4,6|0,3 4,3");
        glyph('I', "1,6 3,6|2,6 2,0|1,0 3,0");
        glyph('J', "4,6 4,1 3,0 1,0 0,1");
        glyph('K', "0,0 0,6|4,6 0,3 4,0");
        glyph('L', "0,6 0,0 4,0");
        glyph('M', "0,0 0,6 2,3 4,6 4,0");
        glyph('N', "0,0 0,6 4,0 4,6");
        glyph('O', "1,0 0,1 0,5 1,6 3,6 4,5 4,1 3,0 1,0");
        glyph('P', "0,0 0,6 3,6 4,5 4,4 3,3 0,3");
        glyph('Q', "1,0 0,1 0,5 1,6 3,6 4,5 4,1 3,0 1,0|2,2 4,0");
        glyph('R', "0,0 0,6 3,6 4,5 4,4 3,3 0,3|2,3 4,0");
        glyph('S', "4,5 3,6 1,6 0,5 0,4 1,3 3,3 4,2 4,1 3,0 1,0 0,1");
        glyph('T', "0,6 4,6|2,6 2,0");
        glyph('U', "0,6 0,1 1,0 3,0 4,1 4,6");
        glyph('V', "0,6 2,0 4,6");
        glyph('W', "0,6 1,0 2,3 3,0 4,6");
        glyph('X', "0,6 4,0|4,6 0,0");
        glyph('Y', "0,6 2,3 4,6|2,3 2,0");
        glyph('Z', "0,6 4,6 0,0 4,0");

        glyph('0', "1,0 0,1 0,5 1,6 3,6 4,5 4,1 3,0 1,0|0,1 4,5");
        glyph('1', "1,5 2,6 2,0|1,0 3,0");
        glyph('2', "0,5 1,6 3,6 4,5 4,4 0,0 4,0");
        glyph('3', "0,5 1,6 3,6 4,5 4,4 3,3 1,3|3,3 4,2 4,1 3,0 1,0 0,1");
        glyph('4', "3,0 3,6 0,2 4,2");
        glyph('5', "4,6 0,6 0,3 3,3 4,2 4,1 3,0 1,0 0,1");
        glyph('6', "4,5 3,6 1,6 0,5 0,1 1,0 3,0 4,1 4,2 3,3 0,3");
        glyph('7', "0,6 4,6 1,0");
        glyph('8', "1,3 0,4 0,5 1,6 3,6 4,5 4,4 3,3 1,3 0,2 0,1 1,0 3,0 4,1 4,2 3,3");
        glyph('9', "0,1 1,0 3,0 4,1 4,5 3,6 1,6 0,5 0,4 1,3 4,3");

        glyph('[', "3,6 1,6 1,0 3,0");
        glyph(']', "1,6 3,6 3,0 1,0");
        glyph('+', "2,1 2,5|0,3 4,3");
        glyph('-', "0,3 4,3");
        glyph('_', "0,0 4,0");
        glyph('.', "2,0 2,1");
        glyph(':', "2,1 2,2|2,4 2,5");
        glyph('/', "0,0 4,6");
        glyph('%', "0,0 4,6|0,6 1,6 1,5 0,5 0,6|3,1 4,1 4,0 3,0 3,1");
        glyph('!', "2,2 2,6|2,0 2,1");
    }

    private static void glyph(char c, String path) {
        String[] lines = path.split("\\|");
        double[][][] strokes = new double[lines.length][][];
        for (int i = 0; i < lines.length; i++) {
            String[] points = lines[i].trim().split(" ");
            strokes[i] = new double[points.length][2];
            for (int j = 0; j < points.length; j++) {
                String[] xy = points[j].split(",");
                strokes[i][j][0] = Double.parseDouble(xy[0]);
                strokes[i][j][1] = Double.parseDouble(xy[1]);
            }
        }
        GLYPHS.put(c, strokes);
    }

    /**
     * @param center the world position the text is centered on
     * @param eye    the camera position (the text turns to face it)
     * @param scale  blocks per font unit (0.035 is a good default); a character is about 6 * scale * 1.5 blocks tall
     */
    public static void draw(Render3DEvent event, String text, Vec3 center, Vec3 eye, double scale, Color color) {
        if (text == null || text.isEmpty()) return;

        // Camera-facing axes: right = dir x up, up = right x dir.
        Vec3 dir = center.subtract(eye);
        if (dir.lengthSqr() < 1.0E-6) return;
        dir = dir.normalize();

        Vec3 right = new Vec3(-dir.z, 0, dir.x); // dir x (0,1,0)
        double horizontal = Math.sqrt(right.x * right.x + right.z * right.z);
        right = horizontal < 1.0E-4 ? new Vec3(1, 0, 0) : new Vec3(right.x / horizontal, 0, right.z / horizontal);
        Vec3 up = new Vec3(
            right.y * dir.z - right.z * dir.y,
            right.z * dir.x - right.x * dir.z,
            right.x * dir.y - right.y * dir.x
        );

        double unit = scale * UNIT_PER_SCALE;
        String upper = text.toUpperCase(Locale.ROOT);
        double width = (upper.length() * ADVANCE - (ADVANCE - 4.0)) * unit;
        double startX = -width / 2.0;
        double startY = -HEIGHT * unit / 2.0;

        for (int i = 0; i < upper.length(); i++) {
            double[][][] strokes = GLYPHS.get(upper.charAt(i));
            if (strokes == null) continue; // space / unknown character: just a gap

            double offsetX = startX + i * ADVANCE * unit;
            for (double[][] stroke : strokes) {
                for (int p = 0; p + 1 < stroke.length; p++) {
                    Vec3 a = point(center, right, up, offsetX + stroke[p][0] * unit, startY + stroke[p][1] * unit);
                    Vec3 b = point(center, right, up, offsetX + stroke[p + 1][0] * unit, startY + stroke[p + 1][1] * unit);
                    event.renderer.line(a.x, a.y, a.z, b.x, b.y, b.z, color);
                }
            }
        }
    }

    private static Vec3 point(Vec3 center, Vec3 right, Vec3 up, double x, double y) {
        return new Vec3(
            center.x + right.x * x + up.x * y,
            center.y + right.y * x + up.y * y,
            center.z + right.z * x + up.z * y
        );
    }
}
