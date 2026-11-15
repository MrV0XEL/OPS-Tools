/*
 * The fast packet placement, packet budget, placement tracking, adaptive rate and ghost-block probing in this module
 * follow the approach of HighwayBuilderTHM from THM Addons (https://github.com/Leonn170709/THM-Addons).
 * Credit the THM Addons devs and keep that link; their licence terms apply to anything derived from their code.
 */
package com.mrvoxel.opstools.modules;

import com.mrvoxel.opstools.OpsTools;
import com.mrvoxel.opstools.utils.LogoUtils;
import com.mrvoxel.opstools.utils.PvpUtils;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * Printer - places the blocks of a loaded Litematica schematic around you.
 *
 * Why it is fast (the same ideas as the packet-build printer in HighwayBuilderTHM):
 *  - Placement is sent as raw packets, no client prediction, and many blocks go out in a single tick.
 *  - A packet budget caps what one tick sends, so you do not trip the server's packet limit.
 *  - Blocks that were just sent count as supports for the next ones, so a whole solid area fills in one go.
 *  - It uses the block from your offhand or hotbar and swaps slots silently (the server swaps, your hotbar does not).
 *  - The rate adapts: it backs off after rubberbands or refused placements and speeds up again when things are stable.
 *
 * Why there are no ghost blocks:
 *  - In Packet mode the client world only ever changes when the SERVER says so, so a refused block simply stays missing
 *    and is retried (a limited number of times). Nothing is faked on your screen.
 *  - Every packet carries a real prediction sequence number, so the server's acknowledgement can correct the client.
 *  - Only blocks the server will accept are attempted: the click must be inside the server's reach, on a face that
 *    faces you, and the placed state is checked with vanilla's own placement logic before anything is sent.
 *  - Placed full blocks are re-checked with a harmless click ("ghost check") that makes the server resend that block.
 *
 * Air place: with it on, blocks with no neighbour to click against are placed by clicking the target position itself.
 * That works on vanilla because air is replaceable: a block placed "on" a replaceable block goes into that spot.
 * Some anti-cheats flag it, so it can be turned off.
 */
public class Printer extends Module {
    public enum Mode { Packet, Predicted }
    public enum AirMode { Never, Smart, Always }
    public enum Order { BottomUp, Nearest }

