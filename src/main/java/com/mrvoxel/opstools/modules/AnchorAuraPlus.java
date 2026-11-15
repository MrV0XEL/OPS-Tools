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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Anchor Aura - blows up enemies with respawn anchors.
 *
 * This merges "Anchor Aura+" and "Anchor Aura Rewrite" into one working module with strict safety checks.
 *
 * How anchor PvP works: right-clicking a respawn anchor with glowstone charges it; right-clicking a CHARGED anchor
 * with anything else makes it explode (power 5) - but only in the Overworld and the End. In the Nether the anchor
 * just sets your spawn, so the module refuses to run there.
 *
 * Each cycle: pick the best position around the nearest enemy (enough damage to them, little to you, no friends
 * hurt) -> place an anchor -> charge it -> explode it. One action per {@code delay} ticks, and the module waits for
 * the server to confirm a step before doing the next one.
 *
 * Needs in your hotbar: respawn anchors and glowstone. Keep glowstone OUT of your offhand (the game would then
 * refuse to explode the anchor with your main hand).
 */
public class AnchorAuraPlus extends Module {
    private static final double ANCHOR_POWER = 5.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDamage = settings.createGroup("Damage");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- General
    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Only enemies within this many blocks of you are targeted.")
        .defaultValue(10).min(3).max(20).sliderMax(20)
        .build()
    );
    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Max distance from your eyes to the anchor / the block it is placed against.")
        .defaultValue(4.5).min(2).max(6).sliderMax(6)
        .build()
    );
    private final Setting<Integer> scanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("scan-radius")
        .description("How far around the enemy positions are considered (horizontal).")
        .defaultValue(3).min(1).max(4).sliderMax(4)
        .build()
    );
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between actions (place / charge / explode).")
        .defaultValue(2).min(0).sliderMax(10)
        .build()
    );
    private final Setting<Integer> confirmTicks = sgGeneral.add(new IntSetting.Builder()
        .name("confirm-ticks")
        .description("How long to wait for the server to confirm a step before repeating it. Raise it with high ping.")
        .defaultValue(6).min(2).sliderMax(20)
        .build()
    );
    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Look at the block before interacting (server-side rotation).")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing your hand when interacting.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> pauseEat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-eat")
        .description("Do nothing while you are eating / drinking.")
        .defaultValue(true)
        .build()
    );

    // --- Damage
    private final Setting<Double> minDamage = sgDamage.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Minimum estimated damage to the enemy.")
        .defaultValue(8).min(0).sliderMax(36)
        .build()
    );
    private final Setting<Double> maxSelfDamage = sgDamage.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("Maximum estimated damage to you.")
        .defaultValue(6).min(0).sliderMax(36)
        .build()
    );
    private final Setting<Double> minRatio = sgDamage.add(new DoubleSetting.Builder()
        .name("min-ratio")
        .description("Enemy damage divided by self damage must be at least this.")
        .defaultValue(2).min(0).sliderMax(10)
        .build()
    );
    private final Setting<Boolean> antiSuicide = sgDamage.add(new BoolSetting.Builder()
        .name("anti-suicide")
        .description("Never blow an anchor that could kill you (health + absorption minus 1).")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> protectFriends = sgDamage.add(new BoolSetting.Builder()
        .name("protect-friends")
        .description("Skip positions that would hurt a Meteor friend.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Double> maxFriendDamage = sgDamage.add(new DoubleSetting.Builder()
        .name("max-friend-damage")
        .description("Maximum estimated damage to a friend.")
        .defaultValue(3).min(0).sliderMax(20)
        .visible(protectFriends::get)
        .build()
    );

    // --- Render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw the current anchor position.")
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

    private record Candidate(BlockPos pos, boolean existing, BlockHitResult placeHit, double damage, double self, double score) {}

    private int tickCounter = 0;
    private int timer = 0;
    private BlockPos renderPos = null;

    // what we did last, so we can wait for the server to answer before the next step
    private BlockPos lastPos = null;
    private BlockState lastState = null;
    private int lastTick = -1000;

    private boolean warnedItems = false;
    private boolean warnedOffhand = false;

    public AnchorAuraPlus() {
        super(OpsTools.CATEGORY, "anchor-aura-plus", "Places, charges and explodes respawn anchors on enemies (Overworld / End only).");
    }

    @Override
    public void onActivate() {
        tickCounter = 0;
        timer = 0;
        renderPos = null;
        lastPos = null;
        lastState = null;
        lastTick = -1000;
        warnedItems = false;
        warnedOffhand = false;
    }

    @Override
    public void onDeactivate() {
        renderPos = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        tickCounter++;

        if (PvpUtils.isNether()) {
            error("Respawn anchors don't explode in the Nether - use this in the Overworld or the End. Disabling.");
            toggle();
            return;
        }

        renderPos = null;

        if (timer > 0) {
            timer--;
            return;
        }
        if (pauseEat.get() && mc.player.isUsingItem()) return;

        if (mc.player.getOffhandItem().getItem() == Items.GLOWSTONE) {
            if (!warnedOffhand) {
                warning("Glowstone in your offhand stops anchors from exploding. Move it to your hotbar.");
                warnedOffhand = true;
            }
            return;
        }
        warnedOffhand = false;

        int anchorSlot = PvpUtils.findHotbar(Items.RESPAWN_ANCHOR);
        int glowSlot = PvpUtils.findHotbar(Items.GLOWSTONE);
        if (anchorSlot == -1 || glowSlot == -1) {
            if (!warnedItems) {
                warning("You need respawn anchors AND glowstone in your hotbar.");
                warnedItems = true;
            }
            return;
        }
        warnedItems = false;

        Player target = PvpUtils.nearestEnemy(targetRange.get());
        if (target == null) return;

        Candidate best = findBest(target);
        if (best == null) return;

        renderPos = best.pos();
        act(best, anchorSlot, glowSlot);
    }

    // ------------------------------------------------------------------ choosing a position

    private Candidate findBest(Player target) {
        BlockPos base = target.blockPosition();
        Vec3 eye = mc.player.getEyePosition();
        double selfHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double targetHealth = target.getHealth() + target.getAbsorptionAmount();
        int r = scanRadius.get();

        Candidate best = null;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    BlockState state = mc.level.getBlockState(pos);

                    boolean existing = state.getBlock() == Blocks.RESPAWN_ANCHOR;
                    if (!existing && !state.isAir()) continue;

                    Vec3 center = Vec3.atCenterOf(pos);
                    if (eye.distanceTo(center) > placeRange.get()) continue;

                    BlockHitResult placeHit = null;
                    if (!existing) {
                        if (PvpUtils.entityBlocks(pos)) continue;
                        placeHit = PvpUtils.supportHit(pos, placeRange.get());
                        if (placeHit == null) continue;
                    }

                    double damage = PvpUtils.explosionDamage(target, center, ANCHOR_POWER);
                    if (damage < minDamage.get()) continue;

                    double self = PvpUtils.explosionDamage(mc.player, center, ANCHOR_POWER);
                    if (self > maxSelfDamage.get()) continue;
                    if (antiSuicide.get() && self >= selfHealth - 1.0) continue;
                    if (self > 0 && damage / self < minRatio.get()) continue;
                    if (protectFriends.get() && hurtsFriend(center)) continue;

                    double score = damage - self;
                    if (damage >= targetHealth) score += 100.0; // a kill beats everything
                    if (existing) score += 5.0;                 // finish what is already placed

                    if (best == null || score > best.score()) {
                        best = new Candidate(pos, existing, placeHit, damage, self, score);
                    }
                }
            }
        }
        return best;
    }

    private boolean hurtsFriend(Vec3 center) {
        for (Player player : mc.level.players()) {
            if (player == mc.player || !PvpUtils.isFriend(player)) continue;
            if (PvpUtils.explosionDamage(player, center, ANCHOR_POWER) > maxFriendDamage.get()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ doing it

    private void act(Candidate c, int anchorSlot, int glowSlot) {
        BlockState state = mc.level.getBlockState(c.pos());

        // Wait for the server to answer our previous step at this position (state still unchanged).
        if (c.pos().equals(lastPos) && state == lastState && tickCounter - lastTick < confirmTicks.get()) return;

        if (state.isAir()) {
            BlockHitResult hit = c.placeHit();
            if (hit == null) return;
            interact(anchorSlot, hit);
        } else if (state.getBlock() == Blocks.RESPAWN_ANCHOR) {
            int charges = state.getValue(RespawnAnchorBlock.CHARGE);
            if (charges < 1) {
                interact(glowSlot, PvpUtils.clickHit(c.pos()));
            } else {
                int slot = explodeSlot();
                if (slot == -1) return;
                interact(slot, PvpUtils.clickHit(c.pos()));
            }
        } else {
            return;
        }

        lastPos = c.pos();
        lastState = state;
        lastTick = tickCounter;
        timer = delay.get();
    }

    /** Any hotbar slot that is not glowstone (an empty hand works too). Prefers the slot you already hold. */
    private int explodeSlot() {
        int selected = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getItem(selected).getItem() != Items.GLOWSTONE) return selected;
        return PvpUtils.findHotbar(stack -> stack.getItem() != Items.GLOWSTONE);
    }

    private void interact(int slot, BlockHitResult hit) {
        PvpUtils.useSlot(slot, () -> {
            if (rotate.get()) PvpUtils.faceVec(hit.getLocation());
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            if (swing.get()) mc.player.swing(InteractionHand.MAIN_HAND);
        });
    }

    // ------------------------------------------------------------------ rendering

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || renderPos == null) return;
        event.renderer.box(
            renderPos.getX(), renderPos.getY(), renderPos.getZ(),
            renderPos.getX() + 1, renderPos.getY() + 1, renderPos.getZ() + 1,
            sideColor.get(), lineColor.get(), ShapeMode.Both, 0
        );
    }
}
