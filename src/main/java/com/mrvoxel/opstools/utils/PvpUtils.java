package com.mrvoxel.opstools.utils;

import meteordevelopment.meteorclient.systems.friends.Friends;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Small toolbox shared by the PvP modules: hotbar lookup, silent slot switching, rotation packets, block placement
 * hits, target selection and a self-contained explosion damage estimate.
 *
 * Everything here only uses plain Minecraft + Meteor APIs, so the modules do not depend on any other addon.
 */
public final class PvpUtils {
    private PvpUtils() {}

    // ------------------------------------------------------------------ inventory

    /** First hotbar slot (0-8) whose stack matches, or -1. */
    public static int findHotbar(Predicate<ItemStack> predicate) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (predicate.test(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    public static int findHotbar(Item item) {
        return findHotbar(stack -> stack.getItem() == item);
    }

    /**
     * Runs the action with the given hotbar slot selected, then switches back. The slot change is sent to the server
     * by the game right before the first interaction packet, and the switch back is sent explicitly (same technique
     * as the Map Placer), so nothing visible happens in your hotbar.
     */
    public static void useSlot(int slot, Runnable action) {
        Inventory inv = mc.player.getInventory();
        int previous = inv.getSelectedSlot();
        boolean switched = slot != previous;

        if (switched) inv.setSelectedSlot(slot);
        try {
            action.run();
        } finally {
            if (switched) {
                inv.setSelectedSlot(previous);
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(previous));
            }
        }
    }

    // ------------------------------------------------------------------ rotation

    public static void faceAngles(float yaw, float pitch) {
        mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, mc.player.onGround(), mc.player.horizontalCollision));
    }

    public static void faceVec(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        faceAngles(yaw, pitch);
    }

    // ------------------------------------------------------------------ blocks

    public static boolean isNether() {
        return mc.level != null && mc.level.dimension() == Level.NETHER;
    }

    /** True if a living thing (not an item / xp orb / spectator) stands where a block would be placed. */
    public static boolean entityBlocks(BlockPos pos) {
        return !mc.level.getEntitiesOfClass(
            Entity.class,
            new AABB(pos),
            e -> !e.isSpectator() && !(e instanceof ItemEntity) && !(e instanceof ExperienceOrb)
        ).isEmpty();
    }

    /** Blocks we must not right-click while "placing against" them (they would open or trigger instead). */
    private static boolean isInteractive(BlockState state) {
        return state.getBlock() instanceof EntityBlock
            || state.getBlock() == Blocks.RESPAWN_ANCHOR
            || state.getBlock() == Blocks.CRAFTING_TABLE
            || state.getBlock() == Blocks.ANVIL
            || state.getBlock() == Blocks.NOTE_BLOCK
            || state.getBlock() == Blocks.LEVER;
    }

    /**
     * A normal-looking click on a neighbouring block's face that places a block at {@code pos}, or null if no suitable
     * neighbour exists within {@code maxDistance} of your eyes. Picks the closest face.
     */
    public static BlockHitResult supportHit(BlockPos pos, double maxDistance) {
        return supportHit(pos, maxDistance, neighbour -> true);
    }

    /** Same as {@link #supportHit(BlockPos, double)} but only neighbours accepted by {@code allowed} are considered. */
    public static BlockHitResult supportHit(BlockPos pos, double maxDistance, Predicate<BlockPos> allowed) {
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult best = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            BlockPos neighbour = pos.relative(dir);
            if (!allowed.test(neighbour)) continue;
            BlockState state = mc.level.getBlockState(neighbour);
            if (state.isAir() || !state.getFluidState().isEmpty() || isInteractive(state)) continue;

            Direction face = dir.getOpposite();
            Vec3 location = Vec3.atCenterOf(neighbour).relative(face, 0.5);
            double dist = eye.distanceTo(location);
            if (dist > maxDistance || dist >= bestDist) continue;

            best = new BlockHitResult(location, face, neighbour, false);
            bestDist = dist;
        }
        return best;
    }

    /** A click on the face of {@code pos} that points at the player (for charging / exploding anchors etc.). */
    public static BlockHitResult clickHit(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        Direction face = faceTowards(eye, center);
        return new BlockHitResult(center.relative(face, 0.5), face, pos, false);
    }

    public static Direction faceTowards(Vec3 eye, Vec3 center) {
        double dx = eye.x - center.x;
        double dy = eye.y - center.y;
        double dz = eye.z - center.z;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** True if a block can be placed into this position (air, fluid, grass, snow...). */
    public static boolean replaceable(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    /**
     * Runs the action with a matching item in the main hand, searching the hotbar first and then the main inventory
     * (the stack is swapped into your selected slot for the action and swapped straight back). @return false if you
     * carry no matching item at all.
     */
    public static boolean withItem(Predicate<ItemStack> predicate, Runnable action) {
        int hotbar = findHotbar(predicate);
        if (hotbar != -1) {
            useSlot(hotbar, action);
            return true;
        }

        Inventory inv = mc.player.getInventory();
        int found = -1;
        for (int i = 9; i < 36; i++) {
            if (predicate.test(inv.getItem(i))) {
                found = i;
                break;
            }
        }
        if (found == -1) return false;

        int menuId = mc.player.inventoryMenu.containerId;
        int selected = inv.getSelectedSlot();
        // Main inventory index 9..35 is the same number as its slot id in the player inventory menu.
        mc.gameMode.handleInventoryMouseClick(menuId, found, selected, ClickType.SWAP, mc.player);
        try {
            action.run();
        } finally {
            mc.gameMode.handleInventoryMouseClick(menuId, found, selected, ClickType.SWAP, mc.player);
        }
        return true;
    }

    /**
     * Places a block by clicking {@code hit}, using whichever hand / hotbar / inventory slot holds a matching item.
     * @return false if no matching item was found anywhere.
     */
    public static boolean placeAgainst(Predicate<ItemStack> predicate, BlockHitResult hit, boolean rotate, boolean swing) {
        Runnable mainHand = () -> {
            if (rotate) faceVec(hit.getLocation());
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            if (swing) mc.player.swing(InteractionHand.MAIN_HAND);
        };

        if (predicate.test(mc.player.getMainHandItem())) {
            mainHand.run();
            return true;
        }
        if (predicate.test(mc.player.getOffhandItem())) {
            if (rotate) faceVec(hit.getLocation());
            mc.gameMode.useItemOn(mc.player, InteractionHand.OFF_HAND, hit);
            if (swing) mc.player.swing(InteractionHand.OFF_HAND);
            return true;
        }
        return withItem(predicate, mainHand);
    }

    // ------------------------------------------------------------------ players

    public static boolean isFriend(Player player) {
        return Friends.get().isFriend(player);
    }

    /** Closest alive, non-spectating, non-friend player within range, or null. */
    public static Player nearestEnemy(double range) {
        Player best = null;
        double bestDist = range * range;
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isSpectator() || !player.isAlive() || isFriend(player)) continue;
            double dist = mc.player.distanceToSqr(player);
            if (dist <= bestDist) {
                best = player;
                bestDist = dist;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ explosion damage (estimate)

    /**
     * Estimated damage an explosion of the given power at {@code center} deals to the entity: vanilla distance falloff,
     * an exposure estimate that treats solid blocks between the blast and the body as cover, and armor reduction.
     *
     * Not modelled (so real damage can be a bit lower): blast protection, resistance, absorption, armor toughness.
     */
    public static double explosionDamage(LivingEntity entity, Vec3 center, double power) {
        double diameter = power * 2.0;
        double distance = Math.sqrt(entity.distanceToSqr(center)) / diameter;
        if (distance > 1.0) return 0.0;

        double impact = (1.0 - distance) * exposure(center, entity.getBoundingBox());
        double damage = (impact * impact + impact) / 2.0 * 7.0 * diameter + 1.0;

        double armor = entity.getArmorValue();
        double reduction = Math.min(20.0, Math.max(armor / 5.0, armor - damage / 2.0));
        damage *= 1.0 - reduction / 25.0;

        return Math.max(0.0, damage);
    }

    /** Fraction (0..1) of sample points on the body that the blast can "see". */
    public static double exposure(Vec3 center, AABB box) {
        BlockPos centerBlock = BlockPos.containing(center);
        int total = 0;
        int seen = 0;

        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 3; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    double x = box.minX + box.getXsize() * (0.25 + 0.5 * xi);
                    double y = box.minY + box.getYsize() * (0.1 + 0.4 * yi);
                    double z = box.minZ + box.getZsize() * (0.25 + 0.5 * zi);
                    total++;
                    if (clearLine(center, new Vec3(x, y, z), centerBlock)) seen++;
                }
            }
        }
        return seen / (double) total;
    }

    private static boolean clearLine(Vec3 from, Vec3 to, BlockPos ignore) {
        double length = from.distanceTo(to);
        int steps = Math.max(1, (int) Math.ceil(length / 0.3));

        for (int i = 1; i < steps; i++) {
            Vec3 point = from.lerp(to, i / (double) steps);
            BlockPos pos = BlockPos.containing(point);
            if (pos.equals(ignore)) continue;

            BlockState state = mc.level.getBlockState(pos);
            if (!state.isAir() && !state.getCollisionShape(mc.level, pos).isEmpty()) return false;
        }
        return true;
    }
}
