package com.fr0nick.vista_advanced_player;

import com.fr0nick.vista_advanced_player.client.AdvancedPlayerManager;
import com.fr0nick.vista_advanced_player.client.AudioEngine;
import com.fr0nick.vista_advanced_player.network.NetworkHandler;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

@Mod("vista_advanced_player")
public class VistaAdvancedPlayer {
    public VistaAdvancedPlayer(IEventBus modBus) {
        modBus.addListener(NetworkHandler::register);
    }

    @EventBusSubscriber(modid = "vista_advanced_player", value = Dist.CLIENT)
    public static class ClientEvents {
        @SubscribeEvent
        public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            AudioEngine.clearAll();
            AdvancedPlayerManager.clearAll();
        }

        @SubscribeEvent
        public static void onRenderFrame(RenderFrameEvent.Pre event) {
            AdvancedPlayerManager.tick();
        }
    }
}