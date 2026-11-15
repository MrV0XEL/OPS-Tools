package com.mrvoxel.opstools.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Finds "holes": 1x1 or 2x1 pits whose floor and walls are explosion-resistant blocks, with open air above.
 */
public final class HoleUtils {
    private HoleUtils() {}

    /** Blocks that count as safe walls / floor. */
    private static final Set<Block> SAFE = Set.of(
        Blocks.BEDROCK, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERITE_BLOCK,
        Blocks.ENDER_CHEST, Blocks.RESPAWN_ANCHOR, Blocks.ENCHANTING_TABLE,
        Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL
    );

    /** @param cells the block(s) you stand in; {@code center} is the horizontal middle at floor level. */
    public record Hole(List<BlockPos> cells, Vec3 center) {
        public boolean contains(BlockPos pos) {
            return cells.contains(pos);
        }

        public boolean isStillOpen() {
            for (BlockPos cell : cells) {
                if (!mc.level.getBlockState(cell).isAir() || !mc.level.getBlockState(cell.above()).isAir()) return false;
            }
            return true;
        }
    }

    private static boolean safe(BlockPos pos) {
        return SAFE.contains(mc.level.getBlockState(pos).getBlock());
    }

    private static boolean open(BlockPos pos) {
        return mc.level.getBlockState(pos).isAir() && mc.level.getBlockState(pos.above()).isAir();
    }

    /** A 1x1 hole at {@code pos}, or null. */
    public static Hole single(BlockPos pos) {
        if (!open(pos) || !safe(pos.below())) return null;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (!safe(pos.relative(dir))) return null;
        }
        return new Hole(List.of(pos), Vec3.atBottomCenterOf(pos));
    }

    /** A 2x1 hole whose cells are {@code pos} and its EAST or SOUTH neighbour, or null. */
    public static Hole dbl(BlockPos pos) {
        for (Direction axis : new Direction[]{Direction.EAST, Direction.SOUTH}) {
            BlockPos other = pos.relative(axis);
            if (!open(pos) || !open(other)) continue;
            if (!safe(pos.below()) || !safe(other.below())) continue;

            boolean walled = true;
            for (BlockPos cell : new BlockPos[]{pos, other}) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos side = cell.relative(dir);
                    if (side.equals(pos) || side.equals(other)) continue;
                    if (!safe(side)) {
                        walled = false;
                        break;
                    }
                }
                if (!walled) break;
            }
            if (!walled) continue;

            Vec3 center = new Vec3((pos.getX() + other.getX()) / 2.0 + 0.5, pos.getY(), (pos.getZ() + other.getZ()) / 2.0 + 0.5);
            return new Hole(List.of(pos, other), center);
        }
        return null;
    }
}
