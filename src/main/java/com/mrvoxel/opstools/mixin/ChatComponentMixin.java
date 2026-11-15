package com.mrvoxel.opstools.mixin;

import com.mrvoxel.opstools.OpsTools;
import com.mrvoxel.opstools.modules.ChatSentinel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets Chat Sentinel move the normal chat: everything the chat draws is shifted by the module's offset,
 * and the mouse position the chat sees is shifted back so hover effects still line up.
 * Target (1.21.11): ChatComponent#render(GuiGraphics, Font, int tick, int mouseX, int mouseY, boolean, boolean).
 * require = 0 means that if a future version changes this method the game still starts, the chat just stays put.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Unique
    private static final String OPSTOOLS_RENDER = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V";

    @Unique
    private boolean opstools$moved = false;

    @Unique
    private static boolean opstools$logged = false;

    // int arguments of render: tick = ordinal 0, mouseX = ordinal 1, mouseY = ordinal 2
    @ModifyVariable(method = OPSTOOLS_RENDER, at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private int opstools$mouseX(int mouseX) {
        return mouseX - Math.round(ChatSentinel.chatDx());
    }

    @ModifyVariable(method = OPSTOOLS_RENDER, at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 0)
    private int opstools$mouseY(int mouseY) {
        return mouseY - Math.round(ChatSentinel.chatDy());
    }

    @Inject(method = OPSTOOLS_RENDER, at = @At("HEAD"), require = 0)
    private void opstools$before(GuiGraphics graphics, Font font, int tick, int mouseX, int mouseY, boolean interactable, boolean other, CallbackInfo ci) {
        ChatSentinel.mixinHit = true;
        if (!opstools$logged) {
            opstools$logged = true;
            OpsTools.LOG.info("ChatComponentMixin is active (chat render hook reached)");
        }

        float dx = ChatSentinel.chatDx();
        float dy = ChatSentinel.chatDy();
        if (dx == 0f && dy == 0f) {
            opstools$moved = false;
            return;
        }

        graphics.pose().pushMatrix();
        graphics.pose().translate(dx, dy);
        opstools$moved = true;
    }

    @Inject(method = OPSTOOLS_RENDER, at = @At("RETURN"), require = 0)
    private void opstools$after(GuiGraphics graphics, Font font, int tick, int mouseX, int mouseY, boolean interactable, boolean other, CallbackInfo ci) {
        if (opstools$moved) {
            graphics.pose().popMatrix();
            opstools$moved = false;
        }
    }
}
