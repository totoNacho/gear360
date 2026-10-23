package com.gear360.downloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado de descarga de un archivo individual. */
enum class DownloadState { PENDING, DOWNLOADING, DONE, ERROR }

data class MediaItem(
    val entry: MediaEntry,
    val state: DownloadState = DownloadState.PENDING,
    val progress: Int = 0,          // 0..100
    val error: String? = null,
)

data class UiState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val cameraInfo: CameraInfo? = null,
    val loadingList: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val message: String? = null,    // mensaje de estado/error a mostrar
    val downloadingAll: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val client = Gear360Client()
    private val saver = MediaSaver(app.applicationContext)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Prueba la conexion con la camara (GET /osc/info). */
    fun connect() {
        _ui.update { it.copy(connecting = true, message = null) }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { client.info() }
                _ui.update {
                    it.copy(
                        connecting = false,
                        connected = true,
                        cameraInfo = info,
                        message = "Conectado a ${info.model.ifEmpty { "la camara" }}",
                    )
                }
                loadList()
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        connecting = false,
                        connected = false,
                        message = conexionError(e),
                    )
                }
            }
        }
    }

    /** Lista las fotos/videos de la camara. */
    fun loadList() {
        _ui.update { it.copy(loadingList = true, message = null) }
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    client.startSession()
                    try {
                        client.listImages()
                    } finally {
                        client.closeSession()
                    }
                }
                _ui.update {
                    it.copy(
                        loadingList = false,
                        items = entries.map { e -> MediaItem(e) },
                        message = if (entries.isEmpty())
                            "No se encontraron fotos ni videos."
                        else
                            "${entries.size} archivos en la camara.",
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(loadingList = false, message = conexionError(e))
                }
            }
        }
    }

    /** Descarga un archivo concreto a la galeria del telefono. */
    fun download(item: MediaItem) {
        updateItem(item.entry.uri) { it.copy(state = DownloadState.DOWNLOADING, progress = 0, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { doDownload(item) }
                updateItem(item.entry.uri) { it.copy(state = DownloadState.DONE, progress = 100) }
            } catch (e: Exception) {
                updateItem(item.entry.uri) {
                    it.copy(state = DownloadState.ERROR, error = e.message ?: "error")
                }
            }
        }
    }

    /** Descarga TODOS los archivos pendientes, uno por uno. */
    fun downloadAll() {
        _ui.update { it.copy(downloadingAll = true, message = "Descargando todo...") }
        viewModelScope.launch {
            val pending = _ui.value.items.filter { it.state != DownloadState.DONE }
            for (item in pending) {
                updateItem(item.entry.uri) {
                    it.copy(state = DownloadState.DOWNLOADING, progress = 0, error = null)
                }
                try {
                    withContext(Dispatchers.IO) { doDownload(item) }
                    updateItem(item.entry.uri) { it.copy(state = DownloadState.DONE, progress = 100) }
                } catch (e: Exception) {
                    updateItem(item.entry.uri) {
                        it.copy(state = DownloadState.ERROR, error = e.message ?: "error")
                    }
                }
            }
            _ui.update { it.copy(downloadingAll = false, message = "Descarga finalizada.") }
        }
    }

    private fun doDownload(item: MediaItem) {
        val handle = saver.openOutput(item.entry.fileName, item.entry.isVideo)
        try {
            client.downloadFile(
                fileUri = item.entry.uri,
                onChunk = { buf, len -> handle.stream.write(buf, 0, len) },
                onProgress = { soFar, total ->
                    if (total > 0) {
                        val pct = (soFar * 100 / total).toInt()
                        updateItem(item.entry.uri) { it.copy(progress = pct) }
                    }
                },
            )
            handle.complete()
        } catch (e: Exception) {
            handle.abortQuietly()
            throw e
        }
    }

    private fun updateItem(uri: String, transform: (MediaItem) -> MediaItem) {
        _ui.update { state ->
            state.copy(items = state.items.map { if (it.entry.uri == uri) transform(it) else it })
        }
    }

    private fun conexionError(e: Exception): String {
        val base = e.message ?: e.javaClass.simpleName
        return "No se pudo conectar con la camara. " +
            "Verifica que el telefono este conectado a la WiFi de la Gear 360. ($base)"
    }
}
