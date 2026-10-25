package com.gear360.downloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Comprueba si hay una version nueva de la app publicada en GitHub Releases
 * y permite descargar e instalar el APK desde la propia app.
 *
 * Flujo:
 *  1. checkForUpdate() consulta la ultima release en GitHub y la compara con
 *     la version instalada.
 *  2. Si hay una mas nueva, la UI avisa y ofrece actualizar.
 *  3. downloadAndInstall() baja el APK a la cache y lanza el instalador del
 *     sistema (Android pide confirmacion al usuario; no se instala solo).
 */
object UpdateChecker {

    // Repositorio de releases. Si cambias de repo, actualiza estos valores.
    private const val OWNER = "totoNacho"
    private const val REPO = "gear360"
    private const val LATEST_URL =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionName: String,   // ej. "1.0.12"
        val apkUrl: String,        // URL directa al APK
        val notes: String,         // descripcion de la release
    )

    /**
     * Consulta la ultima release. Devuelve UpdateInfo solo si es MAS NUEVA que
     * la instalada; null si ya estas al dia o si falla la consulta.
     */
    fun checkForUpdate(currentVersion: String): UpdateInfo? {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string().orEmpty())

            // tag_name viene como "v1.0.12"; quitamos la 'v'.
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isEmpty()) return null

            // Buscamos el asset .apk dentro de la release.
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl == null) return null

            if (!isNewer(tag, currentVersion)) return null

            return UpdateInfo(
                versionName = tag,
                apkUrl = apkUrl,
                notes = json.optString("body"),
            )
        }
    }

    /**
     * Compara dos versiones tipo "1.0.12". Devuelve true si [candidate] es
     * estrictamente mayor que [current].
     */
    private fun isNewer(candidate: String, current: String): Boolean {
        val c = candidate.split(".").mapNotNull { it.toIntOrNull() }
        val cur = current.split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(c.size, cur.size)
        for (i in 0 until n) {
            val a = c.getOrElse(i) { 0 }
            val b = cur.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * Descarga el APK a la cache y lanza el instalador del sistema.
     * [onProgress] reporta 0..100. Lanza excepcion si algo falla.
     */
    fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit,
    ) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Limpiamos APKs viejos para no acumular.
        dir.listFiles()?.forEach { it.delete() }
        val apkFile = File(dir, "gear360-update.apk")

        val req = Request.Builder().url(apkUrl).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("No se pudo descargar la actualizacion (HTTP ${resp.code})")
            }
            val body = resp.body ?: throw RuntimeException("Descarga vacia")
            val total = body.contentLength()
            var written = 0L
            apkFile.outputStream().use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    written += read
                    if (total > 0) onProgress((written * 100 / total).toInt())
                }
            }
        }

        // Lanzamos el instalador del sistema con el APK descargado.
        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
