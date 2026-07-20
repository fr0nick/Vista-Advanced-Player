package com.fr0nick.vista_advanced_player.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class AdvancedPlayer implements AutoCloseable {
    public final String originalUrl;
    public volatile YtDlpHelper.MediaInfo info;
    
    public final ResourceLocation textureId;
    public volatile DynamicTexture texture;
    
    public volatile boolean isReady = false;
    public boolean isRestarting = false;
    public volatile boolean isVideoEOF = false;
    public volatile boolean isAudioEOF = false;
    private boolean isResolving = false;
    private volatile boolean closed = false;

    private Process videoProcess;
    private Process audioProcess;
    private volatile int streamGeneration = 0; 
    
    public volatile boolean permanentlyFailed = false;
    private int continuousFails = 0;
    private volatile int reResolveAttempts = 0;
    
    private volatile int chunksInGen = 0;
    private volatile int framesInGen = 0;
    
    private int alSource = -1;
    private final int[] allBuffers = new int[8];
    private final ConcurrentLinkedQueue<Integer> freeBuffers = new ConcurrentLinkedQueue<>();
    private ByteBuffer audioBuffer = BufferUtils.createByteBuffer(19200);
    private ByteBuffer frameBuffer;
    
    private final ConcurrentLinkedQueue<Frame> frames = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<byte[]> audioChunks = new ConcurrentLinkedQueue<>();
    public Frame currentFrame;
    
    public long localPlaybackMs = 0;
    private long lastSysTime = 0;
    private boolean isStarted = false;
    private volatile boolean isUploading = false;
    
    private boolean wasPaused = false;

    private int bufferW = 640;
    private int bufferH = 360;
    private final int expectedFrameSize;

    public AdvancedPlayer(String url, ResourceLocation textureId, int tvW, int tvH) {
        this.originalUrl = url;
        this.textureId = textureId;
        
        float tvAspect = (float) Math.max(1, tvW) / Math.max(1, tvH);
        if (tvAspect >= 1.0f) {
            this.bufferW = 800;
            this.bufferH = (int) (800 / tvAspect);
        } else {
            this.bufferH = 800;
            this.bufferW = (int) (800 * tvAspect);
        }
        this.bufferW = Math.max(2, (this.bufferW / 2) * 2);
        this.bufferH = Math.max(2, (this.bufferH / 2) * 2);
        this.expectedFrameSize = this.bufferW * this.bufferH * 4;
        this.frameBuffer = BufferUtils.createByteBuffer(this.expectedFrameSize);
        
        Minecraft.getInstance().execute(() -> {
            try {
                alSource = AL10.alGenSources();
                AL10.alGenBuffers(allBuffers);
                for (int b : allBuffers) freeBuffers.add(b);
                
                AL10.alSourcei(alSource, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
                AL10.alSourcef(alSource, AL10.AL_ROLLOFF_FACTOR, 1.0f);
                AL10.alSourcef(alSource, AL10.AL_REFERENCE_DISTANCE, 4.0f);
                AL10.alSourcef(alSource, AL10.AL_MAX_DISTANCE, 64.0f);
            } catch (Throwable ignored) {}
        });
    }

    public void manualSeek(long targetMs) {
        if (info == null || info.isImage() || info.durationMs() <= 0) return;
        permanentlyFailed = false;
        continuousFails = 0;
        reResolveAttempts = 0;
        
        localPlaybackMs = targetMs;
        isStarted = true;
        isRestarting = true;
        restartStreams(targetMs);
    }

    public boolean isFailed() {
        return permanentlyFailed;
    }

    private boolean isLivestream() {
        if (info == null || info.isImage() || info.durationMs() > 0) return false;
        if (originalUrl.matches("(?i).*\\.(mp4|webm|mkv)(\\?.*)?")) return false;
        return true;
    }

    public boolean isAudioOnlyFallback() {
        if (info != null && info.isAudioOnly()) return true;
        if (info != null && info.isImage()) return false;
        
        if (frames.isEmpty() && currentFrame == null) {
            if (isVideoEOF && !isAudioEOF && !permanentlyFailed) return true;
            if (chunksInGen > 20) return true; 
        }
        return false;
    }

    public boolean isBuffering() {
        if (info == null || isRestarting) return true;
        
        boolean isAnimated = info.isImage() && originalUrl.matches("(?i).*\\.(gif|webp|apng)(\\?.*)?");
        
        if (info.isImage() && !isAnimated) {
            return currentFrame == null;
        }
        
        boolean fallbackToAudio = isAudioOnlyFallback();
        boolean expectsVideo = !info.isAudioOnly() && !fallbackToAudio;
        
        boolean outOfVideo = expectsVideo && frames.isEmpty();
        boolean outOfAudio = !info.isImage() && audioChunks.isEmpty();
        
        if (expectsVideo && outOfVideo && !isVideoEOF) return true;
        if (!info.isImage() && outOfAudio && !isAudioEOF) return true;
        
        return false;
    }

    public void update(long targetMs, boolean paused, Vec3 tvPos, float volume) {
        if (closed || permanentlyFailed) return;

        if (info == null) {
            if (!isResolving) {
                isResolving = true;
                final long initialTargetMs = targetMs; 
                YtDlpHelper.resolve(originalUrl)
                    .completeOnTimeout(new YtDlpHelper.MediaInfo(originalUrl, 0, false, false), 15, TimeUnit.SECONDS)
                    .thenAccept(res -> {
                        this.info = res;
                        if (res.durationMs() > 0 && initialTargetMs > 0) {
                            manualSeek(initialTargetMs);
                        } else {
                            isStarted = true;
                            restartStreams(0);
                        }
                    });
            }
            return;
        }

        long durationMs = info.durationMs();
        long now = System.currentTimeMillis();
        long delta = lastSysTime == 0 ? 0 : now - lastSysTime;
        lastSysTime = now;

        if (!isStarted) {
            isStarted = true;
            if (targetMs > 0 && durationMs > 0) {
                manualSeek(targetMs);
            } else {
                restartStreams(0);
            }
        }

        boolean justUnpaused = this.wasPaused && !paused;
        this.wasPaused = paused;
        
        if (justUnpaused && isLivestream()) {
            localPlaybackMs = 0;
            isRestarting = true;
            restartStreams(0);
            return;
        }

        boolean bufferingInternal = isBuffering();
        boolean fallbackToAudio = isAudioOnlyFallback();
        boolean expectsVideo = !info.isAudioOnly() && !fallbackToAudio;
        
        boolean outOfVideo = expectsVideo && frames.isEmpty();
        boolean outOfAudio = !info.isImage() && audioChunks.isEmpty();
        
        boolean finishedVideo = !expectsVideo || (isVideoEOF && outOfVideo);
        boolean finishedAudio = info.isImage() || (isAudioEOF && outOfAudio);

        boolean isAnimatedImage = info.isImage() && originalUrl.matches("(?i).*\\.(gif|webp|apng)(\\?.*)?");
        boolean isStaticImage = info.isImage() && !isAnimatedImage;

        if (finishedVideo && finishedAudio && isStarted && !isRestarting && !permanentlyFailed) {
            boolean noDataAtAll = (framesInGen == 0 && chunksInGen == 0);
            boolean prematureEOF = (durationMs > 0 && localPlaybackMs < durationMs - 5000);

            if (noDataAtAll) {
                handleStreamFailure(streamGeneration, localPlaybackMs);
                return;
            } else if (prematureEOF) {
                isRestarting = true;
                restartStreams(localPlaybackMs);
                return;
            } else if (!isStaticImage) {
                localPlaybackMs = 0;
                isRestarting = true;
                restartStreams(0);
                return;
            }
        }

        if (!paused && isStarted) {
            if (!bufferingInternal) {
                localPlaybackMs += delta;
            }
        }

        if (!info.isAudioOnly()) {
            boolean foundNew = false;
            
            while (!frames.isEmpty() && frames.peek().ptsMs <= localPlaybackMs) {
                currentFrame = frames.poll();
                foundNew = true;
            }
            
            if (foundNew && currentFrame != null && texture != null && !closed && !isUploading) {
                byte[] rgba = currentFrame.rgba;
                if (rgba.length == expectedFrameSize) {
                    isUploading = true;
                    Runnable uploadTask = () -> {
                        try {
                            if (closed || texture == null) return;
                            com.mojang.blaze3d.systems.RenderSystem.bindTexture(texture.getId());
                            
                            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, 0);
                            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
                            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
                            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, 1);
                            
                            frameBuffer.clear();
                            frameBuffer.put(rgba);
                            frameBuffer.flip();
                            org.lwjgl.opengl.GL11.glTexSubImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, 0, 0, bufferW, bufferH, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, frameBuffer);
                        } finally {
                            isUploading = false;
                        }
                    };
                    if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
                        uploadTask.run();
                    } else {
                        com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(uploadTask::run);
                    }
                }
            }
            isReady = currentFrame != null || isVideoEOF || info.isImage(); 
        } else {
            isReady = !audioChunks.isEmpty() || isAudioEOF;
        }

        try {
            if (alSource != -1 && AL10.alIsSource(alSource)) {
                AL10.alGetError(); 

                if (paused || bufferingInternal) {
                    AL10.alSourcePause(alSource);
                    AL10.alSourcef(alSource, AL10.AL_GAIN, 0f);
                } else {
                    int processed = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_PROCESSED);
                    while (processed > 0) {
                        int freedBuf = AL10.alSourceUnqueueBuffers(alSource);
                        if (freedBuf != 0 && !freeBuffers.contains(freedBuf)) freeBuffers.add(freedBuf);
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
                    
                    Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
                    if (cam.isInitialized()) {
                        double dist = cam.getPosition().distanceTo(projPos);
                        float spatialVol = (float) Math.max(0, 1.0 - (dist / 32.0));
                        AL10.alSourcef(alSource, AL10.AL_GAIN, (spatialVol * spatialVol) * volume);
                    }
                }
            }
        } catch (Throwable ignored) {} 
    }

    public static synchronized String getFfmpegPath() {
        if (!net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpegManager.hasRequiredFiles()) {
            try {
                System.out.println("[Vista Advanced] FFmpeg not found, forcing download via Vista...");
                net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpegManager.getOrDownload(null).join();
            } catch (Exception e) {
                System.out.println("[Vista Advanced] Failed to download FFmpeg: " + e.getMessage());
            }
        }

        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("vista_ffmpeg_bin");
        String os = System.getProperty("os.name").toLowerCase();
        String name = os.contains("win") ? "ffmpeg.exe" : (os.contains("mac") ? "ffmpeg_macos" : "ffmpeg_linux");
        Path resolved = dir.resolve(name);
        if (Files.exists(resolved)) return resolved.toString();
        Path fallback = dir.resolve(os.contains("win") ? "ffmpeg.exe" : "ffmpeg");
        if (Files.exists(fallback)) return fallback.toString();
        return "ffmpeg";
    }

    private void handleStreamFailure(int currentGen, long failedAtMs) {
        if (streamGeneration != currentGen) return;
        
        Minecraft.getInstance().execute(() -> {
            if (streamGeneration != currentGen) return;
            
            continuousFails++;
            
            if (continuousFails > 2) {
                if (reResolveAttempts < 2) {
                    reResolveAttempts++;
                    info = null; 
                    isResolving = false;
                    continuousFails = 0;
                } else {
                    permanentlyFailed = true; 
                }
            } else {
                isRestarting = true;
                long restartMs = isLivestream() ? 0 : failedAtMs;
                restartStreams(restartMs);
            }
        });
    }

    private void restartStreams(long startMs) {
        stopStreams();
        int currentGen = ++this.streamGeneration;
        
        isReady = false;
        isVideoEOF = false;
        isAudioEOF = false;
        currentFrame = null;
        frames.clear();
        audioChunks.clear();
        chunksInGen = 0;
        framesInGen = 0;
        
        if (texture == null && !info.isAudioOnly()) {
            Minecraft.getInstance().execute(() -> {
                texture = new DynamicTexture(bufferW, bufferH, true);
                NativeImage img = texture.getPixels();
                if (img != null) {
                    img.fillRect(0, 0, bufferW, bufferH, 0xFF000000);
                    texture.upload();
                }
                Minecraft.getInstance().getTextureManager().register(textureId, texture);
            });
        }

        double startSec = startMs / 1000.0;
        
        boolean isAnimatedImage = info.isImage() && originalUrl.matches("(?i).*\\.(gif|webp|apng)(\\?.*)?");
        boolean isStaticImage = info.isImage() && !isAnimatedImage;

        if (!info.isAudioOnly()) {
            CompletableFuture.runAsync(() -> {
                String ffmpeg = getFfmpegPath();
                try {
                    List<String> cmd = new ArrayList<>();
                    cmd.add(ffmpeg); cmd.add("-hide_banner"); cmd.add("-loglevel"); cmd.add("error");
                    cmd.add("-user_agent"); cmd.add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                    cmd.add("-rw_timeout"); cmd.add("5000000"); 
                    cmd.add("-reconnect"); cmd.add("1"); cmd.add("-reconnect_streamed"); cmd.add("1"); cmd.add("-reconnect_delay_max"); cmd.add("5");
                    
                    if (isAnimatedImage) { cmd.add("-stream_loop"); cmd.add("-1"); }
                    
                    if (!info.isImage() && startSec > 0) { 
                        cmd.add("-ss"); cmd.add(String.format(java.util.Locale.US, "%.3f", startSec)); 
                    }
                    
                    cmd.add("-i"); cmd.add(info.directUrl());
                    
                    cmd.add("-f"); cmd.add("image2pipe"); cmd.add("-vcodec"); cmd.add("rawvideo");
                    cmd.add("-pix_fmt"); cmd.add("rgba"); 
                    
                    if (!isStaticImage) {
                        cmd.add("-r"); cmd.add("20");
                    }
                    
                    if (!info.isImage()) {
                        cmd.add("-vsync"); cmd.add("1");
                    }
                    
                    cmd.add("-vf"); cmd.add("scale=" + bufferW + ":" + bufferH + ":force_original_aspect_ratio=decrease,pad=" + bufferW + ":" + bufferH + ":-1:-1:color=black");
                    cmd.add("-");

                    videoProcess = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    videoProcess.getOutputStream().close();
                    
                    try (DataInputStream in = new DataInputStream(videoProcess.getInputStream())) {
                        byte[] buf = new byte[expectedFrameSize];
                        long frameIdx = 0;

                        while (!closed && streamGeneration == currentGen) {
                            in.readFully(buf); 
                            if (streamGeneration != currentGen) break;

                            byte[] rgba = new byte[expectedFrameSize];
                            System.arraycopy(buf, 0, rgba, 0, expectedFrameSize);
                            
                            frames.add(new Frame(rgba, startMs + (frameIdx * 50L)));
                            frameIdx++;
                            framesInGen++;
                            
                            if (isRestarting && streamGeneration == currentGen) isRestarting = false;
                            
                            while (frames.size() > 20 && !closed && streamGeneration == currentGen) Thread.sleep(10);
                        }
                    }
                } catch (Exception e) { 
                    if (streamGeneration == currentGen) isVideoEOF = true;
                } finally {
                    if (videoProcess != null) videoProcess.destroyForcibly();
                }
            });
        } else {
            isVideoEOF = true;
            isRestarting = false;
        }

        if (!info.isImage()) {
            CompletableFuture.runAsync(() -> {
                try {
                    if (!info.isAudioOnly()) {
                        Thread.sleep(500); 
                    }
                } catch (InterruptedException ignored) {}

                String ffmpeg = getFfmpegPath();
                try {
                    List<String> cmd = new ArrayList<>();
                    cmd.add(ffmpeg); cmd.add("-hide_banner"); cmd.add("-loglevel"); cmd.add("error");
                    cmd.add("-user_agent"); cmd.add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                    cmd.add("-rw_timeout"); cmd.add("5000000"); 
                    cmd.add("-reconnect"); cmd.add("1"); cmd.add("-reconnect_streamed"); cmd.add("1"); cmd.add("-reconnect_delay_max"); cmd.add("5");
                    
                    if (startSec > 0) { 
                        cmd.add("-ss"); cmd.add(String.format(java.util.Locale.US, "%.3f", startSec)); 
                    }
                    cmd.add("-i"); cmd.add(info.directUrl());
                    
                    cmd.add("-f"); cmd.add("s16le"); cmd.add("-acodec"); cmd.add("pcm_s16le");
                    cmd.add("-ac"); cmd.add("1"); cmd.add("-ar"); cmd.add("48000"); 
                    
                    cmd.add("-af"); cmd.add("aresample=async=1");
                    cmd.add("-vn"); cmd.add("-");

                    audioProcess = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    audioProcess.getOutputStream().close();
                    
                    try (DataInputStream in = new DataInputStream(audioProcess.getInputStream())) {
                        byte[] buffer = new byte[9600]; 
                        while (!closed && streamGeneration == currentGen) {
                            in.readFully(buffer);
                            if (streamGeneration != currentGen) break;
                            audioChunks.add(buffer.clone());
                            chunksInGen++;
                            if (isRestarting && streamGeneration == currentGen) isRestarting = false;
                            while (audioChunks.size() > 20 && !closed && streamGeneration == currentGen) Thread.sleep(10);
                        }
                    }
                } catch (Exception e) {
                    if (streamGeneration == currentGen) isAudioEOF = true;
                } finally {
                    if (audioProcess != null) audioProcess.destroyForcibly();
                }
            });
        } else {
            isAudioEOF = true;
            isRestarting = false;
        }
    }

    private void stopStreams() {
        if (videoProcess != null) videoProcess.destroyForcibly();
        if (audioProcess != null) audioProcess.destroyForcibly();
        
        Runnable clearTask = () -> {
            try {
                if (alSource != -1 && AL10.alIsSource(alSource)) {
                    AL10.alSourceStop(alSource);
                    AL10.alSourcei(alSource, AL10.AL_BUFFER, 0); 
                    freeBuffers.clear();
                    for (int b : allBuffers) freeBuffers.add(b);
                }
            } catch (Throwable ignored) {}
        };
        
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            clearTask.run();
        } else {
            Minecraft.getInstance().execute(clearTask);
        }
    }

    @Override
    public void close() {
        closed = true;
        streamGeneration++;
        stopStreams();
        Minecraft.getInstance().execute(() -> {
            try {
                if (alSource != -1 && AL10.alIsSource(alSource)) {
                    AL10.alSourceStop(alSource);
                    AL10.alSourcei(alSource, AL10.AL_BUFFER, 0); 
                    AL10.alDeleteSources(alSource);
                    alSource = -1;
                    for (int b : allBuffers) AL10.alDeleteBuffers(b);
                }
                if (texture != null) texture.close();
                Minecraft.getInstance().getTextureManager().release(textureId);
            } catch (Throwable ignored) {}
        });
    }

    private record Frame(byte[] rgba, long ptsMs) {}
}