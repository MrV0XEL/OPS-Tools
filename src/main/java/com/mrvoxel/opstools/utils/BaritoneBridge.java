package com.mrvoxel.opstools.utils;

import com.mrvoxel.opstools.OpsTools;
import net.minecraft.core.BlockPos;

/**
 * Optional Baritone integration through reflection, so the addon compiles and loads without Baritone installed.
 *
 * Everything goes through Baritone's public API INTERFACES (IBaritone, ICustomGoalProcess, IPathingBehavior) instead of
 * the implementation classes, which avoids IllegalAccessException on non-public implementations.
 */
public final class BaritoneBridge {
    private static Boolean available = null;

    private BaritoneBridge() {}

    public static boolean isAvailable() {
        if (available == null) {
            try {
                Class.forName("baritone.api.BaritoneAPI");
                available = true;
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    private static Object primaryBaritone() throws Exception {
        Class<?> api = Class.forName("baritone.api.BaritoneAPI");
        Object provider = api.getMethod("getProvider").invoke(null);
        Class<?> providerType = Class.forName("baritone.api.IBaritoneProvider");
        return providerType.getMethod("getPrimaryBaritone").invoke(provider);
    }

    /** Starts pathing to within {@code range} blocks of pos. @return false if Baritone is missing or the call failed. */
    public static boolean goNear(BlockPos pos, int range) {
        if (!isAvailable()) return false;
        try {
            Object baritone = primaryBaritone();
            Class<?> baritoneType = Class.forName("baritone.api.IBaritone");
            Object process = baritoneType.getMethod("getCustomGoalProcess").invoke(baritone);

            Class<?> goalType = Class.forName("baritone.api.pathing.goals.Goal");
            Object goal = Class.forName("baritone.api.pathing.goals.GoalNear")
                .getConstructor(BlockPos.class, int.class).newInstance(pos, range);

            Class.forName("baritone.api.process.ICustomGoalProcess")
                .getMethod("setGoalAndPath", goalType).invoke(process, goal);
            return true;
        } catch (Throwable t) {
            OpsTools.LOG.warn("Baritone call failed: {}", t.toString());
            return false;
        }
    }

    /** Stops whatever Baritone is currently doing. Safe to call when Baritone is missing. */
    public static void cancel() {
        if (!isAvailable()) return;
        try {
            Object baritone = primaryBaritone();
            Class<?> baritoneType = Class.forName("baritone.api.IBaritone");
            Object behavior = baritoneType.getMethod("getPathingBehavior").invoke(baritone);
            Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("cancelEverything").invoke(behavior);
        } catch (Throwable ignored) {
            // nothing sensible to do
        }
    }
}
