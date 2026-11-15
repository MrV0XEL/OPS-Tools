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
 * Self Trap+ - walls YOU in with obsidian (or other allowed blocks) so crystals / pistons / anvils can't reach you.
 *
 * The cage is built around your real hitbox: head-height ring ("Eyes") and/or the block above your head ("Top").
 * If a spot has nothing to be placed against, up to two support blocks are placed first.
 * Turns itself off when you move, change height or sneak (each can be switched off).
 */
public class SelfTrapPlus extends Module {
    public enum SwitchMode {
        Disabled, Silent, Inventory
    }

    public enum TrapMode {
        Top, Eyes, Both
    }

    public enum ToggleYMode {
        Disabled, Up, Down, Full
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacing = settings.createGroup("Placing");
    private final SettingGroup sgToggle = settings.createGroup("Toggle");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- General
    private final Setting<Boolean> pauseEat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-eat").description("Do nothing while you are eating / drinking.").defaultValue(true).build());
    private final Setting<SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<SwitchMode>()
        .name("switch-mode").description("Disabled: hold the blocks yourself. Silent: from the hotbar. Inventory: also from the inventory.")
        .defaultValue(SwitchMode.Silent).build());
    private final Setting<TrapMode> trapMode = sgGeneral.add(new EnumSetting.Builder<TrapMode>()
        .name("trap-mode").description("Which parts of the cage to build.").defaultValue(TrapMode.Both).build());
    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Look at the block before placing (server-side rotation).").defaultValue(true).build());

