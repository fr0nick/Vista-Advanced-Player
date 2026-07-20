package com.fr0nick.vista_advanced_player.mixin;

import com.fr0nick.vista_advanced_player.client.BlockEntityRenderContext;
import com.mojang.blaze3d.vertex.PoseStack;
import net.mehvahdjukaar.vista.client.renderer.TvBlockEntityRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TvBlockEntityRenderer.class, remap = false)
public class TvBlockEntityRendererMixin {
    @Inject(method = "render(Lnet/mehvahdjukaar/vista/common/tv/TVBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("HEAD"))
    private void advancedPlayer$onRenderHead(net.mehvahdjukaar.vista.common.tv.TVBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        BlockEntityRenderContext.CURRENT.set(blockEntity);
    }

    @Inject(method = "render(Lnet/mehvahdjukaar/vista/common/tv/TVBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("RETURN"))
    private void advancedPlayer$onRenderReturn(net.mehvahdjukaar.vista.common.tv.TVBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        BlockEntityRenderContext.CURRENT.remove();
    }
}