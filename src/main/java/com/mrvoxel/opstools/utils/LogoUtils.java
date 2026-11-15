package com.mrvoxel.opstools.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Helpers shared by the logo-building modules (LogoBuilder, BaseGuardian, AutoRestock, EnderChestFarmer): inventory
 * counting, shulker contents, slot swaps, and vanilla-style block clicks. Everything on top of {@link PvpUtils}.
 */
public final class LogoUtils {
    private LogoUtils() {}

    // ------------------------------------------------------------------ items

    public static boolean hasSilkTouch(ItemStack stack) {
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null) return false;
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            if (holder.is(Enchantments.SILK_TOUCH)) return true;
        }
        return false;
    }

    public static boolean isShulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock;
    }

    /** How many of {@code item} are inside the shulker box stack (0 if it is not a shulker). */
    public static int shulkerItemCount(ItemStack shulker, Item item) {
        if (!isShulker(shulker)) return 0;
        ItemContainerContents contents = shulker.get(DataComponents.CONTAINER);
        if (contents == null) return 0;
        int count = 0;
        for (ItemStack inner : contents.stream().toList()) {
            if (!inner.isEmpty() && inner.is(item)) count += inner.getCount();
        }
        return count;
    }

    public static boolean shulkerHasAny(ItemStack shulker, Item... items) {
        for (Item item : items) {
            if (shulkerItemCount(shulker, item) > 0) return true;
        }
        return false;
    }

    /** Count in the main inventory (hotbar included) plus the offhand. */
    public static int countItem(Item item) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.is(item)) count += offhand.getCount();
        return count;
    }

    public static int countEmptySlots() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) count++;
        }
        return count;
    }

    /** True if one more item of this kind would fit (an empty slot, or a partial stack of the same item). */
    public static boolean hasRoomFor(Item item) {
        if (mc.player == null) return false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) return true;
            if (stack.is(item) && stack.getCount() < stack.getMaxStackSize()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ slots

    /** Inventory index (0-8 hotbar, 9-35 main) to slot id in the player inventory menu. */
    public static int slotId(int inventoryIndex) {
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }

    /** Swaps the stack in an inventory slot with the offhand. The click is simulated locally, so the result is readable right away. */
    public static void swapWithOffhand(int inventoryIndex) {
        mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, slotId(inventoryIndex), 40, ClickType.SWAP, mc.player);
    }

    /** Swaps the stack in an inventory slot with a hotbar slot. */
    public static void swapWithHotbar(int inventoryIndex, int hotbarSlot) {
        mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, slotId(inventoryIndex), hotbarSlot, ClickType.SWAP, mc.player);
    }

    /** Selects a hotbar slot and tells the server straight away (raw packets do not do that on their own). */
    public static void selectHotbar(int slot) {
        if (slot < 0 || slot > 8) return;
        if (mc.player.getInventory().getSelectedSlot() == slot) return;
        mc.player.getInventory().setSelectedSlot(slot);
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
    }

    // ------------------------------------------------------------------ clicking blocks

    /**
     * A vanilla-style click on {@code pos}: the line from your eye to the middle of the block's real shape, which gives
     * the face that points at you and the exact point where the line enters it. Works for thin blocks such as buttons.
     * Null if the block has no shape.
     */
    public static BlockHitResult clickOn(BlockPos pos, Vec3 eye) {
        if (mc.level == null) return null;
        BlockState state = mc.level.getBlockState(pos);
        VoxelShape shape = state.getShape(mc.level, pos);
        if (shape.isEmpty()) return null;

        AABB bounds = shape.bounds();
        Vec3 center = new Vec3(
            pos.getX() + (bounds.minX + bounds.maxX) / 2.0,
            pos.getY() + (bounds.minY + bounds.maxY) / 2.0,
            pos.getZ() + (bounds.minZ + bounds.maxZ) / 2.0
        );
        return shape.clip(eye, center, pos);
    }

    /** True if nothing is in the way between the eye and the clicked point (outline shapes, so buttons count). */
    public static boolean canSee(Vec3 eye, BlockPos pos, BlockHitResult hit) {
        Vec3 direction = hit.getLocation().subtract(eye);
        if (direction.lengthSqr() < 1.0E-6) return true;
        Vec3 end = hit.getLocation().add(direction.normalize().scale(0.05)); // a hair into the block
        BlockHitResult result = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(pos);
    }

    /** A spot you can stand on: free feet and head space, no fluid, solid floor. */
    public static boolean standable(BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        BlockState feetState = mc.level.getBlockState(feet);
        BlockState headState = mc.level.getBlockState(head);
        BlockState floorState = mc.level.getBlockState(floor);
        return feetState.getCollisionShape(mc.level, feet).isEmpty()
            && headState.getCollisionShape(mc.level, head).isEmpty()
            && feetState.getFluidState().isEmpty()
            && headState.getFluidState().isEmpty()
            && !floorState.getCollisionShape(mc.level, floor).isEmpty();
    }

    /** Looks at a point: turns the camera and sends the rotation to the server. */
    public static void aimAt(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        PvpUtils.faceAngles(yaw, pitch);
    }
}
