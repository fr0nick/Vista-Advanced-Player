package com.fr0nick.vista_advanced_player.client;

import com.fr0nick.vista_advanced_player.common.IAdvancedTV;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AdvancedPlayerManager {
    private static final Map<GlobalPos, AdvancedPlayer> PLAYERS = new ConcurrentHashMap<>();
    private static final Map<GlobalPos, TvState> ACTIVE_TVS = new ConcurrentHashMap<>();

    private static class TvState {
        public TVBlockEntity tv; public GlobalPos pos; public String url; public Vec3 center; 
        public int w, h; public boolean powered, paused; public int playbackTicks; 
        public float volume; public long lastTick;

        public TvState(TVBlockEntity tv, GlobalPos pos, String url, Vec3 center, int w, int h, boolean powered, boolean paused, int playbackTicks, float volume, long lastTick) {
            this.tv = tv; this.pos = pos; this.url = url; this.center = center; this.w = w; this.h = h;
            this.powered = powered; this.paused = paused; this.playbackTicks = playbackTicks; this.volume = volume; this.lastTick = lastTick;
        }
    }

    public static void registerTvTick(TVBlockEntity tv, GlobalPos pos, String url, Vec3 center, int w, int h, boolean powered, boolean paused, int playbackTicks, float volume) {
        long gameTime = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0;
        ACTIVE_TVS.put(pos, new TvState(tv, pos, url, center, w, h, powered, paused, playbackTicks, volume, gameTime));
        
        getPlayer(pos, url, w, h);
    }

    public static void removeTv(GlobalPos pos) {
        ACTIVE_TVS.remove(pos);
    }

    public static AdvancedPlayer getPlayer(GlobalPos pos, String url, int tvW, int tvH) {
        if (pos == null || url == null) return null;

        AdvancedPlayer player = PLAYERS.get(pos);
        if (player != null && !player.originalUrl.equals(url)) {
            player.close();
            PLAYERS.remove(pos);
            player = null;
        }
        if (player == null) {
            net.minecraft.resources.ResourceLocation texLoc = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("vista_advanced_player", "feed_" + Math.abs(pos.hashCode()) + "_" + System.currentTimeMillis());
            player = new AdvancedPlayer(url, texLoc, tvW, tvH);
            PLAYERS.put(pos, player);
        }
        return player;
    }

    public static void removePlayer(GlobalPos pos) {
        if (pos == null) return;
        AdvancedPlayer p = PLAYERS.remove(pos);
        if (p != null) p.close();
    }

    public static AdvancedPlayer getPlayerById(GlobalPos pos) {
        if (pos == null) return null;
        return PLAYERS.get(pos);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean gamePaused = mc.isPaused();
        long currentTick = mc.level != null ? mc.level.getGameTime() : 0;

        if (!gamePaused) {
            ACTIVE_TVS.entrySet().removeIf(e -> Math.abs(currentTick - e.getValue().lastTick) > 10);
        } else {
            for (TvState state : ACTIVE_TVS.values()) {
                state.lastTick = currentTick;
            }
        }

        PLAYERS.entrySet().removeIf(entry -> {
            GlobalPos pos = entry.getKey();
            AdvancedPlayer player = entry.getValue();
            TvState state = ACTIVE_TVS.get(pos);

            if (state == null) {
                if (gamePaused) {
                    player.update(player.localPlaybackMs, true, Vec3.ZERO, 0);
                    return false;
                }
                player.close();
                return true;
            }

            boolean paused = !state.powered || state.paused;
            float vol = state.volume;
            Vec3 center = state.center;

            if (gamePaused) {
                player.update(player.localPlaybackMs, true, Vec3.ZERO, 0);
                return false;
            }

            long targetMs = 0;
            if (state.tv != null && !state.tv.isRemoved()) {
                long tvMs = state.powered ? state.tv.getPlaybackTicks() * 50L : ((IAdvancedTV) state.tv).getAdvancedSavedTicks() * 50L;
                targetMs = tvMs;
            }

            if (!paused && !player.isBuffering()) {
                int correctTicks = (int) (player.localPlaybackMs / 50L);
                if (state.tv != null && !state.tv.isRemoved()) {
                    int tvTicks = state.powered ? state.tv.getPlaybackTicks() : ((IAdvancedTV) state.tv).getAdvancedSavedTicks();
                    if (Math.abs(tvTicks - correctTicks) > 10) { 
                        if (state.powered) {
                            ((IAdvancedTV) state.tv).setVideoPlaybackTicks(correctTicks);
                        } else {
                            ((IAdvancedTV) state.tv).setAdvancedSavedTicks(correctTicks);
                        }
                    }
                }
            }

            player.update(targetMs, paused, center, vol);
            return false;
        });
    }

    public static void clearAll() {
        for (AdvancedPlayer player : PLAYERS.values()) {
            player.close();
        }
        PLAYERS.clear();
        ACTIVE_TVS.clear();
    }
}