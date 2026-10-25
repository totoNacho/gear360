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

    /**
     * Lista todas las fotos y videos de la camara, manejando paginacion
     * (continuationToken) para traer el contenido completo.
     *
     * @param includeThumb si true, pide a la camara la miniatura (base64) de
     *        cada archivo para mostrar vista previa. Hace el listado mas lento
     *        y pesado, por eso es opcional.
     */
    fun listImages(pageSize: Int = 100, includeThumb: Boolean = true): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        var token: String? = null

        while (true) {
            val params = JSONObject()
                .put("entryCount", pageSize)
                .put("includeThumb", includeThumb)
                // _detail=true hace que la Gear 360 incluya metadatos extra
                // (tipo de archivo, dimensiones) y ademas la miniatura.
                .put("_detail", true)
            if (token != null) params.put("continuationToken", token)

            val res = execute("camera.listImages", params)
            val results = res.optJSONObject("results") ?: break
            val entries: JSONArray = results.optJSONArray("entries") ?: JSONArray()

            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                // Campos que ayudan a distinguir foto de video. Distintas
                // firmwares usan nombres distintos, asi que miramos varios.
                val mime = e.optString("_mimeType", e.optString("mimeType", ""))
                val recordTime = e.optInt("_recordTime", e.optInt("recordTime", 0))
                out.add(
                    MediaEntry(
                        name = e.optString("name", e.optString("uri")),
                        uri = e.optString("uri"),
                        size = e.optLong("size", 0L),
                        mimeType = mime,
                        // Un video tiene duracion de grabacion > 0.
                        recordTimeSec = recordTime,
                        thumbnailBase64 = e.optString("thumbnail", "").ifEmpty { null },
                    )
                )
            }

            token = results.optString("continuationToken", "").ifEmpty { null }
            if (token == null || entries.length() == 0) break
        }
        return out
    }

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