    /** Properties the neighbours or the server decide, so they must not count as "wrong" or block a placement. */
    private static final Set<String> IGNORED = Set.of(
        "waterlogged", "powered", "lit", "shape", "north", "south", "east", "west", "up", "down", "distance",
        "persistent", "snowy", "occupied", "attached", "triggered", "hinge", "open", "enabled", "in_wall"
    );
    private static final float[] PITCHES = {0f, -90f, 90f};
    private static final float[] YAWS_4 = {0f, 90f, 180f, 270f};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");
    private final SettingGroup sgGhost = settings.createGroup("Ghost Blocks");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- General
    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Packet: fastest, the world only changes when the server confirms. Predicted: vanilla placement (swaps your hotbar slot).")
        .defaultValue(Mode.Packet).build());
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far from your eyes (to the middle of the block) the printer looks for blocks to place.")
        .defaultValue(6.0).range(1.0, 8.0).sliderRange(1.0, 8.0).decimalPlaces(1).build());
    private final Setting<Double> serverReach = sgGeneral.add(new DoubleSetting.Builder()
        .name("server-reach")
        .description("How far the server accepts a click, measured to the nearest edge of the clicked block. Vanilla is 5.5 (4.5 + 1). " +
            "Add your Reach module's bonus here. Anything beyond it is skipped, because the server would refuse it.")
        .defaultValue(5.5).range(3.0, 9.0).sliderRange(3.0, 9.0).decimalPlaces(1).build());
    private final Setting<AirMode> airPlace = sgGeneral.add(new EnumSetting.Builder<AirMode>()
        .name("air-place")
        .description("Never: only next to existing blocks. Smart: air place when no neighbour works. Always: air place everything.")
        .defaultValue(AirMode.Smart).build());
    private final Setting<Order> order = sgGeneral.add(new EnumSetting.Builder<Order>()
        .name("order")
        .description("BottomUp builds supports first. Nearest takes the closest blocks first.")
        .defaultValue(Order.BottomUp).build());
    private final Setting<Boolean> rotateForState = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate-for-state")
        .description("Turn (server side) when a block's facing or half needs it, e.g. stairs, logs, slabs.")
        .defaultValue(true).build());
    private final Setting<Integer> maxOriented = sgGeneral.add(new IntSetting.Builder()
        .name("max-oriented-per-tick")
        .description("How many blocks per tick may need the orientation search (the search is the expensive part).")
        .defaultValue(4).range(1, 16).sliderRange(1, 16).visible(rotateForState::get).build());
    private final Setting<Boolean> placeOffhand = sgGeneral.add(new BoolSetting.Builder()
        .name("use-offhand")
        .description("Place from the offhand when it holds the block (no slot swap needed).")
        .defaultValue(true).build());
    private final Setting<Boolean> fromInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("from-inventory")
        .description("Move the block from your inventory into the hotbar when it is not there (into an empty slot, else the selected one).")
        .defaultValue(true).build());
    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Send a swing with every placement (one more packet each).")
        .defaultValue(true).build());

    // --- Speed
    private final Setting<Double> blocksPerTick = sgSpeed.add(new DoubleSetting.Builder()
        .name("blocks-per-tick")
        .description("Maximum blocks placed per tick (fractions allowed). The packet budget limits it further.")
        .defaultValue(8.0).range(0.5, 40.0).sliderRange(0.5, 20.0).decimalPlaces(1).build());
    private final Setting<Boolean> packetBudget = sgSpeed.add(new BoolSetting.Builder()
        .name("packet-budget")
        .description("Cap the packets one tick may send so the server does not kick you for spam.")
        .defaultValue(true).build());
    private final Setting<Integer> packetsPerTick = sgSpeed.add(new IntSetting.Builder()
        .name("packets-per-tick")
        .description("Packets the printer may send in one tick. Keep it below the server's limit.")
        .defaultValue(23).range(6, 200).sliderRange(8, 40).visible(packetBudget::get).build());
    private final Setting<Boolean> adaptive = sgSpeed.add(new BoolSetting.Builder()
        .name("adaptive")
        .description("Slow down after rubberbands or refused placements, speed up again when stable.")
        .defaultValue(true).build());
    private final Setting<Boolean> tpsThrottle = sgSpeed.add(new BoolSetting.Builder()
        .name("tps-throttle")
        .description("Scale the rate with the server TPS and pause below 10 TPS.")
        .defaultValue(true).build());
    private final Setting<Integer> scanInterval = sgSpeed.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("Ticks between scans of the area around you.")
        .defaultValue(2).range(1, 20).sliderRange(1, 10).build());

    // --- Ghost blocks
    private final Setting<Integer> resendTicks = sgGhost.add(new IntSetting.Builder()
        .name("resend-ticks")
        .description("Ticks to wait for the server to confirm a block before it counts as refused and is tried again.")
        .defaultValue(10).range(3, 60).sliderRange(3, 40).build());
    private final Setting<Integer> maxAttempts = sgGhost.add(new IntSetting.Builder()
        .name("max-attempts")
        .description("Tries per block before it is skipped for a while.")
        .defaultValue(4).range(1, 20).sliderRange(1, 10).build());
    private final Setting<Boolean> ghostCheck = sgGhost.add(new BoolSetting.Builder()
        .name("ghost-check")
        .description("Re-check placed plain blocks with a harmless click so the server resends them. Needs an empty hand, pickaxe or totem slot in the hotbar.")
        .defaultValue(true).build());
    private final Setting<Integer> ghostDelay = sgGhost.add(new IntSetting.Builder()
        .name("ghost-delay")
        .description("Ticks after a block was confirmed before it is re-checked.")
        .defaultValue(15).range(2, 100).sliderRange(2, 60).visible(ghostCheck::get).build());
    private final Setting<Integer> probesPerTick = sgGhost.add(new IntSetting.Builder()
        .name("probes-per-tick")
        .description("Re-checks per tick.")
        .defaultValue(3).range(1, 20).sliderRange(1, 10).visible(ghostCheck::get).build());

    // --- Render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Outline the blocks still to print and the ones waiting for the server.").defaultValue(true).build());
    private final Setting<SettingColor> missingSide = sgRender.add(new ColorSetting.Builder()
        .name("missing-side-color").description("Fill of blocks still to print.")
        .defaultValue(new SettingColor(225, 25, 25, 20)).visible(render::get).build());
    private final Setting<SettingColor> missingLine = sgRender.add(new ColorSetting.Builder()
        .name("missing-line-color").description("Outline of blocks still to print.")
        .defaultValue(new SettingColor(225, 25, 25, 160)).visible(render::get).build());
    private final Setting<SettingColor> pendingSide = sgRender.add(new ColorSetting.Builder()
        .name("pending-side-color").description("Fill of blocks waiting for the server.")
        .defaultValue(new SettingColor(25, 125, 225, 40)).visible(render::get).build());
    private final Setting<SettingColor> pendingLine = sgRender.add(new ColorSetting.Builder()
        .name("pending-line-color").description("Outline of blocks waiting for the server.")
        .defaultValue(new SettingColor(25, 125, 225, 220)).visible(render::get).build());

    private record Target(BlockPos pos, BlockState state, Item item, double dist) {}
    private record Click(BlockPos clicked, Direction face, Vec3 hit) {}
    private record Plan(Click click, boolean rotate, float yaw, float pitch) {}
    private record Supply(InteractionHand hand, int slot) {}
    private record Pending(BlockState state, Item item, int tick) {}

    private Level schematic;
    private final List<Target> targets = new ArrayList<>();
    private final Map<BlockPos, Pending> pending = new HashMap<>();   // sent, waiting for the server
    private final Map<BlockPos, Pending> stable = new HashMap<>();     // confirmed, watched for a while
    private final Map<BlockPos, Integer> sends = new HashMap<>();      // tries per position
    private final Map<BlockPos, Integer> blocked = new HashMap<>();    // position -> tick it is skipped until
    private final Map<BlockPos, Integer> verifyAt = new HashMap<>();   // position -> tick for the ghost check
    private final AtomicInteger rubberbands = new AtomicInteger();

    private int tick, lastScan, lastNoSchematicWarn, stableTicks, placedTotal, rejectedTotal, budgetLeft;
    private double carry, rate;
    private boolean warnedNoInert;

    public Printer() {
        super(OpsTools.CATEGORY, "printer", "Prints a loaded Litematica schematic around you: fast packet placement, air place, no ghost blocks.");
    }

    @Override
    public void onActivate() {
        if (!Lite.init()) {
            error("Litematica was not found - the printer reads the schematic from it.");
            toggle();
            return;
        }
        tick = 0;
        lastScan = -100;
        lastNoSchematicWarn = -1000;
        targets.clear();
        pending.clear();
        stable.clear();
        sends.clear();
        blocked.clear();
        verifyAt.clear();
        rubberbands.set(0);
        carry = 0.0;
        rate = blocksPerTick.get();
        stableTicks = 0;
        placedTotal = 0;
        rejectedTotal = 0;
        warnedNoInert = false;
        info("Printing within %.1f blocks (%s mode, air place: %s).", range.get(), mode.get(), airPlace.get());
    }

    @Override
    public void onDeactivate() {
        targets.clear();
        pending.clear();
        stable.clear();
        verifyAt.clear();
    }

    @Override
    public String getInfoString() {
        return pending.isEmpty() ? String.valueOf(placedTotal) : placedTotal + "+" + pending.size();
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerPositionPacket) rubberbands.incrementAndGet();
    }

    // ------------------------------------------------------------------ tick

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) return;
        tick++;

        schematic = Lite.schematicLevel();
        if (schematic == null) {
            targets.clear();
            if (tick - lastNoSchematicWarn >= 200) {
                warning("No schematic is loaded in Litematica.");
                lastNoSchematicWarn = tick;
            }
            return;
        }

        tickPending(rubberbands.getAndSet(0));

        if (mc.player.isUsingItem() || mc.player.containerMenu != mc.player.inventoryMenu
            || !mc.player.containerMenu.getCarried().isEmpty()) return;

        double multiplier = 1.0;
        if (tpsThrottle.get()) {
            double tps = TickRate.INSTANCE.getTickRate();
            if (!Double.isFinite(tps) || tps < 10.0) return;
            multiplier = Math.min(1.0, tps / 20.0);
        }

        if (targets.isEmpty() || tick - lastScan >= scanInterval.get()) scan();
        place(multiplier);
        probe();
    }

    /** Confirms or times out what was sent, and drives the adaptive rate. */
    private void tickPending(int rubberbandCount) {
        boolean trouble = rubberbandCount > 0;

        Iterator<Map.Entry<BlockPos, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Pending> entry = it.next();
            BlockPos pos = entry.getKey();
            Pending p = entry.getValue();
            if (!mc.level.hasChunkAt(pos)) {
                it.remove();
            } else if (tick > p.tick() && statesMatch(p.state(), mc.level.getBlockState(pos))) {
                it.remove();
                placedTotal++;
                stable.put(pos, new Pending(p.state(), p.item(), tick));
                if (ghostCheck.get() && plain(p.state())) verifyAt.put(pos, tick + ghostDelay.get());
            } else if (tick - p.tick() >= resendTicks.get()) {
                it.remove();            // the server never made it: it is simply missing again, nothing was faked
                rejectedTotal++;
                trouble = true;
            }
        }

        // A confirmed block that stays for a while is trusted. One that vanished keeps its attempt count.
        Iterator<Map.Entry<BlockPos, Pending>> st = stable.entrySet().iterator();
        while (st.hasNext()) {
            Map.Entry<BlockPos, Pending> entry = st.next();
            Pending p = entry.getValue();
            if (!statesMatch(p.state(), mc.level.getBlockState(entry.getKey()))) st.remove();
            else if (tick - p.tick() >= 60) {
                sends.remove(entry.getKey());
                st.remove();
            }
        }

        blocked.values().removeIf(until -> tick >= until);

        double ceiling = blocksPerTick.get();
        if (!adaptive.get()) {
            rate = ceiling;
        } else {
            if (trouble) {
                rate = Math.max(1.0, rate - 1.0);
                stableTicks = 0;
            } else if (++stableTicks >= 100) {
                stableTicks = 0;
                rate += 0.5;
            }
            rate = Math.min(rate, ceiling);
        }
    }

    // ------------------------------------------------------------------ scanning

    private void scan() {
        lastScan = tick;
        targets.clear();

        double reach = range.get();
        Vec3 eye = mc.player.getEyePosition();
        int r = (int) Math.ceil(reach) + 1;
        BlockPos origin = mc.player.blockPosition();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    mp.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    double dist = Vec3.atCenterOf(mp).distanceTo(eye);
                    if (dist > reach) continue;

                    BlockState want = schematic.getBlockState(mp);
                    if (want.isAir() || !printable(want)) continue;

                    BlockState have = mc.level.getBlockState(mp);
                    if (statesMatch(want, have) || !have.canBeReplaced()) continue;

                    BlockPos pos = mp.immutable();
                    if (pending.containsKey(pos) || blocked.containsKey(pos)) continue;

                    Item item = want.getBlock().asItem();
                    if (item == Items.AIR) continue;
                    targets.add(new Target(pos, want, item, dist));
                }
            }
        }

        if (order.get() == Order.BottomUp) {
            targets.sort(Comparator.comparingInt((Target t) -> t.pos().getY()).thenComparingDouble(Target::dist));
        } else {
            targets.sort(Comparator.comparingDouble(Target::dist));
        }
    }

    /** Skips what cannot or should not be placed on its own. */
    private boolean printable(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof LiquidBlock || state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON)) return false;
        // the top half of a door or plant and the head of a bed come with the other half
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return false;
        if (state.hasProperty(BlockStateProperties.BED_PART)
            && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return false;
        return true;
    }

    private static boolean statesMatch(BlockState want, BlockState have) {
        if (want.getBlock() != have.getBlock()) return false;
        for (Property<?> p : want.getProperties()) {
            if (IGNORED.contains(p.getName())) continue;
            if (!want.getValue(p).equals(have.getValue(p))) return false;
        }
        return true;
    }

    /** True if the block has no property that depends on how it is placed. */
    private static boolean isSimple(BlockState state) {
        for (Property<?> p : state.getProperties()) {
            if (!IGNORED.contains(p.getName())) return false;
        }
        return true;
    }

    /** Plain blocks are safe to "click" for the ghost check: nothing opens, toggles or changes. */
    private static boolean plain(BlockState state) {
        return state.getBlock().getClass() == Block.class;
    }

    // ------------------------------------------------------------------ placing

    private void place(double multiplier) {
        budgetLeft = packetBudget.get() ? packetsPerTick.get() - 2 : Integer.MAX_VALUE; // movement packet + slot restore
        if (targets.isEmpty()) {
            carry = 0.0;
            return;
        }

        carry = Math.min(carry + rate * multiplier, 64.0);
        int allowed = (int) carry;
        if (allowed <= 0) return;
        carry -= allowed;

        boolean predicted = mode.get() == Mode.Predicted;
        Vec3 eye = mc.player.getEyePosition();
        int selected = mc.player.getInventory().getSelectedSlot();
        int held = selected;
        int placed = 0;
        int searches = 0;
        Map<Item, Integer> usedNow = new HashMap<>();

        for (Target t : targets) {
            if (placed >= allowed || budgetLeft < 3) break;

            BlockPos pos = t.pos();
            if (pending.containsKey(pos) || blocked.containsKey(pos)) continue;
            if (!mc.level.getBlockState(pos).canBeReplaced() || PvpUtils.entityBlocks(pos)) continue;

            int prior = sends.getOrDefault(pos, 0);
            if (prior >= maxAttempts.get()) {
                blocked.put(pos, tick + 200);
                sends.remove(pos);
                continue;
            }

            Item item = t.item();
            int available = LogoUtils.countItem(item) - pendingCount(item) - usedNow.getOrDefault(item, 0);
            if (available <= 0) continue;

            Supply supply = findSupply(item, held);
            if (supply == null && fromInventory.get() && budgetLeft >= 4 && refillHotbar(item, selected)) {
                budgetLeft -= 1;
                supply = findSupply(item, held);
            }
            if (supply == null) continue;

            boolean oriented = !isSimple(t.state());
            if (oriented && (!rotateForState.get() || searches >= maxOriented.get())) continue;
            if (oriented) searches++;

            InteractionHand hand = supply.hand();
            ItemStack stack = hand == InteractionHand.OFF_HAND
                ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(supply.slot());

            Plan plan = choosePlan(t, eye, stack, hand, oriented);
            if (plan == null) continue;

            boolean swap = hand == InteractionHand.MAIN_HAND && supply.slot() != held;
            int cost = 1 + (swing.get() ? 1 : 0) + (plan.rotate() ? 1 : 0) + (swap ? 1 : 0);
            if (cost > budgetLeft) break;
            budgetLeft -= cost;

            BlockHitResult hit = new BlockHitResult(plan.click().hit(), plan.click().face(), plan.click().clicked(), false);

            if (predicted) {
                if (swap) {
                    LogoUtils.selectHotbar(supply.slot());
                    held = supply.slot();
                    selected = held;
                }
                float oldYaw = mc.player.getYRot();
                float oldPitch = mc.player.getXRot();
                if (plan.rotate()) {
                    mc.player.setYRot(plan.yaw());
                    mc.player.setXRot(plan.pitch());
                    sendRotation(plan.yaw(), plan.pitch());
                }
                mc.gameMode.useItemOn(mc.player, hand, hit);
                if (swing.get()) mc.player.swing(hand);
                if (plan.rotate()) {
                    mc.player.setYRot(oldYaw);
                    mc.player.setXRot(oldPitch);
                }
            } else {
                if (plan.rotate()) sendRotation(plan.yaw(), plan.pitch());
                if (swap) {
                    mc.getConnection().send(new ServerboundSetCarriedItemPacket(supply.slot()));
                    held = supply.slot();
                }
                sendSequenced(sequence -> new ServerboundUseItemOnPacket(hand, hit, sequence));
                if (swing.get()) mc.getConnection().send(new ServerboundSwingPacket(hand));
            }

            pending.put(pos, new Pending(t.state(), item, tick));
            sends.put(pos, prior + 1);
            usedNow.merge(item, 1, Integer::sum);
            placed++;
        }

        if (!predicted && held != selected) mc.getConnection().send(new ServerboundSetCarriedItemPacket(selected));
    }

    /** Sends a packet that carries a prediction sequence number, the same way vanilla does for block placement. */
    private void sendSequenced(IntFunction<Packet<?>> factory) {
        BlockStatePredictionHandler base = predictionHandler();
        if (base == null) {
            // could not reach the handler: send with sequence 0 rather than not placing at all
            mc.getConnection().send(factory.apply(0));
            return;
        }
        try (BlockStatePredictionHandler handler = base.startPredicting()) {
            mc.getConnection().send(factory.apply(handler.currentSequence()));
        }
    }

    private static Method predictionGetter;

    /**
     * ClientLevel's prediction handler getter is not public. It is found by its return type (so it works whatever the
     * method is called in the current mappings) and made accessible.
     */
    private BlockStatePredictionHandler predictionHandler() {
        try {
            if (predictionGetter == null) {
                for (Method m : ClientLevel.class.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == BlockStatePredictionHandler.class) {
                        m.setAccessible(true);
                        predictionGetter = m;
                        break;
                    }
                }
            }
            return predictionGetter == null ? null : (BlockStatePredictionHandler) predictionGetter.invoke(mc.level);
        } catch (Throwable t) {
            return null;
        }
    }

    private void sendRotation(float yaw, float pitch) {
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, mc.player.onGround(), mc.player.horizontalCollision));
    }

    private int pendingCount(Item item) {
        int count = 0;
        for (Pending p : pending.values()) {
            if (p.item() == item) count++;
        }
        return count;
    }

    private Supply findSupply(Item item, int preferredSlot) {
        if (placeOffhand.get() && mc.player.getOffhandItem().is(item)) return new Supply(InteractionHand.OFF_HAND, -1);
        if (mc.player.getInventory().getItem(preferredSlot).is(item)) return new Supply(InteractionHand.MAIN_HAND, preferredSlot);
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return new Supply(InteractionHand.MAIN_HAND, i);
        }
        return null;
    }

    /** Moves the block into the hotbar (an empty slot, else the selected one). The click is simulated locally. */
    private boolean refillHotbar(Item item, int selected) {
        int from = -1;
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) {
                from = i;
                break;
            }
        }
        if (from == -1) return false;

        int to = selected;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                to = i;
                break;
            }
        }
        LogoUtils.swapWithHotbar(from, to);
        return mc.player.getInventory().getItem(to).is(item);
    }

    // ------------------------------------------------------------------ choosing where to click

    /**
     * Finds a click the server will accept that produces exactly the wanted block state. For blocks with no
     * orientation any reachable click works. Otherwise vanilla's own placement logic is run for each click and rotation
     * until the result matches, so stairs, logs, slabs and the like come out right.
     */
    private Plan choosePlan(Target t, Vec3 eye, ItemStack stack, InteractionHand hand, boolean oriented) {
        for (Click click : candidateClicks(t.pos(), eye)) {
            if (new AABB(click.clicked()).distanceToSqr(eye) >= serverReach.get() * serverReach.get()) continue;
            if (!oriented) return new Plan(click, false, 0f, 0f);

            if (simulates(t.state(), stack, hand, click, mc.player.getYRot(), mc.player.getXRot())) {
                return new Plan(click, false, 0f, 0f);
            }
            for (float pitch : PITCHES) {
                for (float yaw : yawsFor(t.state())) {
                    if (simulates(t.state(), stack, hand, click, yaw, pitch)) return new Plan(click, true, yaw, pitch);
                }
            }
        }
        return null;
    }

    private static float[] yawsFor(BlockState state) {
        if (!state.hasProperty(BlockStateProperties.ROTATION_16)) return YAWS_4;
        float[] yaws = new float[16];
        for (int i = 0; i < 16; i++) yaws[i] = i * 22.5f;
        return yaws;
    }

    /** Runs the game's own placement logic for this click and rotation and checks what block it would give. */
    private boolean simulates(BlockState want, ItemStack stack, InteractionHand hand, Click click, float yaw, float pitch) {
        float oldYaw = mc.player.getYRot();
        float oldPitch = mc.player.getXRot();
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        try {
            BlockHitResult hit = new BlockHitResult(click.hit(), click.face(), click.clicked(), false);
            // anonymous subclass: the 5-argument constructor is protected, a subclass may call it
            BlockPlaceContext context = new BlockPlaceContext(mc.level, mc.player, hand, stack, hit) {};
            if (!context.canPlace()) return false;
            BlockState got = want.getBlock().getStateForPlacement(context);
            return got != null && statesMatch(want, got);
        } finally {
            mc.player.setYRot(oldYaw);
            mc.player.setXRot(oldPitch);
        }
    }

    /** Clicks to try, best first: real neighbours (unless air place is Always), then the spot itself for air place. */
    private List<Click> candidateClicks(BlockPos pos, Vec3 eye) {
        List<Click> out = new ArrayList<>();
        AirMode air = airPlace.get();

        if (air != AirMode.Always) {
            List<Click> supports = new ArrayList<>();
            for (Direction d : Direction.values()) {
                BlockPos neighbour = pos.relative(d);
                if (!isSupport(neighbour)) continue;
                Direction face = d.getOpposite();
                Vec3 base = faceCenter(neighbour, face);
                if (!facesEye(face, base, eye)) continue;
                addVariants(supports, neighbour, face, base);
            }
            supports.sort(Comparator.comparingDouble(c -> c.hit().distanceToSqr(eye)));
            out.addAll(supports);
        }

        if (air != AirMode.Never) {
            for (Direction face : Direction.values()) {
                Vec3 base = faceCenter(pos, face);
                if (!facesEye(face, base, eye)) continue;
                addVariants(out, pos, face, base);
            }
        }
        return out;
    }

    private static Vec3 faceCenter(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    /** The face must point towards you; the server and anti-cheats expect that. */
    private static boolean facesEye(Direction face, Vec3 base, Vec3 eye) {
        return face.getStepX() * (eye.x - base.x) + face.getStepY() * (eye.y - base.y) + face.getStepZ() * (eye.z - base.z) > 0.0;
    }

    /** On side faces the height of the click decides top or bottom for slabs and stairs, so offer both halves. */
    private static void addVariants(List<Click> out, BlockPos clicked, Direction face, Vec3 base) {
        out.add(new Click(clicked, face, base));
        if (face.getAxis().isHorizontal()) {
            out.add(new Click(clicked, face, base.add(0.0, 0.25, 0.0)));
            out.add(new Click(clicked, face, base.add(0.0, -0.25, 0.0)));
        }
    }

    /** A solid, plain neighbour to click on. Blocks that were just sent count too: the server places them first. */
    private boolean isSupport(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        Pending p = pending.get(pos);
        if (p != null) state = p.state();

        if (state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty()) return false;
        if (!Block.isShapeFullBlock(state.getCollisionShape(mc.level, pos))) return false;
        if (state.hasBlockEntity()) return false;
        Block block = state.getBlock();
        return !(block instanceof CraftingTableBlock || block instanceof NoteBlock);
    }

    // ------------------------------------------------------------------ ghost check

    /**
     * Clicks placed plain blocks with an inert item. Nothing happens to the block, but the server answers every such
     * click by resending that block, which replaces anything the client wrongly believes is there.
     */
    private void probe() {
        if (!ghostCheck.get() || verifyAt.isEmpty() || budgetLeft < 5) return;

        Vec3 eye = mc.player.getEyePosition();
        List<BlockPos> due = new ArrayList<>();
        Iterator<Map.Entry<BlockPos, Integer>> it = verifyAt.entrySet().iterator();
        while (it.hasNext() && due.size() < probesPerTick.get()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            if (tick < entry.getValue()) continue;
            BlockPos pos = entry.getKey();
            it.remove();
            if (!plain(mc.level.getBlockState(pos))) continue;
            if (new AABB(pos).distanceToSqr(eye) >= serverReach.get() * serverReach.get()) continue;
            due.add(pos);
        }
        if (due.isEmpty()) return;

        int selected = mc.player.getInventory().getSelectedSlot();
        int inert = findInertSlot(selected);
        if (inert == -1) {
            if (!warnedNoInert) {
                warning("Ghost check needs an empty hand, pickaxe or totem slot in the hotbar - skipping it.");
                warnedNoInert = true;
            }
            verifyAt.clear();
            return;
        }

        boolean swap = inert != selected;
        if (budgetLeft < due.size() + (swap ? 2 : 0)) return;
        budgetLeft -= due.size() + (swap ? 2 : 0);

        if (swap) mc.getConnection().send(new ServerboundSetCarriedItemPacket(inert));
        for (BlockPos pos : due) {
            Direction face = PvpUtils.faceTowards(eye, Vec3.atCenterOf(pos));
            BlockHitResult hit = new BlockHitResult(faceCenter(pos, face), face, pos, false);
            sendSequenced(sequence -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, sequence));
        }
        if (swap) mc.getConnection().send(new ServerboundSetCarriedItemPacket(selected));
    }

    private int findInertSlot(int selected) {
        if (isInert(mc.player.getInventory().getItem(selected))) return selected;
        for (int i = 0; i < 9; i++) {
            if (isInert(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private static boolean isInert(ItemStack stack) {
        return stack.isEmpty() || stack.is(Items.TOTEM_OF_UNDYING) || stack.is(ItemTags.PICKAXES);
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || mc.player == null || schematic == null) return;

        int shown = 0;
        for (Target t : targets) {
            if (shown++ >= 300) break;
            if (pending.containsKey(t.pos())) continue;
            box(event, t.pos(), missingSide.get(), missingLine.get());
        }
        for (BlockPos pos : pending.keySet()) box(event, pos, pendingSide.get(), pendingLine.get());
    }

    private void box(Render3DEvent event, BlockPos pos, SettingColor side, SettingColor line) {
        event.renderer.box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, side, line, ShapeMode.Both, 0);
    }

    // ------------------------------------------------------------------ Litematica (optional, via reflection)

    private static final class Lite {
        private static Boolean available = null;
        private static Method getSchematicWorld;

        static synchronized boolean init() {
            if (available != null) return available;
            try {
                Class<?> handler = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
                getSchematicWorld = handler.getMethod("getSchematicWorld");
                available = true;
            } catch (Throwable t) {
                OpsTools.LOG.warn("Litematica integration unavailable: {}", t.toString());
                available = false;
            }
            return available;
        }

        /** Litematica's schematic world is a real Level, so block states are read directly, no reflection per block. */
        static Level schematicLevel() {
            try {
                return getSchematicWorld.invoke(null) instanceof Level level ? level : null;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
