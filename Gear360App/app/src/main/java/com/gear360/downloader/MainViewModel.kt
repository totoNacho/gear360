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

/** Modo de captura actual de la camara. */
enum class CaptureMode { PHOTO, VIDEO }

data class UiState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val cameraInfo: CameraInfo? = null,
    val loadingList: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val message: String? = null,    // mensaje de estado/error a mostrar
    val downloadingAll: Boolean = false,

    // --- Vista en vivo y captura ---
    val liveActive: Boolean = false,        // el stream de preview esta corriendo
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val recording: Boolean = false,         // hay una grabacion de video en curso
    val busyCapture: Boolean = false,       // ejecutando un comando de captura

    // --- Separacion fotos / videos ---
    val photos: List<MediaItem> = emptyList(),
    val videos: List<MediaItem> = emptyList(),
    val scanningVideos: Boolean = false,
    val videoScanProgress: Int = 0,         // 0..100

    // --- Diagnostico ---
    val diagnostic: String? = null,         // respuesta cruda de la camara (debug)

    // --- Auto-actualizacion ---
    val updateAvailable: UpdateChecker.UpdateInfo? = null,
    val updateDownloading: Boolean = false,
    val updateProgress: Int = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val client = Gear360Client()
    private val saver = MediaSaver(app.applicationContext)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val appContext = app.applicationContext

    init {
        // Al abrir la app, comprobamos en segundo plano si hay version nueva.
        checkForUpdate()
    }

    /** Comprueba si hay una version mas nueva publicada en GitHub. */
    fun checkForUpdate() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                try {
                    UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
                } catch (_: Exception) {
                    null // sin internet o GitHub no responde: no molestamos
                }
            }
            if (info != null) {
                _ui.update { it.copy(updateAvailable = info) }
            }
        }
    }

    /** Descarga el APK de la version nueva y lanza el instalador. */
    fun downloadAndInstallUpdate() {
        val info = _ui.value.updateAvailable ?: return
        _ui.update { it.copy(updateDownloading = true, updateProgress = 0) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    UpdateChecker.downloadAndInstall(
                        context = appContext,
                        apkUrl = info.apkUrl,
                        onProgress = { pct ->
                            _ui.update { it.copy(updateProgress = pct) }
                        },
                    )
                }
                _ui.update {
                    it.copy(
                        updateDownloading = false,
                        message = "Abriendo instalador de la version ${info.versionName}...",
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        updateDownloading = false,
                        message = "No se pudo actualizar: ${e.message ?: "error"}",
                    )
                }
            }
        }
    }

    /** Descarta el aviso de actualizacion (el usuario eligio "mas tarde"). */
    fun dismissUpdate() {
        _ui.update { it.copy(updateAvailable = null) }
    }

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

    /** Lista las FOTOS de la camara. Los videos se buscan aparte (scanVideos). */
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
                // Separamos por si acaso viniera algun video en el listado.
                val photoItems = entries.filter { !it.isVideo }.map { MediaItem(it) }
                _ui.update {
                    it.copy(
                        loadingList = false,
                        photos = photoItems,
                        items = photoItems,     // compatibilidad
                        message = if (photoItems.isEmpty())
                            "No se encontraron fotos."
                        else
                            "${photoItems.size} fotos. Pulsa 'Buscar videos' para los videos.",
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(loadingList = false, message = conexionError(e))
                }
            }
        }
    }

    @Volatile private var stopVideoScan = false

    /**
     * Busca los videos de la camara por numeracion (la Gear 360 no los lista
     * con listImages). Usa las fotos ya cargadas para deducir carpeta y rango.
     */
    fun scanVideos() {
        if (_ui.value.scanningVideos) return
        val photos = _ui.value.photos.map { it.entry }
        if (photos.isEmpty()) {
            _ui.update { it.copy(message = "Primero carga las fotos (Conectar/Actualizar).") }
            return
        }
        stopVideoScan = false
        _ui.update { it.copy(scanningVideos = true, videoScanProgress = 0, message = "Buscando videos...") }

        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    client.startSession()
                    try {
                        client.findVideos(
                            photos = photos,
                            onProgress = { done, total ->
                                val pct = if (total > 0) done * 100 / total else 0
                                _ui.update { it.copy(videoScanProgress = pct) }
                            },
                            shouldStop = { stopVideoScan },
                        )
                    } finally {
                        client.closeSession()
                    }
                }
                _ui.update {
                    it.copy(
                        scanningVideos = false,
                        videos = found.map { v -> MediaItem(v) },
                        message = if (found.isEmpty())
                            "No se encontraron videos."
                        else
                            "${found.size} videos encontrados.",
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(scanningVideos = false, message = "Error al buscar videos: ${e.message}")
                }
            }
        }
    }

    fun stopVideoScan() {
        stopVideoScan = true
    }

    /** Muestra u oculta la respuesta cruda de la camara (diagnostico). */
    fun toggleDiagnostic() {
        _ui.update {
            if (it.diagnostic == null) {
                it.copy(
                    diagnostic = client.lastListRawResponse.ifEmpty {
                        "Aun no hay respuesta. Pulsa Conectar/Actualizar primero."
                    }
                )
            } else {
                it.copy(diagnostic = null)
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

    /** Tipo de lista a descargar en bloque. */
    enum class Which { PHOTOS, VIDEOS }

    /** Descarga TODOS los archivos pendientes de la lista indicada. */
    fun downloadAll(which: Which = Which.PHOTOS) {
        _ui.update { it.copy(downloadingAll = true, message = "Descargando...") }
        viewModelScope.launch {
            val source = if (which == Which.VIDEOS) _ui.value.videos else _ui.value.photos
            val pending = source.filter { it.state != DownloadState.DONE }
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
            fun List<MediaItem>.apply() =
                map { if (it.entry.uri == uri) transform(it) else it }
            state.copy(
                items = state.items.apply(),
                photos = state.photos.apply(),
                videos = state.videos.apply(),
            )
        }
    }

    private fun conexionError(e: Exception): String {
        val base = e.message ?: e.javaClass.simpleName
        return "No se pudo conectar con la camara. " +
            "Verifica que el telefono este conectado a la WiFi de la Gear 360. ($base)"
    }

    // =========================================================================
    //  VISTA EN VIVO Y CAPTURA
    // =========================================================================

    // Frames del preview (JPEG decodificado a Bitmap). Flow aparte porque
    // cambia muchas veces por segundo y no conviene mezclarlo con el UiState.
    private val _liveFrame = MutableStateFlow<android.graphics.Bitmap?>(null)
    val liveFrame: StateFlow<android.graphics.Bitmap?> = _liveFrame.asStateFlow()

    private var liveJob: kotlinx.coroutines.Job? = null
    @Volatile private var stopLive = false

    /** Sesion propia para captura/preview (se abre al entrar al modo camara). */
    private var captureSessionOpen = false

    private suspend fun ensureCaptureSession() = withContext(Dispatchers.IO) {
        if (!captureSessionOpen) {
            client.startSession()
            captureSessionOpen = true
        }
    }

    /** Inicia la vista en vivo (stream MJPEG de la camara). */
    fun startLivePreview() {
        if (_ui.value.liveActive) return
        stopLive = false
        _ui.update { it.copy(liveActive = true, message = "Vista en vivo activa") }

        liveJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureCaptureSession()
                // Aplicamos el modo actual antes de abrir el stream.
                client.setCaptureMode(_ui.value.captureMode == CaptureMode.VIDEO)
                client.streamLivePreview(
                    onFrame = { jpeg ->
                        val bmp = android.graphics.BitmapFactory
                            .decodeByteArray(jpeg, 0, jpeg.size)
                        if (bmp != null) _liveFrame.value = bmp
                    },
                    shouldStop = { stopLive },
                )
            } catch (e: Exception) {
                _ui.update {
                    it.copy(message = "Vista en vivo no disponible: ${e.message ?: "error"}")
                }
            } finally {
                _ui.update { it.copy(liveActive = false) }
            }
        }
    }

    /** Detiene la vista en vivo. */
    fun stopLivePreview() {
        stopLive = true
        liveJob?.cancel()
        liveJob = null
        _liveFrame.value = null
        _ui.update { it.copy(liveActive = false) }
    }

    /** Cambia entre modo foto y modo video. */
    fun setMode(mode: CaptureMode) {
        if (_ui.value.recording) return // no cambiar mientras graba
        _ui.update { it.copy(captureMode = mode) }
        viewModelScope.launch {
            try {
                ensureCaptureSession()
                withContext(Dispatchers.IO) {
                    client.setCaptureMode(mode == CaptureMode.VIDEO)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(message = "No se pudo cambiar de modo: ${e.message}") }
            }
        }
    }

    /**
     * Accion del boton de captura:
     *  - en modo foto: dispara una foto.
     *  - en modo video: inicia o detiene la grabacion.
     */
    fun onCaptureButton() {
        if (_ui.value.busyCapture) return
        _ui.update { it.copy(busyCapture = true) }
        viewModelScope.launch {
            try {
                ensureCaptureSession()
                if (_ui.value.captureMode == CaptureMode.PHOTO) {
                    withContext(Dispatchers.IO) { client.takePicture() }
                    _ui.update { it.copy(message = "Foto capturada") }
                } else {
                    if (_ui.value.recording) {
                        withContext(Dispatchers.IO) { client.stopCapture() }
                        _ui.update { it.copy(recording = false, message = "Grabacion detenida") }
                    } else {
                        withContext(Dispatchers.IO) { client.startCapture() }
                        _ui.update { it.copy(recording = true, message = "Grabando video...") }
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(message = "Error en la captura: ${e.message ?: "error"}") }
            } finally {
                _ui.update { it.copy(busyCapture = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLivePreview()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (captureSessionOpen) client.closeSession()
            } catch (_: Exception) {
            }
        }
    }
}
