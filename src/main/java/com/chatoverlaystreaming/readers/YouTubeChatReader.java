package com.chatoverlaystreaming.readers;

import com.chatoverlaystreaming.emotes.ImageCache;
import com.chatoverlaystreaming.emotes.YouTubeEmojiCache;
import com.chatoverlaystreaming.model.ChatMessage;
import com.chatoverlaystreaming.model.EmoteToken;
import com.chatoverlaystreaming.overlay.Config;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;

public class YouTubeChatReader implements Runnable {

    private static final long RETRY_INTERVAL    = 180000; // 3 minutos
    private long min_polling_interval;
    private final long startTime = System.currentTimeMillis();

    private final String channelId;
    private final String configVideoId;
    private final List<String> apiKeys;
    private final Config config;
    private final BlockingQueue<ChatMessage> queue;
    private final YouTubeEmojiCache youtubeEmojiCache;

    private YouTube youtube;
    private int currentKeyIndex = 0;
    private boolean quotaExceeded = false;
    private String resolvedVideoId = null;

    public YouTubeChatReader(String channelId, String videoId, List<String> apiKeys,
                         BlockingQueue<ChatMessage> queue, Config config,
                         ImageCache imageCache) {
        this.channelId     = channelId;
        this.configVideoId = videoId;
        this.apiKeys       = apiKeys;
        this.config        = config;
        this.queue         = queue;
        this.youtubeEmojiCache = new YouTubeEmojiCache(imageCache);
        this.min_polling_interval = config.getMinPollingInterval() * 1000;
    }

    private String currentApiKey() {
        return apiKeys.get(currentKeyIndex);
    }

    /**
     * Intenta rotar a la siguiente API key disponible.
     * Devuelve true si hay una key disponible, false si se agotaron todas.
     */
    private boolean rotateApiKey() {
        currentKeyIndex++;
        if (currentKeyIndex >= apiKeys.size()) {
            quotaExceeded = true;
            System.err.println("[YouTube] Todas las API keys han superado la quota.");
            postSystemMessage("YouTube: quota excedida en todas las API keys. " +
                              "Se reanudarán las peticiones mañana a medianoche (hora del Pacífico).");
            return false;
        }
        System.err.println("[YouTube] Rotando a API key " + (currentKeyIndex + 1) +
                           " de " + apiKeys.size());
       // postSystemMessage("YouTube: quota excedida, cambiando a API key " +
       //                   (currentKeyIndex + 1) + "...");
        return true;
    }

    @Override
    public void run() {
        youtube = new YouTube.Builder(
                new NetHttpTransport(),
                JacksonFactory.getDefaultInstance(),
                request -> {})
                .setApplicationName("ChatOverlay")
                .build();

        String liveChatId = null;

        while (!Thread.currentThread().isInterrupted()) {
            // Si todas las keys están agotadas, esperar hasta medianoche PT
            if (quotaExceeded) {
                long waitTime = millisUntilMidnightPT();
                System.err.println("[YouTube] Quota excedida. Esperando " +
                                   (waitTime / 60000) + " minutos hasta reset.");
                sleep(waitTime);
                // Reset al amanecer
                currentKeyIndex = 0;
                quotaExceeded = false;
                liveChatId = null;
                postSystemMessage("YouTube: reiniciando tras reset de quota.");
                continue;
            }

            try {
                if (liveChatId == null) {
                    liveChatId = resolveLiveChatId();
                    if (liveChatId == null) {
                        postSystemMessage("YouTube: no se encontró ningún directo activo. " +
                                          "Reintentando en 3 minutos...");
                        sleep(RETRY_INTERVAL);
                        continue;
                    }
                }

                readChat(liveChatId);

            } catch (QuotaExceededException e) {
                if (!rotateApiKey()) {
                    // No hay más keys, el bucle volverá a comprobar quotaExceeded
                    continue;
                }
                // Hay otra key disponible, reintentar con el mismo liveChatId
            } catch (ChatEndedException e) {
                System.err.println("[YouTube] El directo terminó, buscando nuevo directo...");
                liveChatId = null;
                sleep(RETRY_INTERVAL);
            } catch (Exception e) {
                System.err.println("[YouTube] Error de conexión: " + e.getMessage());
                postSystemMessage("YouTube: error de conexión. Reintentando en 3 minutos...");
                sleep(RETRY_INTERVAL);
            }
        }
    }

