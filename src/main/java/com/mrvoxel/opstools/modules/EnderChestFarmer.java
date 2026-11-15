package com.mrvoxel.opstools.modules;

import com.mrvoxel.opstools.OpsTools;
import com.mrvoxel.opstools.utils.LogoUtils;
import com.mrvoxel.opstools.utils.PvpUtils;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.speed.Speed;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Ender Chest Farmer - mines ender chests for obsidian as fast as the server allows. Standalone module.
 *
 *  - Chests are placed from the OFFHAND, the pickaxe stays in the main hand (no slot switching).
 *  - The first chest is mined normally. That starts a break on the server at this position, and the server keeps
 *    remembering it. From then on a bare STOP_DESTROY_BLOCK finishes any chest at that spot instantly.
 *  - BURST: every tick sends N x [STOP_DESTROY_BLOCK, place-from-offhand] as raw packets, blind, without waiting
 *    for the client to see the block. Breaking air / placing onto an existing chest is harmless on the server.
 *  - Optionally the Timer is boosted while farming.
 */
public class EnderChestFarmer extends Module {
    public enum BreakMode {
        Burst("Burst rebreak (fast)"),
        Normal("Normal breaking");

        private final String title;

        BreakMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");
    private final SettingGroup sgLoadout = settings.createGroup("Loadout");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- General
    private final Setting<BreakMode> breakMode = sgGeneral.add(new EnumSetting.Builder<BreakMode>()
        .name("break-mode")
        .description("Burst: raw rebreak+place packets every tick (needs the chests in the offhand). Normal: mine each chest normally.")
        .defaultValue(BreakMode.Burst).build());
    private final Setting<Integer> targetObsidian = sgGeneral.add(new IntSetting.Builder()
        .name("target-obsidian").description("Stop once you hold (and have lying next to the chest) this much obsidian. 0 = until the ender chests run out.")
        .defaultValue(0).min(0).sliderMax(2304).build());
    private final Setting<Integer> saveEnderChests = sgGeneral.add(new IntSetting.Builder()
        .name("save-ender-chests").description("Never use the last this-many ender chests.").defaultValue(0).min(0).sliderMax(64).build());
    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Look at the chest before the first place / normal break (burst packets are never rotated).").defaultValue(false).build());
    private final Setting<Boolean> dontBreakTools = sgGeneral.add(new BoolSetting.Builder()
        .name("dont-break-tools").description("Do not use a pickaxe that is almost broken.").defaultValue(false).build());
    private final Setting<Integer> breakDurability = sgGeneral.add(new IntSetting.Builder()
        .name("durability-percentage").description("Stop using a pickaxe at this durability percentage.")
        .defaultValue(2).range(1, 100).sliderRange(1, 100).visible(dontBreakTools::get).build());

    // --- Speed
    private final Setting<Integer> chestsPerTick = sgSpeed.add(new IntSetting.Builder()
        .name("chests-per-tick").description("Chests placed and broken per tick in burst mode. Higher = faster but more packets; watch the server's packet limit.")
        .defaultValue(2).range(1, 6).sliderRange(1, 6).visible(() -> breakMode.get() == BreakMode.Burst).build());
    private final Setting<Integer> warmupTicks = sgSpeed.add(new IntSetting.Builder()
        .name("warmup-ticks").description("Ticks to wait after the first chest appears before mining it (lets the server settle).")
        .defaultValue(4).range(0, 20).sliderRange(0, 20).visible(() -> breakMode.get() == BreakMode.Burst).build());
    private final Setting<Boolean> useBreakSpeedMultiplier = sgSpeed.add(new BoolSetting.Builder()
        .name("use-break-speed-multiplier").description("Boost the Timer while farming, then restore it. Anti-cheats may notice this.")
        .defaultValue(true).build());
    private final Setting<Double> breakSpeedMultiplier = sgSpeed.add(new DoubleSetting.Builder()
        .name("break-speed-multiplier").description("How much the Timer is boosted.")
        .defaultValue(1.5).range(1, 3).sliderRange(1, 3).visible(useBreakSpeedMultiplier::get).build());

    // --- Loadout
    private final Setting<Integer> totemHealth = sgLoadout.add(new IntSetting.Builder()
        .name("offhand-totem-health").description("At or below this health (+ absorption) a totem takes the offhand and farming pauses. 0 = off.")
        .defaultValue(10).range(0, 36).sliderRange(0, 36).build());
    private final Setting<Boolean> totemWhenPlayerNear = sgLoadout.add(new BoolSetting.Builder()
        .name("totem-when-player-near").description("Same, whenever another (non-friend) player is in render distance.")
        .defaultValue(true).build());

