# Gear 360 Downloader (app Android)

App Android para conectarse a una **Samsung Gear 360 (New) 2017 (SM-R210)** por WiFi, **descargar las fotos y videos** al teléfono y guardarlos en la galería. Pensada para usarla **de vacaciones, sin PC**.

No depende de la app descontinuada de Samsung: habla directamente con la cámara usando el estándar **OSC (Open Spherical Camera API)** sobre HTTP, y está escrita para **Android moderno** (scoped storage vía MediaStore, API level 35).

> Este proyecto se compiló y verificó de verdad: `gradlew assembleDebug` produce un APK instalable (`app-debug.apk`, ~16 MB).

---

## Qué hace la app

- **Conectar cámara**: prueba la conexión con la Gear 360 (botón "Conectar cámara").
- **Listar**: muestra todas las fotos y videos que hay en la cámara, con su tamaño.
- **Descargar uno** o **Descargar todo**: baja los archivos y los guarda en la galería del teléfono:
  - Fotos → `Pictures/Gear360`
  - Videos → `Movies/Gear360`
- Muestra progreso por archivo y marca los ya descargados.

### Ver en 360
La app **descarga los archivos originales** (formato dual-fisheye de la Gear 360). Para verlos como esfera 360 en el teléfono, usa cualquier visor 360 del Play Store sobre los archivos descargados. Para la costura fina (stitching), puedes pasarlos luego por la herramienta de PC del proyecto `gear360_descargar`.

---

## Cómo obtener el APK (sin instalar nada pesado)

Como no necesitas Android Studio, el APK se compila en la nube con **GitHub Actions**.

### 1. Subir el proyecto a GitHub
El workflow ya está en la raíz del repo (`.github/workflows/build-apk.yml`), que es donde GitHub lo detecta. Solo sube todo:
```
git add Gear360App .github/workflows/build-apk.yml
git commit -m "App Android Gear 360 Downloader"
git push
```

### 2. Ejecutar el build
- Entra a tu repo en GitHub → pestaña **Actions**.
- Elige el workflow **Build APK** → botón **Run workflow** (o se ejecuta solo con cada push).
- Espera a que termine (unos minutos). El punto se pone verde.

### 3. Descargar el APK
- Abre el run terminado.
- En la sección **Artifacts**, descarga **gear360-debug-apk**.
- Dentro está `app-debug.apk`.

### 4. Instalar en el teléfono
- Pasa el `app-debug.apk` al teléfono (cable, correo, nube).
- Ábrelo. Android pedirá permitir **instalar apps de origen desconocido** para tu explorador de archivos; actívalo e instala.

---

## Cómo usar la app (de vacaciones)

1. Enciende la Gear 360 y ponla en **modo WiFi** (crea su red `Gear 360(XXXX)`; la contraseña sale en la pantalla de la cámara).
2. En el teléfono, conéctate a esa red WiFi (Ajustes → WiFi).
   - El teléfono dirá "esta red no tiene internet": es normal, estás conectado directo a la cámara. Si Android te ofrece cambiar a datos móviles, dile que **no**, mantén la WiFi de la cámara.
3. Abre la app y pulsa **Conectar cámara**.
4. Pulsa **Descargar todo** (o el botón de descarga de cada archivo).
5. Las fotos/videos quedan en la galería del teléfono, carpeta **Gear360**.

---

## Ubicación del workflow

El workflow vive en `.github/workflows/build-apk.yml` **en la raíz del repositorio** (no dentro de `Gear360App/`), porque GitHub Actions solo detecta workflows en esa ubicación. Está configurado para compilar el proyecto de la subcarpeta `Gear360App/` (usa `working-directory: Gear360App`). No necesitas moverlo ni copiarlo.

---

## Detalles técnicos

- **Lenguaje**: Kotlin + Jetpack Compose (Material 3).
- **Red**: OkHttp hablando OSC (`/osc/info`, `/osc/commands/execute` con `camera.startSession`, `camera.listImages` con paginación, `camera.getImage`).
- **Guardado**: MediaStore (scoped storage), sin permisos de almacenamiento en Android 10+.
- **Build**: AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21 (+ plugin Compose Compiler), compileSdk 35, minSdk 26.
- **IP por defecto de la cámara**: `192.168.43.1:80`. Si tu cámara usa otra, se puede cambiar en `Gear360Client.kt` (constante `DEFAULT_IP`).

### Estructura
```
Gear360App/
├─ app/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/com/gear360/downloader/
│     │  ├─ MainActivity.kt      (UI Compose)
│     │  ├─ MainViewModel.kt     (lógica: conectar/listar/descargar)
│     │  ├─ Gear360Client.kt     (cliente OSC)
│     │  ├─ MediaSaver.kt        (guardado en galería)
│     │  └─ Theme.kt
│     └─ res/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle/ + gradlew + gradlew.bat
└─ .github/workflows/build-apk.yml
```

---

## Limitaciones honestas

- **No probé la conexión real con la cámara** (no tengo la Gear 360 aquí). El cliente OSC sigue el estándar que esta cámara implementa y es el mismo que funciona en la herramienta de PC, pero la descarga real la confirmas tú al usar la app. Si algo falla al conectar, lo más probable es la IP: avísame y lo ajustamos.
- **Sin visor 360 integrado ni stitching en el teléfono** (por ahora). La app descarga; para ver en 360 usas un visor del Play Store. El stitching fino es mejor en el PC. Si quieres el visor integrado, es una segunda fase.