    private String resolveLiveChatId() throws QuotaExceededException {
        System.err.println("[YouTube] Resolviendo liveChatId...");
        if (configVideoId != null && !configVideoId.isBlank()) {
            String chatId = getLiveChatId(configVideoId);
            if (chatId != null) {
                System.err.println("[YouTube] Conectado usando videoId del config.");
                resolvedVideoId = configVideoId;
                return chatId;
            }
            System.err.println("[YouTube] videoId del config no tiene chat activo, buscando directo...");
        }

        if (channelId != null && !channelId.isBlank()) {
            String foundVideoId = searchLiveVideo();
            if (foundVideoId != null) {
                String chatId = getLiveChatId(foundVideoId);
                if (chatId != null) {
                    System.err.println("[YouTube] Directo encontrado: " + foundVideoId);
                    resolvedVideoId = foundVideoId;
                    try {
                        config.saveYouTube(foundVideoId);
                    } catch (IOException ex) {
                        System.err.println("[Config] Error guardando: " + ex.getMessage());
                    }
                    return chatId;
                }
            }
        }

        return null;
    }

    private String getLiveChatId(String videoId) {
        try {
            VideoListResponse response = youtube.videos()
                    .list(List.of("liveStreamingDetails"))
                    .setId(List.of(videoId))
                    .setKey(currentApiKey())
                    .execute();

            if (response.getItems().isEmpty()) {
                System.err.println("[YouTube] videoId no encontrado: " + videoId);
                return null;
            }

            String chatId = response.getItems().get(0)
                    .getLiveStreamingDetails().getActiveLiveChatId();

            if (chatId == null) {
                System.err.println("[YouTube] El vídeo no tiene chat activo: " + videoId);
            }

            return chatId;

        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 403 && e.getMessage().contains("quota")) {
                throw new QuotaExceededException("Quota excedida: " + e.getMessage());
            }
            System.err.println("[YouTube] Error consultando videoId: " + e.getMessage());
            return null;
        } catch (Exception e) {
            System.err.println("[YouTube] Error consultando videoId: " + e.getMessage());
            return null;
        }
    }

    private String searchLiveVideo() {
        try {
            SearchListResponse response = youtube.search()
                    .list(List.of("id"))
                    .setChannelId(channelId)
                    .setEventType("live")
                    .setType(List.of("video"))
                    .setKey(currentApiKey())
                    .execute();

            if (response.getItems().isEmpty()) {
                System.err.println("[YouTube] No hay directo activo en el canal.");
                return null;
            }

            return response.getItems().get(0).getId().getVideoId();

        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 403 && e.getMessage().contains("quota")) {
                throw new QuotaExceededException("Quota excedida: " + e.getMessage());
            }
            System.err.println("[YouTube] Error buscando directo: " + e.getMessage());
            return null;
        } catch (Exception e) {
            System.err.println("[YouTube] Error buscando directo: " + e.getMessage());
            return null;
        }
    }

    private void readChat(String liveChatId) throws Exception {
        System.err.println("[YouTube] Leyendo chat: " + liveChatId);

        // Intentar recuperar pageToken guardado si el videoId coincide
        String pageToken = null;
        String lastVideoId = config.getYoutubeLastVideoId();
        String lastPageToken = config.getYoutubeLastPageToken();

        if (resolvedVideoId != null && resolvedVideoId.equals(lastVideoId) 
                && lastPageToken != null) {
            System.err.println("[YouTube] Usando pageToken guardado, saltando histórico.");
            pageToken = lastPageToken;
        } else {
            // Primera petición para sincronizar sin procesar histórico
            String url = "https://www.googleapis.com/youtube/v3/liveChat/messages" +
                        "?liveChatId=" + liveChatId +
                        "&part=snippet" +
                        "&maxResults=1" +
                        "&key=" + currentApiKey();

            String rawJson = fetch(url);
            org.json.JSONObject firstResponse = new org.json.JSONObject(rawJson);
            checkForErrors(firstResponse);
            pageToken = firstResponse.optString("nextPageToken", null);

            long pollingInterval = Math.max(min_polling_interval,
                                            firstResponse.getLong("pollingIntervalMillis"));
            sleep(pollingInterval);
            System.out.println("[YouTube] Sincronizado, empezando a leer mensajes nuevos.");
        }

        while (!Thread.currentThread().isInterrupted()) {
            String url = "https://www.googleapis.com/youtube/v3/liveChat/messages" +
                        "?liveChatId=" + liveChatId +
                        "&part=snippet,authorDetails" +
                        "&maxResults=200" +
                        "&key=" + currentApiKey() +
                        (pageToken != null ? "&pageToken=" + pageToken : "");

            String rawJson = fetch(url);
            org.json.JSONObject response = new org.json.JSONObject(rawJson);
            checkForErrors(response);

            long pollingInterval = Math.max(min_polling_interval,
                                            response.getLong("pollingIntervalMillis"));
            pageToken = response.optString("nextPageToken", null);

            // Guardar pageToken en config después de cada petición exitosa
            if (pageToken != null && resolvedVideoId != null) {
                try {
                    config.saveYoutubePageToken(resolvedVideoId, pageToken);
                } catch (IOException e) {
                    System.err.println("[Config] Error guardando pageToken: " + e.getMessage());
                }
            }

            org.json.JSONArray items = response.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    org.json.JSONObject item = items.getJSONObject(i);
                    org.json.JSONObject snippet = item.getJSONObject("snippet");

                    if (!"textMessageEvent".equals(snippet.getString("type"))) continue;

                    String publishedAt = snippet.optString("publishedAt", null);
                    if (publishedAt != null) {
                        long msgTime = java.time.Instant.parse(publishedAt).toEpochMilli();
                        if (msgTime < startTime) continue;
                    }

                    org.json.JSONObject authorDetails = item.getJSONObject("authorDetails");
                    String user = authorDetails.getString("displayName");

                    List<EmoteToken> tokens = youtubeEmojiCache.tokenize(snippet);
                    String text = snippet.getJSONObject("textMessageDetails")
                                        .getString("messageText");

                    queue.put(new ChatMessage("youtube", user, text, tokens));
                }
            }

            sleep(pollingInterval);
        }
    }

    /**
     * Comprueba si la respuesta contiene un error y lanza la excepción adecuada.
     */
    private void checkForErrors(org.json.JSONObject response) throws Exception {
        if (!response.has("error")) return;

        org.json.JSONObject error = response.getJSONObject("error");
        int code = error.getInt("code");
        String message = error.optString("message", "");

        if (code == 403 && message.contains("quota")) {
            throw new QuotaExceededException("Quota excedida: " + message);
        }
        if (code == 403 || code == 404) {
            throw new ChatEndedException("Chat terminado: " + message);
        }
        throw new Exception("Error API YouTube (" + code + "): " + message);
    }

    /**
     * Calcula los milisegundos hasta medianoche hora del Pacífico (PT),
     * que es cuando se resetea la quota de YouTube.
     */
    private long millisUntilMidnightPT() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(
                java.time.ZoneId.of("America/Los_Angeles"));
        java.time.ZonedDateTime midnight = now.toLocalDate()
                .plusDays(1)
                .atStartOfDay(java.time.ZoneId.of("America/Los_Angeles"));
        return java.time.Duration.between(now, midnight).toMillis();
    }

    private String fetch(String url) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                java.net.URI.create(url).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "ChatOverlay/1.0");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        try (java.io.InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void postSystemMessage(String text) {
        try {
            queue.put(new ChatMessage("youtube", "⚠ Sistema", text));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class QuotaExceededException extends RuntimeException  {
        QuotaExceededException(String message) { super(message); }
    }

    private static class ChatEndedException extends RuntimeException  {
        ChatEndedException(String message) { super(message); }
    }
}