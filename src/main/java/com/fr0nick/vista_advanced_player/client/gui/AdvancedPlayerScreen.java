package com.fr0nick.vista_advanced_player.client.gui;

import com.fr0nick.vista_advanced_player.client.AdvancedPlayerManager;
import com.fr0nick.vista_advanced_player.client.AdvancedPlayer;
import com.fr0nick.vista_advanced_player.common.IAdvancedTV;
import com.fr0nick.vista_advanced_player.network.SetTvStatePayload;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

public class AdvancedPlayerScreen extends Screen {
    private final TVBlockEntity tv;
    private IconButton playPauseBtn;
    private boolean localPaused;
    private float localVolume = 1.0f;

    private boolean draggingSeek = false;
    private boolean draggingVolume = false;
    private long previewSeekMs = -1;

    private static final int BAR_W = 200;
    private static final int BAR_H = 6;
    private static final int VOL_W = 100;
    private static final int VOL_H = 6;

    private static final ResourceLocation PLAY_ICON = ResourceLocation.fromNamespaceAndPath("vista_advanced_player", "textures/gui/play.png");
    private static final ResourceLocation PAUSE_ICON = ResourceLocation.fromNamespaceAndPath("vista_advanced_player", "textures/gui/pause.png");
    private static final ResourceLocation REWIND_ICON = ResourceLocation.fromNamespaceAndPath("vista_advanced_player", "textures/gui/rewind.png");
    private static final ResourceLocation FORWARD_ICON = ResourceLocation.fromNamespaceAndPath("vista_advanced_player", "textures/gui/forward.png");

    public AdvancedPlayerScreen(TVBlockEntity tv) {
        super(Component.literal("Vista: Advanced Player"));
        this.tv = tv;
    }

