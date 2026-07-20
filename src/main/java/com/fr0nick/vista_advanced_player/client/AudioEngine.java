package com.fr0nick.vista_advanced_player.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import java.io.DataInputStream;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AudioEngine {
    private static final Map<String, AudioPlayer> PLAYERS = new ConcurrentHashMap<>();

    public static void update(String url, int ticks, boolean paused, Vec3 tvPos, float volume) {
        AudioPlayer player = PLAYERS.computeIfAbsent(url, AudioPlayer::new);
        player.update(ticks, paused, tvPos, volume);
        
        long now = System.currentTimeMillis();
        PLAYERS.entrySet().removeIf(e -> {
            if (now - e.getValue().lastTick > 1000) {
                e.getValue().close();
                return true;
            }
            return false;
        });
    }

    public static void clearAll() {
        for (AudioPlayer p : PLAYERS.values()) p.close();
        PLAYERS.clear();
    }

    private static class AudioPlayer {
        private final String url;
        public long lastTick = System.currentTimeMillis();
        private int lastPlayedTick = -1;
        
        private Process audioProcess;
        private int alSource = -1;
        private final int[] allBuffers = new int[8];
        private final ConcurrentLinkedQueue<Integer> freeBuffers = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<byte[]> audioChunks = new ConcurrentLinkedQueue<>();
        private ByteBuffer audioBuffer = BufferUtils.createByteBuffer(19200);
        private boolean closed = false;

        public AudioPlayer(String url) {
            this.url = url;
            Minecraft.getInstance().execute(() -> {
                alSource = AL10.alGenSources();
                AL10.alGenBuffers(allBuffers);
                for (int b : allBuffers) freeBuffers.add(b);
                AL10.alSourcei(alSource, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
                AL10.alSourcef(alSource, AL10.AL_ROLLOFF_FACTOR, 1.0f);
                AL10.alSourcef(alSource, AL10.AL_REFERENCE_DISTANCE, 4.0f);
                AL10.alSourcef(alSource, AL10.AL_MAX_DISTANCE, 64.0f);
            });
        }

        public void update(int ticks, boolean paused, Vec3 tvPos, float volume) {
            this.lastTick = System.currentTimeMillis();
            if (closed) return;

            Path cacheFile = getCachedFile(url);
            if (cacheFile == null || !Files.exists(cacheFile)) return;

            if (lastPlayedTick == -1 || Math.abs(ticks - lastPlayedTick) > 20) {
                restartStream(ticks, cacheFile);
            }
            lastPlayedTick = ticks;

            if (alSource != -1) {
                if (paused) {
                    AL10.alSourcePause(alSource);
                } else {
                    int processed = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_PROCESSED);
                    while (processed > 0) {
                        freeBuffers.add(AL10.alSourceUnqueueBuffers(alSource));
                        processed--;
                    }
                    
                    while (!freeBuffers.isEmpty() && !audioChunks.isEmpty()) {
                        int bufId = freeBuffers.poll();
                        byte[] chunk = audioChunks.poll();
                        
                        if (audioBuffer == null || audioBuffer.capacity() < chunk.length) {
                            audioBuffer = BufferUtils.createByteBuffer(Math.max(19200, chunk.length));
                        }
                        audioBuffer.clear();
                        audioBuffer.put(chunk);
                        audioBuffer.flip();
                        
                        AL10.alBufferData(bufId, AL10.AL_FORMAT_MONO16, audioBuffer, 48000);
                        AL10.alSourceQueueBuffers(alSource, bufId);
                    }
                    
                    int state = AL10.alGetSourcei(alSource, AL10.AL_SOURCE_STATE);
                    int queued = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_QUEUED);
                    if (state != AL10.AL_PLAYING && queued > 0) {
                        AL10.alSourcePlay(alSource);
                    }

                    Vec3 projPos = tvPos;
                    try { projPos = SableHelper.project(Minecraft.getInstance().level, tvPos); } catch (Throwable ignored){}
                    AL10.alSource3f(alSource, AL10.AL_POSITION, (float)projPos.x, (float)projPos.y, (float)projPos.z);
                    
                    net.minecraft.client.Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
                    if (cam.isInitialized()) {
                        double dist = cam.getPosition().distanceTo(projPos);
                        float spatialVol = (float) Math.max(0, 1.0 - (dist / 32.0));
                        AL10.alSourcef(alSource, AL10.AL_GAIN, (spatialVol * spatialVol) * volume);
                    }
                }
            }
        }

        private void restartStream(int ticks, Path cacheFile) {
            if (audioProcess != null) audioProcess.destroyForcibly();
            audioChunks.clear();
            
            double startSec = (ticks * 50L) / 1000.0;

            CompletableFuture.runAsync(() -> {
                String ffmpeg = AdvancedPlayer.getFfmpegPath();
                try {
                    List<String> cmd = new ArrayList<>();
                    cmd.add(ffmpeg); cmd.add("-hide_banner"); cmd.add("-loglevel"); cmd.add("error");
                    if (startSec > 0) { cmd.add("-ss"); cmd.add(String.valueOf(startSec)); }
                    cmd.add("-i"); cmd.add(cacheFile.toString());
                    cmd.add("-f"); cmd.add("s16le"); cmd.add("-acodec"); cmd.add("pcm_s16le");
                    cmd.add("-ac"); cmd.add("1"); cmd.add("-ar"); cmd.add("48000"); cmd.add("-vn"); cmd.add("-");

                    audioProcess = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    audioProcess.getOutputStream().close();
                    
                    try (DataInputStream in = new DataInputStream(audioProcess.getInputStream())) {
                        byte[] buffer = new byte[9600]; 
                        while (!closed) {
                            try {
                                in.readFully(buffer);
                                audioChunks.add(buffer.clone());
                                while (audioChunks.size() > 20 && !closed) Thread.sleep(10);
                            } catch (EOFException e) { break; }
                        }
                    }
                } catch (Exception e) {}
            });
        }

        public void close() {
            closed = true;
            if (audioProcess != null) audioProcess.destroyForcibly();
            Minecraft.getInstance().execute(() -> {
                if (alSource != -1) {
                    AL10.alSourceStop(alSource);
                    int queued = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_QUEUED);
                    while (queued-- > 0) AL10.alSourceUnqueueBuffers(alSource);
                    AL10.alDeleteSources(alSource);
                    for (int b : allBuffers) AL10.alDeleteBuffers(b);
                }
            });
        }
    }

    private static Path getCachedFile(String url) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return Minecraft.getInstance().gameDirectory.toPath().resolve("vista_web_content_cache").resolve(hex.toString() + ".video");
        } catch (Exception e) { return null; }
    }
}