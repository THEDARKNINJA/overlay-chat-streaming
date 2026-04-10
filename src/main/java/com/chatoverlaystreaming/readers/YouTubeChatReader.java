package com.chatoverlaystreaming.readers;

import com.chatoverlaystreaming.emotes.YouTubeEmojiCache;
import com.chatoverlaystreaming.model.ChatMessage;
import com.chatoverlaystreaming.model.EmoteToken;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.*;
import java.util.List;
import java.util.concurrent.BlockingQueue;

public class YouTubeChatReader implements Runnable {

    private static final long RETRY_INTERVAL = 300000; // 5 minutos

    private final String channelId;
    private final String configVideoId;
    private final String apiKey;
    private final BlockingQueue<ChatMessage> queue;
    private final YouTubeEmojiCache youtubeEmojiCache = new YouTubeEmojiCache();
    private YouTube youtube;

    public YouTubeChatReader(String channelId, String videoId, String apiKey,
                             BlockingQueue<ChatMessage> queue) {
        this.channelId     = channelId;
        this.configVideoId = videoId;
        this.apiKey        = apiKey;
        this.queue         = queue;
    }

    @Override
    public void run() {
        youtube = new YouTube.Builder(
                new NetHttpTransport(),
                JacksonFactory.getDefaultInstance(),
                request -> {})
                .setApplicationName("ChatOverlay")
                .build();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                String liveChatId = resolveLiveChatId();

                if (liveChatId == null) {
                    // Ninguna opción funcionó, avisar en el chat y esperar
                    postSystemMessage("YouTube: no se encontró ningún directo activo. " +
                                      "Reintentando en 5 minutos...");
                    sleep(RETRY_INTERVAL);
                    continue;
                }

                readChat(liveChatId);

            } catch (Exception e) {
                System.err.println("[YouTube] Error inesperado: " + e.getMessage());
                postSystemMessage("YouTube: error de conexión. Reintentando en 5 minutos...");
                sleep(RETRY_INTERVAL);
            }
        }
    }

    /**
     * Intenta resolver el liveChatId siguiendo esta prioridad:
     * 1. videoId del config, si existe y tiene chat activo
     * 2. Búsqueda del directo activo en el canal
     * Devuelve null si ninguna opción funciona.
     */
    private String resolveLiveChatId() {
        // Paso 1: intentar con el videoId del config
        System.err.println("[YouTube] Resolviendo el liveChatid");
        if (configVideoId != null && !configVideoId.isBlank()) {
            String chatId = getLiveChatId(configVideoId);
            if (chatId != null) {
                System.out.println("[YouTube] Conectado usando videoId del config.");
                return chatId;
            }
            System.out.println("[YouTube] videoId del config no tiene chat activo, buscando directo...");
        }

        // Paso 2: buscar directo activo en el canal
        if (channelId != null && !channelId.isBlank()) {
            String foundVideoId = searchLiveVideo();
            if (foundVideoId != null) {
                String chatId = getLiveChatId(foundVideoId);
                if (chatId != null) {
                    System.out.println("[YouTube] Directo encontrado: " + foundVideoId);
                    return chatId;
                }
            }
        }

        return null;
    }

    /**
     * Dado un videoId, devuelve su liveChatId si tiene chat activo.
     * Devuelve null si el vídeo no existe, no está en directo o no tiene chat.
     */
    private String getLiveChatId(String videoId) {
        try {
            VideoListResponse response = youtube.videos()
                    .list(List.of("liveStreamingDetails"))
                    .setId(List.of(videoId))
                    .setKey(apiKey)
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

        } catch (Exception e) {
            System.err.println("[YouTube] Error consultando videoId " + videoId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Busca el directo activo del canal y devuelve su videoId.
     * Devuelve null si no hay ningún directo activo.
     */
    private String searchLiveVideo() {
        try {
            SearchListResponse response = youtube.search()
                    .list(List.of("id"))
                    .setChannelId(channelId)
                    .setEventType("live")
                    .setType(List.of("video"))
                    .setKey(apiKey)
                    .execute();

            if (response.getItems().isEmpty()) {
                System.err.println("[YouTube] No hay directo activo en el canal.");
                return null;
            }

            return response.getItems().get(0).getId().getVideoId();

        } catch (Exception e) {
            System.err.println("[YouTube] Error buscando directo: " + e.getMessage());
            return null;
        }
    }

    /**
     * Lee el chat en bucle hasta que se pierda la conexión o el directo termine.
     */
    private void readChat(String liveChatId) throws Exception {
        System.out.println("[YouTube] Leyendo chat: " + liveChatId);
        String pageToken = null;

        while (!Thread.currentThread().isInterrupted()) {
            LiveChatMessageListResponse chatResponse = youtube.liveChatMessages()
                    .list(liveChatId, List.of("snippet", "authorDetails"))
                    .setKey(apiKey)
                    .setPageToken(pageToken)
                    .setMaxResults(200L)
                    .execute();

            for (LiveChatMessage item : chatResponse.getItems()) {
                if (!"textMessageEvent".equals(item.getSnippet().getType())) {
                    continue;
                }
                String user = item.getAuthorDetails().getDisplayName();
                String text = item.getSnippet().getTextMessageDetails().getMessageText();
                List<EmoteToken> tokens = youtubeEmojiCache.tokenize(item);
                queue.put(new ChatMessage("youtube", user, text, tokens));
            }

            pageToken = chatResponse.getNextPageToken();
            sleep(chatResponse.getPollingIntervalMillis());
        }
    }

    /**
     * Inserta un mensaje de sistema en el chat del overlay.
     */
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
}