    // --- Render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Outline the farming spot.").defaultValue(true).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Fill color.").defaultValue(new SettingColor(170, 0, 255, 40, false)).visible(render::get).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Outline color.").defaultValue(new SettingColor(170, 0, 255, 255, false)).visible(render::get).build());

    private record TimerSnapshot(boolean timerActive, boolean speedActive, double speedTimer) {}

    private static final int STALL_TICKS = 80;
    private static final int CHECK_INTERVAL = 4;

    private BlockPos target = null;
    private int tick = 0;

    private boolean primed;      // server remembers a finished break here -> burst allowed
    private boolean mining;      // the first chest is currently being mined normally
    private boolean stopping;
    private int stopTimer;
    private int settle;
    private int stallTicks;
    private int lastObsidian;
    private final int[] sentRing = new int[5]; // raw chest placements of the last few ticks (client count lags behind)

    private int startObsidian;
    private int startChests;
    private TimerSnapshot timerSnapshot = null;

    public EnderChestFarmer() {
        // UPDATED: Using OpsTools.CATEGORY instead of Categories.Render or a missing LOGO_CATEGORY
        super(OpsTools.CATEGORY, "ender-chest-farmer", "Farms obsidian from ender chests with burst instant rebreak (placed from the offhand).");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) {
            toggle();
            return;
        }

        target = findTarget();
        if (target == null) {
            error("No free spot with a solid block under it next to you.");
            toggle();
            return;
        }

        primed = false;
        mining = false;
        stopping = false;
        stopTimer = 0;
        settle = 0;
        stallTicks = 0;
        tick = 0;
        java.util.Arrays.fill(sentRing, 0);
        startObsidian = LogoUtils.countItem(Items.OBSIDIAN);
        lastObsidian = startObsidian;
        startChests = LogoUtils.countItem(Items.ENDER_CHEST);
        applyTimerBoost();

        info("Farming ender chests at %s (%d chests, %d obsidian).", target.toShortString(), startChests, startObsidian);
    }

    @Override
    public void onDeactivate() {
        restoreTimerBoost();
        if (mc.player != null) {
            int gained = Math.max(0, LogoUtils.countItem(Items.OBSIDIAN) - startObsidian);
            int used = Math.max(0, startChests - LogoUtils.countItem(Items.ENDER_CHEST));
            info("Farmed about %d obsidian using %d ender chests.", gained, used);
        }
        target = null;
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;
        return String.valueOf(Math.max(0, LogoUtils.countItem(Items.OBSIDIAN) - startObsidian));
    }

    // ------------------------------------------------------------------ setup

    /** The block behind you first, then the sides, then in front. */
    private BlockPos findTarget() {
        Direction facing = Direction.fromYRot(mc.player.getYRot());
        Direction[] order = {facing.getOpposite(), facing.getClockWise(), facing.getCounterClockWise(), facing};
        BlockPos feet = mc.player.blockPosition();
        for (Direction direction : order) {
            BlockPos candidate = feet.relative(direction);
            if (validTarget(candidate, true)) return candidate;
        }
        return null;
    }

    private boolean validTarget(BlockPos pos, boolean checkEntities) {
        BlockState state = mc.level.getBlockState(pos);
        if (!state.canBeReplaced() && !state.is(Blocks.ENDER_CHEST)) return false;
        BlockPos support = pos.below();
        if (mc.level.getBlockState(support).getCollisionShape(mc.level, support).isEmpty()) return false;
        return !checkEntities || !PvpUtils.entityBlocks(pos);
    }

    private BlockHitResult placeHit() {
        BlockPos support = target.below();
        return new BlockHitResult(Vec3.atCenterOf(support).add(0.0, 0.5, 0.0), Direction.UP, support, false);
    }

