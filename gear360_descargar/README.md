# Gear 360 Downloader

Herramienta para **descargar las fotos y videos de una Samsung Gear 360 (New) 2017 (SM-R210)** por WiFi, sin depender de la app oficial **Gear 360 Manager** de Samsung (descontinuada en agosto de 2020 y que ya no funciona en Android 11+).

Habla directamente con la cámara usando el estándar **OSC (Open Spherical Camera API)** sobre HTTP, que esta cámara implementa de fábrica.

---

## ¿Por qué no se usa la app ni Bluetooth?

- La app oficial solo soporta Android 6–10 y Samsung cortó el soporte en 2020. No es un bug "reparable": el sistema operativo cambió las reglas de permisos de almacenamiento (Android 11+) y la app quedó obsoleta.
- **El Bluetooth de la Gear 360 NO transfiere fotos.** Solo servía para emparejar y controlar la cámara. Los archivos (fotos de 15 MP, videos 4K) siempre se transfieren por **WiFi**, incluso en la app original. Por eso esta herramienta usa WiFi.

---

## Requisitos

- Un PC con **Python 3** (ya probado con 3.10).
- La librería `requests` (ya instalada en este equipo). Si faltara:
  ```
  pip install requests
  ```

---

## Paso a paso

### 1. Poner la cámara en modo WiFi
- Enciende la Gear 360.
- Entra al menú de la cámara y activa el modo de **conexión WiFi** (WiFi Direct). La cámara crea su propia red WiFi, con un nombre tipo `Gear 360(XXXX)`.
- La **contraseña** de esa red aparece en la pantalla de la cámara.

> Importante: no la pongas en el modo "conectar vía app del teléfono". Necesitas el modo WiFi en el que la cámara expone su propia red (el que usaba Street View / OSC).

### 2. Conectar el PC a la WiFi de la cámara
- En Windows, abre la lista de redes WiFi y conéctate a la red `Gear 360(XXXX)` usando la contraseña de la pantalla de la cámara.
- Mientras estés conectado a esa red **no tendrás internet** en el PC: es normal, estás conectado directo a la cámara.

### 3. Probar la conexión
Abre PowerShell en esta carpeta y ejecuta:
```
python gear360.py info
```
Si todo va bien, verás el modelo, firmware y número de serie de la cámara.

Si da **timeout**, la IP de tu cámara puede ser distinta. Averigua el *gateway* de la conexión WiFi:
```
ipconfig
```
Busca "Puerta de enlace predeterminada" de la conexión WiFi (ej. `192.168.43.1` o `192.168.107.1`) y pásala con `--ip`:
```
python gear360.py info --ip 192.168.107.1
```

### 4. Listar lo que hay en la cámara
```
python gear360.py list
```
Muestra todas las fotos y videos con su tamaño.

### 5. Descargar (con costura 360 automática)
Por defecto, al descargar **se genera automáticamente la versión 360** (equirectangular) de cada foto/video, además del archivo original.

Descargar **todo** a la carpeta `descargas/`:
```
python gear360.py download
```
Cada archivo `X.jpg` genera también `X_360.jpg` (el 360 listo para ver).

Descargar solo la **última** captura:
```
python gear360.py download-latest
```
Elegir otra carpeta de destino:
```
python gear360.py download -o C:\Fotos\Gear360
```
Si prefieres **no** coser y quedarte solo con el original dual-fisheye:
```
python gear360.py download --no-stitch
```

### 6. Coser archivos ya descargados
Si ya tienes archivos dual-fisheye y quieres convertirlos a 360:
```
python gear360.py stitch foto1.jpg video1.mp4
```
Genera `foto1_360.jpg`, `video1_360.mp4`, etc.

---

## Comandos disponibles

| Comando            | Qué hace                                                    |
|--------------------|-------------------------------------------------------------|
| `info`             | Prueba la conexión y muestra datos de la cámara             |
| `list`             | Lista las fotos/videos                                      |
| `download`         | Descarga TODOS los archivos y los cose a 360                |
| `download-latest`  | Descarga solo la última captura y la cose a 360             |
| `stitch ARCHIVOS`  | Cose a 360 archivos dual-fisheye ya descargados             |

Opciones globales: `--ip`, `--port`, `--output/-o`.
Opción de descarga: `--no-stitch` (deja el original dual-fisheye sin coser).

---

## Sobre la costura (stitching) a 360

Los archivos de la Gear 360 salen en formato **dual-fisheye**: dos círculos lado a lado (una lente delante, otra detrás). Para verlos como esfera 360 hay que "coserlos" (*stitching*) a formato **equirectangular**.

Esta herramienta lo hace automáticamente con **ffmpeg** y su filtro `v360` (dual-fisheye → equirectangular). El resultado es un 360 válido que se ve en cualquier visor 360.

### Requisito: ffmpeg
El stitching necesita **ffmpeg** instalado. En este equipo ya está instalado (vía `winget install Gyan.FFmpeg`). Si lo reinstalas en otro PC:
```
winget install Gyan.FFmpeg
```
Luego **abre una terminal nueva** para que reconozca el comando `ffmpeg`.

Si ffmpeg no está disponible, la descarga igual funciona: se guardan los archivos originales dual-fisheye y se avisa que no se pudo coser.

### Calidad de la costura
El filtro usa un campo de visión de ~195° por lente (`ih_fov=195:iv_fov=195`), un buen punto de partida para la Gear 360. Si notas la costura desalineada, se pueden ajustar esos valores en `stitch.py` (función `_v360_filter`). Samsung usaba calibración propietaria de sus lentes, así que **Gear 360 ActionDirector** (escritorio, aún descargable) puede dar una costura algo más fina, pero no se automatiza; esta herramienta prioriza el proceso 100% automático.

---

## Alternativa siempre fiable: la microSD

Si la WiFi falla o solo quieres recuperar lo ya grabado, la vía más segura es sacar la **microSD** de la cámara, ponerla en un lector y copiar los archivos directamente. Cero software, cero dependencias.
