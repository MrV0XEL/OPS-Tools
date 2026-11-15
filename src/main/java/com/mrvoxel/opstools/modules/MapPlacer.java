package com.mrvoxel.opstools.modules;

import com.mrvoxel.opstools.OpsTools;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import com.mrvoxel.opstools.utils.LogoUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MapPlacer extends Module {
    public enum Mode {
        Single,   // original 1x1 behaviour
        Wall      // WxH map wall, built + filled automatically
    }

    public enum WallBlock {
        SeaLantern(Items.SEA_LANTERN),
        Glowstone(Items.GLOWSTONE),
        CryingObsidian(Items.CRYING_OBSIDIAN);

        public final Item item;
        WallBlock(Item item) { this.item = item; }
    }

    public enum FillOrder {
        Rows,     // 1 2 3 / 4 5 6 ... (left to right, top to bottom)
        Columns   // 1 4 / 2 5 / 3 6 ... (top to bottom, left to right)
    }

    private enum Phase { IDLE, WALL, FRAMES, FILL }

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgWall = this.settings.createGroup("Wall");

    // ------------------------------------------------------------------ general

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Single = fill 1x1 item frames (original). Wall = right-click to build a backing wall, place frames and fill numbered maparts.")
        .defaultValue(Mode.Single)
        .build()
    );
    private final Setting<Boolean> nearbyFrames = sgGeneral.add(new BoolSetting.Builder()
        .name("nearby-frames")
        .description("Single mode: also fill empty item frames that were already there (not only frames you just placed).")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Single)
        .build()
    );
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Max distance to an item frame for the interaction (vanilla entity interaction range is 3).")
        .defaultValue(3.0)
        .min(1)
        .max(6)
        .sliderMax(6)
        .build()
    );
    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing hand.")
        .defaultValue(true)
        .build()
    );

    // ------------------------------------------------------------------ wall

    private final Setting<Integer> wallWidth = sgWall.add(new IntSetting.Builder()
        .name("width")
        .description("Wall width in maps (as seen by you).")
        .defaultValue(3)
        .min(1).max(15).sliderMax(15)
        .visible(() -> mode.get() == Mode.Wall)
        .build()
    );
    private final Setting<Integer> wallHeight = sgWall.add(new IntSetting.Builder()
        .name("height")
        .description("Wall height in maps.")
        .defaultValue(4)
        .min(1).max(15).sliderMax(15)
        .visible(() -> mode.get() == Mode.Wall)
        .build()
    );
    private final Setting<WallBlock> wallBlock = sgWall.add(new EnumSetting.Builder<WallBlock>()
        .name("block")
        .description("Block used for the backing wall.")
        .defaultValue(WallBlock.SeaLantern)
        .visible(() -> mode.get() == Mode.Wall)
        .build()
    );
    private final Setting<FillOrder> fillOrder = sgWall.add(new EnumSetting.Builder<FillOrder>()
        .name("order")
        .description("How map numbers are laid out. Rows: 1 2 3 / 4 5 6. Columns: 1 4 / 2 5 / 3 6.")
        .defaultValue(FillOrder.Rows)
        .visible(() -> mode.get() == Mode.Wall)
        .build()
    );
    private final Setting<Integer> startIndex = sgWall.add(new IntSetting.Builder()
        .name("first-number")
        .description("Number in the mapart name that goes in the top-left frame.")
        .defaultValue(1)
        .min(0).max(999).sliderMax(20)
        .visible(() -> mode.get() == Mode.Wall)
        .build()
    );
    private final Setting<Boolean> airPlace = sgWall.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Place wall blocks that have no neighbouring block to click on directly in the air (needs server support). Otherwise the wall grows from existing blocks.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Wall)
        .build()
    );
    private final Setting<Double> airDistance = sgWall.add(new DoubleSetting.Builder()
        .name("air-distance")
        .description("When you right-click on air, the wall is centred this many blocks in front of you.")
        .defaultValue(3.0)
        .min(1).max(6).sliderMax(6)
        .visible(() -> mode.get() == Mode.Wall)
        .build()
    );
    private final Setting<Double> placeRange = sgWall.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Max distance for placing wall blocks and frames.")
        .defaultValue(4.5)
        .min(1).max(6).sliderMax(6)
        .visible(() -> mode.get() == Mode.Wall)
        .build()
    );
    private final Setting<Integer> delay = sgWall.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between actions.")
        .defaultValue(2)
        .min(0).max(20).sliderMax(10)
        .visible(() -> mode.get() == Mode.Wall)
        .build()
    );
    private final Setting<Integer> hotbarSlot = sgWall.add(new IntSetting.Builder()
        .name("hotbar-slot")
        .description("Hotbar slot (1-9) used to bring items/maps from your main inventory (both modes).")
        .defaultValue(9)
        .min(1).max(9).sliderMin(1).sliderMax(9)
        .build()
    );

    // ------------------------------------------------------------------ single-mode state (unchanged)

    /** How long after right-clicking with an item frame a newly appeared frame still counts as "yours" (ping allowance, not a delay). */
    private static final int PLACE_WINDOW_TICKS = 20;
    /** A frame that is still empty this many ticks after an attempt may be tried once more. */
    private static final int RETRY_TICKS = 20;
    private static final int MAX_ATTEMPTS = 2;
    /** Frames further than this are not tracked at all. */
    private static final double SCAN_RADIUS = 6.0;

    private int tickCounter = 0;
    private int lastFramePlaceTick = Integer.MIN_VALUE / 2;

    private final Set<Integer> seen = new HashSet<>();       // every frame id observed this activation
    private final Set<Integer> mine = new HashSet<>();       // frames that appeared right after I placed one
    private final Set<Integer> filled = new HashSet<>();     // verified to contain an item - never touched again
    private final Set<Integer> abandoned = new HashSet<>();  // gave up after MAX_ATTEMPTS
    private final Map<Integer, Integer> attempts = new HashMap<>();
    private final Map<Integer, Integer> lastAttempt = new HashMap<>();

    // ------------------------------------------------------------------ wall-mode state

    private static final int NEVER = Integer.MIN_VALUE / 2;
    private static final int WALL_MAX_TRIES = 3;
    private static final int TIMEOUT_TICKS = 400;
    private static final int READY = 0, WAIT = 1, MISSING = 2;
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private static class Cell {
        final BlockPos wall;     // backing block position
        final BlockPos frame;    // item frame position (in front of the wall block)
        final int mapNumber;
        boolean wallDone, wallFailed, frameDone, done;
        int wallTries, frameTries, fillTries;
        int wallLast = NEVER, frameLast = NEVER, fillLast = NEVER;

        Cell(BlockPos wall, BlockPos frame, int mapNumber) {
            this.wall = wall;
            this.frame = frame;
            this.mapNumber = mapNumber;
        }
    }

    private Phase phase = Phase.IDLE;
    private final List<Cell> cells = new ArrayList<>();      // in map-number order
    private final List<Cell> wallOrder = new ArrayList<>();  // bottom row first so the wall grows from supports
    private Direction facing = Direction.NORTH;              // direction the wall face points (towards you)
    private int cooldown = 0;
    private int idleTicks = 0;
    private int originalSlot = 0;

    public MapPlacer() {
        super(OpsTools.CATEGORY, "Map Placer", "Fills item frames with maps. Single mode: 1x1. Wall mode: builds a wall and fills a numbered WxH mapart.");
    }

    @Override
    public void onActivate() {
        clearTracking();
        if (mode.get() == Mode.Wall) {
            info("Wall mode: hold an item frame and right-click to build a %dx%d map wall.", wallWidth.get(), wallHeight.get());
        } else {
            info("Place an item frame with a map in your hotbar and it will be filled automatically.");
        }
    }

    @Override
    public void onDeactivate() {
        clearTracking();
    }

    private void clearTracking() {
        seen.clear();
        mine.clear();
        filled.clear();
        abandoned.clear();
        attempts.clear();
        lastAttempt.clear();
        lastFramePlaceTick = Integer.MIN_VALUE / 2;

        phase = Phase.IDLE;
        cells.clear();
        wallOrder.clear();
        cooldown = 0;
        idleTicks = 0;
    }

    // ---------------------------------------------------------------- click handling

    @EventHandler
    private void onMouseButton(MouseClickEvent event) {
        if (mc.screen != null || mc.player == null || mc.level == null) return;
        if (event.button() != 1) return;
        if (event.action != KeyAction.Press) return;

        boolean holdingFrame = isFrameItem(mc.player.getMainHandItem().getItem()) || isFrameItem(mc.player.getOffhandItem().getItem());

        if (mode.get() == Mode.Wall) {
            // Wall mode: right-click while holding an item frame starts the build (the click is swallowed so no stray frame is placed).
            if (!isFrameItem(mc.player.getMainHandItem().getItem())) return;
            event.cancel();
            if (phase != Phase.IDLE) {
                warning("Already building a wall.");
                return;
            }
            startWall();
            return;
        }

        if (holdingFrame) {
            lastFramePlaceTick = tickCounter;
        }
        // The click is never cancelled in single mode, so normal item frame placement is untouched.
    }

    private static boolean isFrameItem(Item item) {
        return item == Items.ITEM_FRAME || item == Items.GLOW_ITEM_FRAME;
    }

    // ---------------------------------------------------------------- tick

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        tickCounter++;

        if (mode.get() == Mode.Wall) {
            tickWall();
            return;
        }
        if (phase != Phase.IDLE) { phase = Phase.IDLE; cells.clear(); wallOrder.clear(); }

        tickSingle();
    }

    // ================================================================= SINGLE MODE (original logic)

    private void tickSingle() {
        AABB box = mc.player.getBoundingBox().inflate(SCAN_RADIUS);
        List<ItemFrame> frames = mc.level.getEntitiesOfClass(ItemFrame.class, box);

        pruneTracking(frames);
        if (frames.isEmpty()) return;

        for (ItemFrame frame : frames) {
            int id = frame.getId();
            boolean isNew = seen.add(id);

            if (filled.contains(id) || abandoned.contains(id)) continue;

            // Never touch a frame that already holds something. If we had tried it, this is the server-confirmed result.
            if (!frame.getItem().isEmpty()) {
                if (attempts.containsKey(id)) filled.add(id);
                continue;
            }

            if (isNew && tickCounter - lastFramePlaceTick <= PLACE_WINDOW_TICKS) mine.add(id);
            if (!mine.contains(id) && !nearbyFrames.get()) continue;

            if (!inRange(frame)) continue; // a frame placed a bit too far stays tracked until you get close

            Integer last = lastAttempt.get(id);
            if (last != null && tickCounter - last < RETRY_TICKS) continue; // waiting for the server's answer

            if (attempts.getOrDefault(id, 0) >= MAX_ATTEMPTS) {
                abandoned.add(id);
                continue;
            }

            if (!placeMap(frame)) return; // no map in the hotbar: do nothing

            attempts.merge(id, 1, Integer::sum);
            lastAttempt.put(id, tickCounter);
        }
    }

    /** Forget frames that are gone or out of tracking range so the collections stay small. */
    private void pruneTracking(List<ItemFrame> frames) {
        Set<Integer> present = new HashSet<>();
        for (ItemFrame f : frames) present.add(f.getId());

        seen.retainAll(present);
        mine.retainAll(present);
        filled.retainAll(present);
        abandoned.retainAll(present);
        attempts.keySet().retainAll(present);
        lastAttempt.keySet().retainAll(present);
    }

    private boolean inRange(ItemFrame frame) {
        double r = range.get();
        return frame.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) <= r * r;
    }

    /** First map in the hotbar, preferring the slot that is already selected so nothing needs switching. */
    private int findMapSlot() {
        Inventory inv = mc.player.getInventory();
        int selected = inv.getSelectedSlot();
        if (inv.getItem(selected).getItem() == Items.FILLED_MAP) return selected;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).getItem() == Items.FILLED_MAP) return i;
        }
        // not in the hotbar: pull one from the main inventory into the configured hotbar slot
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).getItem() == Items.FILLED_MAP) {
                int hb = hotbarSlot.get() - 1;
                LogoUtils.swapWithHotbar(i, hb);
                return inv.getItem(hb).getItem() == Items.FILLED_MAP ? hb : -1;
            }
        }
        return -1;
    }

    /**
     * Selects the map slot (only if needed), sends the interaction, and immediately switches back.
     * Returns false if there is no map in the hotbar.
     */
    private boolean placeMap(ItemFrame frame) {
        int slot = findMapSlot();
        if (slot == -1) return false;

        Inventory inv = mc.player.getInventory();
        int previous = inv.getSelectedSlot();
        boolean switched = slot != previous;

        if (switched) inv.setSelectedSlot(slot);

        sendFrameInteract(frame);

        if (switched) {
            // Restore locally and tell the server right away instead of waiting for the next tick's slot sync.
            inv.setSelectedSlot(previous);
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(previous));
        }
        return true;
    }

    /** Same two packets vanilla sends for a right-click on an entity (INTERACT_AT, then INTERACT). */
    private void sendFrameInteract(ItemFrame frame) {
        EntityHitResult hit = new EntityHitResult(frame, frame.getBoundingBox().getCenter());
        mc.gameMode.interactAt(mc.player, frame, hit, InteractionHand.MAIN_HAND);
        mc.gameMode.interact(mc.player, frame, InteractionHand.MAIN_HAND);
        if (swingHand.get()) mc.player.swing(InteractionHand.MAIN_HAND);
    }

    // ================================================================= WALL MODE

    /**
     * Plans the wall from the crosshair target and starts the build.
     * Target = block you look at (wall is built against the clicked face) or, on air, a point airDistance in front of you.
     * The wall is centred horizontally on the target and its bottom row sits at the target's height.
     */
    private void startWall() {
        int w = wallWidth.get(), h = wallHeight.get();

        Direction view = mc.player.getDirection();   // horizontal direction you are facing
        facing = view.getOpposite();                 // wall face points back at you
        Direction right = view.getClockWise();       // your right-hand side

        BlockPos anchor;
        HitResult hr = mc.hitResult;
        if (hr instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            anchor = bhr.getBlockPos().relative(bhr.getDirection());
        } else {
            Vec3 p = mc.player.getEyePosition().add(mc.player.getLookAngle().scale(airDistance.get()));
            anchor = BlockPos.containing(p);
        }

        cells.clear();
        wallOrder.clear();
        boolean rows = fillOrder.get() == FillOrder.Rows;
        for (int i = 0; i < w * h; i++) {
            int col = rows ? i % w : i / h;
            int row = rows ? i / w : i % h;   // row 0 = top
            BlockPos wall = anchor.relative(right, col - (w - 1) / 2).above(h - 1 - row);
            cells.add(new Cell(wall, wall.relative(facing), startIndex.get() + i));
        }
        wallOrder.addAll(cells);
        wallOrder.sort(Comparator.comparingInt(c -> c.wall.getY())); // stable: keeps column order inside a row

        // --- resource checks
        Item blockItem = wallBlock.get().item;
        int blocksNeeded = 0;
        for (Cell c : cells) if (mc.level.getBlockState(c.wall).canBeReplaced()) blocksNeeded++;

        if (countItems(s -> s.getItem() == blockItem) < blocksNeeded) {
            error("Need %d %s, you have fewer.", blocksNeeded, wallBlock.get());
            cells.clear(); wallOrder.clear();
            return;
        }
        if (countItems(s -> isFrameItem(s.getItem())) < w * h) {
            error("Need %d item frames, you have fewer.", w * h);
            cells.clear(); wallOrder.clear();
            return;
        }

        Set<Integer> available = new HashSet<>();
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            int n = mapNumber(inv.getItem(i));
            if (n >= 0) available.add(n);
        }
        List<Integer> missing = new ArrayList<>();
        for (Cell c : cells) if (!available.contains(c.mapNumber)) missing.add(c.mapNumber);
        if (!missing.isEmpty()) {
            warning("Missing maparts (by name number): %s. Those frames will stay empty.", missing);
        }

        originalSlot = inv.getSelectedSlot();
        phase = Phase.WALL;
        idleTicks = 0;
        cooldown = 0;
        info("Building %dx%d wall (%s), facing %s.", w, h, wallBlock.get(), facing);
    }

    private void tickWall() {
        if (phase == Phase.IDLE) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (++idleTicks > TIMEOUT_TICKS) {
            error("Timed out (out of reach, no support to place against, or the server rejected placements).");
            finish(false);
            return;
        }
        if (idleTicks % 60 == 59) info("Waiting... move closer to the wall so everything is in reach.");

        switch (phase) {
            case WALL -> stepWall();
            case FRAMES -> stepFrames();
            case FILL -> stepFill();
            default -> {}
        }
    }

    private void progress() {
        idleTicks = 0;
        cooldown = delay.get();
    }

    // ------------------------------------------------------------ phase 1: backing wall

    private void stepWall() {
        boolean remaining = false;
        Cell target = null;
        BlockHitResult targetHit = null;

        for (Cell c : wallOrder) {
            if (c.wallDone || c.wallFailed) continue;

            if (!mc.level.getBlockState(c.wall).canBeReplaced()) { c.wallDone = true; continue; }

            if (tickCounter - c.wallLast < RETRY_TICKS) { remaining = true; continue; } // waiting for the server

            if (c.wallTries >= WALL_MAX_TRIES) {
                c.wallFailed = true;
                warning("Could not place wall block at %s.", c.wall.toShortString());
                continue;
            }

            remaining = true;
            if (target != null) continue;

            BlockHitResult hit = makeBlockHit(c.wall);
            if (hit != null) { target = c; targetHit = hit; }
        }

        if (!remaining) {
            phase = Phase.FRAMES;
            progress();
            return;
        }
        if (target == null) return; // nothing placeable right now (reach / support) - idle timer keeps counting

        Item blockItem = wallBlock.get().item;
        int sel = selectItem(s -> s.getItem() == blockItem);
        if (sel == MISSING) {
            error("Ran out of %s.", wallBlock.get());
            finish(false);
            return;
        }
        if (sel == WAIT) { cooldown = Math.max(1, delay.get()); return; }

        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, targetHit);
        if (swingHand.get()) mc.player.swing(InteractionHand.MAIN_HAND);
        target.wallTries++;
        target.wallLast = tickCounter;
        progress();
    }

    /**
     * Finds a way to place a block at {@code target}: click the face of a neighbouring solid block, or (if enabled)
     * place straight into the air. Returns null if nothing is in reach.
     */
    private BlockHitResult makeBlockHit(BlockPos target) {
        Vec3 eye = mc.player.getEyePosition();
        double r = placeRange.get();
        double r2 = r * r;

        for (Direction d : Direction.values()) {
            BlockPos n = target.relative(d);
            BlockState ns = mc.level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced()) continue;
            if (!ns.isCollisionShapeFullBlock(mc.level, n)) continue; // avoid chests/crafting-ish blocks and slabs etc.

            Direction face = d.getOpposite(); // face of the neighbour that points at the target
            Vec3 loc = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (eye.distanceToSqr(loc) > r2) continue;
            return new BlockHitResult(loc, face, n, false);
        }

        if (airPlace.get()) {
            Vec3 loc = Vec3.atCenterOf(target);
            if (eye.distanceToSqr(loc) <= r2) return new BlockHitResult(loc, Direction.DOWN, target, false);
        }
        return null;
    }

    // ------------------------------------------------------------ phase 2: item frames

    private void stepFrames() {
        boolean remaining = false;
        Cell target = null;
        BlockHitResult targetHit = null;
        double r2 = placeRange.get() * placeRange.get();
        Vec3 eye = mc.player.getEyePosition();

        for (Cell c : cells) {
            if (c.frameDone) continue;

            if (mc.level.getBlockState(c.wall).canBeReplaced()) { c.frameDone = true; c.done = true; continue; } // no wall here

            if (findFrame(c) != null) { c.frameDone = true; continue; }

            if (!mc.level.getBlockState(c.frame).canBeReplaced()) {
                c.frameDone = true;
                c.done = true;
                warning("Space for frame at %s is blocked, skipping map %d.", c.frame.toShortString(), c.mapNumber);
                continue;
            }

            if (tickCounter - c.frameLast < RETRY_TICKS) { remaining = true; continue; }

            if (c.frameTries >= WALL_MAX_TRIES) {
                c.frameDone = true;
                c.done = true;
                warning("Could not place item frame at %s.", c.frame.toShortString());
                continue;
            }

            remaining = true;
            if (target != null) continue;

            Vec3 loc = Vec3.atCenterOf(c.wall).add(facing.getStepX() * 0.5, facing.getStepY() * 0.5, facing.getStepZ() * 0.5);
            if (eye.distanceToSqr(loc) > r2) continue;
            target = c;
            targetHit = new BlockHitResult(loc, facing, c.wall, false);
        }

        if (!remaining) {
            phase = Phase.FILL;
            progress();
            return;
        }
        if (target == null) return;

        int sel = selectItem(s -> isFrameItem(s.getItem()));
        if (sel == MISSING) {
            error("Ran out of item frames.");
            finish(false);
            return;
        }
        if (sel == WAIT) { cooldown = Math.max(1, delay.get()); return; }

        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, targetHit);
        if (swingHand.get()) mc.player.swing(InteractionHand.MAIN_HAND);
        target.frameTries++;
        target.frameLast = tickCounter;
        progress();
    }

    /** The item frame hanging at this cell (facing the right way), or null. */
    private ItemFrame findFrame(Cell c) {
        for (ItemFrame f : mc.level.getEntitiesOfClass(ItemFrame.class, new AABB(c.frame))) {
            if (f.getDirection() == facing && f.blockPosition().equals(c.frame)) return f;
        }
        return null;
    }

    // ------------------------------------------------------------ phase 3: maps in order

    private void stepFill() {
        boolean pending = false;

        for (Cell c : cells) {
            if (c.done) continue;

            ItemFrame frame = findFrame(c);
            if (frame == null) { c.done = true; continue; }
            if (!frame.getItem().isEmpty()) { c.done = true; continue; }

            pending = true;

            if (tickCounter - c.fillLast < RETRY_TICKS) continue; // waiting for the server

            if (c.fillTries >= MAX_ATTEMPTS) {
                c.done = true;
                warning("Map %d was not accepted by its frame.", c.mapNumber);
                continue;
            }

            if (!inRange(frame)) continue;

            final int number = c.mapNumber;
            int sel = selectItem(s -> mapNumber(s) == number);
            if (sel == MISSING) {
                c.done = true;
                warning("No mapart named %d in your inventory.", number);
                continue;
            }
            if (sel == WAIT) { cooldown = Math.max(1, delay.get()); return; }

            sendFrameInteract(frame);
            c.fillTries++;
            c.fillLast = tickCounter;
            progress();
            return; // one map per action, in map-number order
        }

        if (!pending) {
            info("Map wall finished.");
            finish(true);
        }
    }

    // ------------------------------------------------------------ helpers

    private void finish(boolean success) {
        if (mc.player != null && mc.player.getInventory().getSelectedSlot() != originalSlot) {
            setSlot(originalSlot);
        }
        phase = Phase.IDLE;
        cells.clear();
        wallOrder.clear();
        idleTicks = 0;
        cooldown = 0;
    }

    private void setSlot(int slot) {
        mc.player.getInventory().setSelectedSlot(slot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
    }

    /**
     * Makes sure the main hand holds a matching item. Hotbar items are selected directly;
     * items from the main inventory are swapped into the configured hotbar slot first (returns WAIT for one cycle).
     */
    private int selectItem(Predicate<ItemStack> pred) {
        Inventory inv = mc.player.getInventory();

        if (pred.test(inv.getItem(inv.getSelectedSlot()))) return READY;

        for (int i = 0; i < 9; i++) {
            if (pred.test(inv.getItem(i))) {
                setSlot(i);
                return READY;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (pred.test(inv.getItem(i))) {
                int hb = hotbarSlot.get() - 1;
                LogoUtils.swapWithHotbar(i, hb);
                if (!pred.test(inv.getItem(hb))) return MISSING;
                setSlot(hb);
                return WAIT;
            }
        }
        return MISSING;
    }

    private int countItems(Predicate<ItemStack> pred) {
        Inventory inv = mc.player.getInventory();
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && pred.test(s)) total += s.getCount();
        }
        return total;
    }

    /**
     * Number from a filled map's name: the last integer in it ("7", "#7", "Mapart 7" all give 7).
     * Returns -1 for non-maps or names without a number.
     */
    private static int mapNumber(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != Items.FILLED_MAP) return -1;
        String name = stack.getHoverName().getString();
        Matcher m = NUMBER.matcher(name);
        int last = -1;
        while (m.find()) {
            String g = m.group();
            if (g.length() <= 6) last = Integer.parseInt(g);
        }
        return last;
    }
}
