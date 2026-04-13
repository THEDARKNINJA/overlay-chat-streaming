package com.chatoverlaystreaming.readers;

import com.chatoverlaystreaming.model.ChatMessage;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

public class TwitchEventSub implements Runnable {

    private static final String WS_URL       = "wss://eventsub.wss.twitch.tv/ws";
    private static final String SUBSCRIBE_URL = "https://api.twitch.tv/helix/eventsub/subscriptions";

    private final String accessToken;
    private final String clientId;
    private final String broadcasterId; // ID numérico del canal (channelId)
    private final BlockingQueue<ChatMessage> queue;

    private volatile boolean running = true;

    public TwitchEventSub(String accessToken, String clientId,
                          String broadcasterId, BlockingQueue<ChatMessage> queue) {
        this.accessToken  = accessToken;
        this.clientId     = clientId;
        this.broadcasterId = broadcasterId;
        this.queue        = queue;
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                connect();
            } catch (Exception e) {
                System.err.println("[EventSub] Error: " + e.getMessage() + " — reconectando en 10s");
                sleep(10000);
            }
        }
    }

    private void connect() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder messageBuffer = new StringBuilder();

        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(WS_URL), new WebSocket.Listener() {

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        messageBuffer.append(data);
                        if (last) {
                            handleMessage(messageBuffer.toString(), ws);
                            messageBuffer.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        System.err.println("[EventSub] Conexión cerrada: " + statusCode + " " + reason);
                        latch.countDown();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        System.err.println("[EventSub] Error WebSocket: " + error.getMessage());
                        latch.countDown();
                    }

                    @Override
                    public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
                        ws.sendPong(message);
                        ws.request(1);
                        return null;
                    }
                }).join();

        ws.request(1);
        latch.await(); // esperar hasta que se cierre la conexión
        ws.abort();
    }

    private void handleMessage(String raw, WebSocket ws) {
        try {
            JSONObject msg       = new JSONObject(raw);
            JSONObject metadata  = msg.getJSONObject("metadata");
            String messageType   = metadata.getString("message_type");

            switch (messageType) {
                case "session_welcome" -> {
                    // Obtener el session_id y suscribirse
                    String sessionId = msg.getJSONObject("payload")
                                          .getJSONObject("session")
                                          .getString("id");
                    System.out.println("[EventSub] Sesión iniciada: " + sessionId);
                    subscribe(sessionId);
                }
                case "session_keepalive" -> {
                    // Keepalive, no hacer nada
                }
                case "session_reconnect" -> {
                    // Twitch pide reconectar a otra URL
                    String reconnectUrl = msg.getJSONObject("payload")
                                             .getJSONObject("session")
                                             .getString("reconnect_url");
                    System.out.println("[EventSub] Reconectando a: " + reconnectUrl);
                    ws.abort();
                }
                case "notification" -> {
                    handleNotification(msg.getJSONObject("payload"));
                }
                case "revocation" -> {
                    System.err.println("[EventSub] Suscripción revocada.");
                }
            }
        } catch (Exception e) {
            System.err.println("[EventSub] Error procesando mensaje: " + e.getMessage());
        }
    }

    private void subscribe(String sessionId) throws Exception {
        // Suscribirse al evento de recompensas canjeadas
        JSONObject body = new JSONObject();
        body.put("type", "channel.channel_points_custom_reward_redemption.add");
        body.put("version", "1");

        JSONObject condition = new JSONObject();
        condition.put("broadcaster_user_id", broadcasterId);
        body.put("condition", condition);

        JSONObject transport = new JSONObject();
        transport.put("method", "websocket");
        transport.put("session_id", sessionId);
        body.put("transport", transport);

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(SUBSCRIBE_URL))
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", clientId)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        body.toString(), StandardCharsets.UTF_8))
                .build();

        java.net.http.HttpResponse<String> response =
                client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 202) {
            System.out.println("[EventSub] Suscrito a recompensas de canal.");
        } else {
            System.err.println("[EventSub] Error al suscribirse: " + response.statusCode()
                    + " " + response.body());
        }
    }

    private void handleNotification(JSONObject payload) throws Exception {
        String subscriptionType = payload.getJSONObject("subscription")
                                         .getString("type");

        if (!"channel.channel_points_custom_reward_redemption.add".equals(subscriptionType)) {
            return;
        }

        JSONObject event = payload.getJSONObject("event");

        String userName    = event.getString("user_name");
        String rewardTitle = event.getJSONObject("reward").getString("title");
        String userInput   = event.optString("user_input", "").trim();

        // Crear el ChatMessage de tipo reward con el nombre real de la recompensa
        ChatMessage msg;
        if (userInput.isBlank()) {
            msg = new ChatMessage("twitch", userName, "",
                    null, null, null,
                    "reward", rewardTitle, null, null, null);
        } else {
            // La recompensa tiene mensaje del usuario
            msg = new ChatMessage("twitch", userName, userInput,
                    null, null, null,
                    "reward", rewardTitle, null, null, null);
        }

        queue.put(msg);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        running = false;
    }
}