package com.mrvoxel.opstools.modules;

import com.mrvoxel.opstools.OpsTools;
import com.mrvoxel.opstools.utils.CrystalUtils;
import com.mrvoxel.opstools.utils.PvpUtils;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crystal Aura+ - places and breaks end crystals for PvP, modelled on BlackOut's "Auto Crystal+".
 *
 * Every tick: (1) break the best crystal in range, (2) place the best new crystal. "Best" means the most damage to an
 * enemy while staying inside your self / friend damage limits (see the Damage group).
 *
 * Differences to the BlackOut original (it needs classes this addon does not have):
 *  - damage comes from {@link CrystalUtils} (vanilla formula, armor, toughness, resistance, assumed blast protection),
 *  - rotations / switching / placing go through {@link PvpUtils},
 *  - no AutoMine / Piston Crystal hooks and no ID-predict (that needs a packet mixin),
 *  - extrapolation is a simple smoothed horizontal-motion estimate.
 *
 * Fixed from the original: the "Safe" comparison in the break loop divided by the wrong damage, and the explode checks
 * used Force Pop where they meant Anti Self Pop.
 */
public class CrystalAuraPlus extends Module {
    public enum DmgCheckMode { Normal, Safe }

    public enum ExplodeMode { FullCheck, SelfDmgCheck, SelfDmgOwn, AlwaysOwn, Always }

    public enum SwitchMode {
        Disabled, Simple, Silent, Inventory
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlace = settings.createGroup("Place");
    private final SettingGroup sgExplode = settings.createGroup("Explode");
    private final SettingGroup sgSwitch = settings.createGroup("Switch");
    private final SettingGroup sgDamage = settings.createGroup("Damage");
    private final SettingGroup sgExt = settings.createGroup("Extrapolation");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ------------------------------------------------------------------ General
    private final Setting<Boolean> place = sgGeneral.add(new BoolSetting.Builder()
        .name("place").description("Places crystals.").defaultValue(true).build());
    private final Setting<Boolean> explode = sgGeneral.add(new BoolSetting.Builder()
        .name("explode").description("Breaks crystals.").defaultValue(true).build());
    private final Setting<Boolean> needCrystal = sgGeneral.add(new BoolSetting.Builder()
        .name("require-crystals").description("Only act while you carry end crystals (offhand / hotbar, inventory in Inventory switch mode).")
        .defaultValue(true).build());
    private final Setting<Boolean> pauseEat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-eat").description("Pauses while you are eating / drinking.").defaultValue(false).build());
    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Look at the crystal / block before interacting (server-side rotation).").defaultValue(true).build());
    private final Setting<Boolean> ignoreTerrain = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-terrain").description("Assume soft blocks do not shield enemies (spams through terrain to kill them).").defaultValue(true).build());
    private final Setting<Boolean> oldCrystals = sgGeneral.add(new BoolSetting.Builder()
        .name("old-crystals").description("Also require the block above the crystal to be air (1.12 style servers).").defaultValue(false).build());
    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range").description("Players further away than this are ignored for damage calculations.")
        .defaultValue(12).min(4).max(20).sliderMax(20).build());
    private final Setting<Integer> computeInterval = sgGeneral.add(new IntSetting.Builder()
        .name("compute-interval").description("Search for a new placement every this many ticks (higher = less lag).")
        .defaultValue(1).min(1).max(5).sliderMax(5).build());

