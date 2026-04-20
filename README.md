# Chat Overlay Streaming

Aplicación de escritorio Java que muestra el chat de Twitch y YouTube como un overlay transparente sobre cualquier ventana o juego, con soporte de emotes, recompensas de canal, reproducción de media y gestión completa desde la propia interfaz.

---

## Características

- **Overlay transparente** sobre cualquier ventana o juego
- **Twitch y YouTube simultáneamente** en el mismo panel, cada plataforma activable por separado
- **Emotes de Twitch** (CDN oficial) y **BTTV** (Better TTV, opcional)
- **Emojis de YouTube** custom e Unicode sin peticiones de red
- **Badges de Twitch** (globales y de canal)
- **Recompensas de canal** con reproducción de audio o vídeo al canjearlas
- **EventSub de Twitch** para recompensas en tiempo real
- **Moderación**: borrado de mensajes (`CLEARMSG`) y de usuario (`CLEARCHAT`)
- **Contador de viewers** de Twitch y YouTube
- **Links clicables** en los mensajes
- **Timeout de mensajes** configurable con limpieza automática
- **Exclusión de OBS** por defecto, con botón para activar visibilidad
- **Click-through** (los clics pasan a través de la ventana) activable desde la bandeja del sistema
- **Panel de configuración** integrado sin necesidad de editar JSON a mano
- **Gestor de recompensas** para crear, editar y borrar recompensas de canal points desde la propia app
- **Chroma key** en vídeos de recompensa para fondos transparentes
- **Posición aleatoria** opcional para los vídeos de recompensa
- **Autenticación OAuth** con navegador integrado invisible para OBS

---

## Requisitos

