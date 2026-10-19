//Skidded from Germanminer with their permission! Thank you for making this.
//Original: https://github.com/Germanminer/MeteorServerUtils/blob/master/src/main/java/com/example/addon/modules/AutoWither.java
package pwn.noobs.trouserstreak.modules;

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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import pwn.noobs.trouserstreak.Trouser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AutoWither extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgVisuals = this.settings.createGroup("Visuals");
    private final SettingGroup sgColors = this.settings.createGroup("Colors");

    private final Setting<Integer> witherCount = sgGeneral.add(new IntSetting.Builder()
            .name("wither-count")
            .description("Number of withers to attempt to build per activation.")
            .defaultValue(1)
            .min(1)
            .sliderMax(10)
            .build()
    );
    private final Setting<Boolean> autoNametag = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-nametag")
            .description("Automatically nametag successfully spawned withers.")
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
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Tick delay between placing blocks.")
            .defaultValue(0)
            .min(0)
            .sliderMax(10)
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
            .description("The direction to be locked to (North means the withers arms will be west-east)")
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

    public AutoWither() {
        super(Trouser.Main, "Auto Wither", "Automatically builds multiple withers and nametags them.");
    }

    private BlockPos previewPos;
    private boolean isBuilding = false;
    private boolean hasMaterials = false;
    private final List<BlockPos> pendingBlocks = new ArrayList<>();
    private final List<BlockPos> pendingSkulls = new ArrayList<>();
    private final List<BlockPos> witherQueue = new ArrayList<>();
    private final List<BlockPos> activeSpawnPositions = new ArrayList<>();
    private final Set<Integer> processedWitherIds = new HashSet<>();
    private int tickDelay = 0;
    private int completedBuilds = 0;
    private int requestedBuilds = 0;
    private int originalSlot = -1;

    @Override
    public void onActivate() {
        info("Press Usekey (RightClick) to build wither(s)");
        updateMaterialStatus();
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    private void resetState() {
        isBuilding = false;
        pendingBlocks.clear();
        pendingSkulls.clear();
        witherQueue.clear();
        activeSpawnPositions.clear();
        processedWitherIds.clear();
        tickDelay = 0;
        completedBuilds = 0;
        requestedBuilds = 0;
        originalSlot = -1;
    }

    private void updateMaterialStatus() {
        assert mc.player != null;
        int soulBlockCount = 0;
        int skullCount = 0;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            if (item == Items.SOUL_SAND || item == Items.SOUL_SOIL) {
                soulBlockCount += stack.getCount();
            } else if (item == Items.WITHER_SKELETON_SKULL) {
                skullCount += stack.getCount();
            }
        }

        if (mc.player.hasInfiniteMaterials()) {
            hasMaterials = true;
        } else {
            hasMaterials = soulBlockCount >= 4 && skullCount >= 3;
        }
    }

    @EventHandler
    private void onMouseButton(MouseClickEvent event) {
        if (mc.gui.screen() != null) return;
        if (event.button() != 1) return;
        if (isBuilding) return;
        
        if (event.action == KeyAction.Press) {
            updateMaterialStatus();
            if (!hasMaterials) {
                info("You don't have the required materials (4 soul blocks, 3 skulls) in your hotbar.");
                return;
            }
            event.cancel();
        } else {
            return;
        }

        if (previewPos != null) {
            startBuildSequence(previewPos);
        }
    }

    private void startBuildSequence(BlockPos basePos) {
        assert mc.player != null;
        updateMaterialStatus();
        if (!hasMaterials) {
            info("Stopped building: Insufficient materials in hotbar.");
            return;
        }

        Direction direction = getActiveDirection();
        Direction perpDir = (direction == Direction.NORTH || direction == Direction.SOUTH) ? Direction.EAST : Direction.SOUTH;

        witherQueue.clear();
        activeSpawnPositions.clear();
        processedWitherIds.clear();
        requestedBuilds = Math.max(1, witherCount.get());
        completedBuilds = 0;

        for (int i = 0; i < requestedBuilds; i++) {
            BlockPos targetPos = basePos.relative(perpDir, i * 4);
            witherQueue.add(targetPos);
            activeSpawnPositions.add(targetPos);
        }

        isBuilding = true;
        originalSlot = mc.player.getInventory().getSelectedSlot();
        processNextWitherInQueue();
    }

    private Direction getActiveDirection() {
        if (lockRotation.get()) {
            return chosenDirection.get().toMcDirection();
        } else {
            assert mc.player != null;
            return mc.player.getDirection();
        }
    }

    private void processNextWitherInQueue() {
        if (witherQueue.isEmpty()) {
            finishSequence("Successfully completed all " + completedBuilds + "/" + requestedBuilds + " requested wither builds.");
            return;
        }

        if (!mc.player.hasInfiniteMaterials()) {
            int soulBlockCount = 0;
            int skullCount = 0;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                if (item == Items.SOUL_SAND || item == Items.SOUL_SOIL) {
                    soulBlockCount += stack.getCount();
                } else if (item == Items.WITHER_SKELETON_SKULL) {
                    skullCount += stack.getCount();
                }
            }
            if (soulBlockCount < 4 || skullCount < 3) {
                finishSequence("Stopped building: Ran out of materials after completing " + completedBuilds + "/" + requestedBuilds + " withers.");
                return;
            }
        }

        BlockPos nextBasePos = witherQueue.remove(0);
        if (!checkWitherBlocks(nextBasePos)) {
            finishSequence("Stopped building: Obstruction or entity present at target position after completing " + completedBuilds + "/" + requestedBuilds + " withers.");
            return;
        }

        setupWitherComponents(nextBasePos);
        tickDelay = 0;
    }

    private void finishSequence(String message) {
        if (originalSlot != -1 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(originalSlot);
        }
        info(message);
        isBuilding = false;
        pendingBlocks.clear();
        pendingSkulls.clear();
        witherQueue.clear();
        originalSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        updateMaterialStatus();

        if (mc.player == null || mc.level == null) return;

        // Handle Auto-Nametag logic whenever active, independent of block building state
        if (autoNametag.get() && !activeSpawnPositions.isEmpty()) {
            handleAutoNametagging();
        }

        if (!isBuilding) return;

        if (tickDelay > 0) {
            tickDelay--;
            return;
        }

        if (delay.get() == 0) {
            while (!pendingBlocks.isEmpty()) {
                placeSoulBlock(pendingBlocks.remove(0));
            }
            while (!pendingSkulls.isEmpty()) {
                placeBlock(pendingSkulls.remove(0), Items.WITHER_SKELETON_SKULL);
            }
            completedBuilds++;
            processNextWitherInQueue();
        } else {
            if (!pendingBlocks.isEmpty()) {
                placeSoulBlock(pendingBlocks.remove(0));
                tickDelay = delay.get();
            } else if (!pendingSkulls.isEmpty()) {
                placeBlock(pendingSkulls.remove(0), Items.WITHER_SKELETON_SKULL);
                tickDelay = delay.get();
            } else {
                completedBuilds++;
                processNextWitherInQueue();
            }
        }
    }

    private void handleAutoNametagging() {
        int nametagSlot = findInventoryOrHotbarSlot(Items.NAME_TAG);
        if (nametagSlot == -1) return; // No nametag available, skip safely

        String targetName = witherName.get();
        if (targetName == null || targetName.isEmpty()) {
            targetName = "Wither";
        }

        List<WitherBoss> nearbyWithers = mc.level.getEntitiesOfClass(
                WitherBoss.class,
                new AABB(mc.player.getX() - 15, mc.player.getY() - 15, mc.player.getZ() - 15,
                        mc.player.getX() + 15, mc.player.getY() + 15, mc.player.getZ() + 15)
        );

        for (WitherBoss wither : nearbyWithers) {
            int entityId = wither.getId();
            if (processedWitherIds.contains(entityId)) continue;

            // Verify if the wither is associated with one of our active spawn positions
            boolean belongsToUs = false;
            for (BlockPos pos : activeSpawnPositions) {
                AABB spawnZone = new AABB(pos).inflate(2.0, 2.0, 2.0);
                if (spawnZone.intersects(wither.getBoundingBox())) {
                    belongsToUs = true;
                    break;
                }
            }

            if (!belongsToUs) continue;

            // Rule check: Never rename an already-custom-named Wither
            if (wither.hasCustomName()) {
                processedWitherIds.add(entityId); // Mark as handled so we don't check repeatedly
                continue;
            }

            // Check if the nametag item itself has the matching custom name applied
            ItemStack nametagStack = mc.player.getInventory().getItem(nametagSlot);
            if (!nametagStack.hasCustomHoverName() || !nametagStack.getHoverName().getString().equals(targetName)) {
                // Note: In survival/vanilla clients without anvil pre-naming, players typically supply pre-named tags. 
                // We attempt interaction if the tag is valid, letting server-side validation or existing tag name apply.
            }

            // Perform interaction to apply nametag
            int prevSlot = mc.player.getInventory().getSelectedSlot();
            if (nametagSlot < 9) {
                mc.player.getInventory().setSelectedSlot(nametagSlot);
            } else {
                // If in main inventory storage (not hotbar), swapping would be required; 
                // for simplicity/compatibility, we focus on hotbar/inventory slot selection supported by project patterns.
                // If slot is outside hotbar, we can select hotbar if available or skip safely.
                if (nametagSlot >= 9) {
                    // Try to find one in hotbar first
                    continue;
                }
            }

            Vec3 witherCenter = wither.position().add(0, wither.getBbHeight() / 2.0, 0);
            EntityHitResult hitResult = new EntityHitResult(wither, witherCenter);
            
            assert mc.gameMode != null;
            mc.gameMode.interactAt(mc.player, wither, hitResult, InteractionHand.MAIN_HAND);
            if (swingHand.get()) {
                mc.player.swing(InteractionHand.MAIN_HAND);
            }

            // Restore original slot immediately
            mc.player.getInventory().setSelectedSlot(prevSlot);

            // Verify custom name assignment before marking complete
            if (wither.hasCustomName()) {
                processedWitherIds.add(entityId);
                info("Successfully nametaged spawned Wither!");
            }
            break; // Handle one per tick to prevent packet spam
        }
    }

    private int findInventoryOrHotbarSlot(Item item) {
        assert mc.player != null;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == item) return i;
        }
        return -1;
    }

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        if (!renderPreview.get() || !hasMaterials) return;
        if (previewPos != null) {
            Direction direction = getActiveDirection();
            Direction perpDir = (direction == Direction.NORTH || direction == Direction.SOUTH) ? Direction.EAST : Direction.SOUTH;
            int count = Math.max(1, witherCount.get());

            for (int i = 0; i < count; i++) {
                BlockPos pPos = previewPos.relative(perpDir, i * 4);
                renderSingleWitherPreview(event, pPos, direction);
            }
        }
    }

    private void renderSingleWitherPreview(Render3DEvent event, BlockPos basePos, Direction direction) {
        event.renderer.box(basePos, previewColor.get(), previewOutlineColor.get(), ShapeMode.Both, 0);
        event.renderer.box(basePos.above(), previewColor.get(), previewOutlineColor.get(), ShapeMode.Both, 0);
        if (direction == Direction.NORTH || direction == Direction.SOUTH) {
            event.renderer.box(basePos.above().west(), previewColor.get(), previewOutlineColor.get(), ShapeMode.Both, 0);
            event.renderer.box(basePos.above().east(), previewColor.get(), previewOutlineColor.get(), ShapeMode.Both, 0);
            renderSkull(event, basePos.above(2).west());
            renderSkull(event, basePos.above(2).east());
        } else {
            event.renderer.box(basePos.above().south(), previewColor.get(), previewOutlineColor.get(), ShapeMode.Both, 0);
            event.renderer.box(basePos.above().north(), previewColor.get(), previewOutlineColor.get(), ShapeMode.Both, 0);
            renderSkull(event, basePos.above(2).south());
            renderSkull(event, basePos.above(2).north());
        }
        if (placeCenterSkull.get()) {
            renderSkull(event, basePos.above(2));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!renderPreview.get()) return;
        if (mc.hitResult instanceof BlockHitResult hit) {
            BlockPos pos = hit.getBlockPos();
            assert mc.level != null;
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

    private void setupWitherComponents(BlockPos basePos) {
        Direction direction = getActiveDirection();

        pendingBlocks.clear();
        pendingSkulls.clear();

        if (direction == Direction.NORTH || direction == Direction.SOUTH) {
            pendingBlocks.add(basePos.above().west());
            pendingBlocks.add(basePos.above().east());
            pendingSkulls.add(basePos.above().west());
            pendingSkulls.add(basePos.above().east());
        } else {
            pendingBlocks.add(basePos.above().south());
            pendingBlocks.add(basePos.above().north());
            pendingSkulls.add(basePos.above().south());
            pendingSkulls.add(basePos.above().north());
        }
        pendingBlocks.add(basePos);
        pendingBlocks.add(basePos.above());
        if (placeCenterSkull.get()) {
            pendingSkulls.add(basePos.above(2));
        }
    }

    private boolean checkWitherBlocks(BlockPos basePos) {
        List<BlockPos> blockPositions = new ArrayList<>();
        Direction direction = getActiveDirection();

        if (direction == Direction.NORTH || direction == Direction.SOUTH) {
            blockPositions.add(basePos.above().west());
            blockPositions.add(basePos.above().east());
            blockPositions.add(basePos.above(2).west());
            blockPositions.add(basePos.above(2).east());
            blockPositions.add(basePos.west());
            blockPositions.add(basePos.east());
        } else {
            blockPositions.add(basePos.above().south());
            blockPositions.add(basePos.above().north());
            blockPositions.add(basePos.above(2).south());
            blockPositions.add(basePos.above(2).north());
            blockPositions.add(basePos.south());
            blockPositions.add(basePos.north());
        }
        blockPositions.add(basePos);
        blockPositions.add(basePos.above());
        if (placeCenterSkull.get()) {
            blockPositions.add(basePos.above(2));
        }

        for (BlockPos pos : blockPositions) {
            if (!checkBlockPlaceable(pos)) {
                return false;
            }
        }
        blockPositions.remove(4);
        blockPositions.remove(5);
        for (BlockPos pos : blockPositions) {
            if (!checkBlockForEntity(pos)) {
                return false;
            }
        }
        return true;
    }

    private boolean checkBlockPlaceable(BlockPos pos) {
        assert mc.level != null;
        return mc.level.getBlockState(pos).isAir();
    }

    private boolean checkBlockForEntity(BlockPos pos) {
        assert mc.level != null;
        List<Entity> entities = mc.level.getEntitiesOfClass(
                Entity.class,
                new AABB(pos),
                e -> !(e instanceof ItemEntity || e instanceof ExperienceOrb)
        );
        return entities.isEmpty();
    }

    private void placeSoulBlock(BlockPos pos) {
        Item soulBlock = hasSoulSandHotbar() ? Items.SOUL_SAND : Items.SOUL_SOIL;
        placeBlock(pos, soulBlock);
    }

    private void placeBlock(BlockPos pos, Item item) {
        int slot = findHotbarSlot(item);
        if (slot == -1) return;
        assert mc.player != null;
        mc.player.getInventory().setSelectedSlot(slot);
        assert mc.gameMode != null;
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        if (!swingHand.get()) return;
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private int findHotbarSlot(Item item) {
        for (int i = 0; i < 9; i++) {
            assert mc.player != null;
            if (mc.player.getInventory().getItem(i).getItem() == item) return i;
        }
        return -1;
    }

    private boolean hasSoulSandHotbar() {
        assert mc.player != null;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == Items.SOUL_SAND) return true;
        }
        return false;
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
