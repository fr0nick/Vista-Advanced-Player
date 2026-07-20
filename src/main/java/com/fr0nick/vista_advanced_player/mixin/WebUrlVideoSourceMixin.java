package com.fr0nick.vista_advanced_player.mixin;

import com.fr0nick.vista_advanced_player.client.AdvancedPlayerManager;
import com.fr0nick.vista_advanced_player.client.AdvancedPlayer;
import com.fr0nick.vista_advanced_player.common.IWebVideoSourceDuck;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.mehvahdjukaar.moonlight.api.util.math.Vec2i;
import net.mehvahdjukaar.vista.client.CrtOverlay;
import net.mehvahdjukaar.vista.client.textures.TvScreenVertexConsumers;
import net.mehvahdjukaar.vista.client.video_source.WebUrlVideoSource;
import net.mehvahdjukaar.vista.common.tv.IntAnimationState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.URI;
import java.util.UUID;

@Mixin(value = WebUrlVideoSource.class, remap = false)
public class WebUrlVideoSourceMixin implements IWebVideoSourceDuck {

    @Shadow @Final private URI uri;
    @Shadow @Final private UUID projectorID;

    @Override
    public URI advancedPlayer$getUri() { return this.uri; }

    @Override
    public UUID advancedPlayer$getProjectorID() { return this.projectorID; }
    
    private static final ResourceLocation NOTE_ICON = ResourceLocation.fromNamespaceAndPath("vista_advanced_player", "textures/gui/note.png");

    @Inject(method = "getVideoFrameBuilder", at = @At("HEAD"), cancellable = true)
    private void advancedPlayer$overrideVideoSource(float partialTick, MultiBufferSource buffer, boolean shouldUpdate, Vec2i screenSize, Vec2i pixelEffectRes, int videoAnimationTick, boolean paused, IntAnimationState switchAnim, IntAnimationState staticAnim, boolean showsTime, CallbackInfoReturnable<VertexConsumer> cir) {
        try {
            if (this.uri == null) {
                cir.setReturnValue(TvScreenVertexConsumers.getBarsVC(buffer, pixelEffectRes, switchAnim));
                return;
            }

            net.minecraft.world.level.block.entity.BlockEntity be = com.fr0nick.vista_advanced_player.client.BlockEntityRenderContext.CURRENT.get();
            GlobalPos tvPos = null;
            
            if (be != null && be.getLevel() != null) {
                tvPos = GlobalPos.of(be.getLevel().dimension(), be.getBlockPos());
            } else {
                long hash = this.projectorID.getMostSignificantBits();
                tvPos = GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, new BlockPos((int)(hash >> 32), 0, (int)hash));
            }

            AdvancedPlayer player = AdvancedPlayerManager.getPlayer(tvPos, this.uri.toString(), screenSize.x(), screenSize.y());
            
            if (player == null) {
                cir.setReturnValue(TvScreenVertexConsumers.getNoiseVC(buffer, pixelEffectRes, switchAnim));
                return;
            }

            int loadingAnimTick = (int) (System.currentTimeMillis() / 50L);
            CrtOverlay overlay = paused ? CrtOverlay.PAUSE : CrtOverlay.NONE;

            if (player.isFailed()) {
                cir.setReturnValue(TvScreenVertexConsumers.getNoiseVC(buffer, pixelEffectRes, switchAnim));
                return;
            }

            if (player.isAudioOnlyFallback()) {
                if (player.isBuffering()) {
                    cir.setReturnValue(TvScreenVertexConsumers.getWaitingVc(buffer, pixelEffectRes, loadingAnimTick, switchAnim));
                } else {
                    cir.setReturnValue(TvScreenVertexConsumers.getSingleTextureVC(buffer, NOTE_ICON, overlay, pixelEffectRes, switchAnim, IntAnimationState.NO_ANIM));
                }
                return;
            }

            if (player.texture == null || player.isBuffering() || !player.isReady) {
                cir.setReturnValue(TvScreenVertexConsumers.getWaitingVc(buffer, pixelEffectRes, loadingAnimTick, switchAnim));
                return;
            }
            
            cir.setReturnValue(TvScreenVertexConsumers.getSingleTextureVC(buffer, player.textureId, overlay, pixelEffectRes, switchAnim, staticAnim));
        } catch (Throwable t) {
            cir.setReturnValue(TvScreenVertexConsumers.getNoiseVC(buffer, pixelEffectRes, switchAnim));
        }
    }
}