    // ------------------------------------------------------------------ Place
    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range").description("Max distance to the placement when it is in line of sight.")
        .defaultValue(4.5).min(1).max(6).sliderMax(6).build());
    private final Setting<Double> placeWallsRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-walls-range").description("Max distance to the placement when it is behind blocks.")
        .defaultValue(3.5).min(1).max(6).sliderMax(6).build());
    private final Setting<Double> placeSpeed = sgPlace.add(new DoubleSetting.Builder()
        .name("place-speed").description("Crystals placed per second.").defaultValue(10).min(0.1).max(20).sliderMax(20).build());
    private final Setting<Boolean> instantPlace = sgPlace.add(new BoolSetting.Builder()
        .name("instant-place").description("Ignore place-speed when the spot is free (e.g. right after breaking the crystal there).")
        .defaultValue(true).build());
    private final Setting<Integer> placeRetry = sgPlace.add(new IntSetting.Builder()
        .name("place-retry-ticks").description("Ticks before the same spot may be placed on again (raise it with high ping).")
        .defaultValue(2).min(1).sliderMax(10).build());
    private final Setting<Double> slowDamage = sgPlace.add(new DoubleSetting.Builder()
        .name("slow-damage").description("Use slow-speed when the enemy would take at most this much.")
        .defaultValue(3).min(0).sliderMax(20).build());
    private final Setting<Double> slowSpeed = sgPlace.add(new DoubleSetting.Builder()
        .name("slow-speed").description("Crystals per second when under slow-damage.").defaultValue(2).min(0.1).max(20).sliderMax(20).build());

    // ------------------------------------------------------------------ Explode
    private final Setting<Double> breakRange = sgExplode.add(new DoubleSetting.Builder()
        .name("break-range").description("Max distance to the crystal's hitbox when in line of sight.")
        .defaultValue(4.5).min(1).max(6).sliderMax(6).build());
    private final Setting<Double> breakWallsRange = sgExplode.add(new DoubleSetting.Builder()
        .name("break-walls-range").description("Max distance to the crystal's hitbox when behind blocks.")
        .defaultValue(3.5).min(1).max(6).sliderMax(6).build());
    private final Setting<Boolean> onlyOwn = sgExplode.add(new BoolSetting.Builder()
        .name("only-own").description("Only break crystals you placed.").defaultValue(false).build());
    private final Setting<Integer> existedTicks = sgExplode.add(new IntSetting.Builder()
        .name("existed-ticks").description("Crystal must be this many ticks old before it is hit.").defaultValue(0).min(0).sliderMax(10).build());
    private final Setting<Boolean> instantAttack = sgExplode.add(new BoolSetting.Builder()
        .name("instant-attack").description("The first hit on a crystal ignores explode-speed.").defaultValue(true).build());
    private final Setting<Double> expSpeedLimit = sgExplode.add(new DoubleSetting.Builder()
        .name("explode-speed-limit").description("Max hits per second with instant-attack (0 = no limit).")
        .defaultValue(0).min(0).sliderMax(20).visible(instantAttack::get).build());
    private final Setting<Double> expSpeed = sgExplode.add(new DoubleSetting.Builder()
        .name("explode-speed").description("Hits per second.").defaultValue(8).min(0.1).max(20).sliderMax(20).build());
    private final Setting<Integer> attackRetry = sgExplode.add(new IntSetting.Builder()
        .name("attack-retry-ticks").description("Ticks before the same crystal is hit again (raise it with high ping).")
        .defaultValue(3).min(1).sliderMax(10).build());
    private final Setting<Boolean> sequential = sgExplode.add(new BoolSetting.Builder()
        .name("sequential").description("Never break and place during the same tick.").defaultValue(false).build());
    private final Setting<Boolean> setDead = sgExplode.add(new BoolSetting.Builder()
        .name("set-dead").description("Remove the crystal on your screen after hitting it (client-side only).").defaultValue(false).build());
    private final Setting<Integer> setDeadDelay = sgExplode.add(new IntSetting.Builder()
        .name("set-dead-delay").description("Ticks after the hit before the crystal disappears.")
        .defaultValue(1).min(0).sliderMax(10).visible(setDead::get).build());

    // ------------------------------------------------------------------ Switch
    private final Setting<SwitchMode> switchMode = sgSwitch.add(new EnumSetting.Builder<SwitchMode>()
        .name("switch-mode").description("Disabled: hold crystals yourself. Simple: really select the slot. Silent: hotbar, switched back. Inventory: also from the inventory.")
        .defaultValue(SwitchMode.Silent).build());
    private final Setting<Double> switchPenalty = sgSwitch.add(new DoubleSetting.Builder()
        .name("switch-penalty").description("Seconds to wait before hitting crystals after YOU changed your hotbar slot.")
        .defaultValue(0.25).min(0).sliderMax(1).build());

    // ------------------------------------------------------------------ Damage
    private final Setting<DmgCheckMode> dmgCheckMode = sgDamage.add(new EnumSetting.Builder<DmgCheckMode>()
        .name("dmg-check-mode").description("Normal: most enemy damage. Safe: best enemy / self damage ratio.").defaultValue(DmgCheckMode.Normal).build());
    private final Setting<Double> blastAssumption = sgDamage.add(new DoubleSetting.Builder()
        .name("blast-assumption").description("Assumed damage reduction (0-0.8) from Protection / Blast Protection on every player's armor.")
        .defaultValue(0.3).min(0).max(0.8).sliderMax(0.8).build());
    private final Setting<Double> minPlace = sgDamage.add(new DoubleSetting.Builder()
        .name("min-place").description("Minimum enemy damage to place.").defaultValue(4).min(0).sliderMax(20).build());
    private final Setting<Double> maxPlace = sgDamage.add(new DoubleSetting.Builder()
        .name("max-place").description("Max self damage for placing.").defaultValue(8).min(0).sliderMax(20).build());
    private final Setting<Double> minPlaceRatio = sgDamage.add(new DoubleSetting.Builder()
        .name("min-place-ratio").description("Enemy / self damage needed to place.").defaultValue(1.4).min(0).sliderMax(5).build());
    private final Setting<Double> maxFriendPlace = sgDamage.add(new DoubleSetting.Builder()
        .name("max-friend-place").description("Max friend damage for placing.").defaultValue(8).min(0).sliderMax(20).build());
    private final Setting<Double> minFriendPlaceRatio = sgDamage.add(new DoubleSetting.Builder()
        .name("min-friend-place-ratio").description("Enemy / friend damage needed to place.").defaultValue(2).min(0).sliderMax(5).build());
    private final Setting<ExplodeMode> expMode = sgDamage.add(new EnumSetting.Builder<ExplodeMode>()
        .name("explode-damage-mode").description("Which checks are used before breaking a crystal.").defaultValue(ExplodeMode.FullCheck).build());
    private final Setting<Double> minExplode = sgDamage.add(new DoubleSetting.Builder()
        .name("min-explode").description("Minimum enemy damage to break.").defaultValue(2.5).min(0).sliderMax(20).build());
    private final Setting<Double> maxExplode = sgDamage.add(new DoubleSetting.Builder()
        .name("max-explode").description("Max self damage for breaking.").defaultValue(9).min(0).sliderMax(20).build());
    private final Setting<Double> minExpRatio = sgDamage.add(new DoubleSetting.Builder()
        .name("min-explode-ratio").description("Enemy / self damage needed to break.").defaultValue(1.1).min(0).sliderMax(5).build());
    private final Setting<Double> maxFriendExp = sgDamage.add(new DoubleSetting.Builder()
        .name("max-friend-explode").description("Max friend damage for breaking.").defaultValue(12).min(0).sliderMax(20).build());
    private final Setting<Double> minFriendExpRatio = sgDamage.add(new DoubleSetting.Builder()
        .name("min-friend-explode-ratio").description("Enemy / friend damage needed to break.").defaultValue(2).min(0).sliderMax(5).build());
    private final Setting<Double> forcePop = sgDamage.add(new DoubleSetting.Builder()
        .name("force-pop").description("Skip the min-damage / ratio checks if an enemy dies / pops in this many hits (0 = off).")
        .defaultValue(1).min(0).sliderMax(10).build());
    private final Setting<Double> antiFriendPop = sgDamage.add(new DoubleSetting.Builder()
        .name("anti-friend-pop").description("Cancel if a friend would pop in this many hits (0 = off).").defaultValue(1).min(0).sliderMax(10).build());
    private final Setting<Double> antiSelfPop = sgDamage.add(new DoubleSetting.Builder()
        .name("anti-self-pop").description("Cancel if you would pop in this many hits (0 = off).").defaultValue(1).min(0).sliderMax(10).build());

    // ------------------------------------------------------------------ Extrapolation
    private final Setting<Integer> selfExt = sgExt.add(new IntSetting.Builder()
        .name("self-extrapolation").description("Ticks of your own movement predicted for self damage.").defaultValue(0).min(0).max(20).sliderMax(20).build());
    private final Setting<Integer> enemyExt = sgExt.add(new IntSetting.Builder()
        .name("extrapolation").description("Ticks of enemy movement predicted for enemy damage.").defaultValue(0).min(0).max(20).sliderMax(20).build());
    private final Setting<Integer> extSmoothing = sgExt.add(new IntSetting.Builder()
        .name("smoothening").description("Higher = motion estimate reacts slower but is steadier.").defaultValue(3).min(1).max(20).sliderMax(20).build());

    // ------------------------------------------------------------------ Render
    private final Setting<Boolean> swing = sgRender.add(new BoolSetting.Builder()
        .name("swing").description("Swing your hand when placing / breaking.").defaultValue(true).build());
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Draw the current placement.").defaultValue(true).build());
    private final Setting<Double> moveSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("animation-move-speed").description("How fast the box glides to a new position.").defaultValue(8).min(0.5).sliderMax(30).visible(render::get).build());
    private final Setting<Double> growSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("animation-grow-speed").description("How fast the box grows / shrinks.").defaultValue(5).min(0.5).sliderMax(20).visible(render::get).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Which parts of the box are drawn.").defaultValue(ShapeMode.Both).visible(render::get).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Fill color.").defaultValue(new SettingColor(255, 0, 0, 50, false)).visible(render::get).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Outline color.").defaultValue(new SettingColor(255, 0, 0, 255, false)).visible(render::get).build());

    // ------------------------------------------------------------------ data

    /** Everything about a player that the damage maths needs, captured once per tick. */
    private record Target(Player player, AABB box, Vec3 feet, CrystalUtils.Prot prot, boolean friend, double hp) {}

    /** Damage summary of one explosion. -1 means "nobody of that kind in range". */
    private record Dmg(double enemy, double friend, double self, double enemyHp, double friendHp) {}

    private record Candidate(BlockPos pos, BlockHitResult hit, Dmg dmg, double score) {}

    private record SetDeadEntry(int id, int tick) {}

    private int tickCounter = 0;
    private long lastPlaceMs = 0;
    private long lastAttackMs = 0;
    private long lastSwitchMs = 0;
    private int lastPlaceTick = -1000;
    private int lastAttackTick = -1000;
    private BlockPos lastPlacePos = null;
    private int lastSlot = -1;
    private int computeCounter = 0;

    private BlockPos placePos = null;
    private Candidate cachedPlacement = null;

    private final Map<Integer, Integer> attacked = new HashMap<>();   // crystal id -> tick it was hit
    private final Map<BlockPos, Integer> own = new HashMap<>();       // crystal pos -> tick we placed it
    private final List<SetDeadEntry> setDeads = new ArrayList<>();
    private final ArrayDeque<Long> hits = new ArrayDeque<>();

    private final Map<UUID, Vec3> lastPositions = new HashMap<>();
    private final Map<UUID, Vec3> velocities = new HashMap<>();
    private Object lastLevel = null;

    private final List<Target> targets = new ArrayList<>();
    private Target self = null;

    private Vec3 renderPos = null;
    private double renderProgress = 0;
    private long lastRenderMs = System.currentTimeMillis();

    public CrystalAuraPlus() {
        super(OpsTools.CATEGORY, "crystal-aura-plus", "Places and breaks end crystals on enemies (damage based, with self / friend safety).");
    }

    @Override
    public void onActivate() {
        tickCounter = 0;
        lastPlaceMs = 0;
        lastAttackMs = 0;
        lastSwitchMs = 0;
        lastPlaceTick = -1000;
        lastAttackTick = -1000;
        lastPlacePos = null;
        lastSlot = -1;
        computeCounter = 0;
        placePos = null;
        cachedPlacement = null;
        attacked.clear();
        own.clear();
        setDeads.clear();
        hits.clear();
        lastPositions.clear();
        velocities.clear();
        lastLevel = null;
        targets.clear();
        self = null;
        renderPos = null;
        renderProgress = 0;
        lastRenderMs = System.currentTimeMillis();
    }

    @Override
    public void onDeactivate() {
        placePos = null;
        cachedPlacement = null;
        targets.clear();
        self = null;
    }

    @Override
    public String getInfoString() {
        long now = System.currentTimeMillis();
        while (!hits.isEmpty() && now - hits.peekFirst() > 1000) hits.pollFirst();
        return String.valueOf(hits.size());
    }

    // ------------------------------------------------------------------ main loop

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        tickCounter++;
        CrystalUtils.newTick();

        if (lastLevel != mc.level) {
            lastLevel = mc.level;
            attacked.clear();
            own.clear();
            setDeads.clear();
            lastPositions.clear();
            velocities.clear();
            cachedPlacement = null;
        }

        trackMotion();
        cleanup();
        processSetDead();

        int slot = mc.player.getInventory().getSelectedSlot();
        if (slot != lastSlot) {
            if (lastSlot != -1) lastSwitchMs = System.currentTimeMillis();
            lastSlot = slot;
        }

        placePos = null;
        if (pauseEat.get() && mc.player.isUsingItem()) return;
        if (!mc.player.isAlive()) return;
        if (needCrystal.get() && !hasCrystal()) return;

        buildTargets();

        boolean attackedNow = false;
        if (explode.get()) attackedNow = tryExplode();

        if (place.get() && !(sequential.get() && attackedNow)) tryPlace();
    }

    // ------------------------------------------------------------------ explode

    private boolean tryExplode() {
        long now = System.currentTimeMillis();
        if (now - lastSwitchMs < switchPenalty.get() * 1000.0) return false;

        Vec3 eye = mc.player.getEyePosition();
        double reach = Math.max(breakRange.get(), breakWallsRange.get()) + 2.0;

        EndCrystal best = null;
        double bestScore = -Double.MAX_VALUE;

        for (EndCrystal crystal : mc.level.getEntitiesOfClass(EndCrystal.class, mc.player.getBoundingBox().inflate(reach))) {
            if (!crystal.isAlive() || crystal.tickCount < existedTicks.get()) continue;

            Integer at = attacked.get(crystal.getId());
            if (at != null && tickCounter - at < attackRetry.get()) continue;

            BlockPos cp = crystal.blockPosition();
            boolean isOwn = own.containsKey(cp);
            if (onlyOwn.get() && !isOwn) continue;

            AABB box = crystal.getBoundingBox();
            boolean visible = CrystalUtils.visible(eye, box.getCenter());
            double range = visible ? breakRange.get() : breakWallsRange.get();
            if (CrystalUtils.distanceToBox(eye, box) > range) continue;

            Dmg dmg = damage(crystal.position());
            if (!explodeCheck(dmg, isOwn)) continue;

            double score = score(dmg);
            if (score > bestScore) {
                bestScore = score;
                best = crystal;
            }
        }
        if (best == null) return false;

        // Rate limit
        boolean first = !attacked.containsKey(best.getId());
        double interval;
        if (first && instantAttack.get()) interval = expSpeedLimit.get() <= 0 ? 0 : 1000.0 / expSpeedLimit.get();
        else interval = 1000.0 / expSpeed.get();
        if (now - lastAttackMs < interval) return false;

        attack(best, now);
        return true;
    }

    private void attack(EndCrystal crystal, long now) {
        if (rotate.get()) PvpUtils.faceVec(crystal.getBoundingBox().getCenter());

        mc.gameMode.attack(mc.player, crystal);
        if (swing.get()) mc.player.swing(InteractionHand.MAIN_HAND);

        attacked.put(crystal.getId(), tickCounter);
        own.remove(crystal.blockPosition());
        lastAttackMs = now;
        lastAttackTick = tickCounter;
        hits.addLast(now);

        if (setDead.get()) setDeads.add(new SetDeadEntry(crystal.getId(), tickCounter + setDeadDelay.get()));
    }

    // ------------------------------------------------------------------ place

    private void tryPlace() {
        computeCounter++;
        if (cachedPlacement == null || computeCounter >= computeInterval.get()) {
            computeCounter = 0;
            cachedPlacement = findPlacement();
        } else if (cachedPlacement != null && !stillValid(cachedPlacement)) {
            cachedPlacement = findPlacement();
        }

        Candidate best = cachedPlacement;
        if (best == null) return;
        placePos = best.pos();

        long now = System.currentTimeMillis();
        boolean slow = best.dmg().enemy() <= slowDamage.get();
        double speed = slow ? slowSpeed.get() : placeSpeed.get();

        boolean sameSpotTooSoon = best.pos().equals(lastPlacePos) && tickCounter - lastPlaceTick < placeRetry.get();
        if (sameSpotTooSoon) return;
        if (tickCounter == lastPlaceTick) return;

        boolean timerOk = now - lastPlaceMs >= 1000.0 / speed;
        boolean instant = instantPlace.get() && !slow;
        if (!timerOk && !instant) return;

        if (placeCrystal(best.hit())) {
            lastPlaceMs = now;
            lastPlaceTick = tickCounter;
            lastPlacePos = best.pos();
            own.put(best.pos(), tickCounter);
        }
    }

    /** Cheap re-check of a cached placement: still air above a hard block, nothing standing in it. */
    private boolean stillValid(Candidate c) {
        BlockPos pos = c.pos();
        if (!mc.level.getBlockState(pos).isAir()) return false;
        Block base = mc.level.getBlockState(pos.below()).getBlock();
        if (base != Blocks.OBSIDIAN && base != Blocks.BEDROCK) return false;
        return !blockedByEntity(pos);
    }

    private Candidate findPlacement() {
        Vec3 eye = mc.player.getEyePosition();
        BlockPos origin = BlockPos.containing(eye);
        int r = (int) Math.ceil(Math.max(placeRange.get(), placeWallsRange.get()));

        Candidate best = null;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!mc.level.getBlockState(pos).isAir()) continue;

                    BlockPos base = pos.below();
                    Block block = mc.level.getBlockState(base).getBlock();
                    if (block != Blocks.OBSIDIAN && block != Blocks.BEDROCK) continue;
                    if (oldCrystals.get() && !mc.level.getBlockState(pos.above()).isAir()) continue;

                    // The click point: middle of the top face of the base block (same spot the crystal appears at).
                    Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                    double dist = eye.distanceTo(center);
                    double range = CrystalUtils.visible(eye, center) ? placeRange.get() : placeWallsRange.get();
                    if (dist > range) continue;

                    // The crystal must be hittable from where we stand.
                    AABB crystalBox = new AABB(pos.getX() - 0.5, pos.getY(), pos.getZ() - 0.5, pos.getX() + 1.5, pos.getY() + 2, pos.getZ() + 1.5);
                    if (CrystalUtils.distanceToBox(eye, crystalBox) > Math.max(breakRange.get(), breakWallsRange.get())) continue;

                    if (blockedByEntity(pos)) continue;

                    Dmg dmg = damage(center);
                    if (!placeCheck(dmg)) continue;

                    double score = score(dmg);
                    if (best == null || score > best.score()) {
                        BlockHitResult hit = new BlockHitResult(center, Direction.UP, base, false);
                        best = new Candidate(pos, hit, dmg, score);
                    }
                }
            }
        }
        return best;
    }

    /** Vanilla: any non-spectator entity inside the 1x2x1 space stops the placement (crystals we already hit don't count). */
    private boolean blockedByEntity(BlockPos pos) {
        AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 2, pos.getZ() + 1);
        for (Entity entity : mc.level.getEntitiesOfClass(Entity.class, box)) {
            if (entity.isSpectator() || !entity.isAlive()) continue;
            if (entity instanceof EndCrystal && attacked.containsKey(entity.getId())) continue;
            return true;
        }
        return false;
    }

    private boolean placeCrystal(BlockHitResult hit) {
        if (mc.player.getOffhandItem().getItem() == Items.END_CRYSTAL) {
            send(hit, InteractionHand.OFF_HAND);
            return true;
        }
        if (mc.player.getMainHandItem().getItem() == Items.END_CRYSTAL) {
            send(hit, InteractionHand.MAIN_HAND);
            return true;
        }

        switch (switchMode.get()) {
            case Simple -> {
                int slot = PvpUtils.findHotbar(Items.END_CRYSTAL);
                if (slot == -1) return false;
                mc.player.getInventory().setSelectedSlot(slot);
                lastSlot = slot;
                send(hit, InteractionHand.MAIN_HAND);
                return true;
            }
            case Silent -> {
                int slot = PvpUtils.findHotbar(Items.END_CRYSTAL);
                if (slot == -1) return false;
                PvpUtils.useSlot(slot, () -> send(hit, InteractionHand.MAIN_HAND));
                return true;
            }
            case Inventory -> {
                return PvpUtils.withItem(stack -> stack.getItem() == Items.END_CRYSTAL, () -> send(hit, InteractionHand.MAIN_HAND));
            }
            default -> {
                return false;
            }
        }
    }

    private void send(BlockHitResult hit, InteractionHand hand) {
        if (rotate.get()) PvpUtils.faceVec(hit.getLocation());
        mc.gameMode.useItemOn(mc.player, hand, hit);
        if (swing.get()) mc.player.swing(hand);
    }

    private boolean hasCrystal() {
        if (mc.player.getOffhandItem().getItem() == Items.END_CRYSTAL) return true;
        if (PvpUtils.findHotbar(Items.END_CRYSTAL) != -1) return true;
        if (switchMode.get() == SwitchMode.Inventory) {
            for (int i = 9; i < 36; i++) {
                if (mc.player.getInventory().getItem(i).getItem() == Items.END_CRYSTAL) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ damage

    private double selfHp() {
        return mc.player.getHealth() + mc.player.getAbsorptionAmount();
    }

    private void buildTargets() {
        targets.clear();
        self = makeTarget(mc.player, selfExt.get(), false);

        double rangeSq = targetRange.get() * targetRange.get();
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isSpectator() || !player.isAlive() || player.getHealth() <= 0) continue;
            if (mc.player.distanceToSqr(player) > rangeSq + 100) continue; // crystals sit up to ~5 blocks from you
            targets.add(makeTarget(player, enemyExt.get(), PvpUtils.isFriend(player)));
        }
    }

    private Target makeTarget(Player player, int ext, boolean friend) {
        AABB box = player.getBoundingBox();
        if (ext > 0) {
            Vec3 vel = velocities.getOrDefault(player.getUUID(), Vec3.ZERO);
            box = box.move(vel.x * ext, 0, vel.z * ext);
        }
        Vec3 feet = new Vec3((box.minX + box.maxX) / 2.0, box.minY, (box.minZ + box.maxZ) / 2.0);
        CrystalUtils.Prot prot = CrystalUtils.protection(player, blastAssumption.get(), true);
        return new Target(player, box, feet, prot, friend, player.getHealth() + player.getAbsorptionAmount());
    }

    /** Damage an end crystal exploding at {@code center} would deal to you, your friends and the best enemy. */
    private Dmg damage(Vec3 center) {
        double selfDamage = CrystalUtils.damage(center, self.feet(), self.box(), self.prot(), false);

        double enemy = -1, friend = -1, enemyHp = -1, friendHp = -1;
        for (Target t : targets) {
            if (CrystalUtils.maxDamage(center, t.feet(), t.prot()) <= 0.0) continue; // out of blast radius

            // Optimistic terrain only for enemies; friends are always checked with the real blocks.
            double dmg = CrystalUtils.damage(center, t.feet(), t.box(), t.prot(), !t.friend() && ignoreTerrain.get());

            if (t.friend()) {
                if (dmg > friend) {
                    friend = dmg;
                    friendHp = t.hp();
                }
            } else if (dmg > enemy) {
                enemy = dmg;
                enemyHp = t.hp();
            }
        }
        return new Dmg(enemy, friend, selfDamage, enemyHp, friendHp);
    }

    private double score(Dmg d) {
        double s = dmgCheckMode.get() == DmgCheckMode.Normal ? d.enemy() : d.enemy() / Math.max(d.self(), 0.5);
        if (d.enemyHp() > 0 && d.enemy() >= d.enemyHp()) s += 1000.0; // a kill / pop beats everything
        return s - d.self() * 0.01;
    }

    private boolean popsEnemy(Dmg d) {
        return forcePop.get() > 0 && d.enemy() >= 0 && d.enemyHp() > 0 && d.enemy() * forcePop.get() >= d.enemyHp();
    }

    private boolean safetyFails(Dmg d) {
        if (antiSelfPop.get() > 0 && d.self() * antiSelfPop.get() >= selfHp()) return true;
        return antiFriendPop.get() > 0 && d.friend() >= 0 && d.friendHp() > 0 && d.friend() * antiFriendPop.get() >= d.friendHp();
    }

    private boolean placeCheck(Dmg d) {
        if (d.enemy() < 0) return false; // nobody to hurt
        if (safetyFails(d)) return false;
        if (d.self() > maxPlace.get()) return false;
        if (d.friend() > maxFriendPlace.get()) return false;

        if (popsEnemy(d)) return true;

        if (d.enemy() < minPlace.get()) return false;
        if (d.friend() > 0 && d.enemy() / d.friend() < minFriendPlaceRatio.get()) return false;
        return d.self() <= 0 || d.enemy() / d.self() >= minPlaceRatio.get();
    }

    private boolean explodeCheck(Dmg d, boolean isOwn) {
        ExplodeMode mode = expMode.get();
        boolean checkOwn = mode != ExplodeMode.Always;
        boolean checkDmg = mode == ExplodeMode.FullCheck
            || ((mode == ExplodeMode.SelfDmgOwn || mode == ExplodeMode.AlwaysOwn) && !isOwn);

        if (checkOwn && safetyFails(d)) return false;

        if (checkDmg && !popsEnemy(d)) {
            if (d.enemy() < minExplode.get()) return false;
            if (d.friend() > 0 && d.enemy() / d.friend() < minFriendExpRatio.get()) return false;
            if (d.self() > 0 && d.enemy() / d.self() < minExpRatio.get()) return false;
        }

        if (checkOwn) {
            if (d.friend() > maxFriendExp.get()) return false;
            return d.self() <= maxExplode.get();
        }
        return true;
    }

    // ------------------------------------------------------------------ bookkeeping

    /** Smoothed horizontal velocity of every player, for the extrapolation settings. */
    private void trackMotion() {
        double k = 1.0 / Math.max(1, extSmoothing.get());
        for (Player player : mc.level.players()) {
            UUID id = player.getUUID();
            Vec3 pos = player.position();
            Vec3 last = lastPositions.put(id, pos);
            if (last == null) continue;

            Vec3 raw = pos.subtract(last);
            if (raw.lengthSqr() > 25.0) { // teleport
                velocities.put(id, Vec3.ZERO);
                continue;
            }
            Vec3 vel = velocities.getOrDefault(id, Vec3.ZERO);
            velocities.put(id, vel.add(raw.subtract(vel).scale(k)));
        }
    }

    private void cleanup() {
        attacked.values().removeIf(tick -> tickCounter - tick > 40);
        own.values().removeIf(tick -> tickCounter - tick > 100);
    }

    private void processSetDead() {
        Iterator<SetDeadEntry> it = setDeads.iterator();
        while (it.hasNext()) {
            SetDeadEntry entry = it.next();
            if (tickCounter < entry.tick()) continue;
            mc.level.removeEntity(entry.id(), Entity.RemovalReason.KILLED);
            it.remove();
        }
    }

    // ------------------------------------------------------------------ rendering

    @EventHandler
    private void onRender(Render3DEvent event) {
        long now = System.currentTimeMillis();
        double delta = Math.min(0.1, (now - lastRenderMs) / 1000.0);
        lastRenderMs = now;

        if (!render.get() || mc.player == null) return;

        if (placePos != null) {
            Vec3 target = new Vec3(placePos.getX(), placePos.getY(), placePos.getZ());
            if (renderPos == null || renderPos.distanceToSqr(target) > 36.0) renderPos = target;
            else renderPos = renderPos.add(target.subtract(renderPos).scale(Math.min(1.0, delta * moveSpeed.get())));
            renderProgress = Math.min(1.0, renderProgress + delta * growSpeed.get());
        } else {
            renderProgress = Math.max(0.0, renderProgress - delta * growSpeed.get());
        }

        if (renderPos == null || renderProgress <= 0.001) return;

        double eased = 1.0 - Math.pow(1.0 - renderProgress, 3.0);
        double r = 0.5 * eased;
        double cx = renderPos.x + 0.5;
        double cy = renderPos.y - 0.5; // the obsidian block under the crystal
        double cz = renderPos.z + 0.5;

        SettingColor side = sideColor.get();
        SettingColor line = lineColor.get();
        event.renderer.box(
            cx - r, cy - r, cz - r, cx + r, cy + r, cz + r,
            new Color(side.r, side.g, side.b, (int) Math.round(side.a * eased)),
            new Color(line.r, line.g, line.b, (int) Math.round(line.a * eased)),
            shapeMode.get(), 0
        );
    }
}
