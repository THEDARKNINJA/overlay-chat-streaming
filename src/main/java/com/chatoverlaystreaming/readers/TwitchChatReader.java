package com.chatoverlaystreaming.readers;

import com.chatoverlaystreaming.model.ChatMessage;

import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lector del chat de Twitch via IRC sobre SSL.
 *
 * Se conecta al servidor IRC de Twitch (irc.chat.twitch.tv:6697) y escucha
 * los mensajes del canal configurado. Los convierte en {@link ChatMessage}
 * y los encola en la BlockingQueue compartida con ChatOverlay.
 *
 * Autenticación:
 *   - Con OAuth: usa el token y login del usuario autenticado. Permite recibir
 *     eventos de moderación (CLEARMSG, CLEARCHAT) y bits/cheers.
 *   - Sin OAuth (anónimo): usa justinfan + número aleatorio. Solo mensajes normales.
 *
 * Reconexión:
 *   Si la conexión cae por error de red, reintenta automáticamente cada 5 segundos.
 *   Si se detecta un error fatal (canal inválido, credenciales incorrectas),
 *   se detiene sin reconectar y notifica via {@link #setOnFatalError}.
 *
 * Detección de JOIN exitoso:
 *   Twitch envía RPL_NAMREPLY (353) cuando el JOIN se completa.
 *   Los NOTICEs antes del 353 indican errores fatales (canal suspendido, etc.).
 *   Al detectar el 353, se notifica via {@link #setOnConnected}.
 */
public class TwitchChatReader implements Runnable {

    // ── Constantes IRC ────────────────────────────────────────────────────────

    private static final String IRC_HOST = "irc.chat.twitch.tv";
    private static final int    IRC_PORT = 6697;

    /** Regex para detectar cheers: Cheer100, cheer50, PogCheer1000, etc. */
    private static final Pattern CHEER_PATTERN =
            Pattern.compile("(?i)\\b(\\w*cheer)(\\d+)\\b");

    /** NOTICEs fatales que indican que no tiene sentido reconectar. */
    private static final String[] FATAL_NOTICE_KEYWORDS = {
        "does not exist",
        "has been suspended",
        "authentication failed",
        "login unsuccessful",
        "improperly formatted",
        "error logging in"
    };

    // ── Estado ────────────────────────────────────────────────────────────────

    private final String                channel;
    private final BlockingQueue<ChatMessage> queue;
    private final String                oauthToken;
    private final String                twitchLogin;

    private volatile boolean  fatalError   = false;
    private Consumer<String>  onConnected;
    private Consumer<String>  onFatalError;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param channel      Nombre del canal de Twitch (sin #).
     * @param queue        Cola compartida donde encolar los mensajes recibidos.
     * @param oauthToken   Token OAuth del usuario, o null para modo anónimo.
     * @param twitchLogin  Nombre de usuario del token OAuth, o null para modo anónimo.
     */
    public TwitchChatReader(String channel, BlockingQueue<ChatMessage> queue,
                            String oauthToken, String twitchLogin) {
        this.channel      = channel.toLowerCase();
        this.queue        = queue;
        this.oauthToken   = oauthToken;
        this.twitchLogin  = twitchLogin;
    }

    // ── Runnable ──────────────────────────────────────────────────────────────

    /**
     * Bucle principal de conexión con reconexión automática.
     * Se detiene si el hilo es interrumpido o si ocurre un error fatal.
     */
    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted() && !fatalError) {
            try {
                connect();
            } catch (Exception e) {
                if (fatalError) break;
                System.err.println("[Twitch] Error de conexión: " + e.getMessage()
                        + " — reconectando en 5s");
                sleep(5000);
            }
        }
    }

    // ── Conexión y lectura ────────────────────────────────────────────────────

    /**
     * Establece la conexión IRC, se autentica, hace JOIN al canal
     * y procesa los mensajes entrantes hasta que la conexión se cierre.
     */
    private void connect() throws Exception {
        Socket socket = SSLSocketFactory.getDefault()
                .createSocket(IRC_HOST, IRC_PORT);

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));
        PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream()), true);

        authenticate(writer);
        writer.println("CAP REQ :twitch.tv/tags");
        writer.println("JOIN #" + channel);

        boolean joinConfirmed = false;
        String  line;

        while ((line = reader.readLine()) != null) {
            if (line.startsWith("PING")) {
                writer.println("PONG :tmi.twitch.tv");
                continue;
            }

            // Antes del JOIN confirmado: detectar errores fatales y el 353
            if (!joinConfirmed) {
                System.out.println("[Twitch IRC pre-JOIN] " + line);

                if (line.contains("NOTICE") && isFatalNotice(line)) {
                    String msg = extractLastField(line);
                    System.err.println("[Twitch] NOTICE fatal: " + msg);
                    fatalError = true;
                    if (onFatalError != null) onFatalError.accept("Twitch: " + msg);
                    return;
                }

                // RPL_NAMREPLY (353) = JOIN exitoso, canal existe
                if (line.contains("353")) {
                    joinConfirmed = true;
                    if (onConnected != null) onConnected.accept(channel);
                }
                continue;
            }

            // Mensajes normales tras el JOIN
            if      (line.contains("PRIVMSG"))   handlePrivmsg(line);
            else if (line.contains("USERNOTICE")) handleUsernotice(line);
            else if (line.contains("CLEARCHAT"))  handleClearchat(line);
            else if (line.contains("CLEARMSG"))   handleClearmsg(line);
        }
    }

    /**
     * Envía las credenciales al servidor IRC.
     * Con OAuth: token real y nombre de usuario.
     * Sin OAuth: credenciales anónimas de justinfan.
     */
    private void authenticate(PrintWriter writer) {
        if (oauthToken != null && twitchLogin != null) {
            writer.println("PASS oauth:" + oauthToken);
            writer.println("NICK " + twitchLogin);
        } else {
            writer.println("PASS oauth:twitch_anonymous");
            writer.println("NICK justinfan" + (int)(Math.random() * 90000 + 10000));
        }
    }

    // ── Detección de errores ──────────────────────────────────────────────────

    /**
     * Comprueba si una línea NOTICE contiene alguna de las palabras clave de error fatal.
     * Se usa antes del JOIN confirmado para detectar canal inválido o credenciales erróneas.
     */
    private boolean isFatalNotice(String line) {
        String lower = line.toLowerCase();
        for (String keyword : FATAL_NOTICE_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    /** Extrae el último campo de una línea IRC (después del último ':'). */
    private String extractLastField(String line) {
        int lastColon = line.lastIndexOf(':');
        return lastColon >= 0 ? line.substring(lastColon + 1).trim() : line;
    }

    // ── Handlers de mensajes IRC ──────────────────────────────────────────────

    /**
     * Procesa un mensaje PRIVMSG (mensaje normal de chat).
     * Detecta cheers, recompensas de canal y mensajes destacados.
     */
    private void handlePrivmsg(String line) throws InterruptedException {
        String emotesHeader = null;
        String badgesHeader = null;
        String userColor    = null;
        String rewardId     = null;
        String msgId        = null;
        String msgIdTag     = null;

        if (line.startsWith("@")) {
            String tags = line.substring(1, line.indexOf(' '));
            for (String tag : tags.split(";")) {
                if (tag.startsWith("emotes="))           emotesHeader = tag.substring(7);
                if (tag.startsWith("badges="))           badgesHeader = tag.substring(7);
                if (tag.startsWith("color="))            userColor    = tag.substring(6);
                if (tag.startsWith("custom-reward-id=")) rewardId     = tag.substring(17);
                if (tag.startsWith("msg-id="))           msgId        = tag.substring(7);
                if (tag.startsWith("id="))               msgIdTag     = tag.substring(3);
            }
            line = line.substring(line.indexOf(' ') + 1);
        }

        String user = line.substring(1, line.indexOf('!'));
        String text = line.substring(line.indexOf("PRIVMSG #") +
                ("PRIVMSG #" + channel + " :").length());

        String cheerAmount = extractCheerAmount(text);

        if (cheerAmount != null) {
            queue.put(new ChatMessage("twitch", user, text,
                    emotesHeader, badgesHeader, userColor,
                    "cheer", cheerAmount, null, msgIdTag, null));
        } else if (rewardId != null) {
            queue.put(new ChatMessage("twitch", user, text,
                    emotesHeader, badgesHeader, userColor,
                    "reward", "Recompensa de canal", null, msgIdTag, null));
        } else if ("highlighted-message".equals(msgId)) {
            queue.put(new ChatMessage("twitch", user, text,
                    emotesHeader, badgesHeader, userColor,
                    "reward", "Mensaje destacado", null, msgIdTag, null));
        } else {
            queue.put(new ChatMessage("twitch", user, text,
                    emotesHeader, badgesHeader, userColor,
                    null, null, null, msgIdTag, null));
        }
    }

    /**
     * Procesa un mensaje USERNOTICE (suscripciones, regalos, etc.).
     * Solo gestiona subgift y submysterygift por ahora.
     */
    private void handleUsernotice(String line) throws InterruptedException {
        String msgId        = null;
        String displayName  = null;
        String userColor    = null;
        String badgesHeader = null;
        String systemMsg    = null;
        String giftCount    = null;

        if (line.startsWith("@")) {
            String tags = line.substring(1, line.indexOf(' '));
            for (String tag : tags.split(";")) {
                if (tag.startsWith("msg-id="))                       msgId        = tag.substring(6);
                if (tag.startsWith("display-name="))                 displayName  = tag.substring(13);
                if (tag.startsWith("color="))                        userColor    = tag.substring(6);
                if (tag.startsWith("badges="))                       badgesHeader = tag.substring(7);
                if (tag.startsWith("system-msg="))                   systemMsg    = tag.substring(11).replace("\\s", " ");
                if (tag.startsWith("msg-param-mass-gift-count="))    giftCount    = tag.substring(26);
                if (tag.startsWith("msg-param-gift-months=") && giftCount == null)
                                                                      giftCount    = tag.substring(22);
            }
        }

        if (msgId == null || displayName == null) return;
        String text = systemMsg != null ? systemMsg : "";

        switch (msgId) {
            case "subgift", "anonsubgift" -> {
                String extra = giftCount != null ? giftCount + " sub(s) regalo" : "sub regalo";
                queue.put(new ChatMessage("twitch", displayName, text,
                        null, badgesHeader, userColor, "subgift", extra));
            }
            case "submysterygift" -> {
                String extra = giftCount != null ? giftCount + " subs regalo" : "subs regalo";
                queue.put(new ChatMessage("twitch", displayName, text,
                        null, badgesHeader, userColor, "subgift", extra));
            }
        }
    }

    /**
     * Procesa un mensaje CLEARCHAT (ban/timeout de usuario o /clear del chat).
     * Si hay usuario al final es un ban; si no, es un /clear de todo el chat.
     */
    private void handleClearchat(String line) throws InterruptedException {
        String targetUser  = null;
        int    lastColon   = line.lastIndexOf(':');
        int    commandPos  = line.indexOf("CLEARCHAT");

        if (lastColon > commandPos) {
            String after = line.substring(lastColon + 1).trim();
            if (!after.contains("CLEARCHAT")) targetUser = after;
        }

        if (targetUser != null && !targetUser.isBlank()) {
            queue.put(new ChatMessage("twitch", null, null,
                    null, null, null, "clearchat", null, targetUser, null, null));
        } else {
            queue.put(new ChatMessage("twitch", null, null,
                    null, null, null, "clearall",  null, null,       null, null));
        }
    }

    /**
     * Procesa un mensaje CLEARMSG (borrado de un mensaje concreto por ID).
     */
    private void handleClearmsg(String line) throws InterruptedException {
        String targetMsgId = null;
        String targetUser  = null;

        if (line.startsWith("@")) {
            String tags = line.substring(1, line.indexOf(' '));
            for (String tag : tags.split(";")) {
                if (tag.startsWith("target-msg-id=")) targetMsgId = tag.substring(14);
                if (tag.startsWith("login="))         targetUser  = tag.substring(6);
            }
        }

        if (targetMsgId != null) {
            queue.put(new ChatMessage("twitch", "clearmsg", targetUser, targetMsgId));
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    /**
     * Detecta si el texto contiene cheers (Cheer100, cheer50, PogCheer1000, etc.)
     * y devuelve la cantidad total de bits, o null si no hay ninguno.
     *
     * @param text Texto del mensaje PRIVMSG.
     * @return Cantidad total de bits como String, o null si no hay cheers.
     */
    private String extractCheerAmount(String text) {
        Matcher m     = CHEER_PATTERN.matcher(text);
        int     total = 0;
        while (m.find()) {
            try { total += Integer.parseInt(m.group(2)); }
            catch (NumberFormatException ignored) {}
        }
        return total > 0 ? String.valueOf(total) : null;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ── Setters de callbacks ──────────────────────────────────────────────────

    /**
     * Callback invocado cuando el JOIN al canal se confirma (RPL_NAMREPLY 353).
     * El argumento es el nombre del canal.
     */
    public void setOnConnected(Consumer<String> callback) { this.onConnected  = callback; }

    /**
     * Callback invocado cuando ocurre un error fatal (canal inválido, credenciales erróneas).
     * El argumento es el mensaje de error. Tras llamarlo, el reader se detiene sin reconectar.
     */
    public void setOnFatalError(Consumer<String> callback) { this.onFatalError = callback; }
}