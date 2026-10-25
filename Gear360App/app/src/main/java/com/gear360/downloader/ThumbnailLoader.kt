package com.gear360.downloader

import android.content.Context
import coil.ImageLoader
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * ImageLoader de Coil para las miniaturas del servidor del mod.
 *
 * Cada miniatura la genera la camara al vuelo (jhead dentro de la camara,
 * ~350 ms), asi que limitamos a 2 peticiones simultaneas para no saturar su
 * procesador mientras la lista se desplaza. Las miniaturas quedan en la cache
 * de memoria y de disco de Coil; la respuesta de la camara no trae cabeceras
 * de cache, por eso se ignoran.
 */
object ThumbnailLoader {

    @Volatile
    private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        loader ?: synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .okHttpClient {
                    OkHttpClient.Builder()
                        .connectTimeout(4, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 2 })
                        .build()
                }
                .respectCacheHeaders(false)
                .crossfade(true)
                .build()
                .also { loader = it }
        }
}
