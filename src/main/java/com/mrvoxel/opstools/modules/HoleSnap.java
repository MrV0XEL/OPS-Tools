package com.mrvoxel.opstools.modules;

import com.mrvoxel.opstools.OpsTools;
import com.mrvoxel.opstools.utils.HoleUtils;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.phys.Vec3;

/**
 * Hole Snap - pulls you into the closest safe hole (bedrock / obsidian walls and floor).
 *
 * Moves you horizontally toward the hole's center at a fixed speed and lets gravity drop you in. It switches off by
 * itself when you are in the hole, when the hole gets blocked, when you keep running into walls, when the server
 * rubberbands you (so it won't fight an anti-cheat), or after {@code max-ticks}.
 */
public class HoleSnap extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Horizontal search range for holes.")
        .defaultValue(4).min(1).max(8).sliderMax(8)
        .build()
    );
    private final Setting<Integer> downRange = sgGeneral.add(new IntSetting.Builder()
        .name("down-range")
        .description("How far below you holes are searched.")
        .defaultValue(3).min(0).max(6).sliderMax(6)
        .build()
    );
    private final Setting<Boolean> singleHoles = sgGeneral.add(new BoolSetting.Builder()
        .name("single-holes")
        .description("Use 1x1 holes.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> doubleHoles = sgGeneral.add(new BoolSetting.Builder()
        .name("double-holes")
        .description("Use 2x1 holes.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Horizontal speed in blocks per tick (sprinting is about 0.28).")
        .defaultValue(0.28).min(0.05).max(0.6).sliderMax(0.6)
        .build()
    );
    private final Setting<Boolean> jump = sgGeneral.add(new BoolSetting.Builder()
        .name("jump")
        .description("Jump when you run into a block on the way.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> lockTarget = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-target")
        .description("Keep the hole chosen at activation instead of re-picking the nearest one every tick.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxTicks = sgSafety.add(new IntSetting.Builder()
        .name("max-ticks")
        .description("Give up after this many ticks (20 = 1 second).")
        .defaultValue(60).min(10).sliderMax(200)
        .build()
    );
    private final Setting<Integer> maxCollisions = sgSafety.add(new IntSetting.Builder()
        .name("collisions-to-disable")
        .description("Turn off after this many ticks of being stuck against a wall (0 = never).")
        .defaultValue(15).min(0).sliderMax(60)
        .build()
    );
    private final Setting<Integer> rubberbands = sgSafety.add(new IntSetting.Builder()
        .name("rubberbands-to-disable")
        .description("Turn off after the server teleports you back this many times (0 = never).")
        .defaultValue(2).min(0).sliderMax(10)
        .build()
    );

    private HoleUtils.Hole target = null;
    private int ticks = 0;
    private int collisions = 0;
    private int rubberbandCount = 0;

    public HoleSnap() {
        super(OpsTools.CATEGORY, "hole-snap", "Walks you into the nearest safe hole.");
    }

    @Override
    public void onActivate() {
        ticks = 0;
        collisions = 0;
        rubberbandCount = 0;
        target = null;

        if (mc.player == null || mc.level == null) return;
        target = findHole();
        if (target == null) {
            info("No hole found in range.");
            toggle();
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (rubberbands.get() > 0 && event.packet instanceof ClientboundPlayerPositionPacket) {
            rubberbandCount++;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (rubberbands.get() > 0 && rubberbandCount >= rubberbands.get()) {
            info("The server moved you back - turning off.");
            toggle();
            return;
        }
        if (++ticks > maxTicks.get()) {
            info("Couldn't reach the hole in time - turning off.");
            toggle();
            return;
        }

        HoleUtils.Hole hole = lockTarget.get() && target != null ? target : findHole();
        if (hole == null || !hole.isStillOpen()) {
            info("The hole is gone or blocked - turning off.");
            toggle();
            return;
        }
        target = hole;

        Vec3 pos = mc.player.position();
        double dx = hole.center().x - pos.x;
        double dz = hole.center().z - pos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        Vec3 motion = mc.player.getDeltaMovement();

        // Standing in the middle of it: done.
        if (dist < 0.12 && hole.contains(mc.player.blockPosition())) {
            mc.player.setDeltaMovement(0, motion.y, 0);
            info("You are in the hole.");
            toggle();
            return;
        }

        // Already over the cells but sunk below the rim: just stop sliding about.
        if (dist < 0.12) {
            mc.player.setDeltaMovement(0, motion.y, 0);
            return;
        }

        double step = Math.min(speed.get(), dist);
        double vx = dx / dist * step;
        double vz = dz / dist * step;

        double vy = motion.y;
        if (jump.get() && mc.player.onGround() && mc.player.horizontalCollision) vy = 0.42;

        // Count being stuck against something while not making progress.
        if (mc.player.horizontalCollision) {
            collisions++;
            if (maxCollisions.get() > 0 && collisions >= maxCollisions.get()) {
                info("Stuck on the way - turning off.");
                toggle();
                return;
            }
        } else {
            collisions = 0;
        }

        mc.player.setDeltaMovement(vx, vy, vz);
    }

    // ------------------------------------------------------------------ search

    /** Closest hole around you (one you already stand in wins immediately). */
    private HoleUtils.Hole findHole() {
        BlockPos origin = mc.player.blockPosition();
        Vec3 position = mc.player.position();
        int r = range.get();

        HoleUtils.Hole closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 0; dy >= -downRange.get(); dy--) {
                    BlockPos pos = origin.offset(dx, dy, dz);

                    HoleUtils.Hole hole = null;
                    if (singleHoles.get()) hole = HoleUtils.single(pos);
                    if (hole == null && doubleHoles.get()) hole = HoleUtils.dbl(pos);
                    if (hole == null) continue;

                    if (hole.contains(origin)) return hole;

                    double dist = hole.center().distanceTo(position);
                    if (dist < closestDist) {
                        closest = hole;
                        closestDist = dist;
                    }
                }
            }
        }
        return closest;
    }
}
