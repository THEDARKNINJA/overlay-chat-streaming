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

    private final String channelId;
    private String videoId;
    private final String apiKey;
    private final BlockingQueue<ChatMessage> queue;
    private final YouTubeEmojiCache youtubeEmojiCache = new YouTubeEmojiCache();

    public YouTubeChatReader(String channelId, String videoId, String apiKey,
                             BlockingQueue<ChatMessage> queue) {
        this.channelId = channelId;
        this.videoId = videoId;
        this.apiKey  = apiKey;
        this.queue   = queue;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                connect();
            } catch (Exception e) {
                System.err.println("[YouTube] Error: " + e.getMessage() + " — reconectando en 10s");
                sleep(10000);
            }
        }
    }

    private void connect() throws Exception {
        if(true)
            return;
        YouTube youtube = new YouTube.Builder(
                new NetHttpTransport(),
                JacksonFactory.getDefaultInstance(),
                request -> {})
                .setApplicationName("ChatOverlay")
                .build();

        // Obtener el liveChatId del vídeo
        SearchListResponse searchResponse = youtube.search()
        .list(List.of("id"))
        .setChannelId(channelId)
        .setEventType("live")
        .setType(List.of("video"))
        .setKey(apiKey)
        .execute();

        if (searchResponse.getItems().isEmpty()) {
            System.err.println("[YouTube] No se encuentra directo, utilizando el videoId definido previamente.");
            
        } else {
            videoId = searchResponse.getItems().get(0).getId().getVideoId();
        }

        VideoListResponse videoResponse = youtube.videos()
                .list(List.of("liveStreamingDetails"))
                .setId(List.of(videoId))
                .setKey(apiKey)
                .execute();

        if (videoResponse.getItems().isEmpty()) {
            System.err.println("[YouTube] Vídeo no encontrado: " + videoId);
            return;
        }

        String liveChatId = videoResponse.getItems().get(0)
                .getLiveStreamingDetails().getActiveLiveChatId();

        if (liveChatId == null) {
            System.err.println("[YouTube] El vídeo no tiene chat en directo activo.");
            return;
        }

        System.out.println("[YouTube] Conectado al chat: " + liveChatId);

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
                String text = item.getSnippet()
                        .getTextMessageDetails().getMessageText();

                // Procesar emojis aquí donde tenemos el objeto completo
                List<EmoteToken> tokens = youtubeEmojiCache.tokenize(item);

                queue.put(new ChatMessage("youtube", user, text, tokens));
            }

            pageToken = chatResponse.getNextPageToken();
            long pollingInterval = chatResponse.getPollingIntervalMillis();
            sleep(pollingInterval);
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