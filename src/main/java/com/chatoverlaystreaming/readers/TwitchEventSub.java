package com.chatoverlaystreaming.readers;

import com.chatoverlaystreaming.model.ChatMessage;
import org.json.JSONArray;
import org.json.JSONObject;

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

/**
 * Gestiona la conexión WebSocket con el EventSub de Twitch y la API de recompensas.
 *
 * Responsabilidades:
 *   1. Mantener una conexión WebSocket persistente con el EventSub de Twitch.
 *   2. Suscribirse al evento de canje de recompensas al recibir session_welcome.
 *   3. Encolar ChatMessages cuando llegan notificaciones de recompensas.
 *   4. Gestionar recompensas: listar, crear, actualizar, borrar y actualizar redenciones.
 *
 * La conexión se reconecta automáticamente cada 10 segundos si cae.
 * Si Twitch envía session_reconnect, aborta el WebSocket actual para forzar
 * la reconexión (el bucle de run() inicia una nueva conexión).
 *
 * Formato de eventExtra en ChatMessage de tipo "reward":
 *   "titulo|rewardId|redemptionId"
 *   ChatOverlay lo parsea para mostrar el título y EmoteRenderer para los botones.
 */
public class TwitchEventSub implements Runnable {

    // ── Constantes ────────────────────────────────────────────────────────────

    private static final String WS_URL          = "wss://eventsub.wss.twitch.tv/ws";
    private static final String SUBSCRIBE_URL   = "https://api.twitch.tv/helix/eventsub/subscriptions";
    private static final String REDEMPTIONS_URL = "https://api.twitch.tv/helix/channel_points/custom_rewards/redemptions";
    private static final String REWARDS_URL     = "https://api.twitch.tv/helix/channel_points/custom_rewards";

    private static final String REDEMPTION_EVENT =
            "channel.channel_points_custom_reward_redemption.add";

    // ── Estado ────────────────────────────────────────────────────────────────

    private final String                  accessToken;
    private final String                  clientId;
    private final String                  broadcasterId;
    private final BlockingQueue<ChatMessage> queue;

    /** Caché de recompensas gestionadas por esta app. Se actualiza con refreshRewards(). */
    private final List<JSONObject> appRewards = new ArrayList<>();

    private volatile boolean running = true;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param accessToken  Token OAuth del usuario con scopes de recompensas.
     * @param clientId     Client ID de la aplicación.
     * @param broadcasterId ID numérico del canal de Twitch.
     * @param queue        Cola compartida donde encolar las notificaciones.
     */
    public TwitchEventSub(String accessToken, String clientId,
                          String broadcasterId, BlockingQueue<ChatMessage> queue) {
        this.accessToken   = accessToken;
        this.clientId      = clientId;
        this.broadcasterId = broadcasterId;
        this.queue         = queue;
    }

    // ── Runnable ──────────────────────────────────────────────────────────────

