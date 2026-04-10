package com.chatoverlaystreaming.readers;

import com.chatoverlaystreaming.model.ChatMessage;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;

public class TwitchChatReader implements Runnable {

    private final String channel;          // sin el #, ej: "xqc"
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

        // Autenticación anónima
        writer.println("PASS oauth:twitch_anonymous");
        writer.println("NICK justinfan" + (int)(Math.random() * 90000 + 10000));
        writer.println("CAP REQ :twitch.tv/tags");
        writer.println("JOIN #" + channel);

        String line;
        while ((line = reader.readLine()) != null) {
            // Responder pings para mantener la conexión
            if (line.startsWith("PING")) {
                writer.println("PONG :tmi.twitch.tv");
                continue;
            }
            // Parsear mensajes PRIVMSG
            // Formato: :usuario!usuario@usuario.tmi.twitch.tv PRIVMSG #canal :mensaje
            if (line.contains("PRIVMSG")) {
                // Extraer cabecera de emotes y badges
                String emotesHeader = null;
                String badgesHeader = null;
                if (line.startsWith("@")) {
                    String tags = line.substring(1, line.indexOf(' '));
                    for (String tag : tags.split(";")) {
                        if (tag.startsWith("emotes=")) {
                            emotesHeader = tag.substring(7);
                            //break;
                        }
                        if (tag.startsWith("badges=")) {
                            badgesHeader = tag.substring(7);
                        }
                    }
                    line = line.substring(line.indexOf(' ') + 1);
                }
                
                String user = line.substring(1, line.indexOf('!'));
                String text = line.substring(line.indexOf("PRIVMSG #") +
                            ("PRIVMSG #" + channel + " :").length());
                queue.put(new ChatMessage("twitch", user, text, emotesHeader, badgesHeader));
            }
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}