    // --- Placing
    private final Setting<List<Block>> blocks = sgPlacing.add(new BlockListSetting.Builder()
        .name("blocks").description("Blocks that may be used.")
        .defaultValue(List.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERITE_BLOCK)).build());
    private final Setting<Double> placeRange = sgPlacing.add(new DoubleSetting.Builder()
        .name("place-range").description("Max distance from your eyes to the block being placed against.")
        .defaultValue(4.5).min(2).max(6).sliderMax(6).build());
    private final Setting<Integer> placesPerTick = sgPlacing.add(new IntSetting.Builder()
        .name("places-per-tick").description("Blocks placed each tick.").defaultValue(1).min(1).max(4).sliderMax(4).build());
    private final Setting<Integer> spotDelay = sgPlacing.add(new IntSetting.Builder()
        .name("spot-delay").description("Ticks before the same spot may be tried again (about 1.5x your ping in ticks).")
        .defaultValue(6).min(1).sliderMax(20).build());
    private final Setting<Boolean> support = sgPlacing.add(new BoolSetting.Builder()
        .name("support-blocks").description("Place support blocks (up to two deep) when a spot has nothing to place against.")
        .defaultValue(true).build());
    private final Setting<Boolean> swing = sgPlacing.add(new BoolSetting.Builder()
        .name("swing").description("Swing your hand when placing.").defaultValue(true).build());

    // --- Toggle
    private final Setting<Boolean> toggleMove = sgToggle.add(new BoolSetting.Builder()
        .name("toggle-move").description("Turn off when you move to a different block horizontally.").defaultValue(true).build());
    private final Setting<ToggleYMode> toggleY = sgToggle.add(new EnumSetting.Builder<ToggleYMode>()
        .name("toggle-y").description("Turn off when you change height.").defaultValue(ToggleYMode.Full).build());
    private final Setting<Boolean> toggleSneak = sgToggle.add(new BoolSetting.Builder()
        .name("toggle-sneak").description("Turn off when you start sneaking.").defaultValue(false).build());

    // --- Render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Draw the blocks that still need to be placed.").defaultValue(true).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Which parts of the boxes are drawn.").defaultValue(ShapeMode.Both).visible(render::get).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Fill color.").defaultValue(new SettingColor(255, 0, 0, 50, false)).visible(render::get).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Outline color.").defaultValue(new SettingColor(255, 0, 0, 255, false)).visible(render::get).build());
    private final Setting<SettingColor> supportSideColor = sgRender.add(new ColorSetting.Builder()
        .name("support-side-color").description("Fill color of support blocks.").defaultValue(new SettingColor(255, 170, 0, 50, false)).visible(render::get).build());
    private final Setting<SettingColor> supportLineColor = sgRender.add(new ColorSetting.Builder()
        .name("support-line-color").description("Outline color of support blocks.").defaultValue(new SettingColor(255, 170, 0, 255, false)).visible(render::get).build());

    /** @param wanted the cage block, @param place the block actually placed now (== wanted unless it is a support). */
    private record Plan(BlockPos wanted, BlockPos place, BlockHitResult hit) {
        boolean isSupport() { return !wanted.equals(place); }
    }

    private final Map<BlockPos, Integer> cooldowns = new HashMap<>();
    private final List<Plan> pending = new ArrayList<>();
    private BlockPos startPos = null;
    private int tickCounter = 0;
    private boolean lastSneak = false;
    private boolean warnedBlocks = false;

    public SelfTrapPlus() {
        super(OpsTools.CATEGORY, "self-trap-plus", "Traps yourself in obsidian (head ring and / or top), with support blocks.");
    }

    @Override
    public void onActivate() {
        cooldowns.clear();
        pending.clear();
        tickCounter = 0;
        warnedBlocks = false;
        lastSneak = mc.player != null && mc.player.isShiftKeyDown();
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

        if (shouldToggle()) return;
        if (pauseEat.get() && mc.player.isUsingItem()) return;

        cooldowns.values().removeIf(tick -> tickCounter - tick > spotDelay.get());

        List<Plan> plans = new ArrayList<>();
        Set<BlockPos> used = new LinkedHashSet<>();
        for (BlockPos wanted : cage()) {
            if (!PvpUtils.replaceable(wanted)) continue;
            if (PvpUtils.entityBlocks(wanted)) continue;

            Plan plan = plan(wanted);
            if (plan != null && used.add(plan.place())) plans.add(plan);
        }
        pending.addAll(plans);
        if (plans.isEmpty()) return;

        if (!hasBlock()) {
            if (!warnedBlocks) {
                warning("No allowed building blocks (obsidian by default) available.");
                warnedBlocks = true;
            }
            return;
        }
        warnedBlocks = false;

        int placed = 0;
        for (Plan plan : plans) {
            if (placed >= placesPerTick.get()) break;
            if (cooldowns.containsKey(plan.place())) continue;

            if (!placeBlock(plan.hit())) return;
            cooldowns.put(plan.place(), tickCounter);
            placed++;
        }
    }

    /** @return true if the module switched itself off. */
    private boolean shouldToggle() {
        BlockPos now = mc.player.blockPosition();

        if (toggleMove.get() && startPos != null && (now.getX() != startPos.getX() || now.getZ() != startPos.getZ())) {
            info("You moved - turning off.");
            toggle();
            return true;
        }

        if (startPos != null) {
            boolean off = switch (toggleY.get()) {
                case Full -> now.getY() != startPos.getY();
                case Up -> now.getY() > startPos.getY();
                case Down -> now.getY() < startPos.getY();
                case Disabled -> false;
            };
            if (off) {
                info("You changed height - turning off.");
                toggle();
                return true;
            }
        }

        boolean sneaking = mc.player.isShiftKeyDown();
        if (toggleSneak.get() && sneaking && !lastSneak) {
            info("You sneaked - turning off.");
            toggle();
            return true;
        }
        lastSneak = sneaking;
        return false;
    }

    // ------------------------------------------------------------------ shape of the cage

    /** Cage positions based on your own hitbox (so standing across two blocks is handled). */
    private Set<BlockPos> cage() {
        AABB box = mc.player.getBoundingBox();
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
        TrapMode mode = trapMode.get();

        if (mode == TrapMode.Eyes || mode == TrapMode.Both) {
            Set<BlockPos> layer = new LinkedHashSet<>();
            for (BlockPos cell : footprint) layer.add(cell.above(1));
            for (BlockPos cell : layer) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbour = cell.relative(dir);
                    if (!layer.contains(neighbour)) out.add(neighbour);
                }
            }
        }
        if (mode == TrapMode.Top || mode == TrapMode.Both) {
            for (BlockPos cell : footprint) out.add(cell.above(2));
        }
        return out;
    }

    // ------------------------------------------------------------------ planning

    /** How to get a block into {@code wanted}: directly, via one support block or via two. Null if impossible right now. */
    private Plan plan(BlockPos wanted) {
        double range = placeRange.get();

        BlockHitResult direct = PvpUtils.supportHit(wanted, range);
        if (direct != null) return new Plan(wanted, wanted, direct);
        if (!support.get()) return null;

        // one support block
        Plan best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            BlockPos helper = wanted.relative(dir);
            if (!usable(helper)) continue;

            BlockHitResult hit = PvpUtils.supportHit(helper, range);
            if (hit == null) continue;

            double dist = mc.player.getEyePosition().distanceTo(hit.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = new Plan(wanted, helper, hit);
            }
        }
        if (best != null) return best;

        // two support blocks
        for (Direction first : Direction.values()) {
            BlockPos helper1 = wanted.relative(first);
            if (!usable(helper1)) continue;

            for (Direction second : Direction.values()) {
                BlockPos helper2 = helper1.relative(second);
                if (helper2.equals(wanted) || !usable(helper2)) continue;

                BlockHitResult hit = PvpUtils.supportHit(helper2, range);
                if (hit == null) continue;

                double dist = mc.player.getEyePosition().distanceTo(hit.getLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = new Plan(wanted, helper2, hit);
                }
            }
        }
        return best;
    }

    /** A free spot a support block may go into. */
    private boolean usable(BlockPos pos) {
        return PvpUtils.replaceable(pos) && !PvpUtils.entityBlocks(pos);
    }

    // ------------------------------------------------------------------ placing

    private boolean isAllowed(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && blocks.get().contains(item.getBlock());
    }

    private boolean hasBlock() {
        if (isAllowed(mc.player.getMainHandItem()) || isAllowed(mc.player.getOffhandItem())) return true;
        if (switchMode.get() == SwitchMode.Disabled) return false;
        if (PvpUtils.findHotbar(this::isAllowed) != -1) return true;
        if (switchMode.get() == SwitchMode.Inventory) {
            for (int i = 9; i < 36; i++) {
                if (isAllowed(mc.player.getInventory().getItem(i))) return true;
            }
        }
        return false;
    }

    private boolean placeBlock(BlockHitResult hit) {
        if (isAllowed(mc.player.getMainHandItem())) {
            click(hit, InteractionHand.MAIN_HAND);
            return true;
        }
        if (isAllowed(mc.player.getOffhandItem())) {
            click(hit, InteractionHand.OFF_HAND);
            return true;
        }

        switch (switchMode.get()) {
            case Silent -> {
                int slot = PvpUtils.findHotbar(this::isAllowed);
                if (slot == -1) return false;
                PvpUtils.useSlot(slot, () -> click(hit, InteractionHand.MAIN_HAND));
                return true;
            }
            case Inventory -> {
                return PvpUtils.withItem(this::isAllowed, () -> click(hit, InteractionHand.MAIN_HAND));
            }
            default -> {
                return false;
            }
        }
    }

    private void click(BlockHitResult hit, InteractionHand hand) {
        if (rotate.get()) PvpUtils.faceVec(hit.getLocation());
        mc.gameMode.useItemOn(mc.player, hand, hit);
        if (swing.get()) mc.player.swing(hand);
    }

    // ------------------------------------------------------------------ rendering

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || pending.isEmpty()) return;
        for (Plan plan : pending) {
            BlockPos pos = plan.wanted();
            event.renderer.box(
                pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                sideColor.get(), lineColor.get(), shapeMode.get(), 0
            );
            if (plan.isSupport()) {
                BlockPos s = plan.place();
                event.renderer.box(
                    s.getX(), s.getY(), s.getZ(), s.getX() + 1, s.getY() + 1, s.getZ() + 1,
                    supportSideColor.get(), supportLineColor.get(), shapeMode.get(), 0
                );
            }
        }
    }
}
