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

/**
 * Lee en tiempo real el chat de una retransmisión en directo de YouTube
 * e introduce los mensajes en una {@link BlockingQueue} compartida.
 *
 * <p>Ciclo de vida principal (ejecutado en su propio hilo):
 * <ol>
 *   <li>Resuelve el {@code liveChatId} a partir del videoId configurado
 *       o, si no hay videoId, buscando el directo activo del canal.</li>
 *   <li>Entra en el bucle de polling: pide mensajes nuevos a la API de
 *       YouTube con el intervalo que la propia API indica, y los encola.</li>
 *   <li>Si una API key supera la quota, rota a la siguiente. Si se agotan
 *       todas, espera hasta la medianoche hora del Pacífico (PT), momento
 *       en que YouTube resetea las quotas.</li>
 *   <li>Si el directo termina, busca uno nuevo cada {@value #RETRY_INTERVAL} ms.</li>
 *   <li>Si no hay ningún directo activo en la primera conexión, invoca el
 *       callback {@code onFatalError} y termina el hilo.</li>
 * </ol>
 *
 * <p>Notas de implementación:
 * <ul>
 *   <li>El cliente HTTP manual ({@link #fetch}) se usa para el polling de mensajes
 *       en lugar del SDK de Google, por mayor control sobre timeouts y errores.</li>
 *   <li>El SDK oficial de Google se usa para las llamadas de resolución y búsqueda
 *       de directos ({@code videos.list} y {@code search.list}).</li>
 *   <li>El pageToken se persiste en {@link Config} tras cada petición exitosa,
 *       de modo que si la aplicación se reinicia con el mismo directo activo,
 *       se reanuda sin reprocesar el histórico de mensajes.</li>
 * </ul>
 */
public class YouTubeChatReader implements Runnable {

    // ── Constantes ────────────────────────────────────────────────────────────

    /** Tiempo de espera entre reintentos cuando no hay directo o se pierde la conexión. */
    private static final long RETRY_INTERVAL = 180_000; // 3 minutos

    // ── Estado del lector ─────────────────────────────────────────────────────

    /** Intervalo mínimo de polling en milisegundos, leído de la config. */
    private long minPollingInterval;

    /**
     * Marca de tiempo del momento en que arrancó el lector.
     * Se usa para descartar mensajes del histórico anteriores al inicio.
     */
    private final long startTime = System.currentTimeMillis();

    // ── Dependencias ──────────────────────────────────────────────────────────

    /** ID del canal de YouTube (para buscar directos automáticamente). */
    private final String channelId;

    /** VideoId fijo leído del config. Si está presente, se intenta primero. */
    private final String configVideoId;

    /** Lista de API keys a rotar cuando se supera la quota. */
    private final List<String> apiKeys;

    /** Configuración de la aplicación. Se puede leer y escribir en tiempo de ejecución. */
    private final Config config;

    /** Cola compartida en la que se depositan los mensajes leídos. */
    private final BlockingQueue<ChatMessage> queue;

    /** Cache de emojis/emotes de YouTube para parsear los tokens de cada mensaje. */
    private final YouTubeEmojiCache youtubeEmojiCache;

    // ── Estado de la API ──────────────────────────────────────────────────────

    /** Cliente del SDK de YouTube, inicializado al arrancar el hilo. */
    private YouTube youtube;

    /** Índice de la API key activa dentro de {@link #apiKeys}. */
    private int currentKeyIndex = 0;

    /** {@code true} cuando todas las API keys han superado su quota diaria. */
    private boolean quotaExceeded = false;

    /** VideoId del directo actualmente conectado (resuelto en tiempo de ejecución). */
    private String resolvedVideoId = null;

    // ── Callbacks y estado de conexión ────────────────────────────────────────

    /**
     * Callback invocado si la primera conexión falla de forma irrecuperable
     * (no hay directo, error de red, etc.). Recibe el mensaje de error.
     */
    private java.util.function.Consumer<String> onFatalError;