    /**
     * Bucle principal con reconexión automática.
     * Se detiene si el hilo es interrumpido o si se llama a {@link #stop()}.
     */
    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                connect();
            } catch (Exception e) {
                System.err.println("[EventSub] Error: " + e.getMessage()
                        + " — reconectando en 10s");
                sleep(10000);
            }
        }
    }

    /** Detiene el bucle de reconexión. */
    public void stop() {
        running = false;
    }

    // ── Conexión WebSocket ────────────────────────────────────────────────────

    /**
     * Establece la conexión WebSocket y espera hasta que se cierre.
     * Los mensajes se procesan en {@link #handleMessage}.
     */
    private void connect() throws Exception {
        CountDownLatch latch         = new CountDownLatch(1);
        StringBuilder  messageBuffer = new StringBuilder();

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
                        System.err.println("[EventSub] Conexión cerrada: "
                                + statusCode + " " + reason);
                        latch.countDown();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        System.err.println("[EventSub] Error WebSocket: "
                                + error.getMessage());
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
        latch.await();
        ws.abort();
    }

    // ── Procesado de mensajes ─────────────────────────────────────────────────

    /**
     * Procesa un mensaje WebSocket del EventSub.
     * Delega en handlers específicos según el message_type.
     */
    private void handleMessage(String raw, WebSocket ws) {
        try {
            JSONObject msg      = new JSONObject(raw);
            JSONObject metadata = msg.getJSONObject("metadata");
            String messageType  = metadata.getString("message_type");

            switch (messageType) {
                case "session_welcome"  -> onSessionWelcome(msg);
                case "session_keepalive"-> { /* keepalive, no action needed */ }
                case "session_reconnect"-> onSessionReconnect(msg, ws);
                case "notification"     -> handleNotification(msg.getJSONObject("payload"));
                case "revocation"       -> System.err.println("[EventSub] Suscripción revocada.");
            }
        } catch (Exception e) {
            System.err.println("[EventSub] Error procesando mensaje: " + e.getMessage());
        }
    }

    /** Al recibir session_welcome, extrae el session_id y se suscribe. */
    private void onSessionWelcome(JSONObject msg) throws Exception {
        String sessionId = msg.getJSONObject("payload")
                              .getJSONObject("session")
                              .getString("id");
        System.out.println("[EventSub] Sesión iniciada: " + sessionId);
        subscribe(sessionId);
    }

    /** Al recibir session_reconnect, aborta para que el bucle reconecte a la nueva URL. */
    private void onSessionReconnect(JSONObject msg, WebSocket ws) {
        String reconnectUrl = msg.getJSONObject("payload")
                                 .getJSONObject("session")
                                 .getString("reconnect_url");
        System.out.println("[EventSub] Reconectando a: " + reconnectUrl);
        ws.abort();
    }

    /**
     * Suscribe al evento de canje de recompensas del canal.
     *
     * @param sessionId ID de la sesión WebSocket activa.
     */
    private void subscribe(String sessionId) throws Exception {
        JSONObject body = new JSONObject()
                .put("type",    REDEMPTION_EVENT)
                .put("version", "1")
                .put("condition",  new JSONObject()
                        .put("broadcaster_user_id", broadcasterId))
                .put("transport", new JSONObject()
                        .put("method",     "websocket")
                        .put("session_id", sessionId));

        HttpResponse<String> response =
                httpPost(SUBSCRIBE_URL, body.toString());

        if (response.statusCode() == 202) {
            System.out.println("[EventSub] Suscrito a recompensas de canal.");
        } else {
            System.err.println("[EventSub] Error al suscribirse: "
                    + response.statusCode() + " " + response.body());
        }
    }

    /**
     * Procesa una notificación del EventSub.
     * Solo gestiona el evento de canje de recompensas.
     */
    private void handleNotification(JSONObject payload) throws Exception {
        String type = payload.getJSONObject("subscription").getString("type");
        if (!REDEMPTION_EVENT.equals(type)) return;

        JSONObject event       = payload.getJSONObject("event");
        String     userName    = event.getString("user_name");
        String     rewardTitle = event.getJSONObject("reward").getString("title");
        String     rewardId    = event.getJSONObject("reward").getString("id");
        String     redemptionId = event.getString("id");
        String     userInput   = event.optString("user_input", "").trim();

        // eventExtra = "titulo|rewardId|redemptionId" para que ChatOverlay pueda
        // mostrar el título y EmoteRenderer añadir los botones de aprobar/rechazar
        String eventExtra = rewardTitle + "|" + rewardId + "|" + redemptionId;
        String text       = userInput.isBlank() ? "" : userInput;

        queue.put(new ChatMessage("twitch", userName, text,
                null, null, null, "reward", eventExtra, null, null, null));
    }

    // ── Gestión de redenciones ────────────────────────────────────────────────

    /**
     * Actualiza el estado de una redención de recompensa en Twitch.
     * Se ejecuta en un hilo virtual para no bloquear el EDT.
     *
     * @param rewardId     ID de la recompensa.
     * @param redemptionId ID de la redención concreta.
     * @param fulfill      true = FULFILLED (completar), false = CANCELED (devolver puntos).
     */
    public void updateRedemption(String rewardId, String redemptionId, boolean fulfill) {
        Thread.ofVirtual().name("redemption-update").start(() -> {
            try {
                String status = fulfill ? "FULFILLED" : "CANCELED";
                String url    = REDEMPTIONS_URL
                        + "?broadcaster_id=" + broadcasterId
                        + "&reward_id="       + rewardId
                        + "&id="              + redemptionId;

                HttpResponse<String> response = httpPatch(url,
                        new JSONObject().put("status", status).toString());

                if (response.statusCode() == 200) {
                    System.out.println("[EventSub] Redención "
                            + status.toLowerCase() + ": " + redemptionId);
                } else {
                    System.err.println("[EventSub] Error actualizando redención: "
                            + response.statusCode() + " " + response.body());
                }
            } catch (Exception e) {
                System.err.println("[EventSub] Error actualizando redención: "
                        + e.getMessage());
            }
        });
    }

    // ── CRUD de recompensas ───────────────────────────────────────────────────

    /**
     * Refresca la lista de recompensas gestionadas por esta app desde la API
     * y devuelve una copia para que la UI no toque la lista interna.
     *
     * @return Copia de la lista de recompensas actualizada.
     */
    public List<JSONObject> refreshRewards() throws Exception {
        String url = REWARDS_URL
                + "?broadcaster_id=" + broadcasterId
                + "&only_manageable_rewards=true";

        HttpResponse<String> response = httpGet(url);
        if (response.statusCode() != 200) {
            throw new Exception("Error obteniendo recompensas: "
                    + response.statusCode() + " " + response.body());
        }

        appRewards.clear();
        JSONArray data = new JSONObject(response.body()).getJSONArray("data");
        for (int i = 0; i < data.length(); i++) {
            appRewards.add(data.getJSONObject(i));
        }

        return new ArrayList<>(appRewards);
    }

    /** Devuelve la lista de recompensas cacheada sin hacer petición a la API. */
    public List<JSONObject> getListRewards() {
        return appRewards;
    }

    /**
     * Comprueba si una recompensa está en la lista de recompensas de esta app.
     *
     * @param rewardId ID de la recompensa a comprobar.
     * @return true si la recompensa fue creada por esta app.
     */
    public boolean isRewardOwnedByApp(String rewardId) {
        return appRewards.stream()
                .anyMatch(r -> rewardId.equals(r.optString("id", "")));
    }

    /** Variante que acepta el JSONObject completo de la recompensa. */
    public boolean isRewardOwnedByApp(JSONObject reward) {
        return isRewardOwnedByApp(reward.optString("id", ""));
    }

    /**
     * Crea una nueva recompensa de canal en Twitch.
     *
     * @return JSONObject con los datos de la recompensa creada.
     * @throws Exception Si Twitch devuelve error.
     */
    public JSONObject createReward(String title, String prompt, int cost,
                                    boolean userInputRequired, boolean skipQueue,
                                    String backgroundColor,
                                    boolean globalCooldownEnabled,
                                    int globalCooldownSeconds,
                                    boolean enabled) throws Exception {
        JSONObject body = buildRewardBody(title, prompt, cost, userInputRequired,
                skipQueue, backgroundColor, globalCooldownEnabled,
                globalCooldownSeconds, enabled);

        HttpResponse<String> response = httpPost(
                REWARDS_URL + "?broadcaster_id=" + broadcasterId, body.toString());

        if (response.statusCode() != 200) {
            throw new Exception("Error creando recompensa: "
                    + response.statusCode() + " " + response.body());
        }
        return new JSONObject(response.body()).getJSONArray("data").getJSONObject(0);
    }

    /**
     * Actualiza una recompensa existente en Twitch.
     *
     * @param rewardId ID de la recompensa a actualizar.
     * @return JSONObject con los datos de la recompensa actualizada.
     * @throws Exception Si Twitch devuelve error.
     */
    public JSONObject updateReward(String rewardId, String title, String prompt,
                                    int cost, boolean userInputRequired,
                                    boolean skipQueue, String backgroundColor,
                                    boolean globalCooldownEnabled,
                                    int globalCooldownSeconds,
                                    boolean enabled) throws Exception {
        JSONObject body = buildRewardBody(title, prompt, cost, userInputRequired,
                skipQueue, backgroundColor, globalCooldownEnabled,
                globalCooldownSeconds, enabled);

        String url = REWARDS_URL + "?broadcaster_id=" + broadcasterId
                   + "&id=" + rewardId;

        HttpResponse<String> response = httpPatch(url, body.toString());
        if (response.statusCode() != 200) {
            throw new Exception("Error actualizando recompensa: "
                    + response.statusCode() + " " + response.body());
        }
        return new JSONObject(response.body()).getJSONArray("data").getJSONObject(0);
    }

    /**
     * Borra una recompensa de canal de Twitch.
     *
     * @param rewardId ID de la recompensa a borrar.
     * @throws Exception Si Twitch devuelve error.
     */
    public void deleteReward(String rewardId) throws Exception {
        String url = REWARDS_URL + "?broadcaster_id=" + broadcasterId
                   + "&id=" + rewardId;

        HttpResponse<String> response = httpDelete(url);
        if (response.statusCode() != 204) {
            throw new Exception("Error borrando recompensa: "
                    + response.statusCode() + " " + response.body());
        }
    }

    /**
     * Construye el JSONObject del cuerpo para crear o actualizar una recompensa.
     * Extrae la lógica común de createReward y updateReward.
     */
    private JSONObject buildRewardBody(String title, String prompt, int cost,
                                        boolean userInputRequired, boolean skipQueue,
                                        String backgroundColor,
                                        boolean globalCooldownEnabled,
                                        int globalCooldownSeconds,
                                        boolean enabled) {
        JSONObject body = new JSONObject()
                .put("title",                                    title)
                .put("prompt",                                   prompt)
                .put("cost",                                     cost)
                .put("is_user_input_required",                   userInputRequired)
                .put("should_redemptions_skip_request_queue",    skipQueue)
                .put("is_enabled",                               enabled)
                .put("is_global_cooldown_enabled",               globalCooldownEnabled);

        if (backgroundColor != null && !backgroundColor.isBlank()) {
            body.put("background_color", backgroundColor);
        }
        if (globalCooldownEnabled) {
            body.put("global_cooldown_seconds", globalCooldownSeconds);
        }
        return body;
    }

    // ── Utilidades HTTP ───────────────────────────────────────────────────────

    /** Cliente HTTP compartido para todas las peticiones a la API de Twitch. */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", clientId);
    }

    private HttpResponse<String> httpGet(String url) throws Exception {
        return httpClient.send(
                baseRequest(url).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpPost(String url, String body) throws Exception {
        return httpClient.send(
                baseRequest(url)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpPatch(String url, String body) throws Exception {
        return httpClient.send(
                baseRequest(url)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(
                                body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpDelete(String url) throws Exception {
        return httpClient.send(
                baseRequest(url).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}