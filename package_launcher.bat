@echo off
setlocal enabledelayedexpansion

echo ======================================
echo   BUILD CHATOVERLAY + LAUNCHER
echo ======================================

:: =========================
:: CONFIGURACION BASE
:: =========================
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"
set "PROJECT_DIR=%cd%"
set "FX_JMODS_PATH=C:\javafx-jmods-21.0.10"

:: Carpetas de trabajo
set "INPUT_CHAT=package_input_chat"
set "INPUT_LAUNCHER=package_input_launcher"
set "OUTPUT_CHAT=package_output_chat"
set "OUTPUT_LAUNCHER=package_output_launcher"
set "RUNTIME_DIR=target\runtime"

:: Carpeta final de distribucion
set "DIST_DIR=package_output\ChatOverlay"

:: =========================
:: LIMPIEZA
:: =========================
echo [1/9] Limpiando carpetas anteriores...

if exist "%INPUT_CHAT%"       rmdir /s /q "%INPUT_CHAT%"
if exist "%INPUT_LAUNCHER%"   rmdir /s /q "%INPUT_LAUNCHER%"
if exist "%OUTPUT_CHAT%"      rmdir /s /q "%OUTPUT_CHAT%"
if exist "%OUTPUT_LAUNCHER%"  rmdir /s /q "%OUTPUT_LAUNCHER%"
if exist "package_output"     rmdir /s /q "package_output"
if exist "%RUNTIME_DIR%"      rmdir /s /q "%RUNTIME_DIR%"

mkdir "%INPUT_CHAT%"
mkdir "%INPUT_LAUNCHER%"
mkdir "%OUTPUT_CHAT%"
mkdir "%OUTPUT_LAUNCHER%"
mkdir "package_output"
mkdir "%DIST_DIR%"

:: =========================
:: COMPILAR MAVEN
:: =========================
echo [2/9] Compilando proyecto (Maven)...
call mvn clean package -q

if errorlevel 1 (
    echo ERROR: Maven fallo
    exit /b 1
)

:: =========================
:: PREPARAR INPUT CHATOVERLAY
:: overlay.jar + libs\ (todo lo que necesita ChatOverlay)
:: =========================
echo [3/9] Preparando input de ChatOverlay...

copy "target\overlay.jar" "%INPUT_CHAT%\"
xcopy /E /I /Y "target\libs" "%INPUT_CHAT%\libs" >nul
if exist "icon.ico" copy "icon.ico" "%INPUT_CHAT%\"

:: =========================
:: PREPARAR INPUT LAUNCHER
:: Solo launcher.jar, SIN libs\ para no bloquear nada al arrancar.
:: El Launcher usa solo clases del JDK (javax.json, java.net.http, javax.swing)
:: =========================
echo [4/9] Preparando input del Launcher...

copy "target\launcher.jar" "%INPUT_LAUNCHER%\"
if exist "icon.ico" copy "icon.ico" "%INPUT_LAUNCHER%\"

:: =========================
:: DETECTAR MODULOS (jdeps sobre overlay.jar)
:: Detectamos los modulos de ChatOverlay que es el mas completo.
:: El runtime compartido los incluira todos.
:: =========================
echo [5/9] Detectando modulos con jdeps...

"%JAVA_HOME%\bin\jdeps" ^
  --multi-release 21 ^
  --ignore-missing-deps ^
  --print-module-deps ^
  --class-path "%INPUT_CHAT%\libs\*" ^
  "%INPUT_CHAT%\overlay.jar" > modules.txt

set /p MODULES=<modules.txt

:: Modulos obligatorios para JavaFX, crypto y servidor HTTP del OAuth
set "MODULES=%MODULES%,javafx.controls,javafx.graphics,javafx.media,javafx.swing,jdk.httpserver,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.security.auth"

echo Modulos: %MODULES%

:: =========================
:: CREAR RUNTIME COMPARTIDO (JLINK)
:: Un solo runtime para ambos ejecutables
:: =========================
echo [6/9] Creando runtime compartido (jlink)...

"%JAVA_HOME%\bin\jlink" ^
  --module-path "%JAVA_HOME%\jmods;%FX_JMODS_PATH%" ^
  --add-modules %MODULES% ^
  --output "%RUNTIME_DIR%" ^
  --strip-debug ^
  --compress=2 ^
  --no-header-files ^
  --no-man-pages

if errorlevel 1 (
    echo ERROR: jlink fallo
    exit /b 1
)

if not exist "%RUNTIME_DIR%\bin\java.exe" (
    echo ERROR: runtime no creado correctamente
    exit /b 1
)

:: =========================
:: JPACKAGE CHATOVERLAY
:: Genera: ChatOverlay.exe, app\overlay.jar, app\libs\, runtime\
:: =========================
echo [7/9] Empaquetando ChatOverlay...

"%JAVA_HOME%\bin\jpackage" ^
  --type app-image ^
  --name ChatOverlay ^
  --input "%INPUT_CHAT%" ^
  --main-jar overlay.jar ^
  --main-class com.chatoverlaystreaming.Main ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "-Dsun.java2d.noddraw=true" ^
  --java-options "-Dsun.java2d.d3d=false" ^
  --java-options "-Dsun.java2d.opengl=false" ^
  --java-options "-Dsun.java2d.gdi=true" ^
  --java-options "-Dprism.order=sw" ^
  --java-options "--add-modules javafx.media,javafx.swing" ^
  --runtime-image "%RUNTIME_DIR%" ^
  --dest "%OUTPUT_CHAT%" ^
  --icon "%PROJECT_DIR%\icon.ico"

