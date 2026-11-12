package de.azem3u.creator

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { M3UCreatorScreen(contentResolver) }
            }
        }
    }
}

internal data class PlaylistEntry(
    val id: Int,
    val rawLines: List<String>,
    val group: String,
    val title: String
)

internal data class ParsedPlaylist(
    val header: String,
    val entries: List<PlaylistEntry>
)

private const val MAX_BYTES = 50L * 1024 * 1024
private const val SENDER_LIMIT = 300

@Composable
private fun M3UCreatorScreen(contentResolver: ContentResolver) {
    var playlistUrl by remember { mutableStateOf("") }
    var playlistName by remember { mutableStateOf("Meine_Liste.m3u") }
    var header by remember { mutableStateOf("#EXTM3U") }
    var entries by remember { mutableStateOf<List<PlaylistEntry>>(emptyList()) }
    var selectedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var groupSearch by remember { mutableStateOf("") }
    var senderSearch by remember { mutableStateOf("") }
    var selectedSenders by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var newGroupName by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("M3U-Link einfügen oder Datei öffnen.") }
    var loading by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val groupCounts = remember(entries) { entries.groupingBy { it.group }.eachCount() }
    val groups = remember(groupCounts) { groupCounts.keys.sortedWith(String.CASE_INSENSITIVE_ORDER) }
    val filteredGroups = remember(groups, groupSearch) { groups.filter { it.contains(groupSearch, ignoreCase = true) } }
    val selectedCount = remember(groupCounts, selectedGroups) { selectedGroups.sumOf { groupCounts[it] ?: 0 } }
    val senderMatches = remember(entries, senderSearch) {
        val q = senderSearch.trim()
        if (q.isEmpty()) entries else entries.filter { it.title.contains(q, true) || it.group.contains(q, true) }
    }

    fun applyPlaylist(text: String, source: String) {
        val parsed = parseM3U(text)
        if (parsed.entries.isEmpty()) {
            status = "$source: Keine Sender gefunden. Ist das wirklich eine M3U-Playlist?"
            return
        }
        header = parsed.header
        entries = parsed.entries
        selectedGroups = parsed.entries.map { it.group }.toSet()
        selectedSenders = emptySet()
        groupSearch = ""; senderSearch = ""
        status = "$source: ${parsed.entries.size} Sender in ${selectedGroups.size} Gruppen geladen."
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        val content = exportText
        if (uri == null) {
            status = "Speichern abgebrochen."
        } else if (content != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    ?: error("Datei konnte nicht geöffnet werden.")
                status = "Fertig: M3U-Datei wurde gespeichert."
            } catch (e: Exception) {
                status = "Speichern fehlgeschlagen: ${e.message ?: "Unbekannter Fehler"}"
            }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        status = "Datei wird gelesen …"
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { readLimited(it) }
                        ?: error("Datei konnte nicht geöffnet werden.")
                }
                applyPlaylist(text, "Datei")
            } catch (e: Exception) {
                status = "Datei konnte nicht gelesen werden: ${e.message ?: "Unbekannter Fehler"}"
            } finally {
                loading = false
            }
        }
    }

    fun loadPlaylist() {
        val input = playlistUrl.trim()
        if (!input.startsWith("http://", true) && !input.startsWith("https://", true)) {
            status = "Bitte einen gültigen http://- oder https://-Link eingeben."
            return
        }
        loading = true
        status = "Playlist wird geladen …"
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { downloadText(input) }
                applyPlaylist(text, "Link")
            } catch (e: Exception) {
                status = "Laden fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    fun moveSelectedSenders() {
        val name = newGroupName.replace("\"", "'").replace(Regex("[\\r\\n]+"), " ").trim()
        if (name.isEmpty()) { status = "Bitte einen Gruppennamen eingeben."; return }
        if (selectedSenders.isEmpty()) { status = "Bitte zuerst Sender auswählen."; return }
        val existedBefore = name in groupCounts
        val moved = selectedSenders.size
        entries = entries.map { if (it.id in selectedSenders) assignGroup(it, name) else it }
        val remaining = entries.map { it.group }.toSet()
        selectedGroups = (if (existedBefore) selectedGroups else selectedGroups + name).filter { it in remaining }.toSet()
        selectedSenders = emptySet()
        newGroupName = ""
        status = "$moved Sender in die Gruppe „$name“ verschoben."
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("M3U Creator", style = MaterialTheme.typography.headlineMedium)
            Text("Gruppen auswählen, eigene Gruppen bauen, M3U speichern – Streams bleiben unverändert.",
                style = MaterialTheme.typography.bodyMedium)
        }
        item {
            OutlinedTextField(
                value = playlistUrl,
                onValueChange = { playlistUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("M3U- / M3U8-Link") },
                placeholder = { Text("http://…/get.php?username=…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { loadPlaylist() }, enabled = !loading && playlistUrl.isNotBlank(),
                    modifier = Modifier.weight(1f)) { Text(if (loading) "Lädt …" else "Link laden") }
                OutlinedButton(onClick = { openLauncher.launch(arrayOf("*/*")) }, enabled = !loading,
                    modifier = Modifier.weight(1f)) { Text("Datei öffnen") }
            }
        }
        item { Text(status, style = MaterialTheme.typography.bodySmall) }

        if (entries.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text("${selectedGroups.size} von ${groups.size} Gruppen · $selectedCount von ${entries.size} Sendern ausgewählt",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Gruppen") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Eigene Gruppe") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Speichern") })
                }
            }

            when (tab) {
                0 -> {
                    item {
                        OutlinedTextField(value = groupSearch, onValueChange = { groupSearch = it },
                            modifier = Modifier.fillMaxWidth(), label = { Text("Gruppe suchen") }, singleLine = true)
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { selectedGroups = selectedGroups + filteredGroups }) { Text("Treffer an") }
                            OutlinedButton(onClick = { selectedGroups = selectedGroups - filteredGroups.toSet() }) { Text("Treffer aus") }
                        }
                    }
                    items(filteredGroups, key = { "g_$it" }) { group ->
                        CheckRow(
                            checked = group in selectedGroups,
                            title = group,
                            subtitle = "${groupCounts[group] ?: 0} Sender",
                            onToggle = { c -> selectedGroups = if (c) selectedGroups + group else selectedGroups - group }
                        )
                    }
                }
                1 -> {
                    item {
                        OutlinedTextField(value = senderSearch, onValueChange = { senderSearch = it },
                            modifier = Modifier.fillMaxWidth(), label = { Text("Sender oder Gruppe suchen") }, singleLine = true)
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { selectedSenders = selectedSenders + senderMatches.map { it.id } }) { Text("Alle Treffer") }
                            OutlinedButton(onClick = { selectedSenders = emptySet() }) { Text("Keine") }
                            Text("${selectedSenders.size} gewählt", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = newGroupName, onValueChange = { newGroupName = it },
                                modifier = Modifier.weight(1f), label = { Text("Zielgruppe (neu oder vorhanden)") }, singleLine = true)
                            Button(onClick = { moveSelectedSenders() }) { Text("Verschieben") }
                        }
                    }
                    items(senderMatches.take(SENDER_LIMIT), key = { "s_${it.id}" }) { e ->
                        CheckRow(
                            checked = e.id in selectedSenders,
                            title = e.title,
                            subtitle = e.group,
                            onToggle = { c -> selectedSenders = if (c) selectedSenders + e.id else selectedSenders - e.id }
                        )
                    }
                    if (senderMatches.size > SENDER_LIMIT) {
                        item {
                            Text("$SENDER_LIMIT von ${senderMatches.size} Treffern angezeigt – Suche eingrenzen. „Alle Treffer“ wählt trotzdem alle aus.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                else -> {
                    item {
                        OutlinedTextField(value = playlistName, onValueChange = { playlistName = it },
                            modifier = Modifier.fillMaxWidth(), label = { Text("Dateiname") }, singleLine = true)
                    }
                    item {
                        Button(
                            onClick = {
                                val chosen = entries.filter { it.group in selectedGroups }
                                exportText = buildString {
                                    append(header).append('\n')
                                    chosen.forEach { e -> e.rawLines.forEach { append(it).append('\n') } }
                                }
                                val safeName = playlistName.trim().replace(Regex("[\\\\/:*?\"<>|]+"), "_")
                                    .ifBlank { "Meine_Liste.m3u" }
                                    .let { if (it.endsWith(".m3u", true) || it.endsWith(".m3u8", true)) it else "$it.m3u" }
                                status = "Speicherort für $safeName auswählen."
                                saveLauncher.launch(safeName)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedCount > 0
                        ) { Text("M3U-Datei speichern ($selectedCount Sender)") }
                    }
                    item { Text("Vorschau", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                    items(groups.filter { it in selectedGroups }, key = { "p_$it" }) { g ->
                        Text("• $g – ${groupCounts[g] ?: 0} Sender", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, title: String, subtitle: String, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onToggle(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun readLimited(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > MAX_BYTES) error("Playlist ist größer als 50 MB.")
        output.write(buffer, 0, count)
    }
    return output.toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF")
}

private fun downloadText(address: String): String {
    var current = URL(address)
    repeat(6) {
        val connection = (current.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: error("Weiterleitung ohne Ziel (HTTP $code)")
                current = URL(current, location) // erlaubt auch http <-> https
                return@repeat
            }
            if (code !in 200..299) error("Server antwortet mit HTTP $code")
            if (connection.contentLengthLong > MAX_BYTES) error("Playlist ist größer als 50 MB.")
            return connection.inputStream.use { readLimited(it) }
        } finally {
            connection.disconnect()
        }
    }
    error("Zu viele Weiterleitungen.")
}

private val GROUP_TITLE = Regex("group-title\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
private val TVG_NAME = Regex("tvg-name\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

/** Position des Kommas vor dem Sendernamen (Kommas in "..." werden ignoriert). */
internal fun titleComma(extinf: String): Int {
    var quoted = false
    for (i in extinf.indices) {
        val c = extinf[i]
        if (c == '"') quoted = !quoted
        else if (c == ',' && !quoted) return i
    }
    return -1
}

private fun isExtinf(line: String) = line.startsWith("#EXTINF", ignoreCase = true)
private fun isExtgrp(line: String) = line.startsWith("#EXTGRP:", ignoreCase = true)

private fun makeEntry(id: Int, lines: List<String>): PlaylistEntry {
    val extinf = lines.firstOrNull { isExtinf(it) }.orEmpty()
    val group = GROUP_TITLE.find(extinf)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        ?: lines.firstOrNull { isExtgrp(it) }?.substring(8)?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Ohne Gruppe"
    val title = if (extinf.isNotEmpty()) {
        val c = titleComma(extinf)
        (if (c >= 0) extinf.substring(c + 1).trim() else "").ifEmpty {
            TVG_NAME.find(extinf)?.groupValues?.get(1)?.trim().orEmpty()
        }.ifEmpty { "Sender ohne Namen" }
    } else lines.last().trim()
    return PlaylistEntry(id, lines, group, title)
}

internal fun parseM3U(text: String): ParsedPlaylist {
    val entries = mutableListOf<PlaylistEntry>()
    var header = ""
    var pending = mutableListOf<String>()
    var block: MutableList<String>? = null

    for (raw in text.removePrefix("\uFEFF").lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        when {
            line.startsWith("#EXTM3U", ignoreCase = true) -> if (header.isEmpty()) header = line
            isExtinf(line) -> {
                if (block != null) pending = mutableListOf()
                block = (pending + line).toMutableList()
                pending = mutableListOf()
            }
            line.startsWith("#") -> (block ?: pending).add(line)
            else -> {
                val current = block
                if (current != null) {
                    current.add(line)
                    entries += makeEntry(entries.size, current)
                    block = null
                } else {
                    entries += makeEntry(entries.size, pending + line)
                    pending = mutableListOf()
                }
            }
        }
    }
    return ParsedPlaylist(header.ifEmpty { "#EXTM3U" }, entries)
}

internal fun assignGroup(entry: PlaylistEntry, name: String): PlaylistEntry {
    var found = false
    val lines = entry.rawLines.map { line ->
        when {
            isExtinf(line) -> {
                found = true
                if (GROUP_TITLE.containsMatchIn(line)) {
                    GROUP_TITLE.replace(line) { "group-title=\"$name\"" }
                } else {
                    val c = titleComma(line)
                    if (c >= 0) line.substring(0, c) + " group-title=\"$name\"" + line.substring(c)
                    else "$line group-title=\"$name\",${entry.title}"
                }
            }
            isExtgrp(line) -> "#EXTGRP:$name"
            else -> line
        }
    }.toMutableList()
    if (!found) {
        val urlIndex = lines.indexOfFirst { !it.startsWith("#") }.coerceAtLeast(0)
        lines.add(urlIndex, "#EXTINF:-1 group-title=\"$name\",${entry.title}")
    }
    return entry.copy(rawLines = lines, group = name)
}
