package com.gear360.downloader

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente de la API OSC (Open Spherical Camera) para la Samsung Gear 360 2017.
 *
 * Equivale al script de PC gear360.py: habla directamente con el servidor HTTP
 * de la camara (no usa la app descontinuada de Samsung).
 *
 * La camara, cuando crea su propia red WiFi, responde en 192.168.43.1:80.
 */
class Gear360Client(
    private val ip: String = DEFAULT_IP,
    private val port: Int = DEFAULT_PORT,
) {
    companion object {
        const val DEFAULT_IP = "192.168.43.1"
        const val DEFAULT_PORT = 80
    }

    private val baseUrl = "http://$ip:$port"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private var sessionId: String? = null

    private fun execute(name: String, parameters: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject()
            .put("name", name)
            .put("parameters", parameters)
            .toString()
            .toRequestBody(jsonType)

        val req = Request.Builder()
            .url("$baseUrl/osc/commands/execute")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("OSC error HTTP ${resp.code}: $text")
            }
            return JSONObject(text)
        }
    }

    /** GET /osc/info: datos basicos de la camara. Sirve para probar la conexion. */
    fun info(): CameraInfo {
        val req = Request.Builder().url("$baseUrl/osc/info").get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("No se pudo conectar (HTTP ${resp.code})")
            }
            val j = JSONObject(text)
            return CameraInfo(
                manufacturer = j.optString("manufacturer"),
                model = j.optString("model"),
                firmware = j.optString("firmwareVersion"),
                serial = j.optString("serialNumber"),
            )
        }
    }

    fun startSession() {
        val res = execute("camera.startSession")
        sessionId = res.getJSONObject("results").getString("sessionId")
    }

    fun closeSession() {
        val sid = sessionId ?: return
        try {
            execute("camera.closeSession", JSONObject().put("sessionId", sid))
        } catch (_: Exception) {
            // ignoramos errores al cerrar
        } finally {
            sessionId = null
        }
    }

    // --- Captura y modo -------------------------------------------------------

    /**
     * Cambia el modo de captura de la camara.
     * @param video true para modo video, false para modo foto.
     * La Gear 360 usa los valores "image" y "_video" en la opcion captureMode.
     */
    fun setCaptureMode(video: Boolean) {
        val sid = sessionId ?: return
        val options = JSONObject().put("captureMode", if (video) "_video" else "image")
        execute(
            "camera.setOptions",
            JSONObject().put("sessionId", sid).put("options", options)
        )
    }

    /**
     * Fija el formato del preview en vivo. getLivePreview devuelve HTTP 400 si
     * la camara no tiene un previewFormat valido seteado. Probamos una
     * resolucion comun y de bajo peso. Si la camara rechaza el valor, se
     * ignora el error (puede que use uno por defecto).
     */
    private fun trySetPreviewFormat() {
        val sid = sessionId ?: return
        // Formato tipico soportado por la Gear 360 para el preview.
        val previewFormat = JSONObject()
            .put("width", 1024)
            .put("height", 512)
            .put("framerate", 30)
        val options = JSONObject().put("previewFormat", previewFormat)
        try {
            execute(
                "camera.setOptions",
                JSONObject().put("sessionId", sid).put("options", options)
            )
        } catch (_: Exception) {
            // Si no acepta este formato, seguimos: la camara puede tener uno
            // por defecto que igual sirva para el stream.
        }
    }

    /** Dispara una foto (modo foto). Devuelve true si la camara acepto el comando. */
    fun takePicture(): Boolean {
        val sid = sessionId ?: return false
        val res = execute("camera.takePicture", JSONObject().put("sessionId", sid))
        // La camara responde con state "inProgress" o "done"; ambos son exito.
        val state = res.optString("state", "")
        return state == "inProgress" || state == "done" || res.has("results")
    }

    /** Inicia la grabacion de video (modo video). */
    fun startCapture(): Boolean {
        val sid = sessionId ?: return false
        val res = execute("camera.startCapture", JSONObject().put("sessionId", sid))
        return res.optString("state", "").let { it == "inProgress" || it == "done" } ||
            res.has("results")
    }

    /** Detiene la grabacion de video en curso. */
    fun stopCapture(): Boolean {
        val sid = sessionId ?: return false
        val res = execute("camera.stopCapture", JSONObject().put("sessionId", sid))
        return res.has("results") || res.optString("state", "") == "done"
    }

    /**
     * Abre el stream de vista en vivo (camera.getLivePreview).
     * La camara responde con un stream MJPEG: multipart/x-mixed-replace, es
     * decir una secuencia continua de imagenes JPEG. Esta funcion entrega
     * cada frame JPEG (como ByteArray) a [onFrame] hasta que [shouldStop]
     * devuelva true o se corte la conexion.
     *
     * Pensada para ejecutarse en un hilo de IO.
     */
    fun streamLivePreview(
        onFrame: (ByteArray) -> Unit,
        shouldStop: () -> Boolean,
    ) {
        // Paso previo: fijar el formato del preview. Sin esto, la Gear 360
        // suele responder HTTP 400 a getLivePreview.
        trySetPreviewFormat()

        // El stream es largo: usamos un cliente con readTimeout amplio.
        val streamClient = http.newBuilder()
            .readTimeout(0, TimeUnit.SECONDS) // sin timeout de lectura
            .build()

        // Probamos dos variantes del cuerpo, porque segun la firmware la
        // Gear 360 acepta una u otra:
        //   1) sin sessionId (API level 2, sessionId esta deprecado)
        //   2) con sessionId  (firmwares mas antiguas)
        val bodies = buildList {
            add(
                JSONObject()
                    .put("name", "camera.getLivePreview")
                    .put("parameters", JSONObject())
                    .toString()
            )
            sessionId?.let { sid ->
                add(
                    JSONObject()
                        .put("name", "camera.getLivePreview")
                        .put("parameters", JSONObject().put("sessionId", sid))
                        .toString()
                )
            }
        }

        var lastCode = -1
        for (bodyStr in bodies) {
            if (shouldStop()) return
            val req = Request.Builder()
                .url("$baseUrl/osc/commands/execute")
                .post(bodyStr.toRequestBody(jsonType))
                .build()

            val resp = streamClient.newCall(req).execute()
            if (resp.isSuccessful) {
                resp.use { r ->
                    val source = r.body?.byteStream()
                        ?: throw RuntimeException("Vista en vivo sin cuerpo")
                    parseMjpeg(source, onFrame, shouldStop)
                }
                return
            } else {
                lastCode = resp.code
                resp.close()
            }
        }
        throw RuntimeException("No se pudo iniciar la vista en vivo (HTTP $lastCode)")
    }

    /**
     * Parsea un stream MJPEG extrayendo cada imagen JPEG. Busca los marcadores
     * de inicio (FF D8) y fin (FF D9) de cada JPEG en el flujo de bytes.
     */
    private fun parseMjpeg(
        input: java.io.InputStream,
        onFrame: (ByteArray) -> Unit,
        shouldStop: () -> Boolean,
    ) {
        val buffer = java.io.ByteArrayOutputStream()
        var prev = -1
        var inImage = false
        val reader = java.io.BufferedInputStream(input)

        while (!shouldStop()) {
            val b = reader.read()
            if (b == -1) break

            if (!inImage) {
                // Buscamos el marcador de inicio de JPEG: 0xFF 0xD8
                if (prev == 0xFF && b == 0xD8) {
                    inImage = true
                    buffer.reset()
                    buffer.write(0xFF)
                    buffer.write(0xD8)
                }
                prev = b
            } else {
                buffer.write(b)
                // Marcador de fin de JPEG: 0xFF 0xD9
                if (prev == 0xFF && b == 0xD9) {
                    onFrame(buffer.toByteArray())
                    inImage = false
                    buffer.reset()
                    prev = -1
                } else {
                    prev = b
                }
            }
        }
    }

    /**
     * Lista todas las fotos y videos de la camara, manejando paginacion
     * (continuationToken) para traer el contenido completo.
     *
     * @param includeThumb si true, pide a la camara la miniatura (base64) de
     *        cada archivo para mostrar vista previa. Hace el listado mas lento
     *        y pesado, por eso es opcional.
     */
    /**
     * Ultima respuesta cruda del listado, para diagnostico. La UI puede
     * mostrarla si algo no aparece como se espera.
     */
    @Volatile
    var lastListRawResponse: String = ""
        private set

    /**
     * Lista las FOTOS de la camara (camera.listImages). En la Gear 360 este
     * comando solo devuelve imagenes, no videos (los videos se buscan aparte
     * con findVideos).
     */
    fun listImages(pageSize: Int = 100, includeThumb: Boolean = true): List<MediaEntry> {
        // Intento 1: listFiles con fileType="image" (API v2) si la soporta.
        try {
            val viaFiles = listViaListFiles(pageSize, includeThumb)
            if (viaFiles.isNotEmpty()) return viaFiles
        } catch (_: Exception) {
        }
        // Intento 2 (el que funciona en tu firmware): listImages.
        return listViaListImages(pageSize, includeThumb)
    }

    /**
     * Busca VIDEOS (.MP4) en la camara por numeracion. La Gear 360 no los
     * lista con listImages, pero SI se pueden pedir por su ruta directa.
     *
     * Estrategia: a partir de las fotos ya listadas, deducimos la carpeta
     * (ej. /mnt/mmc/DCIM/100PHOTO/) y el rango de numeros de archivo
     * (los nombres son del tipo PREFIJO_NNNN.ext). Para cada numero del rango
     * probamos si existe un .MP4 con los prefijos conocidos ("360_", "SAM_").
     *
     * @param photos lista de fotos ya obtenida (para deducir carpeta y rango).
     * @param onProgress reporta (actual, total) del escaneo.
     * @param shouldStop permite cancelar el escaneo.
     */
    fun findVideos(
        photos: List<MediaEntry>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false },
    ): List<MediaEntry> {
        if (photos.isEmpty()) return emptyList()

        // Carpeta base: tomamos la del primer archivo con uri valida.
        val sampleUri = photos.firstOrNull { it.uri.contains("/") }?.uri ?: return emptyList()
        val folder = sampleUri.substringBeforeLast('/')   // /mnt/mmc/DCIM/100PHOTO

        // Numeros ya ocupados por FOTOS (esos no son videos, no hay que probarlos).
        val photoNumbers = photos.mapNotNull { extractNumber(it.fileName) }.toSet()
        if (photoNumbers.isEmpty()) return emptyList()
        val min = photoNumbers.min()
        val max = photoNumbers.max()

        // OPTIMIZACION CLAVE: los videos ocupan los "huecos" de numeracion que
        // dejan las fotos. En vez de probar TODO el rango, solo probamos los
        // numeros que NO tienen foto (mas un pequeno margen por los extremos).
        val from = (min - 3).coerceAtLeast(0)
        val to = max + 3
        val candidates = (from..to).filter { it !in photoNumbers }

        // En la Gear 360 los videos usan prefijo "360_". Probamos ese primero
        // y "SAM_" solo como respaldo.
        val prefixes = listOf("360_", "SAM_")

        val videos = ArrayList<MediaEntry>()
        val total = candidates.size
        var done = 0

        for (n in candidates) {
            if (shouldStop()) break
            val nnnn = n.toString().padStart(4, '0')
            for (prefix in prefixes) {
                if (shouldStop()) break
                val fileName = "$prefix$nnnn.MP4"
                val uri = "$folder/$fileName"
                val size = headSize(uri)
                if (size > 0) {
                    videos.add(
                        MediaEntry(
                            name = fileName,
                            uri = uri,
                            size = size,
                            mimeType = "video/mp4",
                        )
                    )
                    break
                }
            }
            done++
            onProgress(done, total)
        }
        return videos
    }

    /** Extrae el numero NNNN de un nombre tipo "360_0270.MP4" o "SAM_0302.JPG". */
    private fun extractNumber(fileName: String): Int? {
        val m = Regex("""_(\d{3,5})\.""").find(fileName) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    // Cliente dedicado para comprobar existencia: timeouts CORTOS para que un
    // archivo inexistente falle rapido y no bloquee el escaneo de videos.
    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Devuelve el tamano en bytes de un archivo si existe, o 0 si no existe.
     *
     * CLAVE DE RENDIMIENTO: leemos SOLO la cabecera Content-Length y cerramos
     * la respuesta de inmediato SIN consumir el cuerpo. Antes el cuerpo (un
     * video de cientos de MB) empezaba a transferirse y saturaba la camara,
     * haciendo el escaneo lentisimo. Ademas usamos timeouts cortos.
     */
    private fun headSize(fileUri: String): Long {
        val body = JSONObject()
            .put("name", "camera.getImage")
            .put("parameters", JSONObject().put("fileUri", fileUri))
            .toString()
            .toRequestBody(jsonType)
        val req = Request.Builder()
            .url("$baseUrl/osc/commands/execute")
            .post(body)
            .build()
        return try {
            val resp = probeClient.newCall(req).execute()
            val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            val ok = resp.isSuccessful
            // Cerramos SIN leer el cuerpo: corta la transferencia del video.
            resp.close()
            when {
                !ok -> 0L
                len > 0 -> len
                else -> 1L
            }
        } catch (_: Exception) {
            0L
        }
    }

    /** Listado con camera.listFiles (API v2): fotos + videos + miniaturas. */
    private fun listViaListFiles(pageSize: Int, includeThumb: Boolean): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        var start = 0

        while (true) {
            val params = JSONObject()
                .put("fileType", "all")          // fotos Y videos
                .put("startPosition", start)
                .put("entryCount", pageSize)
            if (includeThumb) params.put("maxThumbSize", 320) // activa miniaturas

            val res = execute("camera.listFiles", params)
            lastListRawResponse = truncateForDiag(res.toString())

            val results = res.optJSONObject("results") ?: break
            val entries: JSONArray = results.optJSONArray("entries") ?: JSONArray()
            if (entries.length() == 0) break

            for (i in 0 until entries.length()) {
                out.add(parseEntry(entries.getJSONObject(i)))
            }

            val total = results.optInt("totalEntries", out.size)
            start += entries.length()
            if (start >= total) break
        }
        return out
    }

    /** Listado con camera.listImages (API v1), como respaldo. */
    private fun listViaListImages(pageSize: Int, includeThumb: Boolean): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        var token: String? = null

        while (true) {
            val params = JSONObject()
                .put("entryCount", pageSize)
                .put("includeThumb", includeThumb)
                .put("maxSize", 320)
            if (token != null) params.put("continuationToken", token)

            val res = execute("camera.listImages", params)
            lastListRawResponse = truncateForDiag(res.toString())

            val results = res.optJSONObject("results") ?: break
            val entries: JSONArray = results.optJSONArray("entries") ?: JSONArray()

            for (i in 0 until entries.length()) {
                out.add(parseEntry(entries.getJSONObject(i)))
            }

            token = results.optString("continuationToken", "").ifEmpty { null }
            if (token == null || entries.length() == 0) break
        }
        return out
    }

    /**
     * Convierte un entry JSON (de listFiles o listImages) a MediaEntry,
     * tolerando los distintos nombres de campo que usan las firmwares.
     */
    private fun parseEntry(e: JSONObject): MediaEntry {
        // La URL del archivo puede venir como fileUrl (listFiles) o uri (listImages).
        val url = e.optString("fileUrl", e.optString("uri", ""))
        val mime = e.optString("_mimeType", e.optString("mimeType", ""))
        val recordTime = e.optInt("_recordTime", e.optInt("recordTime", 0))
        // La miniatura puede venir en "thumbnail" (base64).
        val thumb = e.optString("thumbnail", "").ifEmpty { null }
        return MediaEntry(
            name = e.optString("name", url),
            uri = url,
            size = e.optLong("size", 0L),
            mimeType = mime,
            recordTimeSec = recordTime,
            thumbnailBase64 = thumb,
        )
    }

    private fun truncateForDiag(s: String): String =
        if (s.length > 1200) s.substring(0, 1200) + "...(recortado)" else s

    /**
     * Descarga el binario de un archivo (por su uri) y entrega los bytes por
     * bloques a [onChunk], informando progreso con [onProgress].
     * Devuelve el total de bytes escritos.
     */
    fun downloadFile(
        fileUri: String,
        onChunk: (ByteArray, Int) -> Unit,
        onProgress: (bytesSoFar: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Long {
        val body = JSONObject()
            .put("name", "camera.getImage")
            .put(
                "parameters",
                JSONObject().put("fileUri", fileUri).put("_type", "image")
            )
            .toString()
            .toRequestBody(jsonType)

        val req = Request.Builder()
            .url("$baseUrl/osc/commands/execute")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Error al descargar (HTTP ${resp.code})")
            }
            val respBody = resp.body ?: throw RuntimeException("Respuesta vacia")
            val total = respBody.contentLength()
            var written = 0L
            val stream = respBody.byteStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                onChunk(buffer, read)
                written += read
                onProgress(written, total)
            }
            return written
        }
    }
}

data class CameraInfo(
    val manufacturer: String,
    val model: String,
    val firmware: String,
    val serial: String,
)

data class MediaEntry(
    val name: String,
    val uri: String,
    val size: Long,
    val mimeType: String = "",
    val recordTimeSec: Int = 0,
    /** Miniatura en base64 (si la camara la envio con includeThumb). */
    val thumbnailBase64: String? = null,
) {
    /**
     * Detecta si es video mirando varias pistas, porque distintas firmwares
     * de la Gear 360 rellenan campos distintos:
     *  - extension del nombre/uri (.mp4/.mov)
     *  - mimeType que empieza con "video"
     *  - duracion de grabacion > 0 (solo los videos la tienen)
     */
    val isVideo: Boolean
        get() {
            val n = (uri.ifEmpty { name }).lowercase()
            return n.endsWith(".mp4") ||
                n.endsWith(".mov") ||
                mimeType.startsWith("video", ignoreCase = true) ||
                recordTimeSec > 0
        }

    /** Nombre de archivo limpio (ultima parte del uri/name). */
    val fileName: String
        get() = (uri.ifEmpty { name }).substringAfterLast('/')
}
