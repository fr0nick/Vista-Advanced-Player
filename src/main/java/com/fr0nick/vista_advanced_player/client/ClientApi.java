package com.fr0nick.vista_advanced_player.client;

import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class ClientApi {
    public static void registerTvTick(TVBlockEntity tv, ResourceKey<Level> dim, BlockPos pos, String url, Vec3 center, int w, int h, boolean powered, boolean paused, int playbackTicks, float volume) {
        AdvancedPlayerManager.registerTvTick(tv, GlobalPos.of(dim, pos), url, center, w, h, powered, paused, playbackTicks, volume);
    }

    public static void removeTv(ResourceKey<Level> dim, BlockPos pos) {
        AdvancedPlayerManager.removeTv(GlobalPos.of(dim, pos));
    }

    public static boolean isImage(ResourceKey<Level> dim, BlockPos pos) {
        AdvancedPlayer player = AdvancedPlayerManager.getPlayerById(GlobalPos.of(dim, pos));
        return player != null && player.info != null && player.info.isImage();
    }

    public static void openScreen(TVBlockEntity tv) {
        com.fr0nick.vista_advanced_player.client.gui.AdvancedPlayerScreen.open(tv);
    }

    public static void togglePause(TVBlockEntity tv) {
        boolean newPaused = !tv.isPaused();
        float vol = 1.0f;
        if (tv instanceof com.fr0nick.vista_advanced_player.common.IAdvancedTV advTv) {
            vol = advTv.getAdvancedVolume();
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new com.fr0nick.vista_advanced_player.network.SetTvStatePayload(tv.getBlockPos(), tv.getPlaybackTicks(), newPaused, vol)
        );
    }
}