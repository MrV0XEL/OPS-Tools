//Skidded from Germanminer with their permission! Thank you for making this.
//Original: https://github.com/Germanminer/MeteorServerUtils/blob/master/src/main/java/com/example/addon/modules/AutoWither.java
package com.mrvoxel.opstools.modules;

import com.mrvoxel.opstools.OpsTools;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AdvancedAutoWither extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgVisuals = this.settings.createGroup("Visuals");
    private final SettingGroup sgColors = this.settings.createGroup("Colors");

    private final Setting<Boolean> repeat = sgGeneral.add(new BoolSetting.Builder()
        .name("repeat")
        .description("Keep building withers one after another in front of the last one until you run out of materials (right-click again to stop).")
        .defaultValue(false)
        .build()
    );
    private final Setting<Integer> witherCount = sgGeneral.add(new IntSetting.Builder()
        .name("wither-count")
        .description("Number of withers to build per activation.")
        .defaultValue(1)
        .min(1)
        .sliderMax(10)
        .visible(() -> !repeat.get())
        .build()
    );
    private final Setting<Integer> gap = sgGeneral.add(new IntSetting.Builder()
        .name("gap")
        .description("Empty blocks between each wither (0 = directly behind each other).")
        .defaultValue(0)
        .min(0)
        .sliderMax(5)
        .build()
    );
    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Maximum blocks placed per tick (scroll over the slider to adjust). Servers may reject fast placement; rejected blocks are retried and the speed is lowered automatically.")
        .defaultValue(7)
        .min(1)
        .max(20)
        .sliderMax(20)
        .build()
    );
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks a placed block must stay in the world before it counts as confirmed. Failed blocks are retried after this long. Raise it if blocks still ghost.")
        .defaultValue(3)
        .min(0)
        .sliderMax(10)
        .build()
    );
    private final Setting<Integer> maxRetries = sgGeneral.add(new IntSetting.Builder()
        .name("max-retries")
        .description("How many times to re-place a block the server removed before giving up.")
        .defaultValue(3)
        .min(0)
        .sliderMax(10)
        .build()
    );
    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("Max distance to place blocks. If the next block is further, the module waits for you to move closer.")
        .defaultValue(4.5)
        .min(1)
        .sliderMax(6)
        .build()
    );
    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Face each block before placing it (needed on most servers).")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> autoNametag = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-nametag")
        .description("Automatically nametag successfully spawned withers (uses a name tag from your hotbar).")
        .defaultValue(false)
        .build()
    );
    private final Setting<String> witherName = sgGeneral.add(new StringSetting.Builder()
        .name("wither-name")
        .description("The custom name to give to spawned withers.")
        .defaultValue("Wither")
        .visible(() -> autoNametag.get())
        .build()
    );
    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("Air Place")
        .description("Allow air placing.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> placeCenterSkull = sgGeneral.add(new BoolSetting.Builder()
        .name("Center Skull")
        .description("Place the skull in the center of the wither.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> lockRotation = sgGeneral.add(new BoolSetting.Builder()
        .name("Lock Rotation")
        .description("Locks the rotation of the wither.")
        .defaultValue(false)
        .build()
    );
    private final Setting<CardinalDirection> chosenDirection = sgGeneral.add(new EnumSetting.Builder<CardinalDirection>()
        .name("Direction")
        .description("The direction withers are built toward (North means the arms go west-east).")
        .defaultValue(CardinalDirection.North)
        .visible(() -> lockRotation.get())
        .build()
    );
    private final Setting<Boolean> renderPreview = sgVisuals.add(new BoolSetting.Builder()
        .name("Preview")
        .description("If the blocks should be rendered")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> swingHand = sgVisuals.add(new BoolSetting.Builder()
        .name("Swing")
        .description("Swing hand.")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> previewColor = sgColors.add(new ColorSetting.Builder()
        .name("preview-color")
        .description("fill color for the preview.")
        .defaultValue(new SettingColor(51, 207, 255, 50, false))
        .build()
    );
    private final Setting<SettingColor> previewOutlineColor = sgColors.add(new ColorSetting.Builder()
        .name("preview-outline-color")
        .description("outline color for the preview.")
        .defaultValue(new SettingColor(112, 136, 255, 255, false))
        .build()
    );

    public AdvancedAutoWither() {
        super(OpsTools.CATEGORY, "advanced-auto-wither", "Builds withers one after another in front of you (with retry, anti-ghost-block checks and auto-nametag).");
    }

    /**
     * One block to place. For soul blocks pos is the target; for skulls pos is the soul block the skull sits on.
     * attempts/lastAttempt let the module tell "just placed, waiting for the server" from "confirmed" and "rejected".
     */
    private static final class Step {
        final BlockPos pos;
        final boolean skull;
        int attempts = 0;
        int lastAttempt = Integer.MIN_VALUE;
        boolean failureCounted = false;

        Step(BlockPos pos, boolean skull) {
            this.pos = pos;
            this.skull = skull;
        }

        BlockPos placedPos() {
            return skull ? pos.above() : pos;
        }
    }

    private enum StepState { CONFIRMED, IN_FLIGHT, MISSING }

    private static final int SPAWN_GRACE_TICKS = 10;
    private static final int NO_SPAWN_TIMEOUT_TICKS = 40;
    private static final int NAMETAG_RETRY_TICKS = 40;
    private static final int NAMETAG_MAX_ATTEMPTS = 2;
    private static final int NAMETAG_MIN_AGE_TICKS = 3;

    private BlockPos previewPos;
    private boolean isBuilding = false;
    private boolean hasMaterials = false;

    private BlockPos startPos;
    private Direction buildDir = Direction.NORTH;
    private BlockPos currentBase;
    private final List<Step> soulSteps = new ArrayList<>();
    private final List<Step> skullSteps = new ArrayList<>();
    private final Set<Integer> witherIdsAtStart = new HashSet<>();
    private int waitTicks = 0;
    private boolean warnedReach = false;
    private int effectiveBpt = 7;
    private int tickCounter = 0;

    private int buildIndex = 0;
    private int totalBuilds = 1;
    private int completedBuilds = 0;
    private int originalSlot = -1;

    private final List<BlockPos> activeSpawnPositions = new ArrayList<>();
    private final Set<Integer> processedWitherIds = new HashSet<>();
    private final Map<Integer, Integer> nametagAttempts = new HashMap<>();
    private final Map<Integer, Integer> nametagLastTry = new HashMap<>();
    private boolean warnedNoTag = false;

    @Override
    public void onActivate() {
        info("Press Usekey (RightClick) to build wither(s)");
        if (mc.player != null) updateMaterialStatus();
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    private void resetBuildState() {
        isBuilding = false;
        soulSteps.clear();
        skullSteps.clear();
        witherIdsAtStart.clear();
        waitTicks = 0;
        warnedReach = false;
        buildIndex = 0;
        completedBuilds = 0;
        originalSlot = -1;
        effectiveBpt = blocksPerTick.get();
    }

    private void resetState() {
        resetBuildState();
        activeSpawnPositions.clear();
        processedWitherIds.clear();
        nametagAttempts.clear();
        nametagLastTry.clear();
        warnedNoTag = false;
    }

    // ---------------------------------------------------------------- materials

    private int[] countHotbar() {
        int soul = 0;
        int skull = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item == Items.SOUL_SAND || item == Items.SOUL_SOIL) {
                soul += stack.getCount();
            } else if (item == Items.WITHER_SKELETON_SKULL) {
                skull += stack.getCount();
            }
        }
        return new int[]{soul, skull};
    }

    private boolean hasEnoughMaterials() {
        if (mc.player.hasInfiniteMaterials()) return true;
        int[] c = countHotbar();
        return c[0] >= 4 && c[1] >= (placeCenterSkull.get() ? 3 : 2);
    }

    private void updateMaterialStatus() {
        if (mc.player == null) return;
        hasMaterials = hasEnoughMaterials();
    }

    // ---------------------------------------------------------------- input

    @EventHandler
    private void onMouseButton(MouseClickEvent event) {
        if (mc.screen != null) return;
        if (event.button() != 1) return;
        if (event.action != KeyAction.Press) return;

        if (isBuilding) {
            event.cancel();
            finishSequence("Build stopped after " + completedBuilds + " wither(s).");
            return;
        }

        updateMaterialStatus();
        if (!hasMaterials) {
            info("You don't have the required materials (4 soul blocks, 3 skulls) in your hotbar.");
            return;
        }
        // FIX: previously the click was swallowed even when there was nowhere to build, which silently
        // blocked normal right-click use (opening chests, placing blocks) while the module was on.
        if (previewPos == null) return;
        event.cancel();
        startBuildSequence(previewPos);
    }

    // ---------------------------------------------------------------- build control

    private void startBuildSequence(BlockPos basePos) {
        updateMaterialStatus();
        if (!hasMaterials) {
            info("Stopped building: Insufficient materials in hotbar.");
            return;
        }

        resetBuildState();
        buildDir = getActiveDirection();
        startPos = basePos;
        totalBuilds = repeat.get() ? Integer.MAX_VALUE : Math.max(1, witherCount.get());
        originalSlot = mc.player.getInventory().getSelectedSlot();
        isBuilding = true;

        startNextWither();
    }

    /** Sets up the next wither in the row. Returns false (and finishes) if building can't continue. */
    private boolean startNextWither() {
        if (buildIndex >= totalBuilds) {
            finishSequence("Successfully completed " + completedBuilds + " wither build(s).");
            return false;
        }
        if (!hasEnoughMaterials()) {
            finishSequence("Stopped building: Ran out of materials after " + completedBuilds + " wither(s).");
            return false;
        }

        BlockPos base = startPos.relative(buildDir, buildIndex * (gap.get() + 1));
        if (!checkWitherBlocks(base)) {
            finishSequence("Stopped building: Obstruction or entity at the next position after " + completedBuilds + " wither(s).");
            return false;
        }

        currentBase = base;
        activeSpawnPositions.add(base);
        buildSteps(base);
        warnedReach = false;

        // Remember withers that already exist so only a NEW one counts as "ours spawned".
        witherIdsAtStart.clear();
        for (WitherBoss w : mc.level.getEntitiesOfClass(WitherBoss.class, new AABB(base).inflate(4.0))) {
            witherIdsAtStart.add(w.getId());
        }
        return true;
    }

    private void buildSteps(BlockPos base) {
        soulSteps.clear();
        skullSteps.clear();
        Direction[] arms = armDirs(buildDir);
        BlockPos mid = base.above();

        // Bottom-up so every soul block has something to be placed against.
        soulSteps.add(new Step(base, false));
        soulSteps.add(new Step(mid, false));
        soulSteps.add(new Step(mid.relative(arms[0]), false));
        soulSteps.add(new Step(mid.relative(arms[1]), false));
        // Skulls only ever start once all four soul blocks are confirmed; the final skull spawns the wither.
        skullSteps.add(new Step(mid.relative(arms[0]), true));
        skullSteps.add(new Step(mid.relative(arms[1]), true));
        if (placeCenterSkull.get()) {
            skullSteps.add(new Step(mid, true));
        }
    }

    private void finishSequence(String message) {
        if (originalSlot != -1 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(originalSlot);
        }
        info(message);
        isBuilding = false;
        soulSteps.clear();
        skullSteps.clear();
        waitTicks = 0;
        originalSlot = -1;
    }

    private Direction getActiveDirection() {
        if (lockRotation.get()) {
            return chosenDirection.get().toMcDirection();
        } else {
            assert mc.player != null;
            return mc.player.getDirection();
        }
    }

    private static Direction[] armDirs(Direction facing) {
        if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            return new Direction[]{Direction.WEST, Direction.EAST};
        }
        return new Direction[]{Direction.NORTH, Direction.SOUTH};
    }

    // ---------------------------------------------------------------- tick

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        tickCounter++;
        updateMaterialStatus();

        if (autoNametag.get() && !activeSpawnPositions.isEmpty()) {
            handleAutoNametagging();
        }

        if (!isBuilding) return;

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        buildTick();
    }

    private int confirmTicks() {
        return Math.max(1, delay.get());
    }

    private void buildTick() {
        int confirm = confirmTicks();

        // 1. Did the wither spawn? (A new WitherBoss appears and the structure is consumed.)
        if (allAttempted()) {
            int sinceLast = tickCounter - lastAttemptTick();
            if (newWitherSpawned()) {
                completeWither();
                return;
            }
            if (structureConsumed() && sinceLast <= confirm + SPAWN_GRACE_TICKS) {
                return; // blocks are gone, waiting for the wither entity to arrive - don't re-place anything
            }
            if (sinceLast > confirm + NO_SPAWN_TIMEOUT_TICKS && allConfirmed(soulSteps) && allConfirmed(skullSteps)) {
                finishSequence("Stopped building: the structure is complete but no wither spawned (Peaceful difficulty, or the server blocked it).");
                return;
            }
        }

        // 2. Soul sand strictly first: skulls are only considered once all soul blocks are confirmed in the world.
        List<Step> phase = allConfirmed(soulSteps) ? skullSteps : soulSteps;

        int budget = Math.max(1, Math.min(blocksPerTick.get(), effectiveBpt));
        int placed = 0;

        for (Step step : phase) {
            StepState state = stateOf(step);
            if (state != StepState.MISSING) continue;

            // A previously attempted block that is missing again was rejected by the server.
            if (step.attempts > 0 && !step.failureCounted) {
                step.failureCounted = true;
                onPlacementFailed();
            }
            if (step.attempts > maxRetries.get()) {
                finishSequence("Stopped building: the server keeps rejecting the block at " + step.placedPos().toShortString()
                    + " after " + step.attempts + " attempts (protection/anticheat?).");
                return;
            }
            if (placed >= budget) break;
            if (!placeStep(step)) break; // out of reach / missing items / aborted
            placed++;
        }

        if (placed > 0 && swingHand.get()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private void completeWither() {
        completedBuilds++;
        buildIndex++;
        // Server handled this wither fine: creep the speed back up toward the configured value.
        effectiveBpt = Math.min(blocksPerTick.get(), effectiveBpt + 1);
        if (startNextWither()) {
            waitTicks = delay.get();
        }
    }

    private void onPlacementFailed() {
        int lowered = Math.max(1, effectiveBpt / 2);
        if (lowered < effectiveBpt) {
            effectiveBpt = lowered;
            info("Server rejected a placement - slowing down to " + effectiveBpt + " block(s) per tick.");
        }
    }

    // ---------------------------------------------------------------- step state (world-confirmed)

    private boolean isPresent(Step step) {
        BlockState state = mc.level.getBlockState(step.placedPos());
        if (step.skull) return state.is(Blocks.WITHER_SKELETON_SKULL);
        return state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL);
    }

    /**
     * CONFIRMED = block is in the world and has survived the confirm window (the server had time to revert it).
     * IN_FLIGHT = placed very recently, outcome not known yet. MISSING = never placed, or rejected/removed.
     */
    private StepState stateOf(Step step) {
        boolean present = isPresent(step);
        if (step.attempts == 0) return present ? StepState.CONFIRMED : StepState.MISSING;
        if (tickCounter - step.lastAttempt <= confirmTicks()) return StepState.IN_FLIGHT;
        return present ? StepState.CONFIRMED : StepState.MISSING;
    }

    private boolean allConfirmed(List<Step> list) {
        for (Step s : list) {
            if (stateOf(s) != StepState.CONFIRMED) return false;
        }
        return !list.isEmpty();
    }

    private boolean allAttempted() {
        if (soulSteps.isEmpty() || skullSteps.isEmpty()) return false;
        for (Step s : soulSteps) if (s.attempts == 0) return false;
        for (Step s : skullSteps) if (s.attempts == 0) return false;
        return true;
    }

    private int lastAttemptTick() {
        int last = Integer.MIN_VALUE;
        for (Step s : soulSteps) last = Math.max(last, s.lastAttempt);
        for (Step s : skullSteps) last = Math.max(last, s.lastAttempt);
        return last;
    }

    private boolean structureConsumed() {
        return mc.level.getBlockState(currentBase).isAir() && mc.level.getBlockState(currentBase.above()).isAir();
    }

    private boolean newWitherSpawned() {
        for (WitherBoss w : mc.level.getEntitiesOfClass(WitherBoss.class, new AABB(currentBase).inflate(2.0))) {
            if (!witherIdsAtStart.contains(w.getId())) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- placing

    /** Places one block. Returns false if it couldn't (waiting for reach, or the sequence was aborted). */
    private boolean placeStep(Step step) {
        BlockPos placedPos = step.placedPos();

        Vec3 eye = mc.player.getEyePosition();
        if (eye.distanceTo(Vec3.atCenterOf(placedPos)) > reach.get()) {
            if (!warnedReach) {
                info("Next block is out of reach - move closer to continue (right-click to stop).");
                warnedReach = true;
            }
            return false;
        }
        warnedReach = false;

        Item item = step.skull ? Items.WITHER_SKELETON_SKULL : soulItem();
        int slot = findHotbarSlot(item);
        if (slot == -1) {
            finishSequence("Stopped building: missing " + (step.skull ? "wither skeleton skulls" : "soul sand/soil") + " in your hotbar.");
            return false;
        }

        BlockHitResult hit = step.skull ? topFaceHit(step.pos) : supportHit(step.pos);
        if (hit == null) {
            finishSequence("Stopped building: no block to place against at " + step.pos.toShortString() + " (enable Air Place).");
            return false;
        }

        mc.player.getInventory().setSelectedSlot(slot);
        if (rotate.get()) faceVec(hit.getLocation());
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);

        step.attempts++;
        step.lastAttempt = tickCounter;
        step.failureCounted = false;
        return true;
    }

    // ---------------------------------------------------------------- placing helpers

    private Item soulItem() {
        return findHotbarSlot(Items.SOUL_SAND) != -1 ? Items.SOUL_SAND : Items.SOUL_SOIL;
    }

    private int findHotbarSlot(Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == item) return i;
        }
        return -1;
    }

    /** Click an existing neighbouring block's face so the server sees a normal placement. */
    private BlockHitResult supportHit(BlockPos pos) {
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction dir : order) {
            BlockPos neighbour = pos.relative(dir);
            BlockState state = mc.level.getBlockState(neighbour);
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                Direction face = dir.getOpposite();
                Vec3 loc = Vec3.atCenterOf(neighbour).relative(face, 0.5);
                return new BlockHitResult(loc, face, neighbour, false);
            }
        }
        if (airPlace.get()) {
            return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        }
        return null;
    }

    /** Skulls are placed by clicking the top face of the soul block. */
    private BlockHitResult topFaceHit(BlockPos soulPos) {
        Vec3 loc = Vec3.atCenterOf(soulPos).add(0, 0.5, 0);
        return new BlockHitResult(loc, Direction.UP, soulPos, false);
    }

    private void faceVec(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, mc.player.onGround(), mc.player.horizontalCollision));
    }

    // ---------------------------------------------------------------- obstruction checks

    private boolean checkWitherBlocks(BlockPos base) {
        Direction[] arms = armDirs(buildDir);
        BlockPos mid = base.above();

        List<BlockPos> cells = new ArrayList<>();
        cells.add(base);
        cells.add(mid);
        cells.add(mid.relative(arms[0]));
        cells.add(mid.relative(arms[1]));
        cells.add(mid.above().relative(arms[0]));
        cells.add(mid.above().relative(arms[1]));
        if (placeCenterSkull.get()) {
            cells.add(mid.above());
        }

        // The two blocks beside the base must be empty too, or the wither pattern won't match.
        List<BlockPos> mustBeAir = new ArrayList<>(cells);
        mustBeAir.add(base.relative(arms[0]));
        mustBeAir.add(base.relative(arms[1]));

        for (BlockPos pos : mustBeAir) {
            if (!mc.level.getBlockState(pos).isAir()) return false;
        }
        for (BlockPos pos : cells) {
            if (!checkBlockForEntity(pos)) return false;
        }
        return true;
    }

    private boolean checkBlockForEntity(BlockPos pos) {
        List<Entity> entities = mc.level.getEntitiesOfClass(
            Entity.class,
            new AABB(pos),
            e -> !(e instanceof ItemEntity || e instanceof ExperienceOrb)
        );
        return entities.isEmpty();
    }

    // ---------------------------------------------------------------- nametagging

    /** Hotbar name tag that actually carries a name. Prefers the one matching the configured wither-name. */
    private int findNameTagSlot() {
        String wanted = witherName.get();
        int fallback = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() != Items.NAME_TAG) continue;
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            if (name == null) continue; // an unnamed tag would do nothing
            if (wanted != null && !wanted.isEmpty() && name.getString().equals(wanted)) return i;
            if (fallback == -1) fallback = i;
        }
        return fallback;
    }

    private boolean belongsToUs(WitherBoss wither) {
        for (BlockPos pos : activeSpawnPositions) {
            if (new AABB(pos).inflate(2.0).intersects(wither.getBoundingBox())) return true;
        }
        return false;
    }

    private void handleAutoNametagging() {
        List<WitherBoss> nearbyWithers = mc.level.getEntitiesOfClass(
            WitherBoss.class,
            new AABB(mc.player.getX() - 15, mc.player.getY() - 15, mc.player.getZ() - 15,
                mc.player.getX() + 15, mc.player.getY() + 15, mc.player.getZ() + 15)
        );

        for (WitherBoss wither : nearbyWithers) {
            int id = wither.getId();
            if (processedWitherIds.contains(id)) continue;
            if (wither.isRemoved() || !wither.isAlive()) continue; // entity not usable right now

            // Already named (by us earlier, or by anyone else): never use a name tag on it.
            if (wither.hasCustomName()) {
                if (nametagAttempts.containsKey(id)) info("Successfully nametagged spawned Wither!");
                processedWitherIds.add(id);
                continue;
            }

            if (!belongsToUs(wither)) continue;
            if (wither.tickCount < NAMETAG_MIN_AGE_TICKS) continue; // let its entity data sync first

            int attempts = nametagAttempts.getOrDefault(id, 0);
            if (attempts >= NAMETAG_MAX_ATTEMPTS) {
                processedWitherIds.add(id);
                info("Gave up nametagging a Wither after " + attempts + " attempts.");
                continue;
            }
            Integer lastTry = nametagLastTry.get(id);
            if (lastTry != null && tickCounter - lastTry < NAMETAG_RETRY_TICKS) continue; // waiting for the server to apply it

            int slot = findNameTagSlot();
            if (slot == -1) {
                if (!warnedNoTag) {
                    info("Auto-nametag: no renamed name tag in your hotbar.");
                    warnedNoTag = true;
                }
                return;
            }

            // Last-moment guard right before the item is used.
            if (wither.hasCustomName() || wither.getCustomName() != null) {
                processedWitherIds.add(id);
                continue;
            }

            useNameTag(wither, slot);
            nametagAttempts.merge(id, 1, Integer::sum);
            nametagLastTry.put(id, tickCounter);
            break; // at most one interaction per tick
        }
    }

    private void useNameTag(WitherBoss wither, int slot) {
        int prevSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(slot);

        Vec3 center = wither.position().add(0, wither.getBbHeight() / 2.0, 0);
        EntityHitResult hitResult = new EntityHitResult(wither, center);

        // Same two packets vanilla sends: INTERACT_AT, then INTERACT (the one that actually applies the tag).
        mc.gameMode.interactAt(mc.player, wither, hitResult, InteractionHand.MAIN_HAND);
        mc.gameMode.interact(mc.player, wither, InteractionHand.MAIN_HAND);
        if (swingHand.get()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        mc.player.getInventory().setSelectedSlot(prevSlot);
    }

    // ---------------------------------------------------------------- preview

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        if (!renderPreview.get() || !hasMaterials || isBuilding) return;
        if (previewPos != null) {
            Direction direction = getActiveDirection();
            int count = repeat.get() ? 3 : Math.max(1, witherCount.get());

            for (int i = 0; i < count; i++) {
                BlockPos pPos = previewPos.relative(direction, i * (gap.get() + 1));
                renderSingleWitherPreview(event, pPos, direction);
            }
        }
    }

    private void renderSingleWitherPreview(Render3DEvent event, BlockPos basePos, Direction direction) {
        Direction[] arms = armDirs(direction);
        BlockPos mid = basePos.above();

        event.renderer.box(basePos, previewColor.get(), previewOutlineColor.get(), ShapeMode.Both, 0);
        event.renderer.box(mid, previewColor.get(), previewOutlineColor.get(), ShapeMode.Both, 0);
        for (Direction arm : arms) {
            event.renderer.box(mid.relative(arm), previewColor.get(), previewOutlineColor.get(), ShapeMode.Both, 0);
            renderSkull(event, mid.above().relative(arm));
        }
        if (placeCenterSkull.get()) {
            renderSkull(event, mid.above());
        }
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        // FIX: this used to return early when 'Preview' was off, so previewPos stayed null and the module
        // could never start a build. The position is needed for building; only the rendering is optional.
        if (mc.level == null || isBuilding) return;
        if (mc.hitResult instanceof BlockHitResult hit) {
            BlockPos pos = hit.getBlockPos();
            BlockState state = mc.level.getBlockState(pos);
            if (airPlace.get()) {
                if (state.isRedstoneConductor(mc.level, pos)) {
                    previewPos = pos.above();
                } else {
                    previewPos = pos;
                }
            } else {
                if (state.isRedstoneConductor(mc.level, pos)) {
                    previewPos = pos.above();
                } else {
                    previewPos = null;
                }
            }
        } else {
            previewPos = null;
        }
    }

    private void renderSkull(Render3DEvent event, BlockPos basePos) {
        double shrink = 0.23;
        event.renderer.box(
            basePos.getX() + shrink,
            basePos.getY(),
            basePos.getZ() + shrink,
            basePos.getX() + 1 - shrink,
            basePos.getY() + 0.5,
            basePos.getZ() + 1 - shrink,
            previewColor.get(),
            previewOutlineColor.get(),
            ShapeMode.Both,
            0
        );
    }

    private enum CardinalDirection {
        North(Direction.NORTH),
        South(Direction.SOUTH),
        East(Direction.EAST),
        West(Direction.WEST);

        private final Direction inflDir;

        CardinalDirection(Direction mcDirection) {
            this.inflDir = mcDirection;
        }

        public Direction toMcDirection() {
            return inflDir;
        }
    }
}