- **Windows 10 o superior** (usa APIs nativas de Windows para transparencia y exclusión de OBS)
- **VLC Media Player** instalado — recomendado para máxima compatibilidad de formatos. Si no está instalado, la reproducción de media usa JavaFX con soporte de codecs limitado
- Cuenta de **Twitch** con una aplicación registrada en [dev.twitch.tv](https://dev.twitch.tv)
- Clave de API de **YouTube Data API v3** (opcional, solo para chat de YouTube)

---

## Instalación

Descarga el archivo `.zip` desde la sección de releases y descomprímelo en una carpeta. Dentro encontrarás `ChatOverlay.exe` junto a todos los archivos necesarios, incluyendo el JRE. No hace falta instalar Java por separado. Ejecuta `ChatOverlay.exe` directamente.

La primera vez que arranques sin `config.json`, la aplicación creará uno vacío automáticamente y mostrará un mensaje indicando que configures tus datos antes de conectar.

---

## Configuración inicial

### Registro de aplicación en Twitch

1. Ve a [dev.twitch.tv/console](https://dev.twitch.tv/console) e inicia sesión.
2. Crea una nueva aplicación:
   - **Nombre**: el que quieras
   - **URL de redirección OAuth**: `http://localhost:7734`
   - **Categoría**: Chat Bot o Broadcasting Suite
3. Copia el **Client ID** y genera un **Client Secret**.

### Clave de API de YouTube

1. Ve a [console.cloud.google.com](https://console.cloud.google.com).
2. Crea un proyecto y habilita la **YouTube Data API v3**.
3. Genera una clave de API en *Credenciales*.

### Panel de configuración

Pulsa el botón **⚙** en la barra inferior (visible cuando la app tiene el foco) para abrir el panel de configuración. Desde ahí puedes configurar todo sin tocar el JSON a mano. Los campos disponibles son:

**Twitch** — Canal, ID de canal, Client ID, Client Secret. Puedes deshabilitar Twitch completamente si no lo necesitas; en ese caso no aparecerá su botón ni el contador de viewers.

**YouTube** — ID de canal, Video ID del directo activo, API Keys (una por línea para rotación automática si se agota la cuota). Puedes deshabilitar YouTube completamente si no lo necesitas.

**Panel** — Alpha (transparencia del fondo), mostrar fondo, tamaño de iconos.

**Misc** — Intervalo de polling de YouTube, contador de viewers, links clicables, cargar emotes BTTV, timeout de mensajes en segundos (0 = no borrar nunca), registro de actividad en log.

> Si cambias el Client ID o Secret, los tokens OAuth guardados se invalidan automáticamente y se pedirá nueva autorización al conectar.

### config.json (referencia)

```json
{
  "twitch": {
    "enabled": true,
    "channel": "nombre_del_canal",
    "channelId": "12345678",
    "clientId": "tu_client_id",
    "clientSecret": "tu_client_secret"
  },
  "youtube": {
    "enabled": true,
    "apiKeys": ["tu_api_key"],
    "videoId": "id_del_directo",
    "channelId": "UCxxxxxxxxx"
  },
  "panel": {
    "x": 100, "y": 100,
    "width": 380, "height": 450,
    "alpha": 200,
    "showBackground": true,
    "iconSize": 16
  },
  "misc": {
    "showViewerCount": true,
    "minPollingInterval": 10000,
    "loadBTTV": true,
    "canClickLink": true,
    "messageTimeoutSeconds": 0,
    "logActivity": true
  },
  "twitchRewards": {}
}
```

---

## Conexión a las plataformas

Al arrancar, aparecen los botones de las plataformas habilitadas en la barra inferior. Pulsa cada botón para iniciar la conexión. Si los datos de configuración no están bien, aparecerá un mensaje de error en el chat y el botón se rehabilitará para que puedas reintentar después de corregirlos.

### Twitch

Al pulsar el botón de Twitch se abre una ventana de autorización OAuth integrada en la propia aplicación e invisible para OBS. Inicia sesión con tu cuenta de Twitch y acepta los permisos. El token se guarda en `config.json` y las siguientes veces conectará directamente sin pedir autorización de nuevo, a menos que cambies las credenciales.

Si el OAuth falla, la aplicación conecta en **modo anónimo**: el chat es visible pero las recompensas y la moderación no estarán disponibles. El botón de Twitch permanece visible en naranja para que puedas reintentar con OAuth cuando corrijas los datos.

Los scopes que solicita la aplicación son `chat:read`, `channel:read:redemptions` y `channel:manage:redemptions`.

### YouTube

Al pulsar el botón de YouTube la aplicación busca el directo activo en el canal configurado. Si no hay ningún directo activo, aparece un mensaje en el chat y el botón se rehabilita para reintentar más tarde.

La cuota de la API de YouTube es de 10.000 unidades diarias por clave. Si se agota, la app rota automáticamente a la siguiente clave configurada. La cuota se resetea a medianoche hora del Pacífico.

---

## Interfaz

### Barra inferior

Visible solo cuando la aplicación tiene el foco:

| Elemento | Función |
|----------|---------|
| Icono Twitch (morado) | Botón de conexión, o contador de viewers cuando está conectado |
| Icono YouTube (rojo) | Botón de conexión, o contador de viewers cuando está conectado |
| ★ (amarillo) | Gestor de recompensas — solo visible cuando hay OAuth activo |
| ⚙ (gris) | Panel de configuración |
| 👁 (verde/gris) | Activar o desactivar visibilidad en OBS |

### Bandeja del sistema

Clic derecho en el icono de la bandeja para activar o desactivar el **click-through** de la ventana, que permite que los clics del ratón pasen a través del overlay sin interactuar con él.

### Mover y redimensionar

Arrastra la **barra superior morada** (visible cuando la app tiene foco) para mover el panel. Usa la **esquina inferior derecha** para redimensionar. La posición y tamaño se guardan automáticamente.

---

## Recompensas de canal

El botón ★ solo aparece cuando Twitch está conectado con OAuth activo. Pulsa para abrir el gestor de recompensas.

### Crear o editar una recompensa

1. Selecciona **— NUEVO —** en el desplegable o elige una existente para editarla.
2. Rellena los campos de Twitch: título, descripción, coste, color de fondo, cooldown, si requiere texto del usuario, si se completa automáticamente, y si está activa.
3. Elige el **tipo** de media: `audio` o `video`.
4. Configura la **carpeta** con la ruta absoluta donde están los archivos. Puedes escribirla directamente o usar el botón 📁 para navegar. Marca **buscar en subcarpetas** si quieres incluir directorios anidados.
5. Elige el **modo de reproducción**: aleatorio, secuencial o aleatorio sin repetir.
6. Ajusta el **volumen** (0–100%).
7. Para vídeo, configura además: ancho, alto, pantalla de destino, posición X e Y relativas a esa pantalla (o marca **posición aleatoria**), título de la ventana, FPS de captura, y chroma key si el vídeo tiene fondo de color a eliminar.
8. Pulsa **Guardar**.

Para borrar una recompensa, selecciónala en el desplegable y pulsa el botón **Borrar** que aparece a la derecha.

### Modos de reproducción

- **Aleatorio**: elige un archivo al azar en cada reproducción.
- **Secuencial**: elige el archivo con menos reproducciones; en empates, el primero por orden alfabético.
- **Aleatorio sin repetir**: aleatorio entre los archivos con menos reproducciones, garantizando que todos se reproduzcan antes de repetir.

Los contadores se guardan en `rewards/ID_RECOMPENSA.json` y se sincronizan automáticamente si añades o eliminas archivos de la carpeta.

Si un archivo falla al reproducirse, la aplicación lo marca en memoria para esta sesión y prueba con el siguiente sin interrumpir.

### Chroma key

Disponible para recompensas de vídeo. Activa **Activar croma**, selecciona el color del fondo a eliminar con el selector de color, y ajusta la tolerancia según la uniformidad del fondo. Los píxeles dentro del rango del color clave se vuelven transparentes en tiempo real durante la reproducción.

### Posición del vídeo

Puedes fijar la posición manualmente indicando coordenadas X e Y relativas a la pantalla elegida, o marcar **Posición aleatoria** para que cada reproducción aparezca en un punto distinto de la pantalla. En ambos casos la posición se ajusta automáticamente si el vídeo quedaría fuera de los límites de la pantalla.

### Aprobar o rechazar recompensas

Cuando llega una recompensa no configurada para completarse automáticamente, aparecen dos botones en el mensaje del chat: **✔** para marcarla como completada en Twitch y **✖** para cancelarla y devolver los puntos al usuario. Solo funcionan para recompensas creadas con esta misma aplicación (mismo Client ID).

### Codecs soportados

Si VLC está instalado, la reproducción usa vlcj y soporta prácticamente cualquier formato. Sin VLC, la reproducción usa JavaFX con soporte más limitado:

| Tipo | Con VLC | Sin VLC (JavaFX) |
|------|---------|-----------------|
| Audio | Cualquier formato | MP3, AAC, WAV, AIFF |
| Vídeo | Cualquier formato | MP4 (H.264), MOV, WMV |

> OGG Opus no está soportado por JavaFX. Usa MP3 o AAC si no tienes VLC instalado.

---

## Integración con OBS

### Chat invisible para OBS (comportamiento por defecto)

El panel de chat está excluido de la captura de OBS por defecto mediante `SetWindowDisplayAffinity`. Usa el botón **👁** en la barra inferior para alternarlo entre visible e invisible. Los paneles de configuración, recompensas y la ventana de autorización OAuth también son siempre invisibles para OBS.

### Vídeo de recompensas en OBS

La ventana de reproducción de vídeo **sí es capturada por OBS**. Hay dos formas de capturarla:

**Captura de ventana (pantalla principal)**: en OBS añade una fuente de tipo *Captura de ventana*, selecciona el título que hayas configurado para esa recompensa (por defecto `VideoOverlay`), y usa el método de captura **BitBlt**. Si el vídeo aparece en negro, asegúrate de que el ejecutable arranca con `-Dprism.order=sw` (incluido por defecto en el `.exe`).

**Captura de pantalla (segunda pantalla)**: si configuras la recompensa para reproducir el vídeo en una segunda pantalla, en OBS añade una fuente de tipo *Captura de pantalla* apuntando a esa pantalla.

---

## Emojis de YouTube

Los emojis custom de YouTube se cargan desde la carpeta `youtube_emojis/` junto al ejecutable. Los emojis Unicode estándar se renderizan directamente con la fuente del sistema (`Segoe UI Emoji`) sin peticiones de red ni consumo de cuota de la API.

---

## Estructura de carpetas

```
ChatOverlay.exe
config.json
youtube_unicode_chars.json
youtube_emojis/
    hand-pink-waving.png
    face-blue-smiling.png
    ...
rewards/
    12345.json          ← contadores de reproducción de la recompensa 12345
logs/
    overlay-2026-04-20_12-00-00.log
```

Las carpetas de media de las recompensas las elige el usuario libremente desde el gestor y pueden estar en cualquier ruta del sistema.

---

## Logs

Los logs se guardan en la carpeta `logs/` con marca de tiempo en el nombre. Si **Registrar actividad** está activado en la configuración, todos los mensajes de consola incluyendo errores se escriben en el archivo de log además de mostrarse por consola.

---

## Problemas frecuentes

**Los botones de conexión no aparecen**
- Comprueba que la plataforma está habilitada en ⚙ Configuración.

**La app dice que está conectada pero no llegan mensajes**
- Comprueba que el nombre del canal de Twitch está bien escrito.
- Para YouTube, asegúrate de que el directo está activo en el momento de conectar.

**La ventana de OAuth se cierra antes de poder autorizar**
- Ocurría por redirecciones internas de Twitch que se malinterpretaban como cancelación. Está corregido en la versión actual.

**Modo anónimo en Twitch aunque los datos sean correctos**
- Borra los campos `accessToken` y `refreshToken` del `config.json` para forzar una nueva autorización.
- Verifica que `http://localhost:7734` está añadida como URL de redirección en tu aplicación de dev.twitch.tv.

**Las recompensas no llegan o no se pueden aprobar/rechazar**
- El OAuth debe haberse autorizado con una cuenta con permisos en el canal.
- Los botones de aprobar/rechazar solo aparecen para recompensas creadas con esta misma aplicación (mismo Client ID).
- Comprueba en los logs que aparece `[EventSub] Suscrito a recompensas de canal.`

**YouTube no carga el chat**
- El `videoId` debe ser el del directo activo, no el del canal. Si no lo sabes, deja el campo vacío y la app lo buscará automáticamente usando el `channelId`.
- Si la cuota está agotada, configura más de una API key.

**El audio suena muy bajo o no suena**
- El volumen se configura individualmente por recompensa. Un valor de 9% es muy bajo — revisa el slider en el gestor de recompensas.
- Comprueba el formato del archivo (ver tabla de codecs soportados).

**El vídeo se ve en negro en OBS**
- Usa el método de captura **BitBlt** en OBS, no Windows Graphics Capture.

**El chroma key no elimina bien el fondo**
- Aumenta la tolerancia en la configuración de la recompensa.
- Usa el selector de color para elegir el color exacto del fondo del vídeo.

---

## Compilar desde el código fuente

Requisitos: JDK 21, Maven 3.8+, JavaFX jmods 21.

```bat
mvn clean package
```

El jar se genera en `target/overlay.jar` y las dependencias en `target/libs/`.

Para generar el paquete `.exe` con JRE incluido:

```bat
package.bat
```

Requiere los jmods de JavaFX en la ruta configurada en `package.bat`. El resultado es una carpeta lista para comprimir en `.zip` con el `.exe` y todos los archivos necesarios, sin instalador.

---

## Licencia

Este proyecto es de uso personal. No redistribuyas sin permiso del autor.