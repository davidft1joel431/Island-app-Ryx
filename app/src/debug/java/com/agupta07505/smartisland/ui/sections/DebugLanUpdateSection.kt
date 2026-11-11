/* Ryx Isla debug-only local APK updater. Not included in release source sets. */
package com.agupta07505.smartisland.ui.sections

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.agupta07505.smartisland.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val DEFAULT_PC_IP = "192.168.100.6"
private const val FALLBACK_PC_IP = "172.22.160.1"
private const val APP_ID = "com.dav.isla"

@Composable
fun DebugLanUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pcIp by rememberSaveable { mutableStateOf(DEFAULT_PC_IP) }
    var publishToken by rememberSaveable { mutableStateOf("") }
    var connectedIp by remember { mutableStateOf("") }
    var remoteVersion by remember { mutableStateOf(0) }
    var remoteName by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("Buscando actualizaciones en el PC…") }
    var checking by remember { mutableStateOf(false) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var showPublishConfirmation by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }

    fun startInstaller(file: File) {
        val uri = FileProvider.getUriForFile(context, "$APP_ID.debugupdates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        downloaded?.takeIf { it.exists() }?.let(::startInstaller)
    }

    suspend fun check() {
        checking = true
        val result = withContext(Dispatchers.IO) {
            val candidates = listOf(pcIp.trim(), DEFAULT_PC_IP, FALLBACK_PC_IP)
                .filter { it.matches(Regex("[A-Za-z0-9.-]{1,253}")) }
                .distinct()
            candidates.firstNotNullOfOrNull { host ->
                runCatching {
                    val conn = URL("http://$host:8765/update.json").openConnection() as HttpURLConnection
                    try {
                        conn.connectTimeout = 1800
                        conn.readTimeout = 1800
                        conn.requestMethod = "GET"
                        host to conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                    } finally { conn.disconnect() }
                }.getOrNull()
            }
        }
        if (result != null) {
            val (host, json) = result
            connectedIp = host
            remoteVersion = json.optInt("versionCode")
            remoteName = json.optString("versionName")
            state = if (remoteVersion > BuildConfig.VERSION_CODE) "Nueva APK debug disponible: v$remoteName (PC: $host)" else "Sin cambios (v${BuildConfig.VERSION_NAME}) · PC: $host"
        } else {
            connectedIp = ""
            state = "PC no disponible. Revisa la IP, la misma red y el servidor del PC."
        }
        checking = false
    }

    fun publishReadyVersion() {
        if (publishToken.isBlank() || connectedIp.isBlank()) return
        publishing = true
        state = "Enviando al PC: v${BuildConfig.VERSION_NAME} lista para lanzar…"
        scope.launch {
            val accepted = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL("http://$connectedIp:8765/publish").openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 4000
                        connection.readTimeout = 5000
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.setRequestProperty("X-Ryx-Token", publishToken)
                        connection.outputStream.use {
                            it.write(JSONObject()
                                .put("versionCode", BuildConfig.VERSION_CODE)
                                .put("versionName", BuildConfig.VERSION_NAME)
                                .toString().toByteArray(Charsets.UTF_8))
                        }
                        require(connection.responseCode == 202) {
                            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "El PC rechazó la solicitud"
                        }
                    } finally { connection.disconnect() }
                }
            }
            if (accepted.isFailure) {
                state = "El PC no aceptó la publicación: ${accepted.exceptionOrNull()?.localizedMessage ?: "error"}"
                publishing = false
                return@launch
            }
            state = "El PC recibió la orden. Consultando el progreso…"
            repeat(300) {
                delay(3000)
                val status = withContext(Dispatchers.IO) {
                    runCatching {
                        val connection = URL("http://$connectedIp:8765/publish-status").openConnection() as HttpURLConnection
                        try {
                            connection.connectTimeout = 3000
                            connection.readTimeout = 4000
                            connection.setRequestProperty("X-Ryx-Token", publishToken)
                            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                        } finally { connection.disconnect() }
                    }
                }.getOrNull() ?: return@repeat
                state = status.optString("message", "Publicando en el PC…")
                if (status.optBoolean("done")) {
                    publishing = false
                    return@launch
                }
            }
            state = "El PC sigue trabajando. Deja el servidor abierto y revisa su ventana."
            publishing = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            check()
            delay(30_000)
        }
    }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Actualizaciones de prueba por Wi-Fi", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = pcIp,
                onValueChange = { pcIp = it.filter { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '-' }.take(253) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("IP o nombre del PC") },
                placeholder = { Text("192.168.100.6") }
            )
            OutlinedTextField(
                value = publishToken,
                onValueChange = { publishToken = it.take(128) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Clave temporal del servidor (para publicar)") }
            )
            Text(state, style = MaterialTheme.typography.bodySmall)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { check() } }, enabled = !checking, modifier = Modifier.fillMaxWidth()) { Text(if (checking) "Buscando…" else "Buscar ahora") }
                if (remoteVersion > BuildConfig.VERSION_CODE) Button(onClick = {
                    checking = true
                    state = "Descargando APK debug…"
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            runCatching {
                                val target = File(context.cacheDir, "debug-updates/app-debug.apk").apply { parentFile?.mkdirs() }
                                require(connectedIp.isNotBlank()) { "Busca el PC antes de descargar" }
                                val connection = URL("http://$connectedIp:8765/app-debug.apk").openConnection() as HttpURLConnection
                                try {
                                    connection.connectTimeout = 5000
                                    connection.readTimeout = 30_000
                                    connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                                } finally { connection.disconnect() }
                                val info = context.packageManager.getPackageArchiveInfo(target.path, 0)
                                    ?: error("El archivo descargado no es una APK válida")
                                require(info.packageName == APP_ID) { "La APK pertenece a otra aplicación" }
                                @Suppress("DEPRECATION")
                                require(info.versionCode > BuildConfig.VERSION_CODE && info.versionCode == remoteVersion) { "La versión de la APK no coincide" }
                                target
                            }
                        }
                        file.onSuccess { apk ->
                            downloaded = apk
                            state = "Descarga lista. Android solicitará confirmar la instalación."
                            if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                                settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$APP_ID")))
                            } else startInstaller(apk)
                        }.onFailure { state = "No se pudo descargar: ${it.localizedMessage ?: "error"}" }
                        checking = false
                    }
                }, enabled = !checking, modifier = Modifier.fillMaxWidth()) { Text("Descargar e instalar") }
                if (connectedIp.isNotBlank() && remoteVersion == BuildConfig.VERSION_CODE) {
                    Button(onClick = { showPublishConfirmation = true }, enabled = !checking && !publishing && publishToken.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (publishing) "Publicando…" else "v${BuildConfig.VERSION_NAME} lista para lanzar")
                    }
                }
            }
        }
    }
    if (showPublishConfirmation) {
        AlertDialog(
            onDismissRequest = { showPublishConfirmation = false },
            title = { Text("Publicar v${BuildConfig.VERSION_NAME} en GitHub?") },
            text = { Text("El PC compilará la APK debug, comprobará los cambios permitidos, creará el commit y la etiqueta, y hará push de main a GitHub. GitHub Actions preparará la APK oficial firmada.") },
            confirmButton = {
                TextButton(onClick = {
                    showPublishConfirmation = false
                    publishReadyVersion()
                }) { Text("Confirmar commit y push") }
            },
            dismissButton = { TextButton(onClick = { showPublishConfirmation = false }) { Text("Cancelar") } }
        )
    }
}