    // ------------------------------------------------------------------ main loop

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || target == null) return;
        tick++;
        sentRing[tick % sentRing.length] = 0;

        // Never fight a GUI or a held cursor stack for the inventory.
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) return;

        if (!validTarget(target, false)) {
            error("The farming spot at %s is blocked or lost its support.", target.toShortString());
            toggle();
            return;
        }

        if (tickLoadout()) return;

        if (stopping) {
            if (stopTimer-- <= 0) toggle(); // lets the last drops be picked up first
            return;
        }

        if (settle > 0) {
            settle--;
            return;
        }

        if (tick % CHECK_INTERVAL == 0) {
            int obsidianCount = countObsidianNear();
            if (targetObsidian.get() > 0 && obsidianCount >= targetObsidian.get()) {
                info("Reached %d obsidian.", obsidianCount);
                finish();
                return;
            }
            if (!LogoUtils.hasRoomFor(Items.OBSIDIAN)) {
                warning("Your inventory is full.");
                finish();
                return;
            }
        }

        if (!ensurePickaxe()) return;

        if (primed && breakMode.get() == BreakMode.Burst) burst();
        else slowStep();
    }

    private void finish() {
        stopping = true;
        stopTimer = 40;
    }

    // ------------------------------------------------------------------ slow path (first chest / normal mode)

    private void slowStep() {
        BlockState state = mc.level.getBlockState(target);

        if (state.is(Blocks.ENDER_CHEST)) {
            mining = true;
            if (rotate.get()) PvpUtils.faceVec(Vec3.atCenterOf(target));
            Direction direction = PvpUtils.faceTowards(mc.player.getEyePosition(), Vec3.atCenterOf(target));
            mc.gameMode.continueDestroyBlock(target, direction);
            mc.player.swing(InteractionHand.MAIN_HAND);
            return;
        }

        // The chest is gone. If we just mined one normally the server now remembers the break: go to burst.
        if (mining) {
            mining = false;
            if (breakMode.get() == BreakMode.Burst) {
                primed = true;
                stallTicks = 0;
                lastObsidian = LogoUtils.countItem(Items.OBSIDIAN);
                burst();
                return;
            }
        }

        // Place the next chest from the offhand.
        if (!offhandHoldsChest() || usableChests() <= 0) {
            info(usableChests() <= 0 ? "Out of ender chests." : "No ender chest to place.");
            finish();
            return;
        }
        if (!PvpUtils.replaceable(target) || PvpUtils.entityBlocks(target)) return;

        BlockHitResult hit = placeHit();
        if (rotate.get()) PvpUtils.faceVec(hit.getLocation());
        mc.gameMode.useItemOn(mc.player, InteractionHand.OFF_HAND, hit);
        mc.player.swing(InteractionHand.OFF_HAND);
        if (breakMode.get() == BreakMode.Burst) settle = warmupTicks.get();
    }

    // ------------------------------------------------------------------ burst path

    /** Blind raw packets: break whatever chest is on the server, place the next one, repeat. */
    private void burst() {
        if (!offhandHoldsChest()) {
            info("Out of ender chests.");
            finish();
            return;
        }

        // Stall check: the server stopped finishing chests for us -> mine one normally and prime again.
        int obsidian = LogoUtils.countItem(Items.OBSIDIAN);
        if (obsidian > lastObsidian) {
            lastObsidian = obsidian;
            stallTicks = 0;
        } else if (++stallTicks > STALL_TICKS) {
            info("Rebreak stalled - re-priming with a normal break.");
            primed = false;
            stallTicks = 0;
            return;
        }

        int pending = 0;
        for (int sent : sentRing) pending += sent;
        int usable = Math.min(mc.player.getOffhandItem().getCount(), usableChests()) - pending;
        Direction face = PvpUtils.faceTowards(mc.player.getEyePosition(), Vec3.atCenterOf(target));
        BlockHitResult hit = placeHit();
        var connection = mc.player.connection;

        if (usable <= 0) {
            // Break the last chest still standing on the server.
            connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target, face));
            mc.player.swing(InteractionHand.MAIN_HAND);
            if (pending == 0) {
                info("Out of ender chests.");
                finish();
            }
            return;
        }

        int pairs = Math.min(chestsPerTick.get(), usable);
        for (int i = 0; i < pairs; i++) {
            connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target, face));
            connection.send(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, 0));
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
        sentRing[tick % sentRing.length] += pairs;
    }

    // ------------------------------------------------------------------ loadout (offhand)

    /** @return true if this tick was spent on a swap or a pause, so the farming code should skip it. */
    private boolean tickLoadout() {
        if (holdTotem()) {
            if (!mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
                for (int i = 0; i < 36; i++) {
                    if (mc.player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                        LogoUtils.swapWithOffhand(i);
                        return true;
                    }
                }
                return false;           // no totem to hold: keep farming
            }
            return true;                // totem is in the offhand: paused
        }

        int chestSlot = findChestSlot();
        if (!offhandHoldsChest()) {
            if (chestSlot != -1) {
                LogoUtils.swapWithOffhand(chestSlot);
                return true;
            }
            return false;
        }

        // Keep the offhand stocked: swap to a bigger stack before it runs dry.
        ItemStack offhand = mc.player.getOffhandItem();
        if (breakMode.get() == BreakMode.Burst
            && offhand.getCount() <= chestsPerTick.get() * 3 + 1
            && chestSlot != -1
            && mc.player.getInventory().getItem(chestSlot).getCount() > offhand.getCount()) {
            LogoUtils.swapWithOffhand(chestSlot);
            return true;
        }
        return false;
    }

    private boolean holdTotem() {
        if (totemHealth.get() > 0 && mc.player.getHealth() + mc.player.getAbsorptionAmount() <= totemHealth.get()) return true;
        if (!totemWhenPlayerNear.get()) return false;
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isSpectator() || PvpUtils.isFriend(player)) continue;
            return true;
        }
        return false;
    }

    private boolean offhandHoldsChest() {
        return mc.player.getOffhandItem().is(Items.ENDER_CHEST);
    }

    /** Chests we may still use (everything minus the reserve). */
    private int usableChests() {
        return LogoUtils.countItem(Items.ENDER_CHEST) - saveEnderChests.get();
    }

    /** The biggest ender chest stack in the inventory (not the offhand), or -1. */
    private int findChestSlot() {
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.ENDER_CHEST) && stack.getCount() > bestCount) {
                best = i;
                bestCount = stack.getCount();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ pickaxes

    private boolean usablePickaxe(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(ItemTags.PICKAXES)) return false;
        if (LogoUtils.hasSilkTouch(stack)) return false;
        if (!dontBreakTools.get()) return true;
        int max = stack.getMaxDamage();
        return max <= 0 || (max - stack.getDamageValue()) > max * (breakDurability.get() / 100.0);
    }

    /** Makes sure a usable pickaxe is held in the main hand. @return true when it is ready this tick. */
    private boolean ensurePickaxe() {
        int selected = mc.player.getInventory().getSelectedSlot();
        if (usablePickaxe(mc.player.getInventory().getItem(selected))) return true;

        BlockState state = Blocks.ENDER_CHEST.defaultBlockState();
        int best = -1;
        float bestScore = -1.0f;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!usablePickaxe(stack)) continue;
            float score = stack.getDestroySpeed(state);
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }

        if (best == -1) {
            error("No pickaxe without Silk Touch (and with durability left) to mine ender chests.");
            toggle();
            return false;
        }
        if (best < 9) LogoUtils.selectHotbar(best);
        else LogoUtils.swapWithHotbar(best, selected);
        return false; // use it next tick so the server has the new held slot
    }

    // ------------------------------------------------------------------ obsidian

    /** Obsidian in your inventory and offhand plus the drops lying next to the farming spot. */
    private int countObsidianNear() {
        int count = LogoUtils.countItem(Items.OBSIDIAN);
        AABB box = new AABB(target.getX(), target.getY(), target.getZ(), target.getX() + 1, target.getY() + 2, target.getZ() + 1).inflate(2);
        for (ItemEntity entity : mc.level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (entity.getItem().is(Items.OBSIDIAN)) count += entity.getItem().getCount();
        }
        return count;
    }

    // ------------------------------------------------------------------ timer boost

    private void applyTimerBoost() {
        if (!useBreakSpeedMultiplier.get() || breakSpeedMultiplier.get() <= 1.0) return;

        Timer timer = Modules.get().get(Timer.class);
        Speed speed = Modules.get().get(Speed.class);
        if (timer == null) return;

        timerSnapshot = new TimerSnapshot(timer.isActive(), speed != null && speed.isActive(), speed == null ? 1.0 : speed.timer.get());
        if (speed != null) speed.timer.set(1.0);       // no double boost
        if (!timer.isActive()) timer.toggle();
        timer.setOverride(breakSpeedMultiplier.get());
    }

    private void restoreTimerBoost() {
        if (timerSnapshot == null) return;

        Timer timer = Modules.get().get(Timer.class);
        Speed speed = Modules.get().get(Speed.class);
        if (speed != null) speed.timer.set(timerSnapshot.speedTimer());
        if (timer != null) {
            timer.setOverride(Timer.OFF);
            if (timer.isActive() != timerSnapshot.timerActive()) timer.toggle();
        }
        timerSnapshot = null;
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || target == null) return;
        event.renderer.box(
            target.getX(), target.getY(), target.getZ(), target.getX() + 1, target.getY() + 1, target.getZ() + 1,
            sideColor.get(), lineColor.get(), ShapeMode.Both, 0
        );
    }
}
