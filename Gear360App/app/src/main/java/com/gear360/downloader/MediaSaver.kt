package com.gear360.downloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

/**
 * Guarda los archivos descargados en la galeria del telefono usando MediaStore
 * (scoped storage). Esto funciona en Android moderno SIN pedir permisos de
 * almacenamiento, que es justo lo que rompia a la app vieja de Samsung.
 *
 * Las fotos van a   Pictures/Gear360
 * Los videos van a  Movies/Gear360
 */
class MediaSaver(private val context: Context) {

    companion object {
        private const val SUBDIR = "Gear360"
    }

    /**
     * Abre un OutputStream hacia un nuevo archivo en la galeria.
     * Devuelve el stream y un "finalize" que hay que llamar al terminar de
     * escribir (marca el archivo como completo / visible).
     */
    fun openOutput(fileName: String, isVideo: Boolean): SaveHandle {
        val resolver = context.contentResolver

        val collection = if (isVideo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val relativeBase = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(fileName, isVideo))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeBase/$SUBDIR")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collection, values)
            ?: throw RuntimeException("No se pudo crear el archivo en la galeria")

        val stream = resolver.openOutputStream(uri)
            ?: throw RuntimeException("No se pudo abrir el archivo para escribir")

        return SaveHandle(
            stream = stream,
            finalize = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                }
            }
        )
    }

    private fun mimeOf(fileName: String, isVideo: Boolean): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".png") -> "image/png"
            isVideo -> "video/mp4"
            else -> "image/jpeg"
        }
    }

    class SaveHandle(
        val stream: OutputStream,
        private val finalize: () -> Unit,
    ) {
        fun complete() {
            stream.flush()
            stream.close()
            finalize()
        }

        fun abortQuietly() {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
    }
}
