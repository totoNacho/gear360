package com.gear360.downloader

import java.io.File
import java.io.RandomAccessFile

/**
 * Inyecta metadatos 360 (spherical video) en un MP4 ya cosido a equirectangular,
 * para que YouTube, Facebook y otros reproductores lo muestren como esfera
 * navegable.
 *
 * Replica EXACTAMENTE lo que hace la herramienta oficial de Google
 * (spatial-media): inserta un atomo "uuid" con el UUID magico de spherical
 * video y un XML rdf:SphericalVideo dentro del atomo "moov" del MP4.
 *
 * Este es el formato clasico (GSpherical/UUID) que YouTube reconoce y que
 * verificamos que funciona.
 */
object Spherical360Injector {

    // UUID magico de spherical video de Google (16 bytes).
    private val SPHERICAL_UUID = byteArrayOf(
        0xFF.toByte(), 0xCC.toByte(), 0x82.toByte(), 0x63.toByte(),
        0xF8.toByte(), 0x55.toByte(), 0x4A.toByte(), 0x93.toByte(),
        0x88.toByte(), 0x14.toByte(), 0x58.toByte(), 0x7A.toByte(),
        0x02.toByte(), 0x52.toByte(), 0x1F.toByte(), 0xDD.toByte(),
    )

    // XML de metadatos esfericos (equirectangular, ya cosido).
    private const val SPHERICAL_XML =
        "<?xml version=\"1.0\"?>" +
        "<rdf:SphericalVideo\n" +
        "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n" +
        "xmlns:GSpherical=\"http://ns.google.com/videos/1.0/spherical/\">" +
        "<GSpherical:Spherical>true</GSpherical:Spherical>" +
        "<GSpherical:Stitched>true</GSpherical:Stitched>" +
        "<GSpherical:StitchingSoftware>Gear360 Downloader</GSpherical:StitchingSoftware>" +
        "<GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>" +
        "</rdf:SphericalVideo>"

    /**
     * Inyecta los metadatos 360 en [mp4File] (lo modifica in-place reescribiendolo).
     * Devuelve true si tuvo exito.
     *
     * Estrategia: localizamos el atomo "moov" de nivel superior y le anadimos
     * dentro un atomo "uuid" (con el UUID esferico + XML). Ajustamos el tamano
     * del moov. El atomo uuid dentro de moov es reconocido por los reproductores.
     */
    fun inject(mp4File: File): Boolean {
        return try {
            val data = mp4File.readBytes()
            val moov = findTopLevelAtom(data, "moov") ?: return false

            // Construimos el atomo uuid: [size(4)][="uuid"(4)][UUID(16)][XML]
            val xmlBytes = SPHERICAL_XML.toByteArray(Charsets.UTF_8)
            val uuidAtomSize = 4 + 4 + 16 + xmlBytes.size
            val uuidAtom = ByteArray(uuidAtomSize)
            writeInt32(uuidAtom, 0, uuidAtomSize)
            "uuid".toByteArray(Charsets.US_ASCII).copyInto(uuidAtom, 4)
            SPHERICAL_UUID.copyInto(uuidAtom, 8)
            xmlBytes.copyInto(uuidAtom, 24)

            // Insertamos el uuidAtom JUSTO despues del final del contenido actual
            // del moov (al final del moov), y aumentamos el tamano del moov.
            val moovStart = moov.first
            val moovEnd = moov.second            // posicion tras el ultimo byte del moov
            val oldMoovSize = readInt32(data, moovStart)
            val newMoovSize = oldMoovSize + uuidAtomSize

            val out = ByteArray(data.size + uuidAtomSize)
            // 1) Todo hasta el final del moov.
            data.copyInto(out, 0, 0, moovEnd)
            // 2) El atomo uuid, insertado dentro del moov (al final de su contenido).
            uuidAtom.copyInto(out, moovEnd)
            // 3) El resto del archivo (lo que iba despues del moov).
            data.copyInto(out, moovEnd + uuidAtomSize, moovEnd, data.size)
            // 4) Corregir el tamano del moov en su cabecera.
            writeInt32(out, moovStart, newMoovSize)

            // Si el moov estuviera ANTES del mdat, los offsets de muestras (stco)
            // se desplazarian. Para evitar corrupcion, solo inyectamos cuando el
            // moov esta al FINAL (tras el mdat), que es lo habitual en los MP4 de
            // ffmpeg con +faststart desactivado. Verificamos ese caso.
            val mdat = findTopLevelAtom(data, "mdat")
            if (mdat != null && moovStart < mdat.first) {
                // moov antes de mdat: la insercion desplazaria el mdat. No es
                // seguro sin reescribir los offsets. Abortamos para no corromper.
                return false
            }

            mp4File.writeBytes(out)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Busca un atomo de nivel superior por su nombre (4 chars). Devuelve
     * (posicionInicio, posicionFin) o null. posicionFin es el byte siguiente
     * al atomo.
     */
    private fun findTopLevelAtom(data: ByteArray, name: String): Pair<Int, Int>? {
        var pos = 0
        val target = name.toByteArray(Charsets.US_ASCII)
        while (pos + 8 <= data.size) {
            val size = readInt32(data, pos)
            val matches = data[pos + 4] == target[0] && data[pos + 5] == target[1] &&
                data[pos + 6] == target[2] && data[pos + 7] == target[3]
            if (matches) return Pair(pos, pos + size)
            if (size <= 0) break   // tamano invalido, evitar bucle infinito
            pos += size
        }
        return null
    }

    private fun readInt32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
        ((data[offset + 1].toInt() and 0xFF) shl 16) or
        ((data[offset + 2].toInt() and 0xFF) shl 8) or
        (data[offset + 3].toInt() and 0xFF)

    private fun writeInt32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }
}
