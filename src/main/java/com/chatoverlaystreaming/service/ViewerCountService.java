package com.chatoverlaystreaming.service;

import com.chatoverlaystreaming.overlay.Config;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.VideoListResponse;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

public class ViewerCountService {

    private static final long TWITCH_INTERVAL  = 60000; // 60 segundos
    private static final long YOUTUBE_INTERVAL = 60000;

    private final Config config;
    private final Consumer<String> onTwitchUpdate;  // callback con el nuevo valor
    private final Consumer<String> onYoutubeUpdate;

    private String cachedTwitchToken = null;
    private String currentVideoId    = null;

    public ViewerCountService(Config config,
                              Consumer<String> onTwitchUpdate,
                              Consumer<String> onYoutubeUpdate) {
        this.config          = config;
        this.onTwitchUpdate  = onTwitchUpdate;
        this.onYoutubeUpdate = onYoutubeUpdate;
    }

    public void start() {
        // Hilo para Twitch
        Thread twitchThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String count = fetchTwitchViewers();
                    onTwitchUpdate.accept(count != null ? count : "?");
                } catch (Exception e) {
                    System.err.println("[Viewers] Error Twitch: " + e.getMessage());
                    onTwitchUpdate.accept("?");
                }
                sleep(TWITCH_INTERVAL);
            }
        }, "twitch-viewers");
        twitchThread.setDaemon(true);
        twitchThread.start();

        // Hilo para YouTube
        Thread youtubeThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String count = fetchYoutubeViewers();
                    onYoutubeUpdate.accept(count != null ? count : "?");
                } catch (Exception e) {
                    System.err.println("[Viewers] Error YouTube: " + e.getMessage());
                    onYoutubeUpdate.accept("?");
                }
                sleep(YOUTUBE_INTERVAL);
            }
        }, "youtube-viewers");
        youtubeThread.setDaemon(true);
        youtubeThread.start();
    }

    /**
     * Permite al YouTubeChatReader notificar cuál es el videoId activo
     * para que el servicio pueda consultar los espectadores correctamente.
     */
    public void setCurrentVideoId(String videoId) {
        this.currentVideoId = videoId;
    }

    private String fetchTwitchViewers() throws Exception {
        if (cachedTwitchToken == null) {
            cachedTwitchToken = fetchTwitchToken();
        }

        String url = "https://api.twitch.tv/helix/streams?user_login=" +
                     config.getTwitchChannel();

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("Client-Id", config.getTwitchClientId());
        conn.setRequestProperty("Authorization", "Bearer " + cachedTwitchToken);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        int responseCode = conn.getResponseCode();
        if (responseCode == 401) {
            // Token expirado, obtener uno nuevo
            cachedTwitchToken = fetchTwitchToken();
            return fetchTwitchViewers();
        }

        try (InputStream is = conn.getInputStream()) {
            JSONObject response = new JSONObject(
                new String(is.readAllBytes(), StandardCharsets.UTF_8));
            org.json.JSONArray data = response.optJSONArray("data");
            if (data == null || data.length() == 0) return "?"; // canal offline
            return formatViewers(data.getJSONObject(0).getInt("viewer_count"));
        }
    }

    private String fetchTwitchToken() throws Exception {
        String url = "https://id.twitch.tv/oauth2/token";
        String body = "client_id=" + config.getTwitchClientId().trim() +
                      "&client_secret=" + config.getTwitchClientSecret().trim() +
                      "&grant_type=client_credentials";

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

        try (InputStream is = conn.getInputStream()) {
            JSONObject response = new JSONObject(
                new String(is.readAllBytes(), StandardCharsets.UTF_8));
            return response.getString("access_token");
        }
    }

    private String fetchYoutubeViewers() throws Exception {
        String videoId = currentVideoId != null ? currentVideoId : config.getYoutubeVideoId();
        if (videoId == null || videoId.isBlank()) return "?";

        String apiKey = config.getYoutubeApiKeys().get(0);
        String url = "https://www.googleapis.com/youtube/v3/videos" +
                     "?part=liveStreamingDetails" +
                     "&id=" + videoId +
                     "&key=" + apiKey;

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try (InputStream is = conn.getInputStream()) {
            JSONObject response = new JSONObject(
                new String(is.readAllBytes(), StandardCharsets.UTF_8));
            org.json.JSONArray items = response.optJSONArray("items");
            if (items == null || items.length() == 0) return "?";

            JSONObject details = items.getJSONObject(0)
                    .optJSONObject("liveStreamingDetails");
            if (details == null) return "?";

            String viewers = details.optString("concurrentViewers", null);
            return viewers != null ? formatViewers(Integer.parseInt(viewers)) : "?";
        }
    }

    /**
     * Formatea el número de espectadores: 1234 -> "1.2K", 1000000 -> "1M"
     */
    private String formatViewers(int count) {
        if (count >= 1000000) return String.format("%.1fM", count / 1000000.0);
        if (count >= 1000)    return String.format("%.1fK", count / 1000.0);
        return String.valueOf(count);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}