    public static void open(TVBlockEntity tv) {
        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().setScreen(new AdvancedPlayerScreen(tv));
        });
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int cy = this.height / 2;
        this.localPaused = tv.isPaused();
        
        if (tv instanceof IAdvancedTV advTv) {
            this.localVolume = advTv.getAdvancedVolume();
        }

        this.addRenderableWidget(new IconButton(cx - 35, cy + 10, 20, 20, REWIND_ICON, b -> seek(-10000)));

        playPauseBtn = this.addRenderableWidget(new IconButton(cx - 10, cy + 10, 20, 20, this.localPaused ? PLAY_ICON : PAUSE_ICON, b -> {
            this.localPaused = !this.localPaused;
            PacketDistributor.sendToServer(new SetTvStatePayload(tv.getBlockPos(), tv.getPlaybackTicks(), this.localPaused, this.localVolume));
            ((IconButton)b).icon = this.localPaused ? PLAY_ICON : PAUSE_ICON;
        }));

        this.addRenderableWidget(new IconButton(cx + 15, cy + 10, 20, 20, FORWARD_ICON, b -> seek(10000)));
    }

    private void seek(long offsetMs) {
        GlobalPos tvPos = GlobalPos.of(tv.getLevel().dimension(), tv.getBlockPos());
        AdvancedPlayer p = AdvancedPlayerManager.getPlayerById(tvPos);
        long duration = (p != null && p.info != null) ? p.info.durationMs() : 0;
        
        long totalMs = p != null ? p.localPlaybackMs : tv.getPlaybackTicks() * 50L;
        long absoluteMs = totalMs + offsetMs;
        if (absoluteMs < 0) absoluteMs = 0;
        if (duration > 0 && absoluteMs > duration) absoluteMs = duration;
        
        if (p != null) p.manualSeek(absoluteMs);
        
        int targetTicks = (int)(absoluteMs / 50L);
        if (tv instanceof IAdvancedTV advTv) advTv.setVideoPlaybackTicks(targetTicks);
        PacketDistributor.sendToServer(new SetTvStatePayload(tv.getBlockPos(), targetTicks, this.localPaused, this.localVolume));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cx = this.width / 2;
        int cy = this.height / 2;

        int barX = cx - BAR_W / 2;
        int barY = cy - 6;
        int volX = cx - VOL_W / 2;
        int volY = cy + 60;

        if (button == 0) {
            if (mouseX >= barX - 10 && mouseX <= barX + BAR_W + 10 && mouseY >= barY - 10 && mouseY <= barY + BAR_H + 10) {
                draggingSeek = true;
                this.setDragging(true);
                updateSeekFromMouse(mouseX, barX, BAR_W);
                return true; 
            }
            if (mouseX >= volX - 10 && mouseX <= volX + VOL_W + 10 && mouseY >= volY - 10 && mouseY <= volY + VOL_H + 10) {
                draggingVolume = true;
                this.setDragging(true);
                updateVolumeFromMouse(mouseX, volX, VOL_W);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingSeek) {
            int cx = this.width / 2;
            updateSeekFromMouse(mouseX, cx - BAR_W / 2, BAR_W);
            return true;
        }
        if (draggingVolume) {
            int cx = this.width / 2;
            updateVolumeFromMouse(mouseX, cx - VOL_W / 2, VOL_W);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private void endDragging() {
        if (draggingSeek) {
            draggingSeek = false;
            this.setDragging(false);
            if (previewSeekMs != -1) {
                GlobalPos tvPos = GlobalPos.of(tv.getLevel().dimension(), tv.getBlockPos());
                AdvancedPlayer p = AdvancedPlayerManager.getPlayerById(tvPos);
                if (p != null) p.manualSeek(previewSeekMs);
                
                int targetTicks = (int)(previewSeekMs / 50L);
                if (tv instanceof IAdvancedTV advTv) advTv.setVideoPlaybackTicks(targetTicks);
                PacketDistributor.sendToServer(new SetTvStatePayload(tv.getBlockPos(), targetTicks, this.localPaused, this.localVolume));
                previewSeekMs = -1;
            }
        }
        if (draggingVolume) {
            draggingVolume = false;
            this.setDragging(false);
            PacketDistributor.sendToServer(new SetTvStatePayload(tv.getBlockPos(), tv.getPlaybackTicks(), this.localPaused, this.localVolume));
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        endDragging();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void updateSeekFromMouse(double mouseX, int barX, int barW) {
        GlobalPos tvPos = GlobalPos.of(tv.getLevel().dimension(), tv.getBlockPos());
        AdvancedPlayer p = AdvancedPlayerManager.getPlayerById(tvPos);
        long duration = (p != null && p.info != null) ? p.info.durationMs() : 0;
        if (duration > 0) {
            double percent = (mouseX - barX) / (double) barW;
            percent = Math.max(0, Math.min(1, percent));
            previewSeekMs = (long) (percent * duration);
        }
    }

    private void updateVolumeFromMouse(double mouseX, int volX, int volW) {
        double percent = (mouseX - volX) / (double) volW;
        this.localVolume = (float) Math.max(0, Math.min(1, percent));
        if (tv instanceof IAdvancedTV advTv) advTv.setAdvancedVolume(this.localVolume);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GlobalPos tvPos = GlobalPos.of(tv.getLevel().dimension(), tv.getBlockPos());
        AdvancedPlayer p = AdvancedPlayerManager.getPlayerById(tvPos);
        
        if (p != null && p.info != null && p.info.isImage()) {
            this.onClose();
            return;
        }

        int cx = this.width / 2;
        int cy = this.height / 2;
        int barX = cx - BAR_W / 2;
        int volX = cx - VOL_W / 2;

        boolean isMouseLeftDown = GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (draggingSeek || draggingVolume) {
            if (!isMouseLeftDown) {
                endDragging();
            } else {
                if (draggingSeek) updateSeekFromMouse(mouseX, barX, BAR_W);
                if (draggingVolume) updateVolumeFromMouse(mouseX, volX, VOL_W);
            }
        }

        graphics.fill(0, 0, this.width, this.height, 0xAA000000); 
        super.render(graphics, mouseX, mouseY, partialTick);

        if (playPauseBtn != null) {
            playPauseBtn.icon = this.localPaused ? PLAY_ICON : PAUSE_ICON;
        }

        long duration = (p != null && p.info != null) ? p.info.durationMs() : 0;
        long playerMs = p != null ? p.localPlaybackMs : tv.getPlaybackTicks() * 50L;
        
        if (tv instanceof IAdvancedTV advTv) {
            int st = advTv.getAdvancedSavedTicks();
            if (st > 0 && tv.getPlaybackTicks() == 0) playerMs = st * 50L;
        }

        long currentMs = previewSeekMs != -1 ? previewSeekMs : playerMs;
        
        if (duration > 0) {
            if (currentMs > duration) currentMs = duration;
        }

        boolean isResolving = p == null || p.info == null;
        boolean isBuffering = p != null && p.isBuffering();
        boolean isFailed = p != null && p.isFailed();
        boolean isAudio = p != null && p.isAudioOnlyFallback();

        if (isResolving) {
            graphics.drawCenteredString(this.font, "Resolving URL...", cx, cy - 35, 0xFFFFFFFF);
        } else if (isFailed) {
            graphics.drawCenteredString(this.font, "Failed to load", cx, cy - 35, 0xFF5555);
        } else {
            if (isBuffering && !p.isReady) {
                graphics.drawCenteredString(this.font, "Loading...", cx, cy - 35, 0xFFFFFFFF);
            }
            
            if (duration > 0) {
                String timeText = formatTime(currentMs) + " / " + formatTime(duration);
                graphics.drawCenteredString(this.font, timeText, cx, cy - 20, 0xFFFFFF);
                
                int barY = cy - 6;
                graphics.fill(barX, barY, barX + BAR_W, barY + BAR_H, 0xFF555555);
                int progressWidth = (int)((currentMs * BAR_W) / duration);
                graphics.fill(barX, barY, barX + progressWidth, barY + BAR_H, 0xFF00FF00);
                graphics.fill(barX + progressWidth - 2, barY - 2, barX + progressWidth + 2, barY + BAR_H + 2, 0xFFFFFFFF);
            } else {
                if (isAudio) graphics.drawCenteredString(this.font, "Radio", cx, cy - 20, 0xFFFFFF);
                else graphics.drawCenteredString(this.font, "Live Stream", cx, cy - 20, 0xFFFFFF);
            }
        }

        int volY = cy + 60;
        graphics.drawCenteredString(this.font, "Volume: " + (int)(this.localVolume * 100) + "%", cx, volY - 12, 0xFFFFFF);
        graphics.fill(volX, volY, volX + VOL_W, volY + VOL_H, 0xFF555555);
        int volFill = (int)(this.localVolume * VOL_W);
        graphics.fill(volX, volY, volX + volFill, volY + VOL_H, 0xFF00AAFF);
        graphics.fill(volX + volFill - 2, volY - 2, volX + volFill + 2, volY + VOL_H + 2, 0xFFFFFFFF);
    }

    private String formatTime(long ms) {
        long totalSecs = ms / 1000;
        long hours = totalSecs / 3600;
        long mins = (totalSecs % 3600) / 60;
        long secs = totalSecs % 60;
        if (hours > 0) return String.format("%02d:%02d:%02d", hours, mins, secs);
        return String.format("%02d:%02d", mins, secs);
    }
    
    @Override
    public boolean isPauseScreen() { return false; }

    private class IconButton extends Button {
        public ResourceLocation icon;

        public IconButton(int x, int y, int w, int h, ResourceLocation icon, OnPress onPress) {
            super(x, y, w, h, Component.empty(), onPress, DEFAULT_NARRATION);
            this.icon = icon;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            g.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, this.isHovered() ? 0xAA777777 : 0xAA444444);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            
            int iconSize = 16;
            int px = this.getX() + (this.width - iconSize) / 2;
            int py = this.getY() + (this.height - iconSize) / 2;
            
            g.blit(icon, px, py, 0, 0, iconSize, iconSize, iconSize, iconSize);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        }
    }
}