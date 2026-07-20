package com.fr0nick.vista_advanced_player.common;

public interface IAdvancedTV {
    float getAdvancedVolume();
    void setAdvancedVolume(float volume);
    int getAdvancedSavedTicks();
    void setAdvancedSavedTicks(int ticks);
    int getVideoPlaybackTicks();
    void setVideoPlaybackTicks(int ticks);
    void setVideoPaused(boolean paused);
    String advancedPlayer$getUrl();
}