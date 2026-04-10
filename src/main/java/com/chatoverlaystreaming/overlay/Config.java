package com.chatoverlaystreaming.overlay;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Config {

    private final JSONObject root;
    private final JSONObject twitch;
    private final JSONObject youtube;
    private final JSONObject panel;

    public Config() throws IOException {
        Path configPath = Paths.get("config.json");
        if (!Files.exists(configPath)) {
            throw new IOException(
                "No se encontró config.json. " +
                "Crea el archivo en la carpeta del proyecto."
            );
        }
        String content = Files.readString(configPath);
        root = new JSONObject(content);
        twitch  = root.getJSONObject("twitch");
        youtube = root.getJSONObject("youtube");
        panel = root.getJSONObject("panel");

    }

    public String getTwitchChannel()   { return twitch.getString("channel");   }
    public String getTwitchChannelId() { return twitch.getString("channelId"); }
    public String getTwitchClientId() { return twitch.getString("clientId"); }
    public String getTwitchClientSecret() { return twitch.getString("clientSecret"); }
    public String getYoutubeChannelId()  { return youtube.getString("channelId");  }
    public String getYoutubeVideoId()  { return youtube.getString("videoId");  }
    public String getYoutubeApiKey()   { return youtube.getString("apiKey");   }
    public int getPanelX()   { return panel.getInt("x");   }
    public int getPanelY()   { return panel.getInt("y");   }
    public int getPanelWidth()   { return panel.getInt("width");   }
    public int getPanelHeight()   { return panel.getInt("height");   }
    public int getPanelAlpha()   { return panel.getInt("alpha");   }

    public void savePanel(int x, int y, int width, int height) throws IOException {
        // Actualizar los valores en el objeto JSON en memoria
        JSONObject panel = root.getJSONObject("panel");
        panel.put("x", x);
        panel.put("y", y);
        panel.put("width", width);
        panel.put("height", height);
        panel.put("alpha", getPanelAlpha());

        // Escribir al disco con formato legible
        Files.writeString(
            Paths.get("config.json"),
            root.toString(2)  // el 2 es la indentación
        );
    }
}