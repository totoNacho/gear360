# Gear 360 Downloader (app Android)

App Android para la **Samsung Gear 360 (New) 2017 (SM-R210)**: se conecta por WiFi, lista y descarga fotos y videos al teléfono, y los convierte a 360 listos para compartir en redes. Pensada para usarla de vacaciones, sin PC.

No depende de la app descontinuada de Samsung. Usa dos vías:
- **Servidor de archivos del mod** de la tarjeta SD (puerto 8888), si está activo: listado completo en una llamada y miniaturas.
- **API OSC** de la cámara (puerto 80) como respaldo, y para tomar fotos y grabar.

---

## Qué hace la app

- **Galería** con dos pestañas, **Imágenes** y **Videos**, con todos los archivos de la cámara.
- **Miniaturas** de las fotos (con el servidor del mod) y **vista en grande** al tocar la miniatura o el ojo.
- **Descargar uno** o **descargar todos**, con progreso. Fotos → `Pictures/Gear360`, videos → `Movies/Gear360`.
- **Convertir a 360 al descargar** (activado por defecto): cose el archivo dual-fisheye a equirectangular con FFmpeg. En los videos además recodifica el audio e inyecta los metadatos esféricos que YouTube y Facebook necesitan para mostrarlo como 360.
- **Ajuste de hora**: con el servidor del mod, la cámara toma la hora del teléfono en cada conexión (su reloj vuelve a 2017-01-01).
- **Actualización automática**: avisa cuando hay una versión nueva en GitHub y la instala.

---

## Cómo usar la app

1. Enciende la Gear 360 en modo **Street View** (crea la red `Gear 360(XXXX).OSC`; la contraseña sale en la pantalla).
2. **Haz doble clic en Power**. La luz de arriba hace verde, naranja, verde: el servidor de archivos del mod quedó activo. Hay que repetirlo cada vez que enciendes la cámara.
3. En el teléfono, conéctate a la red WiFi de la cámara. Android dirá que no tiene internet: es normal, mantén esa red.
4. Abre la app, ve a **Galería** y pulsa **Conectar cámara**. Debe aparecer "Servidor de archivos activo".
5. Descarga lo que quieras. Los archivos quedan en la galería del teléfono, carpeta **Gear360**.

Si no hiciste el doble clic, la app igual funciona por OSC: escanea los archivos por numeración (más lento) y no muestra miniaturas. Haz el doble clic y pulsa **Actualizar**.

Mientras el servidor del mod está activo, el Bluetooth de la cámara no funciona.

---

## Cómo obtener el APK

Cada cambio que se sube a `main` se compila en GitHub Actions y se publica firmado en **https://github.com/totoNacho/gear360/releases**. Descarga el `gear360-v1.0.X-release.apk` más reciente e instálalo (Android pedirá permitir instalar apps de origen desconocido). Desde ahí, la propia app avisa de las versiones nuevas.

El APK pesa unos 77 MB por las librerías de FFmpeg y solo funciona en teléfonos de 64 bits (arm64).

---

## Detalles técnicos

- Kotlin + Jetpack Compose (Material 3), OkHttp, Coil, MediaStore.
- FFmpeg: `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7`, filtro `v360=dfisheye:e:ih_fov=195:iv_fov=195`.
- Build: AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, compileSdk 35, minSdk 26, JDK 17.
- IP de la cámara: `192.168.43.1` (constante `DEFAULT_IP` en `Gear360Client.kt`).
- Servidor del mod (ilker-aktuna, instalado en la tarjeta SD): `/cgi-bin/files` (listado), `?thumbnail=` (miniatura 320x160), `?dl=` (descarga), `/cgi-bin/setdate?millis=` (hora, en segundos). No tiene usuario ni contraseña: cualquiera conectado a la WiFi de la cámara puede usarlo.

### Archivos principales
```
app/src/main/java/com/gear360/downloader/
├─ MainActivity.kt          UI: pestañas Cámara y Galería
├─ MainViewModel.kt         conectar, listar, descargar, vista previa, captura
├─ Gear360Client.kt         cliente OSC y del servidor del mod
├─ ThumbnailLoader.kt       miniaturas con Coil (máx. 2 peticiones a la vez)
├─ Stitcher.kt              conversión a 360 con FFmpeg
├─ Spherical360Injector.kt  metadatos 360 para videos
├─ MediaSaver.kt            guardado en la galería
└─ UpdateChecker.kt         actualización desde GitHub Releases
```

---

## Limitaciones

- **Vista en vivo**: este firmware rechaza `getLivePreview` por OSC y el mod no la incluye.
- **Videos sin miniatura**: el servidor del mod solo genera miniaturas de fotos.
- **Conversión de video lenta** en el teléfono: un video 4K largo puede tardar varios minutos.
- **Fotos 360**: se cosen a equirectangular, pero todavía no llevan metadatos Photo Sphere.
- **Grabar video desde la app** no está confirmado en este firmware.
