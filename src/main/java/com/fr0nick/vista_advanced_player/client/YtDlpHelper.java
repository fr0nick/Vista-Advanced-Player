package com.fr0nick.vista_advanced_player.client;

import net.minecraft.client.Minecraft;
import net.mehvahdjukaar.moonlight.api.util.FileDownloadUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class YtDlpHelper {
    private static Path ytDlpPath;

    public record MediaInfo(String directUrl, long durationMs, boolean isAudioOnly, boolean isImage) {}

    public static CompletableFuture<MediaInfo> resolve(String url) {
        return CompletableFuture.supplyAsync(() -> {
            System.out.println("[Vista Advanced] Resolving URL: " + url);

            if (url.matches("(?i).*\\.(png|jpg|jpeg|gif|webp)(\\?.*)?")) {
                return new MediaInfo(url, 0, false, true);
            }
            if (url.matches("(?i).*\\.(mp4|webm|mkv|mp3|wav|ogg)(\\?.*)?") && !url.contains("drive.google.com")) {
                return new MediaInfo(url, 0, url.endsWith(".mp3") || url.endsWith(".wav") || url.endsWith(".ogg"), false);
            }

            java.net.HttpURLConnection conn = null;
            try {
                conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                String contentType = conn.getContentType();
                if (contentType != null && contentType.startsWith("image/")) {
                    System.out.println("[Vista Advanced] Detected image via HTTP headers.");
                    return new MediaInfo(url, 0, false, true);
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }

            try {
                if (ytDlpPath == null) downloadYtDlp();
                
                ProcessBuilder pb = new ProcessBuilder(
                    ytDlpPath.toString(), 
                    "--no-warnings", 
                    "--no-playlist", 
                    "-f", "best", 
                    "--geo-bypass", 
                    "--socket-timeout", "10",
                    "--extractor-args", "youtube:player_client=android", 
                    "--extractor-args", "twitch:skip_hls_ads=true",
                    "--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", 
                    "--print", "url", 
                    "--print", "duration", 
                    "--print", "vcodec", 
                    "--print", "acodec", 
                    url
                );
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process p = pb.start();
                
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.trim().isEmpty()) lines.add(line.trim());
                }
                p.waitFor();

                String directUrl = null;
                int urlIdx = -1;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).startsWith("http")) {
                        directUrl = lines.get(i);
                        urlIdx = i;
                        break;
                    }
                }

                if (directUrl != null) {
                    System.out.println("[Vista Advanced] yt-dlp success! Direct URL found.");
                    
                    if (directUrl.matches("(?i).*\\.(png|jpg|jpeg|gif|webp)(\\?.*)?")) {
                        return new MediaInfo(directUrl, 0, false, true);
                    }
                    
                    long duration = 0;
                    if (lines.size() > urlIdx + 1 && !lines.get(urlIdx + 1).contains("NA")) {
                        try { duration = (long) (Double.parseDouble(lines.get(urlIdx + 1)) * 1000L); } catch (Exception ignored) {}
                    }
                    
                    boolean isAudioOnly = false;
                    if (lines.size() > urlIdx + 3) {
                        isAudioOnly = "none".equals(lines.get(urlIdx + 2)) || (!"none".equals(lines.get(urlIdx + 3)) && url.contains("soundcloud"));
                    }
                    return new MediaInfo(directUrl, duration, isAudioOnly, false);
                } else {
                    System.err.println("[Vista Advanced] yt-dlp failed to find URL. Lines returned: " + lines.size());
                }
            } catch (Exception e) {
                System.err.println("[Vista Advanced] yt-dlp exception: " + e.getMessage());
            }
            
            return new MediaInfo(url, 0, false, false);
        });
    }

    private static synchronized void downloadYtDlp() throws Exception {
        if (ytDlpPath != null) return;
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("vista_ffmpeg_bin");
        Files.createDirectories(dir);
        String os = System.getProperty("os.name").toLowerCase();
        String name = os.contains("win") ? "yt-dlp.exe" : (os.contains("mac") ? "yt-dlp_macos" : "yt-dlp_linux");
        Path resolvedPath = dir.resolve(name);
        
        if (!Files.exists(resolvedPath) || Files.size(resolvedPath) < 1024) { 
            System.out.println("[Vista Advanced] Downloading yt-dlp...");
            try {
                FileDownloadUtils.download("https://github.com/yt-dlp/yt-dlp/releases/latest/download/" + name, resolvedPath, null, percent -> {});
            } catch (Exception e) {
                System.out.println("[Vista Advanced] GitHub download failed, trying mirror proxy...");
                FileDownloadUtils.download("https://mirror.ghproxy.com/https://github.com/yt-dlp/yt-dlp/releases/latest/download/" + name, resolvedPath, null, percent -> {});
            }
            resolvedPath.toFile().setExecutable(true);
        }
        ytDlpPath = resolvedPath;
    }
}