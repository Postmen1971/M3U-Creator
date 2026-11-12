package de.azem3u.creator

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { M3UCreatorScreen(contentResolver) } }
    }
}

private data class PlaylistEntry(
    val rawLines: List<String>,
    val group: String,
    val title: String
)

private data class ParsedPlaylist(
    val headerLines: List<String>,
    val entries: List<PlaylistEntry>
)

@Composable
private fun M3UCreatorScreen(contentResolver: ContentResolver) {
    var playlistUrl by remember { mutableStateOf("") }
    var playlistName by remember { mutableStateOf("Meine_Liste.m3u") }
    var entries by remember { mutableStateOf<List<PlaylistEntry>>(emptyList()) }
    var selectedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var search by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("M3U-Link einfügen und Liste laden.") }
    var loading by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val groups = remember(entries) { entries.map { it.group }.distinct().sorted() }
    val filteredGroups = remember(groups, search) {
        groups.filter { it.contains(search, ignoreCase = true) }
    }
    val selectedCount = remember(entries, selectedGroups) {
        entries.count { it.group in selectedGroups }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        val content = exportText
        if (uri != null && content != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(content.toByteArray(Charset.forName("UTF-8")))
                } ?: error("Datei konnte nicht geöffnet werden.")
                status = "Fertig: M3U-Datei wurde gespeichert."
            } catch (e: Exception) {
                status = "Speichern fehlgeschlagen: ${e.message ?: "Unbekannter Fehler"}"
            }
        } else if (uri == null) {
            status = "Speichern abgebrochen."
        }
    }

    fun loadPlaylist() {
        val input = playlistUrl.trim()
        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            status = "Bitte einen gültigen http://- oder https://-Link eingeben."
            return
        }
        loading = true
        status = "Playlist wird geladen …"
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { downloadText(input) }
                val parsed = parseM3U(text)
                entries = parsed.entries
                selectedGroups = parsed.entries.map { it.group }.toSet()
                status = if (parsed.entries.isEmpty()) {
                    "Keine Sender gefunden. Prüfe, ob der Link eine M3U-Datei liefert."
                } else {
                    "${parsed.entries.size} Sender in ${parsed.entries.map { it.group }.distinct().size} Gruppen gefunden."
                }
            } catch (e: Exception) {
                status = "Laden fehlgeschlagen: ${e.message ?: "Verbindung prüfen"}"
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("M3U Creator", style = MaterialTheme.typography.headlineMedium)
        Text("Nur ausgewählte Gruppen exportieren – Original-Streams bleiben unverändert.",
            style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = playlistUrl,
            onValueChange = { playlistUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("M3U- / M3U8-Link") },
            placeholder = { Text("https://…/playlist.m3u") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        Button(
            onClick = { loadPlaylist() },
            enabled = !loading && playlistUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "Lädt …" else "Playlist laden")
        }

        Text(status, style = MaterialTheme.typography.bodySmall)

        if (entries.isNotEmpty()) {
            HorizontalDivider()
            Text("Gruppen auswählen", style = MaterialTheme.typography.titleLarge)
            Text("${selectedGroups.size} von ${groups.size} Gruppen · $selectedCount von ${entries.size} Sendern")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { selectedGroups = groups.toSet() }) {
                    Text("Alle")
                }
                OutlinedButton(onClick = { selectedGroups = emptySet() }) {
                    Text("Keine")
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gruppe suchen") },
                singleLine = true
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filteredGroups) { group ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("$group (${entries.count { it.group == group }})",
                            modifier = Modifier.weight(1f))
                        Checkbox(
                            checked = group in selectedGroups,
                            onCheckedChange = { checked ->
                                selectedGroups = if (checked) selectedGroups + group
                                else selectedGroups - group
                            }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = playlistName,
                onValueChange = { playlistName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Dateiname") },
                singleLine = true
            )
            Button(
                onClick = {
                    val chosen = entries.filter { it.group in selectedGroups }
                    if (chosen.isEmpty()) {
                        status = "Bitte mindestens eine Gruppe auswählen."
                    } else {
                        val header = "#EXTM3U"
                        exportText = buildString {
                            append(header).append('\\n')
                            chosen.forEach { entry ->
                                entry.rawLines.forEach { append(it).append('\\n') }
                            }
                        }
                        val safeName = playlistName.trim().ifBlank { "Meine_Liste.m3u" }
                            .let { if (it.endsWith(".m3u", true)) it else "$it.m3u" }
                        status = "Speicherort für $safeName auswählen."
                        saveLauncher.launch(safeName)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedCount > 0
            ) {
                Text("Gefilterte M3U-Datei speichern ($selectedCount Sender)")
            }
        }
    }
}

private fun downloadText(address: String): String {
    val connection = (URL(address).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 20_000
        readTimeout = 60_000
        setRequestProperty("User-Agent", "M3U-Creator-Android/1.0")
        instanceFollowRedirects = true
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) error("HTTP-Fehler $code")
        val length = connection.contentLengthLong
        if (length > 50L * 1024 * 1024) error("Playlist ist größer als 50 MB.")
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > 50L * 1024 * 1024) error("Playlist ist größer als 50 MB.")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    } finally {
        connection.disconnect()
    }
}

private fun parseM3U(text: String): ParsedPlaylist {
    val lines = text.removePrefix("\uFEFF").lines()
    val entries = mutableListOf<PlaylistEntry>()
    val header = mutableListOf<String>()
    var current = mutableListOf<String>()
    var started = false

    fun finishEntry() {
        if (current.isEmpty()) return
        val extinf = current.firstOrNull { it.startsWith("#EXTINF", ignoreCase = true) }.orEmpty()
        val groupFromAttribute = Regex("group-title\\s*=\\s*\\\"([^\\\"]*)\\\"", RegexOption.IGNORE_CASE)
            .find(extinf)?.groupValues?.getOrNull(1)?.trim()
        val groupFromExtgrp = current.firstOrNull { it.startsWith("#EXTGRP:", true) }
            ?.substringAfter(':')?.trim()
        val group = groupFromAttribute?.takeIf { it.isNotBlank() }
            ?: groupFromExtgrp?.takeIf { it.isNotBlank() }
            ?: "Ohne Gruppe"
        val title = extinf.substringAfterLast(',', "Unbekannter Sender").trim()
        val hasStream = current.any { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (hasStream) entries += PlaylistEntry(current.toList(), group, title)
        current = mutableListOf()
    }

    for (line in lines) {
        if (line.startsWith("#EXTINF", ignoreCase = true)) {
            if (started) finishEntry()
            else {
                header += current
                current = mutableListOf()
                started = true
            }
            current += line
        } else if (started) {
            current += line
        } else {
            header += line
        }
    }
    if (started) finishEntry()
    return ParsedPlaylist(header, entries)
}