    /**
     * {@code true} una vez que se establece la primera conexión exitosa.
     * Controla si un fallo es fatal (antes de la primera conexión) o recuperable.
     */
    private boolean initialConnectionDone = false;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Crea un nuevo lector de chat de YouTube.
     *
     * @param channelId  ID del canal de YouTube. Si se proporciona, se usa para
     *                   buscar el directo activo cuando no hay videoId configurado.
     * @param videoId    VideoId fijo leído del config. Puede ser nulo o vacío.
     * @param apiKeys    Lista de API keys a rotar si se supera la quota.
     * @param queue      Cola en la que se depositan los mensajes leídos.
     * @param config     Configuración de la aplicación (lectura y persistencia).
     * @param imageCache Cache de imágenes compartida, usada por {@link YouTubeEmojiCache}.
     */
    public YouTubeChatReader(String channelId, String videoId, List<String> apiKeys,
                             BlockingQueue<ChatMessage> queue, Config config,
                             ImageCache imageCache) {
        this.channelId          = channelId;
        this.configVideoId      = videoId;
        this.apiKeys            = apiKeys;
        this.config             = config;
        this.queue              = queue;
        this.youtubeEmojiCache  = new YouTubeEmojiCache(imageCache);
        this.minPollingInterval = config.getMinPollingInterval() * 1000L;
    }

    // ── API key ───────────────────────────────────────────────────────────────

    /** Devuelve la API key actualmente activa. */
    private String currentApiKey() {
        return apiKeys.get(currentKeyIndex);
    }

    /**
     * Rota a la siguiente API key disponible.
     *
     * <p>Si no quedan más keys, marca {@link #quotaExceeded} como {@code true}
     * y publica un mensaje de sistema en la cola.
     *
     * @return {@code true} si hay una key disponible; {@code false} si se agotaron todas.
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
        return true;
    }

    // ── Hilo principal ────────────────────────────────────────────────────────

    /**
     * Bucle principal del lector. Se ejecuta en su propio hilo hasta que
     * el hilo es interrumpido o se produce un error fatal en la primera conexión.
     *
     * <p>Estados del bucle:
     * <ul>
     *   <li><b>Quota excedida:</b> espera hasta medianoche PT y resetea el estado.</li>
     *   <li><b>Sin liveChatId:</b> intenta resolver el directo activo.</li>
     *   <li><b>Con liveChatId:</b> entra en el bucle de polling de mensajes.</li>
     * </ul>
     */
    @Override
    public void run() {
        // Inicializar el cliente del SDK de YouTube
        youtube = new YouTube.Builder(
                new NetHttpTransport(),
                JacksonFactory.getDefaultInstance(),
                request -> {})
                .setApplicationName("ChatOverlay")
                .build();

        String liveChatId = null;

        while (!Thread.currentThread().isInterrupted()) {

            // ── Espera por reset de quota ─────────────────────────────────────
            if (quotaExceeded) {
                long waitTime = millisUntilMidnightPT();
                System.err.println("[YouTube] Quota excedida. Esperando " +
                                   (waitTime / 60_000) + " minutos hasta reset.");
                sleep(waitTime);
                currentKeyIndex = 0;
                quotaExceeded   = false;
                liveChatId      = null;
                postSystemMessage("YouTube: reiniciando tras reset de quota.");
                continue;
            }

            try {
                // ── Resolver directo activo ───────────────────────────────────
                if (liveChatId == null) {
                    liveChatId = resolveLiveChatId();

                    if (liveChatId == null) {
                        if (!initialConnectionDone) {
                            // Primera conexión sin directo activo → error fatal, terminar hilo
                            String msg = "YouTube: no se encontró ningún directo activo.";
                            if (onFatalError != null) onFatalError.accept(msg);
                            return;
                        }
                        // Tras una primera conexión exitosa → reintentar periódicamente
                        postSystemMessage("YouTube: no se encontró ningún directo activo. " +
                                          "Reintentando en 3 minutos...");
                        sleep(RETRY_INTERVAL);
                        continue;
                    }
                    initialConnectionDone = true;
                }

                // ── Polling de mensajes ───────────────────────────────────────
                readChat(liveChatId);

            } catch (QuotaExceededException e) {
                if (!rotateApiKey()) {
                    // Sin más keys disponibles; el bucle esperará al reset de quota
                    continue;
                }
                // Reintentar con la nueva key y el mismo liveChatId

            } catch (ChatEndedException e) {
                System.err.println("[YouTube] El directo terminó, buscando nuevo directo...");
                liveChatId = null;
                sleep(RETRY_INTERVAL);

            } catch (Exception e) {
                System.err.println("[YouTube] Error de conexión: " + e.getMessage());
                if (!initialConnectionDone) {
                    // Error en la primera conexión → fatal
                    String msg = "YouTube: error de conexión — " + e.getMessage();
                    postSystemMessage(msg);
                    if (onFatalError != null) onFatalError.accept(msg);
                    return;
                }
                postSystemMessage("YouTube: error de conexión. Reintentando en 3 minutos...");
                sleep(RETRY_INTERVAL);
            }
        }
    }

