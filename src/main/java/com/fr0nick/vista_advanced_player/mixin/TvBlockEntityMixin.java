package com.fr0nick.vista_advanced_player.mixin;

import com.fr0nick.vista_advanced_player.common.IAdvancedTV;
import com.fr0nick.vista_advanced_player.common.IWebVideoSourceDuck;
import net.mehvahdjukaar.vista.common.tv.TVBlock;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(TVBlockEntity.class)
public abstract class TvBlockEntityMixin extends net.mehvahdjukaar.moonlight.api.block.ItemDisplayTile implements IAdvancedTV {

    public TvBlockEntityMixin(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Shadow private int videoPlaybackTicks;
    @Shadow private boolean paused;
    @Shadow private net.mehvahdjukaar.vista.client.video_source.IVideoSource videoSource;
    @Shadow public abstract int getScreenPixelWidth();
    @Shadow public abstract int getScreenPixelHeight();
    @Shadow public abstract boolean isPaused();
    @Shadow public abstract net.minecraft.world.phys.Vec2 getScreenBlockCenter();

    @Unique private float advancedPlayer$volume = 1.0f;
    @Unique private int advancedPlayer$savedTicks = 0;

    @Override public float getAdvancedVolume() { return advancedPlayer$volume; }
    @Override public void setAdvancedVolume(float volume) { this.advancedPlayer$volume = volume; }
    @Override public int getAdvancedSavedTicks() { return advancedPlayer$savedTicks; }
    @Override public void setAdvancedSavedTicks(int ticks) { this.advancedPlayer$savedTicks = ticks; }
    @Override public int getVideoPlaybackTicks() { return this.videoPlaybackTicks; }
    @Override public void setVideoPlaybackTicks(int ticks) { this.videoPlaybackTicks = ticks; }
    @Override public void setVideoPaused(boolean paused) { this.paused = paused; }

    @Override
    public String advancedPlayer$getUrl() {
        try {
            net.mehvahdjukaar.vista.client.video_source.IVideoSource src = this.videoSource;
            if (src instanceof net.mehvahdjukaar.vista.client.video_source.BroadcastVideoSource bvs) {
                net.mehvahdjukaar.vista.common.broadcast.BroadcastManager bm = net.mehvahdjukaar.vista.common.broadcast.BroadcastManager.getInstance(this.level);
                if (bm != null) {
                    net.mehvahdjukaar.vista.common.cassette.IBroadcastSource bs = bm.getBroadcast(bvs.uuid(), true);
                    if (bs instanceof net.mehvahdjukaar.vista.common.wave_gate.WaveGateBlockEntity wg) {
                        return wg.getUrl();
                    }
                }
            }
            if (src instanceof IWebVideoSourceDuck webSrc) {
                java.net.URI uri = webSrc.advancedPlayer$getUri();
                if (uri != null) return uri.toString();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void advancedPlayer$saveAdditional(net.minecraft.nbt.CompoundTag compound, net.minecraft.core.HolderLookup.Provider registries, CallbackInfo ci) {
        compound.putFloat("AdvancedPlayerVolume", this.advancedPlayer$volume);
        compound.putInt("AdvancedPlayerSavedTicks", Math.max(this.videoPlaybackTicks, this.advancedPlayer$savedTicks));
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void advancedPlayer$loadAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries, CallbackInfo ci) {
        if (tag.contains("AdvancedPlayerVolume")) this.advancedPlayer$volume = tag.getFloat("AdvancedPlayerVolume");
        if (tag.contains("AdvancedPlayerSavedTicks")) this.advancedPlayer$savedTicks = tag.getInt("AdvancedPlayerSavedTicks");
    }

    @Inject(method = "updateTileOnInventoryChanged", at = @At("TAIL"), remap = false)
    private void advancedPlayer$updateClientSource(CallbackInfo ci) {
        if (this.level != null && this.level.isClientSide) {
            this.videoSource = net.mehvahdjukaar.vista.client.video_source.IVideoSource.create(this.getDisplayedItem());
        }
    }

    @Inject(method = "getViewingFeedId", at = @At("HEAD"), cancellable = true, remap = false)
    private void advancedPlayer$fixViewingFeedId(CallbackInfoReturnable<UUID> cir) {
        try {
            if (this.getBlockState().hasProperty(TVBlock.POWER_STATE) && ((net.mehvahdjukaar.vista.common.tv.PowerState)this.getBlockState().getValue(TVBlock.POWER_STATE)).isOn()) {
                if (this.videoSource instanceof IWebVideoSourceDuck) {
                    BlockPos pos = this.getBlockPos();
                    cir.setReturnValue(new java.util.UUID(pos.asLong(), pos.asLong() ^ 0x123456789L));
                    return;
                }
                if (this.videoSource instanceof net.mehvahdjukaar.vista.client.video_source.BroadcastVideoSource bvs) {
                    cir.setReturnValue(bvs.uuid());
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "onTick", at = @At("HEAD"), remap = false)
    private static void advancedPlayer$onTickHead(Level level, BlockPos pos, BlockState state, TVBlockEntity tv, CallbackInfo ci) {
        if (state.getBlock() instanceof TVBlock) {
            boolean powered = ((net.mehvahdjukaar.vista.common.tv.PowerState)state.getValue(TVBlock.POWER_STATE)).isOn();
            if (tv instanceof IAdvancedTV mixin) {
                if (powered) {
                    if (mixin.getAdvancedSavedTicks() > 0) {
                        mixin.setVideoPlaybackTicks(mixin.getAdvancedSavedTicks());
                        mixin.setAdvancedSavedTicks(0);
                    }
                } else {
                    if (mixin.getVideoPlaybackTicks() > 0) {
                        mixin.setAdvancedSavedTicks(mixin.getVideoPlaybackTicks());
                        mixin.setVideoPlaybackTicks(0);
                    }
                }
            }
        }
    }

    @Inject(method = "onTick", at = @At("TAIL"), remap = false)
    private static void advancedPlayer$onTvTick(Level level, BlockPos pos, BlockState state, TVBlockEntity tv, CallbackInfo ci) {
        if (level.isClientSide && tv instanceof IAdvancedTV m) {
            String url = m.advancedPlayer$getUrl();
            if (url != null && !url.isEmpty()) {
                boolean powered = state.hasProperty(TVBlock.POWER_STATE) && ((net.mehvahdjukaar.vista.common.tv.PowerState)state.getValue(TVBlock.POWER_STATE)).isOn();
                boolean paused = tv.isPaused();
                int ticks = (!powered && m.getAdvancedSavedTicks() > 0) ? m.getAdvancedSavedTicks() : tv.getPlaybackTicks();
                
                net.minecraft.core.Direction facing = state.getValue(TVBlock.FACING);
                net.minecraft.world.phys.Vec2 relativeCenter = tv.getScreenBlockCenter();
                net.minecraft.world.phys.Vec3 center = pos.getCenter().add(net.mehvahdjukaar.moonlight.api.util.math.MthUtils.rotateVec3(new net.minecraft.world.phys.Vec3(relativeCenter.x, relativeCenter.y, 0.5), facing.getOpposite()));

                com.fr0nick.vista_advanced_player.client.ClientApi.registerTvTick(tv, level.dimension(), pos, url, center, tv.getScreenPixelWidth(), tv.getScreenPixelHeight(), powered, paused, ticks, m.getAdvancedVolume());
                return;
            }
            com.fr0nick.vista_advanced_player.client.ClientApi.removeTv(level.dimension(), pos);
        }
    }

    @Inject(method = "interactWithPlayerItem", at = @At("HEAD"), cancellable = true, remap = false)
    private void advancedPlayer$onInteract(Player player, InteractionHand handIn, ItemStack stack, int slot, BlockHitResult hit, CallbackInfoReturnable<ItemInteractionResult> cir) {
        try {
            TVBlockEntity tv = (TVBlockEntity) (Object) this;
            if (tv.getBlockState().getBlock() instanceof TVBlock) {
                boolean powered = ((net.mehvahdjukaar.vista.common.tv.PowerState)tv.getBlockState().getValue(TVBlock.POWER_STATE)).isOn();
                ItemStack displayed = tv.getDisplayedItem();
                if (!displayed.isEmpty() && powered && player.isSecondaryUseActive() && stack.isEmpty()) {
                    boolean isWeb = false;
                    String itemName = displayed.getItem().getClass().getSimpleName();
                    
                    if (itemName.equals("UrlCassetteItem")) isWeb = true;
                    else if (itemName.equals("HollowCassetteItem")) {
                        java.util.UUID feedId = (java.util.UUID) displayed.get(net.mehvahdjukaar.vista.VistaMod.LINKED_FEED_COMPONENT.get());
                        if (feedId != null) {
                            net.mehvahdjukaar.vista.common.cassette.IBroadcastSource src = net.mehvahdjukaar.vista.common.broadcast.BroadcastManager.getInstance(tv.getLevel()).getBroadcast(feedId, tv.getLevel().isClientSide);
                            if (src instanceof net.mehvahdjukaar.vista.common.wave_gate.WaveGateBlockEntity) isWeb = true;
                        }
                    }

                    if (isWeb) {
                        if (tv.getLevel().isClientSide) {
                            if (!com.fr0nick.vista_advanced_player.client.ClientApi.isImage(tv.getLevel().dimension(), tv.getBlockPos())) {
                                com.fr0nick.vista_advanced_player.client.ClientApi.openScreen(tv);
                            } else {
                                com.fr0nick.vista_advanced_player.client.ClientApi.togglePause(tv);
                            }
                        }
                        cir.setReturnValue(ItemInteractionResult.sidedSuccess(tv.getLevel().isClientSide));
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}