package com.gear360.downloader

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Cose (stitching) archivos dual-fisheye de la Gear 360 a formato
 * equirectangular 360 usando FFmpeg (filtro v360), igual que la herramienta
 * de PC del proyecto.
 *
 * La Gear 360 graba cada captura como DOS circulos fisheye lado a lado. El
 * filtro v360 los convierte en una imagen/video equirectangular que los
 * visores 360 (Google Fotos, etc.) muestran como esfera navegable.
 */
object Stitcher {

    /**
     * Filtro v360: dual-fisheye (dos circulos lado a lado) -> equirectangular.
     * ih_fov/iv_fov = ~195 grados por lente (valor que funciona en la Gear 360).
     */
    private const val V360 = "v360=dfisheye:e:ih_fov=195:iv_fov=195"

    sealed class Result {
        data class Ok(val outputPath: String) : Result()
        data class Error(val message: String) : Result()
    }

    /**
     * Cose un archivo (foto o video) a 360. Devuelve la ruta del archivo
     * resultante (con sufijo _360) o un error.
     *
     * @param inputPath ruta del archivo dual-fisheye de entrada.
     * @param onProgress progreso aproximado 0..100 (solo videos; fotos van directo).
     */
    fun stitch(inputPath: String, onProgress: (Int) -> Unit = {}): Result {
        val input = File(inputPath)
        if (!input.exists()) return Result.Error("El archivo no existe")

        val isVideo = inputPath.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mov") }
        val base = input.nameWithoutExtension
        val ext = input.extension
        val outputFile = File(input.parentFile, "${base}_360.$ext")
        val out = outputFile.absolutePath

        // Comando FFmpeg.
        // - Video: recodificamos VIDEO (v360) y AUDIO a AAC. NO usar "-c:a copy"
        //   porque corrompia el MP4 (moov atom not found). El moov queda al
        //   final (sin +faststart) para que el inyector 360 pueda anadir el
        //   atomo uuid sin desplazar offsets.
        // - Foto: solo el filtro v360.
        val cmd = if (isVideo) {
            "-y -i \"$inputPath\" -vf $V360 -c:v libx264 -preset veryfast -c:a aac \"$out\""
        } else {
            "-y -i \"$inputPath\" -vf $V360 \"$out\""
        }

        val session = FFmpegKit.execute(cmd)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            return Result.Error("FFmpeg fallo (code ${session.returnCode}). ${session.failStackTrace ?: ""}")
        }

        // Para VIDEO: inyectar metadatos 360 para que YouTube/Facebook lo
        // reconozcan como esfera navegable (coser no basta por si solo).
        if (isVideo) {
            Spherical360Injector.inject(outputFile)
            // Si la inyeccion falla, el video igual quedo cosido (equirectangular);
            // simplemente no llevara el metadato. No es motivo de error.
        }

        return Result.Ok(out)
    }
}
