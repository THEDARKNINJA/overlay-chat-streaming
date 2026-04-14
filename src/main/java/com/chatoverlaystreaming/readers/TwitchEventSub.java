package com.chatoverlaystreaming.readers;

import com.chatoverlaystreaming.model.ChatMessage;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

public class TwitchEventSub implements Runnable {

    private static final String WS_URL          = "wss://eventsub.wss.twitch.tv/ws";
    private static final String SUBSCRIBE_URL   = "https://api.twitch.tv/helix/eventsub/subscriptions";
    private static final String REDEMPTIONS_URL = "https://api.twitch.tv/helix/channel_points/custom_rewards/redemptions";
    private static final String APP_REWARDS_URL = "https://api.twitch.tv/helix/channel_points/custom_rewards";

    private final String accessToken;
    private final String clientId;
    private final String broadcasterId; // ID numérico del canal (channelId)
    private final BlockingQueue<ChatMessage> queue;

    private static List<JSONObject> appRewards = new ArrayList<>();

    private volatile boolean running = true;

    public TwitchEventSub(String accessToken, String clientId,
                          String broadcasterId, BlockingQueue<ChatMessage> queue) {
        this.accessToken  = accessToken;
        this.clientId     = clientId;
        this.broadcasterId = broadcasterId;
        this.queue        = queue;
        //retrieveListRewards();
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

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SUBSCRIBE_URL))
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", clientId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

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
        String rewardId      = event.getJSONObject("reward").getString("id");
        String redemptionId  = event.getString("id");

        String eventExtra = rewardTitle + "|" + rewardId + "|" + redemptionId;

        // Crear el ChatMessage de tipo reward con el nombre real de la recompensa
        ChatMessage msg;
        /* 
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
        */
       if (userInput.isBlank()) {
            msg = new ChatMessage("twitch", userName, "",
                    null, null, null,
                    "reward", eventExtra, null, null, null);
        } else {
            msg = new ChatMessage("twitch", userName, userInput,
                    null, null, null,
                    "reward", eventExtra, null, null, null);
        }

        queue.put(msg);
    }

    public void updateRedemption(String rewardId, String redemptionId, boolean fulfill) {
        Thread.ofVirtual().start(() -> {
            try {
                String status = fulfill ? "FULFILLED" : "CANCELED";
                String url = REDEMPTIONS_URL +
                        "?broadcaster_id=" + broadcasterId +
                        "&reward_id=" + rewardId +
                        "&id=" + redemptionId;

                JSONObject body = new JSONObject();
                body.put("status", status);

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Client-Id", clientId)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(
                                body.toString(), StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    System.out.println("[EventSub] Redención " + status.toLowerCase() + ": " + redemptionId);
                } else {
                    System.err.println("[EventSub] Error actualizando redención: "
                            + response.statusCode() + " " + response.body());
                }
            } catch (Exception e) {
                System.err.println("[EventSub] Error actualizando redención: " + e.getMessage());
            }
        });
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

    /*
    private void retrieveListRewards() {
        try{
            String url = APP_REWARDS_URL + "?broadcaster_id=" + broadcasterId + "&only_manageable_rewards=true";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Client-Id", clientId)
                    .build();

            HttpResponse<String> response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            JSONObject json = new JSONObject(response.body());
            appRewards.clear(); // limpiamos la lista antes de actualizar
            for (Object o : json.getJSONArray("data")) {
                JSONObject reward = (JSONObject) o;

                //String title   = reward.getString("title");
                String creator = reward.getString("creator_id");

                if (creator.equals(clientId)) {
                    // Guardar recompensa completa en la lista
                    appRewards.add(reward);
                }
            }
        } catch(Exception e) {
            System.err.println("[EventSub] Exception: "+ e.getMessage());
        }
    }
    */
    private void retrieveListRewards() {
        try {
            String url = APP_REWARDS_URL + "?broadcaster_id=" + broadcasterId + "&only_manageable_rewards=true";

            HttpResponse<String> response = httpGet(url);

            if (response.statusCode() != 200) {
                throw new Exception("Error obteniendo recompensas: "
                        + response.statusCode() + " " + response.body());
            }

            appRewards.clear(); // limpiamos la lista antes de actualizar
            JSONObject json = new JSONObject(response.body());
            org.json.JSONArray data = json.getJSONArray("data");
            for (int i = 0; i < data.length(); i++) {
                appRewards.add(data.getJSONObject(i));
            }
            
        } catch (Exception e) {
            System.err.println("[EventSub] Error recuperando lista de recompensas: " +e.getMessage() );
        }
    }

    public boolean isRewardOwnedByApp(JSONObject reward) {
        return isRewardOwnedByApp(reward.optString("id", ""));
    }

    public boolean isRewardOwnedByApp(String id_reward) {
        for (JSONObject rewardAux : appRewards) {
            String clientId = rewardAux.getString("id");
            if(id_reward.equals(clientId))
                return true;
        }
        return false;
    }

    public JSONObject createReward(String title, String prompt, int cost,
                                boolean userInputRequired,
                                boolean skipQueue,
                                String backgroundColor,
                                boolean globalCooldownEnabled,
                                int globalCooldownSeconds,
                                boolean enabled) throws Exception {
        JSONObject body = new JSONObject();
        body.put("title", title);
        body.put("prompt", prompt);
        body.put("cost", cost);
        body.put("is_user_input_required", userInputRequired);
        body.put("should_redemptions_skip_request_queue", skipQueue);
        body.put("is_enabled", enabled);
        if (backgroundColor != null && !backgroundColor.isBlank()) {
            body.put("background_color", backgroundColor);
        }
        body.put("is_global_cooldown_enabled", globalCooldownEnabled);
        if (globalCooldownEnabled) {
            body.put("global_cooldown_seconds", globalCooldownSeconds);
        }

        HttpResponse<String> response = httpPost(APP_REWARDS_URL +
                "?broadcaster_id=" + broadcasterId, body.toString());

        if (response.statusCode() != 200) {
            throw new Exception("Error creando recompensa: "
                    + response.statusCode() + " " + response.body());
        }

        return new JSONObject(response.body()).getJSONArray("data").getJSONObject(0);
    }

    public JSONObject updateReward(String rewardId, String title, String prompt,
                                    int cost, boolean userInputRequired,
                                    boolean skipQueue, String backgroundColor,
                                    boolean globalCooldownEnabled,
                                    int globalCooldownSeconds,
                                    boolean enabled) throws Exception {
        JSONObject body = new JSONObject();
        body.put("title", title);
        body.put("prompt", prompt);
        body.put("cost", cost);
        body.put("is_user_input_required", userInputRequired);
        body.put("should_redemptions_skip_request_queue", skipQueue);
        body.put("is_enabled", enabled);
        if (backgroundColor != null && !backgroundColor.isBlank()) {
            body.put("background_color", backgroundColor);
        }
        body.put("is_global_cooldown_enabled", globalCooldownEnabled);
        if (globalCooldownEnabled) {
            body.put("global_cooldown_seconds", globalCooldownSeconds);
        }

        String url = APP_REWARDS_URL + "?broadcaster_id=" + broadcasterId
                + "&id=" + rewardId;

        HttpResponse<String> response = httpPatch(url, body.toString());

        if (response.statusCode() != 200) {
            throw new Exception("Error actualizando recompensa: "
                    + response.statusCode() + " " + response.body());
        }

        return new JSONObject(response.body()).getJSONArray("data").getJSONObject(0);
    }

    public void deleteReward(String rewardId) throws Exception {
        String url = APP_REWARDS_URL + "?broadcaster_id=" + broadcasterId
                + "&id=" + rewardId;

        HttpResponse<String> response = httpDelete(url);

        if (response.statusCode() != 204) {
            throw new Exception("Error borrando recompensa: "
                    + response.statusCode() + " " + response.body());
        }
    }

    public List<JSONObject> getListRewards () {
        return appRewards;
    }

    // ── Utilidades HTTP ──────────────────────────────────────────────────────────

    private HttpResponse<String> httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", clientId)
                .GET()
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpPost(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", clientId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        body, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpPatch(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", clientId)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        body, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpDelete(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", clientId)
                .DELETE()
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    public List<JSONObject> refreshRewards() throws Exception {
        retrieveListRewards();
        // Devolver una copia para evitar que la UI toque la lista estática
        return new ArrayList<>(appRewards);
    }
}