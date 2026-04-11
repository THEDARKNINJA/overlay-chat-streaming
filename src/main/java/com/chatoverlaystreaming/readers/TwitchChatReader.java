package com.chatoverlaystreaming.readers;

import com.chatoverlaystreaming.model.ChatMessage;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;

public class TwitchChatReader implements Runnable {

    private final String channel;
    private final BlockingQueue<ChatMessage> queue;

    public TwitchChatReader(String channel, BlockingQueue<ChatMessage> queue) {
        this.channel = channel.toLowerCase();
        this.queue   = queue;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                connect();
            } catch (Exception e) {
                System.err.println("[Twitch] Error: " + e.getMessage() + " — reconectando en 5s");
                sleep(5000);
            }
        }
    }

    private void connect() throws Exception {
        Socket socket = SSLSocketFactory.getDefault()
                .createSocket("irc.chat.twitch.tv", 6697);

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));
        PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream()), true);

        writer.println("PASS oauth:twitch_anonymous");
        writer.println("NICK justinfan" + (int)(Math.random() * 90000 + 10000));
        writer.println("CAP REQ :twitch.tv/tags");
        writer.println("JOIN #" + channel);

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("PING")) {
                writer.println("PONG :tmi.twitch.tv");
                continue;
            }

            if (line.contains("PRIVMSG")) {
                handlePrivmsg(line);
            } else if (line.contains("USERNOTICE")) {
                handleUsernotice(line);
            } else if (line.contains("CLEARCHAT")) {
                handleClearchat(line);
            } else if (line.contains("CLEARMSG")) {
                handleClearmsg(line);
            }
            System.err.println("[Twitch IRC] " + line);
        }
    }

    private void handlePrivmsg(String line) throws InterruptedException {
        String emotesHeader = null;
        String badgesHeader = null;
        String userColor    = null;
        String rewardId     = null;
        // String rewardTitle  = null;
        String msgId        = null;
        String msgIdTag     = null;

        if (line.startsWith("@")) {
            String tags = line.substring(1, line.indexOf(' '));
            for (String tag : tags.split(";")) {
                if (tag.startsWith("emotes="))            emotesHeader = tag.substring(7);
                if (tag.startsWith("badges="))            badgesHeader = tag.substring(7);
                if (tag.startsWith("color="))             userColor    = tag.substring(6);
                if (tag.startsWith("custom-reward-id="))  rewardId     = tag.substring(17);
                if (tag.startsWith("msg-id="))            msgId        = tag.substring(7);
                if (tag.startsWith("id="))                msgIdTag     = tag.substring(3); // ID único del mensaje
            }
            line = line.substring(line.indexOf(' ') + 1);
        }

        String user = line.substring(1, line.indexOf('!'));
        String text = line.substring(line.indexOf("PRIVMSG #") +
                      ("PRIVMSG #" + channel + " :").length());

        // Detectar cheers (bits) por el patrón "CheerNNN" en el texto
        String cheerAmount = extractCheerAmount(text, emotesHeader);

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
                if (tag.startsWith("msg-id="))              msgId       = tag.substring(6);
                if (tag.startsWith("display-name="))        displayName = tag.substring(13);
                if (tag.startsWith("color="))               userColor   = tag.substring(6);
                if (tag.startsWith("badges="))              badgesHeader = tag.substring(7);
                if (tag.startsWith("system-msg="))          systemMsg   = tag.substring(11).replace("\\s", " ");
                if (tag.startsWith("msg-param-mass-gift-count=")) giftCount = tag.substring(26);
                if (tag.startsWith("msg-param-gift-months=") && giftCount == null) giftCount = tag.substring(22);
            }
        }

        if (msgId == null || displayName == null) return;

        switch (msgId) {
            case "subgift", "anonsubgift" -> {
                String extra = giftCount != null ? giftCount + " sub(s) regalo" : "sub regalo";
                queue.put(new ChatMessage("twitch", displayName, systemMsg != null ? systemMsg : "",
                        null, badgesHeader, userColor, "subgift", extra));
            }
            case "submysterygift" -> {
                String extra = giftCount != null ? giftCount + " subs regalo" : "subs regalo";
                queue.put(new ChatMessage("twitch", displayName, systemMsg != null ? systemMsg : "",
                        null, badgesHeader, userColor, "subgift", extra));
            }
        }
    }

    private void handleClearchat(String line) throws InterruptedException {
        // Formato: @ban-duration=X;... :tmi.twitch.tv CLEARCHAT #canal :usuario
        // Si no hay usuario al final es un /clear de todo el chat
        String targetUser = null;
        int lastColon = line.lastIndexOf(':');
        int clearchatPos = line.indexOf("CLEARCHAT");

        if (lastColon > clearchatPos) {
            String afterCommand = line.substring(lastColon + 1).trim();
            if (!afterCommand.contains("CLEARCHAT")) {
                targetUser = afterCommand;
            }
        }

        if (targetUser != null && !targetUser.isBlank()) {
            queue.put(new ChatMessage("twitch", null, null,
                    null, null, null,
                    "clearchat", null, targetUser, null, null));
        } else {
            queue.put(new ChatMessage("twitch", null, null,
                    null, null, null,
                    "clearall", null, null, null, null));
        }
    }

    private void handleClearmsg(String line) throws InterruptedException {
        System.err.println("[Twitch DEBUG] CLEARMSG recibido: " + line);
        // Formato: @login=usuario;target-msg-id=UUID :tmi.twitch.tv CLEARMSG #canal :texto
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

    /**
     * Detecta si el mensaje contiene bits (Cheer100, cheer50, etc.)
     * y devuelve la cantidad total, o null si no hay bits.
     */
    private String extractCheerAmount(String text, String emotesHeader) {
        int total = 0;
        // Los cheers tienen formato: Cheer100, cheer50, PogCheer1000, etc.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\b(\\w*cheer)(\\d+)\\b")
                .matcher(text);
        while (m.find()) {
            try {
                total += Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {}
        }
        return total > 0 ? String.valueOf(total) : null;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}