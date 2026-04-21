@echo off
setlocal enabledelayedexpansion

echo ======================================
echo   BUILD CHATOVERLAY - APP IMAGE
echo ======================================

:: =========================
:: CONFIGURACION BASE
:: =========================
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"
set "PROJECT_DIR=%cd%"

set "TARGET_JAR=target\overlay.jar"

set "FX_JMODS_PATH=C:\javafx-jmods-21.0.10"

:: carpetas seguras (IMPORTANTÍSIMO)
set "INPUT_DIR=package_input"
set "OUTPUT_DIR=package_output"
set "RUNTIME_DIR=target\runtime"

:: =========================
:: LIMPIEZA
:: =========================
echo [1/7] Limpiando carpetas...

if exist "%INPUT_DIR%" rmdir /s /q "%INPUT_DIR%"
if exist "%OUTPUT_DIR%" rmdir /s /q "%OUTPUT_DIR%"
if exist "%RUNTIME_DIR%" rmdir /s /q "%RUNTIME_DIR%"

mkdir "%INPUT_DIR%"
mkdir "%OUTPUT_DIR%"

:: =========================
:: COMPILAR MAVEN
:: =========================
echo [2/7] Compilando proyecto...
call mvn clean package -q

if errorlevel 1 (
    echo ERROR: Maven fallo
    exit /b 1
)

:: =========================
:: COPIAR ARCHIVOS
:: =========================
echo [3/7] Preparando app-image input...

copy "target\overlay.jar" "%INPUT_DIR%\"
xcopy /E /I /Y "target\libs" "%INPUT_DIR%\libs" >nul
xcopy /E /I /Y "youtube_emojis" "%INPUT_DIR%\youtube_emojis" >nul

:: si tienes config por defecto
if exist "config.example.json" copy "config.example.json" "%INPUT_DIR%\"

:: si existen los chars unicode
if exist "youtube_unicode_chars.json" copy "youtube_unicode_chars.json" "%INPUT_DIR%\"

:: si existe el icono
if exist "icon.ico" copy "icon.ico" "%INPUT_DIR%\"
if exist "icon.png" copy "icon.png" "%INPUT_DIR%\"

:: si existe el archivo de version
if exist "version.txt" copy "version.txt" "%INPUT_DIR%\"

:: =========================
:: DETECTAR MODULOS (jdeps)
:: =========================
echo [4/7] Detectando modulos con jdeps...

"%JAVA_HOME%\bin\jdeps" ^
  --multi-release 21 ^
  --ignore-missing-deps ^
  --print-module-deps ^
  --class-path "%INPUT_DIR%\libs\*" ^
  "%INPUT_DIR%\overlay.jar" > modules.txt

set /p MODULES=<modules.txt

:: MODULOS OBLIGATORIOS (JavaFX + crypto + http server)
set "MODULES=%MODULES%,javafx.controls,javafx.graphics,javafx.media,javafx.swing,jdk.httpserver,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.security.auth"

echo MODULOS: %MODULES%

:: =========================
:: CREAR RUNTIME (JLINK)
:: =========================
echo [5/7] Creando runtime...

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
:: JPACKAGE (APP-IMAGE)
:: =========================
echo [6/7] Creando app-image...

:: añadir --win-console ^   si se quiere consola
"%JAVA_HOME%\bin\jpackage" ^
  --type app-image ^
  --name ChatOverlay ^
  --input "%INPUT_DIR%" ^
  --main-jar overlay.jar ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --main-class com.chatoverlaystreaming.Main ^
  --runtime-image "%RUNTIME_DIR%" ^
  --dest "%OUTPUT_DIR%" ^
  --icon "%PROJECT_DIR%\icon.ico"

if errorlevel 1 (
    echo ERROR: jpackage fallo
    exit /b 1
)

:: =========================
:: COPIAR CONFIG DE PLANTILLA
:: =========================
echo [7/7] Copiando configuracion por defecto...

copy /Y "config.example.json" "%OUTPUT_DIR%\ChatOverlay\config.json"

copy /Y "youtube_unicode_chars.json" "%OUTPUT_DIR%\ChatOverlay\youtube_unicode_chars.json"

copy /Y "icon.ico" "%OUTPUT_DIR%\ChatOverlay\icon.ico"
copy /Y "icon.png" "%OUTPUT_DIR%\ChatOverlay\icon.png"

copy /Y "version.txt" "%OUTPUT_DIR%\ChatOverlay\version.txt"

if exist "youtube_emojis" (
    xcopy /E /I /Y "youtube_emojis" "%OUTPUT_DIR%\ChatOverlay\youtube_emojis" >nul
)

echo Configuracion lista

echo.
echo ======================================
echo   BUILD COMPLETADO CORRECTAMENTE
echo ======================================
echo Output: %OUTPUT_DIR%\ChatOverlay
echo.

endlocal
pause