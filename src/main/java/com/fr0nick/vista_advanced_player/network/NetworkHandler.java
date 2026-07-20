package com.fr0nick.vista_advanced_player.network;

import com.fr0nick.vista_advanced_player.common.IAdvancedTV;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class NetworkHandler {
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(SetTvStatePayload.TYPE, SetTvStatePayload.STREAM_CODEC, (payload, context) -> {
            context.enqueueWork(() -> {
                Player player = context.player();
                if (player instanceof ServerPlayer sp) {
                    Level level = sp.level();
                    if (sp.distanceToSqr(Vec3.atCenterOf(payload.pos())) > 1024) return;
                    BlockEntity be = level.getBlockEntity(payload.pos());
                    if (be instanceof TVBlockEntity tv && tv instanceof IAdvancedTV advTv) {
                        advTv.setVideoPlaybackTicks(payload.ticks());
                        advTv.setVideoPaused(payload.paused());
                        advTv.setAdvancedVolume(payload.volume());

                        tv.setChanged();
                        level.sendBlockUpdated(payload.pos(), tv.getBlockState(), tv.getBlockState(), 3);
                    }
                }
            });
        });
    }
}