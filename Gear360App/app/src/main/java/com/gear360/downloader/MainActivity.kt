package com.gear360.downloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Gear360Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val vm: MainViewModel = viewModel()
                    AppScreen(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    var tab by remember { mutableStateOf(0) } // 0 = Camara, 1 = Galeria

    Scaffold(
        topBar = { TopAppBar(title = { Text("Gear 360") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = {
                        tab = 0
                    },
                    text = { Text("Camara") }
                )
                Tab(
                    selected = tab == 1,
                    onClick = {
                        // Al salir de la pestana camara, cortamos el preview
                        // para liberar la conexion con la camara.
                        vm.stopLivePreview()
                        tab = 1
                    },
                    text = { Text("Galeria") }
                )
            }

            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                if (tab == 0) CameraTab(vm, ui) else GalleryTab(vm, ui)
            }
        }
    }
}

@Composable
fun CameraTab(vm: MainViewModel, ui: UiState) {
    val frame by vm.liveFrame.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // --- Vista en vivo ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            contentAlignment = Alignment.Center
        ) {
            val f = frame
            if (ui.liveActive && f != null) {
                Image(
                    bitmap = f.asImageBitmap(),
                    contentDescription = "Vista en vivo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (ui.liveActive) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Esperando imagen de la camara...")
                }
            } else {
                Text("Vista en vivo apagada")
            }
        }

        Spacer(Modifier.height(12.dp))

        // --- Boton iniciar/detener vista en vivo ---
        Button(
            onClick = { if (ui.liveActive) vm.stopLivePreview() else vm.startLivePreview() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (ui.liveActive) "Apagar vista en vivo" else "Encender vista en vivo")
        }

        Spacer(Modifier.height(16.dp))

        // --- Selector de modo foto / video ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChipLike(
                selected = ui.captureMode == CaptureMode.PHOTO,
                enabled = !ui.recording,
                label = "Foto",
                onClick = { vm.setMode(CaptureMode.PHOTO) },
                modifier = Modifier.weight(1f)
            )
            FilterChipLike(
                selected = ui.captureMode == CaptureMode.VIDEO,
                enabled = !ui.recording,
                label = "Video",
                onClick = { vm.setMode(CaptureMode.VIDEO) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))

        // --- Boton de captura ---
        Button(
            onClick = { vm.onCaptureButton() },
            enabled = ui.connected || ui.liveActive,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = if (ui.recording)
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.buttonColors()
        ) {
            val label = when {
                ui.busyCapture -> "..."
                ui.captureMode == CaptureMode.PHOTO -> "Capturar foto"
                ui.recording -> "Detener grabacion"
                else -> "Iniciar grabacion"
            }
            Text(label)
        }

        Spacer(Modifier.height(12.dp))

        if (!ui.connected) {
            Button(onClick = { vm.connect() }, enabled = !ui.connecting) {
                Text("Conectar camara")
            }
        }

        ui.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun GalleryTab(vm: MainViewModel, ui: UiState) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { vm.connect() }, enabled = !ui.connecting) {
                Text(if (ui.connected) "Actualizar" else "Conectar camara")
            }
            if (ui.connected) {
                Button(
                    onClick = { vm.downloadAll() },
                    enabled = ui.items.isNotEmpty() && !ui.downloadingAll
                ) {
                    Text("Descargar todo")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (ui.connecting || ui.loadingList) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (ui.connecting) "Conectando..." else "Cargando lista...")
            }
        }

        ui.cameraInfo?.let { info ->
            Text(
                text = "${info.model}  ·  fw ${info.firmware}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        ui.message?.let { msg ->
            Text(text = msg, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(ui.items, key = { it.entry.uri }) { item ->
                MediaRow(
                    item = item,
                    onDownload = { vm.download(item) }
                )
            }
        }
    }
}

/** Boton tipo "chip" para seleccionar modo, sin dependencias extra. */
@Composable
fun FilterChipLike(
    selected: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = if (selected)
            ButtonDefaults.buttonColors()
        else
            ButtonDefaults.outlinedButtonColors()
    ) {
        Text(label)
    }
}

@Composable
fun MediaRow(item: MediaItem, onDownload: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Vista previa: miniatura si la hay; si no, un icono segun tipo.
        val thumb = remember(item.entry.thumbnailBase64) {
            decodeThumb(item.entry.thumbnailBase64)
        }
        Box(
            modifier = Modifier.width(56.dp).height(56.dp),
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = item.entry.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Marca de "play" encima para los videos.
                if (item.entry.isVideo) {
                    Icon(
                        imageVector = Icons.Filled.PlayCircle,
                        contentDescription = "video",
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }
            } else {
                Icon(
                    imageVector = if (item.entry.isVideo) Icons.Filled.Movie else Icons.Filled.Photo,
                    contentDescription = null
                )
            }
        }
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.entry.fileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = humanSize(item.entry.size),
                style = MaterialTheme.typography.bodySmall
            )
            if (item.state == DownloadState.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { item.progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
            if (item.state == DownloadState.ERROR && item.error != null) {
                Text(
                    text = item.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        when (item.state) {
            DownloadState.DONE -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Descargado",
                tint = MaterialTheme.colorScheme.primary
            )
            DownloadState.ERROR -> IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Error, contentDescription = "Reintentar", tint = MaterialTheme.colorScheme.error)
            }
            DownloadState.DOWNLOADING -> CircularProgressIndicator(
                modifier = Modifier.width(24.dp).height(24.dp)
            )
            DownloadState.PENDING -> IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, contentDescription = "Descargar")
            }
        }
    }
}

/**
 * Decodifica la miniatura en base64 que envia la camara a un ImageBitmap
 * listo para Compose. Devuelve null si no hay miniatura o falla el decode.
 */
private fun decodeThumb(base64: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (base64.isNullOrEmpty()) return null
    return try {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return null
        bmp.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var i = 0
    while (size >= 1024 && i < units.size - 1) {
        size /= 1024
        i++
    }
    return String.format("%.1f %s", size, units[i])
}
