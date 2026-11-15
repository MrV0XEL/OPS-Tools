package com.mrvoxel.opstools.modules;

import com.mrvoxel.opstools.OpsTools;
import com.mrvoxel.opstools.stash.ContainerInspector;
import com.mrvoxel.opstools.stash.FuzzySearchUtil;
import com.mrvoxel.opstools.stash.StashLabelRenderer;
import com.mrvoxel.opstools.stash.StashManager;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Global Stash Tracker - indexes every container you open and draws labels for them.
 *
 * How it hooks into Meteor / Fabric
 * ---------------------------------
 *  - Registration: OpsTools.onInitialize() calls Modules.get().add(new GlobalStashTrackerModule()). Meteor scans this
 *    class for @EventHandler methods (via the addon's getPackage()) and subscribes them to its event bus while the
 *    module is enabled.
 *  - MouseClickEvent     : remembers which block was under the crosshair when you right-clicked. A container menu
 *                          carries no position, so this is how an opened menu is tied to a block in the world.
 *  - TickEvent.Post      : once per client tick, if a chest-like menu is open, fingerprints its slots (max 54, trivial
 *                          cost). When the fingerprint changes - the server filled the menu in, or items were moved - the
 *                          menu is read via ContainerInspector (loose items + shulker box names/contents) and handed to
 *                          StashManager. File I/O is done by StashManager on its own thread, so the game thread never
 *                          waits on the disk. No packet listener is needed: the menu slots are the source of truth.
 *  - Render3DEvent       : draws the container / stash labels and the search highlight boxes.
 */
public class GlobalStashTrackerModule extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<Integer> assignRadius = sgGeneral.add(new IntSetting.Builder()
        .name("assign-radius")
        .description("A container opened within this many blocks of a registered stash is filed under that stash.")
        .defaultValue(64)
        .min(8)
        .max(512)
        .sliderMax(256)
        .build()
    );
    private final Setting<Boolean> promptUnregistered = sgGeneral.add(new BoolSetting.Builder()
        .name("prompt-unregistered")
        .description("Tell me (once in a while) when I open a container outside every registered stash.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Double> fuzzyThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("fuzzy-threshold")
        .description("Minimum match score (0-1) for .find. Lower = more forgiving, higher = stricter.")
        .defaultValue(FuzzySearchUtil.DEFAULT_THRESHOLD)
        .min(0.3)
        .max(1.0)
        .sliderMax(1.0)
        .build()
    );

    private final Setting<Boolean> renderLabels = sgRender.add(new BoolSetting.Builder()
        .name("render-labels")
        .description("Draw floating labels above tracked containers and stash centers.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> labelRange = sgRender.add(new IntSetting.Builder()
        .name("label-range")
        .description("Container labels are drawn within this distance (stash labels within twice this).")
        .defaultValue(32)
        .min(8)
        .max(128)
        .sliderMax(96)
        .build()
    );
    private final Setting<Integer> maxLabels = sgRender.add(new IntSetting.Builder()
        .name("max-labels")
        .description("Maximum number of labels drawn at once (nearest first).")
        .defaultValue(12)
        .min(1)
        .max(40)
        .sliderMax(40)
        .build()
    );
    private final Setting<Double> labelScale = sgRender.add(new DoubleSetting.Builder()
        .name("label-scale")
        .description("Text size in blocks per font unit. Far labels are enlarged automatically.")
        .defaultValue(0.035)
        .min(0.01)
        .max(0.15)
        .sliderMax(0.1)
        .build()
    );
    private final Setting<SettingColor> containerColor = sgRender.add(new ColorSetting.Builder()
        .name("container-label-color")
        .description("Color of container labels.")
        .defaultValue(new SettingColor(255, 255, 255, 255, false))
        .build()
    );
    private final Setting<SettingColor> stashColor = sgRender.add(new ColorSetting.Builder()
        .name("stash-label-color")
        .description("Color of stash name labels.")
        .defaultValue(new SettingColor(85, 255, 255, 255, false))
        .build()
    );
    private final Setting<SettingColor> highlightFill = sgRender.add(new ColorSetting.Builder()
        .name("search-highlight-fill")
        .description("Fill color for containers matching the last .find.")
        .defaultValue(new SettingColor(255, 200, 0, 50, false))
        .build()
    );
    private final Setting<SettingColor> highlightOutline = sgRender.add(new ColorSetting.Builder()
        .name("search-highlight-outline")
        .description("Outline color for containers matching the last .find.")
        .defaultValue(new SettingColor(255, 200, 0, 255, false))
        .build()
    );

    // ------------------------------------------------------------------ small shared types

    /** A container block the player opened: its canonical position (one position per double chest) and a type name. */
    public record ContainerInfo(BlockPos pos, String type) {}

    private record Label(double x, double y, double z, String text, boolean stash) {}

    // ------------------------------------------------------------------ state

    private static final int CLICK_TTL_TICKS = 40; // a right-click only "claims" the next menu for 2 seconds
    private static final long HIGHLIGHT_MS = 30_000;

    /** A freshly opened menu must have been open this many ticks before its first index (lets the server fill it in). */
    private static final int SETTLE_TICKS = 2;
    /** An all-empty menu is only trusted as "really empty" after this many ticks (guards against slow contents packets). */
    private static final int EMPTY_TRUST_TICKS = 10;

    // Client thread only.
    private int tickCounter = 0;

    // Block under the crosshair at the last right-click (and the latest one seen while no container was open).
    private BlockPos clickedPos = null;
    private int clickedTick = Integer.MIN_VALUE / 2;
    private BlockPos crosshairPos = null;

    // Client-thread only.
    private int openMenuId = -1;
    private int openedTick = 0;
    private ContainerInfo boundContainer = null;
    private int lastSignature = 0;
    private boolean indexedOnce = false;

    private int lastPromptTick = Integer.MIN_VALUE / 2;
    private BlockPos lastPromptPos = null;

    private volatile List<BlockPos> highlighted = List.of();
    private volatile long highlightUntil = 0;

    private int cachedVersion = -1;
    private String cachedDimension = "";
    private final List<Label> containerLabels = new ArrayList<>();
    private final List<Label> stashLabels = new ArrayList<>();

    public GlobalStashTrackerModule() {
        super(OpsTools.CATEGORY, "Global Stash Tracker",
            "Indexes the contents of every container you open across all your stashes and labels them. Use .find <item> and .stash.");
    }

    @Override
    public void onActivate() {
        resetTransientState();
        info("Open containers inside a registered stash to index them. Use .stash create <name> to register one.");
    }

    @Override
    public void onDeactivate() {
        resetTransientState();
    }

    private void resetTransientState() {
        openMenuId = -1;
        boundContainer = null;
        indexedOnce = false;
        clickedPos = null;
        crosshairPos = null;
        cachedVersion = -1;
        containerLabels.clear();
        stashLabels.clear();
    }

    // ------------------------------------------------------------------ accessors used by the commands

    public double fuzzyThreshold() {
        return fuzzyThreshold.get();
    }

    public int assignRadius() {
        return assignRadius.get();
    }

    /** Highlights these container blocks in the world for 30 seconds (called by .find). */
    public void highlight(Collection<BlockPos> positions) {
        highlighted = List.copyOf(positions);
        highlightUntil = System.currentTimeMillis() + HIGHLIGHT_MS;
    }

    // ------------------------------------------------------------------ chat helpers (shared with the commands)

    private static Component prefix() {
        return Component.literal("[StashTracker] ").withStyle(ChatFormatting.DARK_AQUA);
    }

    /** Client-side only chat line with the [StashTracker] prefix. Never sent to the server. */
    public static void chat(Component message) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.gui == null) return;
        mc.gui.getChat().addMessage(prefix().copy().append(message));
    }

    public static void chat(String message) {
        chat(Component.literal(message).withStyle(ChatFormatting.GRAY));
    }

    public static void chatError(String message) {
        chat(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    // ------------------------------------------------------------------ container recognition

    /**
     * Works out what kind of container block is at {@code pos}. Returns null for anything that is not a trackable
     * container (including ender chests, whose contents belong to the player rather than the place).
     * Double chests are reported once, at the lower of their two positions, so both halves share one entry.
     */
    public static ContainerInfo describeContainer(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        if (block instanceof EnderChestBlock) return null;

        if (block instanceof ChestBlock) {
            boolean trapped = block == Blocks.TRAPPED_CHEST;
            ChestType chestType = state.getValue(ChestBlock.TYPE);
            if (chestType == ChestType.SINGLE) {
                return new ContainerInfo(pos, trapped ? "Trapped Chest" : "Chest");
            }
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            BlockPos canonical = pos.asLong() <= other.asLong() ? pos : other;
            return new ContainerInfo(canonical, trapped ? "Double Trapped Chest" : "Double Chest");
        }
        if (block instanceof BarrelBlock) return new ContainerInfo(pos, "Barrel");
        if (block instanceof ShulkerBoxBlock) return new ContainerInfo(pos, "Shulker Box");
        if (block instanceof HopperBlock) return new ContainerInfo(pos, "Hopper");
        if (block instanceof DropperBlock) return new ContainerInfo(pos, "Dropper");   // before Dispenser: Dropper extends it
        if (block instanceof DispenserBlock) return new ContainerInfo(pos, "Dispenser");
        return null;
    }

    private static boolean isTrackableMenu(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu
            || menu instanceof HopperMenu || menu instanceof DispenserMenu;
    }

    // ------------------------------------------------------------------ click tracking

    /** The player right-clicked: remember which block was under the crosshair so the menu that opens next can be attributed to it. */
    @EventHandler
    private void onMouseButton(MouseClickEvent event) {
        if (mc.screen != null || mc.player == null) return;
        if (event.button() != 1) return;
        if (event.action != KeyAction.Press) return;

        // null when aiming at air or an entity: that clears any stale claim (chest minecarts etc.)
        clickedPos = lookedAtBlock();
        clickedTick = tickCounter;
        // The click itself is never cancelled - normal container opening is untouched.
    }

    private BlockPos lookedAtBlock() {
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos();
        }
        return null;
    }

    // ------------------------------------------------------------------ indexing (client thread)

    @EventHandler
    private void onTick(TickEvent.Post event) {
        tickCounter++;
        if (mc.player == null || mc.level == null) return;

        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null || menu == mc.player.inventoryMenu) {
            openMenuId = -1;
            boundContainer = null;
            return;
        }
        if (!isTrackableMenu(menu)) return;

        // A different menu than last tick: a container was just opened.
        if (menu.containerId != openMenuId) {
            openMenuId = menu.containerId;
            openedTick = tickCounter;
            boundContainer = resolveOpenedContainer(menu);
            lastSignature = 0;
            indexedOnce = false;
        }
        if (boundContainer == null) return; // can't tell where it is (e.g. opened by a plugin command) -> don't guess

        int openFor = tickCounter - openedTick;
        if (!indexedOnce && openFor < SETTLE_TICKS) return;

        // Re-index only when something inside actually changed (server filled the menu in, or items were moved).
        int signature = signature(menu);
        if (indexedOnce && signature == lastSignature) return;

        // An empty menu right after opening is most likely "contents haven't arrived yet": wait a bit before
        // trusting it, otherwise a laggy open would overwrite the saved contents with nothing.
        if (!indexedOnce && isMenuEmpty(menu) && openFor < EMPTY_TRUST_TICKS) return;

        lastSignature = signature;
        indexedOnce = true;
        indexMenu(menu);
    }

    private ContainerInfo resolveOpenedContainer(AbstractContainerMenu menu) {
        BlockPos pos = clickedPos;
        clickedPos = null; // a click claims one menu only
        if (pos == null || tickCounter - clickedTick > CLICK_TTL_TICKS) return null;
        if (mc.player.position().distanceTo(Vec3.atCenterOf(pos)) > 8) return null;

        ContainerInfo info = describeContainer(mc.level, pos);
        return info != null && menuMatches(menu, info) ? info : null;
    }

    /** The menu must look like what the block should open (menu class + slot count), otherwise it is a plugin GUI etc. */
    private static boolean menuMatches(AbstractContainerMenu menu, ContainerInfo info) {
        int slots = containerSlotCount(menu);
        return switch (info.type()) {
            case "Chest", "Trapped Chest", "Barrel" -> menu instanceof ChestMenu && slots == 27;
            case "Double Chest", "Double Trapped Chest" -> menu instanceof ChestMenu && slots == 54;
            case "Shulker Box" -> menu instanceof ShulkerBoxMenu && slots == 27;
            case "Hopper" -> menu instanceof HopperMenu && slots == 5;
            case "Dropper", "Dispenser" -> menu instanceof DispenserMenu && slots == 9;
            default -> false;
        };
    }

    /** Number of slots that belong to the container itself (the last 36 menu slots are the player's inventory). */
    private static int containerSlotCount(AbstractContainerMenu menu) {
        return Math.max(0, menu.slots.size() - 36);
    }

    private static boolean isMenuEmpty(AbstractContainerMenu menu) {
        int n = containerSlotCount(menu);
        for (int i = 0; i < n; i++) {
            if (!menu.getSlot(i).getItem().isEmpty()) return false;
        }
        return true;
    }

    /** Cheap fingerprint of the container's slots: item, count, anvil name and (for shulkers) the contents. */
    private static int signature(AbstractContainerMenu menu) {
        int hash = 1;
        int n = containerSlotCount(menu);
        for (int i = 0; i < n; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            int h = 0;
            if (!stack.isEmpty()) {
                h = System.identityHashCode(stack.getItem());
                h = 31 * h + stack.getCount();
                h = 31 * h + Objects.hashCode(stack.get(DataComponents.CUSTOM_NAME));
                h = 31 * h + Objects.hashCode(stack.get(DataComponents.CONTAINER));
            }
            hash = 31 * hash + h;
        }
        return hash;
    }

    private void indexMenu(AbstractContainerMenu menu) {
        // Loose items (aggregated by id + custom name) plus the name/contents of every shulker box in the menu.
        ContainerInspector.Inspection inspection = ContainerInspector.inspect(menu, containerSlotCount(menu));

        StashManager.UpsertResult result = StashManager.get().upsertContainer(
            StashManager.dimensionName(mc.level),
            boundContainer.pos(),
            boundContainer.type(),
            inspection.items(),
            inspection.shulkers(),
            assignRadius.get()
        );

        if (result.status() == StashManager.UpsertStatus.UNASSIGNED) maybePrompt(boundContainer.pos());
    }

    /** Subtle one-line hint, rate-limited so opening ten chests in a row doesn't spam chat. */
    private void maybePrompt(BlockPos pos) {
        if (!promptUnregistered.get()) return;
        boolean recentlyPrompted = tickCounter - lastPromptTick < 1200; // 60 s
        boolean sameArea = lastPromptPos != null && lastPromptPos.distSqr(pos) < 32 * 32;
        if (recentlyPrompted && sameArea) return;

        lastPromptTick = tickCounter;
        lastPromptPos = pos;
        chat(Component.literal("Unregistered area. Type .stash create <name> to index this location.")
            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    // ------------------------------------------------------------------ rendering

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;

        // Containers that matched the last .find
        List<BlockPos> marked = highlighted;
        if (!marked.isEmpty()) {
            if (System.currentTimeMillis() > highlightUntil) {
                highlighted = List.of();
            } else {
                for (BlockPos pos : marked) {
                    event.renderer.box(pos, highlightFill.get(), highlightOutline.get(), ShapeMode.Both, 0);
                }
            }
        }

        if (!renderLabels.get()) return;
        refreshLabelCache();

        Vec3 eye = mc.player.getEyePosition();
        double range = labelRange.get();
        List<Label> visible = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        collectVisible(containerLabels, eye, range, visible, distances);
        collectVisible(stashLabels, eye, range * 2, visible, distances);
        if (visible.isEmpty()) return;

        // Nearest first, then cap at max-labels.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble(idx -> distances.get(idx)));

        int limit = Math.min(maxLabels.get(), order.size());
        for (int k = 0; k < limit; k++) {
            int i = order.get(k);
            Label label = visible.get(i);
            double dist = distances.get(i);
            // Keep far labels readable: grow the text with distance beyond ~10 blocks.
            double scale = labelScale.get() * Math.max(1.0, dist / 10.0);
            StashLabelRenderer.draw(
                event, label.text(), new Vec3(label.x(), label.y(), label.z()), eye, scale,
                label.stash() ? stashColor.get() : containerColor.get()
            );
        }
    }

    private static void collectVisible(List<Label> source, Vec3 eye, double range, List<Label> out, List<Double> distances) {
        double rangeSq = range * range;
        for (Label label : source) {
            double dx = label.x() - eye.x;
            double dy = label.y() - eye.y;
            double dz = label.z() - eye.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= rangeSq) {
                out.add(label);
                distances.add(Math.sqrt(distSq));
            }
        }
    }

    /** Rebuilds the label lists only when the stash data changed or the player changed dimension - not every frame. */
    private void refreshLabelCache() {
        String dimension = StashManager.dimensionName(mc.level);
        int version = StashManager.get().version();
        if (version == cachedVersion && dimension.equals(cachedDimension)) return;

        cachedVersion = version;
        cachedDimension = dimension;
        containerLabels.clear();
        stashLabels.clear();

        StashManager.get().readLocked(stashes -> {
            for (StashManager.Stash stash : stashes) {
                if (!stash.dimension.equals(dimension)) continue;
                stashLabels.add(new Label(stash.centerX, stash.centerY + 2.5, stash.centerZ, "[" + stash.stashName + "]", true));

                for (StashManager.ContainerData container : stash.containers) {
                    containerLabels.add(new Label(
                        container.x + 0.5, container.y + 1.4, container.z + 0.5, summarize(container), false
                    ));
                }
            }
        });
    }

    /** Short label text: the most plentiful item, its count, and how many other kinds there are. */
    private static String summarize(StashManager.ContainerData container) {
        if (container.items == null || container.items.isEmpty()) return "EMPTY";

        StashManager.ItemEntry top = container.items.get(0);
        for (StashManager.ItemEntry item : container.items) {
            if (item.count > top.count) top = item;
        }
        String name = FuzzySearchUtil.stripNamespace(top.itemId);
        if (name.length() > 18) name = name.substring(0, 18);

        String text = name + " X" + top.count;
        if (container.items.size() > 1) text += " +" + (container.items.size() - 1);
        return text;
    }
}