    // ── Resolución del directo ────────────────────────────────────────────────

    /**
     * Determina el {@code liveChatId} del directo activo siguiendo esta prioridad:
     * <ol>
     *   <li>Si hay un {@code videoId} fijo en el config, lo consulta primero.</li>
     *   <li>Si no tiene chat activo (o no hay videoId), busca el directo del canal.</li>
     * </ol>
     *
     * <p>Si se encuentra un directo mediante búsqueda, persiste su videoId en el config.
     *
     * @return El {@code liveChatId} del directo activo, o {@code null} si no se encontró.
     * @throws QuotaExceededException Si la API key activa ha superado su quota.
     */
    private String resolveLiveChatId() throws QuotaExceededException {
        System.err.println("[YouTube] Resolviendo liveChatId...");

        // Intentar primero con el videoId del config
        if (configVideoId != null && !configVideoId.isBlank()) {
            String chatId = getLiveChatId(configVideoId);
            if (chatId != null) {
                System.err.println("[YouTube] Conectado usando videoId del config.");
                resolvedVideoId = configVideoId;
                return chatId;
            }
            System.err.println("[YouTube] videoId del config no tiene chat activo, buscando directo...");
        }

        // Buscar el directo activo del canal
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
                        System.err.println("[Config] Error guardando videoId: " + ex.getMessage());
                    }
                    return chatId;
                }
            }
        }

        return null;
    }

    /**
     * Consulta la API de YouTube para obtener el {@code liveChatId} de un vídeo concreto.
     *
     * @param videoId ID del vídeo a consultar.
     * @return El {@code liveChatId} si el vídeo está en directo, o {@code null} si no.
     * @throws QuotaExceededException Si la API key activa ha superado su quota.
     */
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

    /**
     * Busca el directo activo del canal mediante {@code search.list}.
     *
     * @return El {@code videoId} del primer directo encontrado, o {@code null} si no hay ninguno.
     * @throws QuotaExceededException Si la API key activa ha superado su quota.
     */
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

    // ── Polling de mensajes ───────────────────────────────────────────────────

    /**
     * Bucle de polling de mensajes para un {@code liveChatId} activo.
     *
     * <p>Antes de entrar en el bucle, sincroniza el punto de lectura:
     * <ul>
     *   <li>Si hay un pageToken guardado en config para este mismo directo, lo reutiliza
     *       para reanudar desde donde se dejó sin reprocesar el histórico.</li>
     *   <li>Si no, hace una primera petición de un solo mensaje para obtener el pageToken
     *       actual y descartar los mensajes anteriores al inicio de la aplicación.</li>
     * </ul>
     *
     * <p>En cada iteración del bucle:
     * <ol>
     *   <li>Solicita hasta 200 mensajes nuevos con {@code part=snippet,authorDetails}.</li>
     *   <li>Persiste el pageToken en config para poder reanudar tras un reinicio.</li>
     *   <li>Filtra mensajes que no sean de tipo {@code textMessageEvent} o anteriores al inicio.</li>
     *   <li>Encola un {@link ChatMessage} por cada mensaje válido.</li>
     *   <li>Espera el intervalo de polling indicado por la API (mínimo {@link #minPollingInterval}).</li>
     * </ol>
     *
     * @param liveChatId ID del chat del directo activo.
     * @throws QuotaExceededException Si la API key activa ha superado su quota.
     * @throws ChatEndedException     Si el chat ha terminado (código 403/404 de la API).
     * @throws Exception              Para cualquier otro error de red o de la API.
     */
    private void readChat(String liveChatId) throws Exception {
        System.err.println("[YouTube] Leyendo chat: " + liveChatId);

        // ── Sincronización inicial del pageToken ──────────────────────────────
        String pageToken    = null;
        String lastVideoId  = config.getYoutubeLastVideoId();
        String lastToken    = config.getYoutubeLastPageToken();

        if (resolvedVideoId != null && resolvedVideoId.equals(lastVideoId) && lastToken != null) {
            // Mismo directo que la sesión anterior: reanudar desde el último pageToken guardado
            System.err.println("[YouTube] Usando pageToken guardado, saltando histórico.");
            pageToken = lastToken;
        } else {
            // Nuevo directo: pedir un solo mensaje para obtener el pageToken actual
            // y así descartar el histórico sin procesarlo
            String url = "https://www.googleapis.com/youtube/v3/liveChat/messages" +
                         "?liveChatId=" + liveChatId +
                         "&part=snippet" +
                         "&maxResults=1" +
                         "&key=" + currentApiKey();

            String rawJson           = fetch(url);
            org.json.JSONObject firstResponse = new org.json.JSONObject(rawJson);
            checkForErrors(firstResponse);
            pageToken = firstResponse.optString("nextPageToken", null);

            long pollingInterval = Math.max(minPollingInterval,
                                            firstResponse.getLong("pollingIntervalMillis"));
            sleep(pollingInterval);
            System.out.println("[YouTube] Sincronizado, empezando a leer mensajes nuevos.");
        }

        // ── Bucle de polling ──────────────────────────────────────────────────
        while (!Thread.currentThread().isInterrupted()) {
            String url = "https://www.googleapis.com/youtube/v3/liveChat/messages" +
                         "?liveChatId=" + liveChatId +
                         "&part=snippet,authorDetails" +
                         "&maxResults=200" +
                         "&key=" + currentApiKey() +
                         (pageToken != null ? "&pageToken=" + pageToken : "");

            String rawJson                    = fetch(url);
            org.json.JSONObject response      = new org.json.JSONObject(rawJson);
            checkForErrors(response);

            long pollingInterval = Math.max(minPollingInterval,
                                            response.getLong("pollingIntervalMillis"));
            pageToken = response.optString("nextPageToken", null);

            // Persistir el pageToken para reanudar tras un posible reinicio
            if (pageToken != null && resolvedVideoId != null) {
                try {
                    config.saveYoutubePageToken(resolvedVideoId, pageToken);
                } catch (IOException e) {
                    System.err.println("[Config] Error guardando pageToken: " + e.getMessage());
                }
            }

            // Procesar y encolar los mensajes recibidos
            org.json.JSONArray items = response.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    org.json.JSONObject item    = items.getJSONObject(i);
                    org.json.JSONObject snippet = item.getJSONObject("snippet");

                    // Solo procesar mensajes de texto
                    if (!"textMessageEvent".equals(snippet.getString("type"))) continue;

                    // Descartar mensajes anteriores al inicio de la aplicación
                    String publishedAt = snippet.optString("publishedAt", null);
                    if (publishedAt != null) {
                        long msgTime = java.time.Instant.parse(publishedAt).toEpochMilli();
                        if (msgTime < startTime) continue;
                    }

                    String user              = item.getJSONObject("authorDetails")
                                                   .getString("displayName");
                    List<EmoteToken> tokens  = youtubeEmojiCache.tokenize(snippet);
                    String text              = snippet.getJSONObject("textMessageDetails")
                                                      .getString("messageText");

                    queue.put(new ChatMessage("youtube", user, text, tokens));
                }
            }

            sleep(pollingInterval);
        }
    }

    // ── Utilidades de API ─────────────────────────────────────────────────────

    /**
     * Comprueba si una respuesta JSON de la API contiene un error y lanza
     * la excepción correspondiente según el código HTTP.
     *
     * @param response Objeto JSON devuelto por la API de YouTube.
     * @throws QuotaExceededException Si el error es de quota (código 403 + "quota").
     * @throws ChatEndedException     Si el error indica que el chat ya no existe (403/404).
     * @throws Exception              Para cualquier otro error de la API.
     */
    private void checkForErrors(org.json.JSONObject response) throws Exception {
        if (!response.has("error")) return;

        org.json.JSONObject error = response.getJSONObject("error");
        int    code    = error.getInt("code");
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
     * Calcula los milisegundos que faltan hasta la medianoche en hora del Pacífico (PT).
     * La quota de YouTube se resetea a medianoche PT, por lo que este valor se usa
     * para saber cuánto tiempo esperar antes de reanudar las peticiones.
     *
     * @return Milisegundos hasta la próxima medianoche PT.
     */
    private long millisUntilMidnightPT() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(
                java.time.ZoneId.of("America/Los_Angeles"));
        java.time.ZonedDateTime midnight = now.toLocalDate()
                .plusDays(1)
                .atStartOfDay(java.time.ZoneId.of("America/Los_Angeles"));
        return java.time.Duration.between(now, midnight).toMillis();
    }

    /**
     * Realiza una petición HTTP GET a la URL indicada y devuelve el cuerpo como {@code String}.
     *
     * <p>Se usa en lugar del SDK de Google para el polling de mensajes, lo que permite
     * controlar directamente los timeouts de conexión y lectura.
     *
     * @param url URL completa de la petición, incluyendo parámetros de query.
     * @return Cuerpo de la respuesta en UTF-8.
     * @throws Exception Si la conexión o la lectura fallan.
     */
    private String fetch(String url) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                java.net.URI.create(url).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "ChatOverlay/1.0");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        try (java.io.InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // ── Utilidades generales ──────────────────────────────────────────────────

    /**
     * Publica un mensaje de sistema en la cola del chat.
     * Se usa para informar al usuario de eventos relevantes (quota, errores, reinicios).
     *
     * @param text Texto del mensaje a mostrar en el overlay.
     */
    private void postSystemMessage(String text) {
        try {
            queue.put(new ChatMessage("youtube", "⚠ Sistema", text));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Pausa el hilo actual durante el tiempo indicado.
     * Restaura el flag de interrupción si el hilo es interrumpido durante la espera.
     *
     * @param ms Milisegundos a dormir.
     */
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Excepciones internas ──────────────────────────────────────────────────

    /**
     * Señala que la API key activa ha superado su quota diaria de YouTube.
     * Se captura en el bucle principal para rotar a la siguiente key disponible.
     */
    private static class QuotaExceededException extends RuntimeException {
        QuotaExceededException(String message) { super(message); }
    }

    /**
     * Señala que el chat del directo ha terminado (el directo acabó o el chat fue cerrado).
     * Se captura en el bucle principal para buscar un nuevo directo.
     */
    private static class ChatEndedException extends RuntimeException {
        ChatEndedException(String message) { super(message); }
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Registra el callback que se invocará si la primera conexión falla de forma irrecuperable.
     * Debe llamarse antes de arrancar el hilo.
     *
     * @param callback Consumidor que recibe el mensaje de error fatal.
     */
    public void setOnFatalError(java.util.function.Consumer<String> callback) {
        this.onFatalError = callback;
    }

    /**
     * Indica si la primera conexión exitosa ya se ha establecido.
     *
     * @return {@code true} si el lector se ha conectado al menos una vez con éxito.
     */
    public boolean isConnected() {
        return initialConnectionDone;
    }
}