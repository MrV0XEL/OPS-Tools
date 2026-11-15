package com.mrvoxel.opstools.modules;

import com.mrvoxel.opstools.OpsTools;
import com.mrvoxel.opstools.utils.PvpUtils;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto Trap - walls in the nearest enemy with obsidian (or whatever blocks you allow).
 *
 * The cage is built around the enemy's real hitbox, so players standing across two blocks are handled correctly.
 * Order: feet ring (Full mode only) -> head ring -> top. If a spot has nothing to place against, one support block
 * is placed next to it first.
 */
public class AutoTrap extends Module {
    public enum TrapMode {
        Top("Only the block above the head"),
        Eyes("Only the ring at head height"),
        Both("Head ring + top"),
        Full("Feet ring + head ring + top");

        private final String description;
        TrapMode(String description) { this.description = description; }
        @Override public String toString() { return name(); }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Only enemies within this many blocks of you are trapped.")
        .defaultValue(6).min(2).max(12).sliderMax(12)
        .build()
    );
    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Max distance from your eyes to the block being placed against.")
        .defaultValue(4.5).min(2).max(6).sliderMax(6)
        .build()
    );
    private final Setting<TrapMode> mode = sgGeneral.add(new EnumSetting.Builder<TrapMode>()
        .name("mode")
        .description("Which parts of the cage to build.")
        .defaultValue(TrapMode.Both)
        .build()
    );
    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks that may be used. They must be in your hotbar.")
        .defaultValue(List.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERITE_BLOCK))
        .build()
    );
    private final Setting<Integer> placesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("places-per-tick")
        .description("Blocks placed each tick.")
        .defaultValue(1).min(1).max(4).sliderMax(4)
        .build()
    );
    private final Setting<Integer> spotDelay = sgGeneral.add(new IntSetting.Builder()
        .name("spot-delay")
        .description("Ticks before the same spot may be tried again (about 1.5x your ping in ticks).")
        .defaultValue(6).min(1).sliderMax(20)
        .build()
    );
    private final Setting<Boolean> support = sgGeneral.add(new BoolSetting.Builder()
        .name("support-blocks")
        .description("Place a support block when a spot has nothing to be placed against.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> cevFriendly = sgGeneral.add(new BoolSetting.Builder()
        .name("cev-friendly")
        .description("Don't place a block that would sit directly under an end crystal.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Look at the block before placing (server-side rotation).")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing your hand when placing.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> pauseEat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-eat")
        .description("Do nothing while you are eating / drinking.")
        .defaultValue(false)
        .build()
    );
    private final Setting<Boolean> disableOnMove = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-move")
        .description("Turn off when you move to a different block horizontally.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw the blocks that still need to be placed.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill color.")
        .defaultValue(new SettingColor(255, 0, 0, 40, false))
        .build()
    );
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline color.")
        .defaultValue(new SettingColor(255, 0, 0, 255, false))
        .build()
    );

    private record Plan(BlockPos wanted, BlockPos place, BlockHitResult hit) {}

    private final Map<BlockPos, Integer> cooldowns = new HashMap<>();
    private final List<BlockPos> pending = new ArrayList<>();
    private BlockPos startPos = null;
    private int tickCounter = 0;
    private boolean warnedBlocks = false;

    public AutoTrap() {
        super(OpsTools.CATEGORY, "auto-trap-plus", "Traps the nearest enemy in obsidian.");
    }

    @Override
    public void onActivate() {
        cooldowns.clear();
        pending.clear();
        tickCounter = 0;
        warnedBlocks = false;
        startPos = mc.player != null ? mc.player.blockPosition() : null;
    }

    @Override
    public void onDeactivate() {
        pending.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        tickCounter++;
        pending.clear();

        if (disableOnMove.get() && startPos != null) {
            BlockPos now = mc.player.blockPosition();
            if (now.getX() != startPos.getX() || now.getZ() != startPos.getZ()) {
                info("You moved - turning off.");
                toggle();
                return;
            }
        }
        if (pauseEat.get() && mc.player.isUsingItem()) return;

        cooldowns.values().removeIf(tick -> tickCounter - tick > spotDelay.get());

        Player target = PvpUtils.nearestEnemy(targetRange.get());
        if (target == null) return;

        // Work out what still needs to be built (also used for the render).
        List<Plan> plans = new ArrayList<>();
        for (BlockPos wanted : cage(target)) {
            if (!mc.level.getBlockState(wanted).isAir()) continue;
            if (cevFriendly.get() && crystalAbove(wanted)) continue;
            if (PvpUtils.entityBlocks(wanted)) continue; // someone is standing in that spot

            Plan plan = plan(wanted);
            if (plan != null) {
                pending.add(wanted);
                plans.add(plan);
            }
        }
        if (plans.isEmpty()) return;

        int slot = PvpUtils.findHotbar(this::isAllowedBlock);
        if (slot == -1) {
            if (!warnedBlocks) {
                warning("No allowed building blocks (obsidian by default) in your hotbar.");
                warnedBlocks = true;
            }
            return;
        }
        warnedBlocks = false;

        int placed = 0;
        for (Plan plan : plans) {
            if (placed >= placesPerTick.get()) break;
            if (cooldowns.containsKey(plan.place())) continue;

            PvpUtils.useSlot(slot, () -> {
                if (rotate.get()) PvpUtils.faceVec(plan.hit().getLocation());
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, plan.hit());
                if (swing.get()) mc.player.swing(InteractionHand.MAIN_HAND);
            });
            cooldowns.put(plan.place(), tickCounter);
            placed++;
        }
    }

    // ------------------------------------------------------------------ shape of the cage

    /** Positions of the cage, in build order, based on the enemy's hitbox. */
    private Set<BlockPos> cage(Player target) {
        AABB box = target.getBoundingBox();
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX - 1.0E-4);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ - 1.0E-4);
        int feetY = (int) Math.floor(box.minY + 1.0E-4);

        List<BlockPos> footprint = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) footprint.add(new BlockPos(x, feetY, z));
        }

        Set<BlockPos> out = new LinkedHashSet<>();
        TrapMode m = mode.get();

        if (m == TrapMode.Full) addRing(out, footprint, 0);
        if (m == TrapMode.Eyes || m == TrapMode.Both || m == TrapMode.Full) addRing(out, footprint, 1);
        if (m == TrapMode.Top || m == TrapMode.Both || m == TrapMode.Full) {
            for (BlockPos cell : footprint) out.add(cell.above(2));
        }
        return out;
    }

    /** The blocks horizontally next to the footprint at the given height above the feet. */
    private void addRing(Set<BlockPos> out, List<BlockPos> footprint, int height) {
        Set<BlockPos> layer = new LinkedHashSet<>();
        for (BlockPos cell : footprint) layer.add(cell.above(height));

        for (BlockPos cell : layer) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos neighbour = cell.relative(dir);
                if (!layer.contains(neighbour)) out.add(neighbour);
            }
        }
    }

    // ------------------------------------------------------------------ placing

    /** How to get a block into {@code wanted}: directly, or via one support block. Null if impossible right now. */
    private Plan plan(BlockPos wanted) {
        double range = placeRange.get();

        BlockHitResult direct = PvpUtils.supportHit(wanted, range);
        if (direct != null) return new Plan(wanted, wanted, direct);

        if (!support.get()) return null;

        for (Direction dir : Direction.values()) {
            BlockPos helper = wanted.relative(dir);
            if (!mc.level.getBlockState(helper).isAir() || PvpUtils.entityBlocks(helper)) continue;

            BlockHitResult hit = PvpUtils.supportHit(helper, range);
            if (hit != null) return new Plan(wanted, helper, hit);
        }
        return null;
    }

    private boolean isAllowedBlock(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blocks.get().contains(blockItem.getBlock());
    }

    private boolean crystalAbove(BlockPos pos) {
        return !mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(pos.above())).isEmpty();
    }

    // ------------------------------------------------------------------ rendering

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || pending.isEmpty()) return;
        for (BlockPos pos : pending) {
            event.renderer.box(
                pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                sideColor.get(), lineColor.get(), ShapeMode.Both, 0
            );
        }
    }
}