if errorlevel 1 (
    echo ERROR: jpackage ChatOverlay fallo
    exit /b 1
)

:: =========================
:: JPACKAGE LAUNCHER
:: Genera: Launcher.exe, app\launcher.jar, runtime\
:: Solo carga launcher.jar — no bloquea overlay.jar ni las libs
:: =========================
echo [8/9] Empaquetando Launcher...

"%JAVA_HOME%\bin\jpackage" ^
  --type app-image ^
  --name Launcher ^
  --input "%INPUT_LAUNCHER%" ^
  --win-console ^
  --main-jar launcher.jar ^
  --main-class com.chatoverlaystreaming.Launcher ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --runtime-image "%RUNTIME_DIR%" ^
  --dest "%OUTPUT_LAUNCHER%" ^
  --icon "%PROJECT_DIR%\icon.ico"

if errorlevel 1 (
    echo ERROR: jpackage Launcher fallo
    exit /b 1
)

:: =========================
:: FUSIONAR EN DIST_DIR
::
:: Estructura final:
::   DIST_DIR\
::   ├── ChatOverlay.exe     <- punto de entrada directo
::   ├── Launcher.exe        <- punto de entrada con actualizaciones
::   ├── app\
::   │   ├── overlay.jar
::   │   ├── launcher.jar
::   │   ├── ChatOverlay.cfg
::   │   ├── Launcher.cfg
::   │   └── libs\
::   ├── runtime\            <- JRE compartido por ambos exes
::   ├── youtube_emojis\
::   ├── youtube_unicode_chars.json
::   ├── version.txt
::   ├── icon.png
::   ├── icon.ico
::   └── config.example.json
:: =========================
echo [9/9] Fusionando en carpeta de distribucion...

:: Base: estructura completa de ChatOverlay (exe + app\ + runtime\)
copy "%OUTPUT_CHAT%\ChatOverlay\ChatOverlay.exe"   "%DIST_DIR%\ChatOverlay.exe" >nul
xcopy /E /I /Y "%OUTPUT_CHAT%\ChatOverlay\app"     "%DIST_DIR%\app"     >nul
xcopy /E /I /Y "%OUTPUT_CHAT%\ChatOverlay\runtime" "%DIST_DIR%\runtime" >nul

:: Anadir Launcher.exe al mismo nivel que ChatOverlay.exe
copy "%OUTPUT_LAUNCHER%\Launcher\Launcher.exe" "%DIST_DIR%\Launcher.exe" >nul

:: Anadir launcher.jar y Launcher.cfg a app\ (junto a overlay.jar y ChatOverlay.cfg)
copy "%OUTPUT_LAUNCHER%\Launcher\app\launcher.jar" "%DIST_DIR%\app\launcher.jar" >nul
copy "%OUTPUT_LAUNCHER%\Launcher\app\Launcher.cfg" "%DIST_DIR%\app\Launcher.cfg" >nul

:: El runtime\ generado para el Launcher lo descartamos:
:: ambos exes usaran DIST_DIR\runtime\ que ya copiamos de ChatOverlay

:: Archivos de datos en la raiz (mismo nivel que los exes)
if exist "youtube_unicode_chars.json" copy /Y "youtube_unicode_chars.json" "%DIST_DIR%\" >nul
if exist "version.txt"               copy /Y "version.txt"                "%DIST_DIR%\" >nul
if exist "icon.png"                  copy /Y "icon.png"                   "%DIST_DIR%\" >nul
if exist "icon.ico"                  copy /Y "icon.ico"                   "%DIST_DIR%\" >nul
if exist "config.example.json"       copy /Y "config.example.json"        "%DIST_DIR%\" >nul

if exist "youtube_emojis" (
    xcopy /E /I /Y "youtube_emojis" "%DIST_DIR%\youtube_emojis" >nul
)

:: Limpieza de carpetas temporales de trabajo
rmdir /s /q "%INPUT_CHAT%"
rmdir /s /q "%INPUT_LAUNCHER%"
rmdir /s /q "%OUTPUT_CHAT%"
rmdir /s /q "%OUTPUT_LAUNCHER%"

echo.
echo ======================================
echo   BUILD COMPLETADO
echo ======================================
echo.
echo Estructura generada en: %DIST_DIR%
echo.
echo   %DIST_DIR%\
echo   ├── Launcher.exe              (punto de entrada con actualizaciones)
echo   ├── ChatOverlay.exe           (acceso directo sin actualizaciones)
echo   ├── app\
echo   │   ├── overlay.jar
echo   │   ├── launcher.jar
echo   │   ├── ChatOverlay.cfg
echo   │   ├── Launcher.cfg
echo   │   └── libs\
echo   ├── runtime\
echo   ├── youtube_emojis\
echo   ├── youtube_unicode_chars.json
echo   ├── version.txt
echo   ├── icon.png
echo   └── config.example.json
echo.
echo Para distribuir: comprime %DIST_DIR% en un .zip
echo El usuario descomprime y ejecuta Launcher.exe
echo.

endlocal
pause