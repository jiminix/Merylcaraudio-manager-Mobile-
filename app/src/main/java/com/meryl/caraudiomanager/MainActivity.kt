package com.meryl.caraudiomanager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

private val Bg = Color(0xFF14181C)
private val Panel = Color(0xFF202830)
private val Yellow = Color(0xFFFFDE59)
private val Red = Color(0xFFFF3131)
private val Blue = Color(0xFF1800AD)
private val Green = Color(0xFF7BD66A)
private val Muted = Color(0xFF9AA7B4)

private data class TrackInfo(
    val uri: Uri,
    val displayName: String,
    val title: String,
    val artist: String,
    val composer: String,
    val disc: Int,
    val track: Int,
    val durationMs: Long
)

private data class AlbumDraft(
    val sourceUri: Uri,
    val sourceName: String,
    val tracks: List<TrackInfo>,
    val coverUri: Uri?,
    val sourceBytes: Long,
    val estimatedOutputBytes: Long,
    val artist: String,
    val year: String,
    val album: String
)

private data class AlbumItem(
    val id: Long,
    val sourceUri: Uri,
    val sourceName: String,
    val tracks: List<TrackInfo>,
    val coverUri: Uri?,
    val sourceBytes: Long,
    val estimatedOutputBytes: Long,
    val artist: String,
    val year: String,
    val album: String,
    val alreadyOnSd: Boolean
)

class MainActivity : ComponentActivity() {
    private val queue = mutableStateListOf<AlbumItem>()

    private var destinationUri by mutableStateOf<Uri?>(null)
    private var destinationLabel by mutableStateOf("Carte SD non sélectionnée")
    private var destinationAlbums by mutableStateOf<List<String>>(emptyList())
    private var destinationAlbumKeys by mutableStateOf<Set<String>>(emptySet())
    private var sdFreeBytes by mutableLongStateOf(-1L)

    private var pendingDraft by mutableStateOf<AlbumDraft?>(null)
    private var editArtist by mutableStateOf("")
    private var editYear by mutableStateOf("")
    private var editAlbum by mutableStateOf("")

    private var statusText by mutableStateOf("Choisis d'abord le dossier Music de la carte SD.")
    private var busyText by mutableStateOf("")
    private var sending by mutableStateOf(false)
    private var currentAlbumProgress by mutableFloatStateOf(0f)
    private var overallProgress by mutableFloatStateOf(0f)
    private var currentAlbumName by mutableStateOf("—")
    private var transferJob: Job? = null
    private var duplicateDialogItem by mutableStateOf<AlbumItem?>(null)
    private var showExistingAlbums by mutableStateOf(false)

    private val prefs by lazy { getSharedPreferences("meryl_mobile", MODE_PRIVATE) }
    private var learnedBytesPerSecond by mutableDoubleStateOf(0.0)

    private val chooseDestination = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTreePermission(uri)
            destinationUri = uri
            prefs.edit().putString("destination_uri", uri.toString()).apply()
            refreshDestination()
        }
    }

    private val chooseAlbum = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTreePermission(uri)
            analyzeSingleAlbum(uri)
        }
    }

    private val chooseBatchFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTreePermission(uri)
            importBatch(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        learnedBytesPerSecond = prefs.getFloat("learned_bps", 0f).toDouble()
        prefs.getString("destination_uri", null)?.let {
            runCatching { Uri.parse(it) }.getOrNull()?.let { saved ->
                destinationUri = saved
                refreshDestination()
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    AppScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) FFmpegKit.cancel()
    }

    @Composable
    private fun AppScreen() {
        duplicateDialogItem?.let { item ->
            AlertDialog(
                onDismissRequest = { duplicateDialogItem = null },
                title = { Text("Album déjà présent") },
                text = { Text("${item.artist} — ${item.album}\n\nCet album existe déjà dans le dossier SD sélectionné. Tu peux quand même le garder dans la file pour le remplacer.") },
                confirmButton = {
                    TextButton(onClick = { duplicateDialogItem = null }) { Text("GARDER", color = Red) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        queue.removeAll { it.id == item.id }
                        duplicateDialogItem = null
                        statusText = "Album retiré de la file."
                    }) { Text("RETIRER") }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Header()
            Spacer(Modifier.height(7.dp))
            StorageBlocks()
            Spacer(Modifier.height(8.dp))
            AlbumEditor()
            Spacer(Modifier.height(8.dp))
            QueueSection()
            Spacer(Modifier.height(8.dp))
            TransferSection()
            Spacer(Modifier.height(6.dp))
            ExistingAlbumsSection()
        }
    }

    @Composable
    private fun Header() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("MerylCarAudio Manager", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Mobile V0.1 · ${Build.MODEL}", color = Yellow, fontSize = 12.sp)
            }
            OutlinedButton(
                enabled = !sending,
                onClick = { chooseDestination.launch(destinationUri) }
            ) {
                Text("SD", color = Yellow)
            }
        }
        Text(
            destinationLabel,
            color = Muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    private fun StorageBlocks() {
        val sendBytes = queue.sumOf { it.estimatedOutputBytes }
        val remaining = if (sdFreeBytes >= 0) sdFreeBytes - sendBytes else -1
        val time = estimateTimeText(sendBytes)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            InfoBlock("${Build.MODEL} · SD", if (sdFreeBytes >= 0) "${formatBytes(sdFreeBytes)} libres" else "espace ?", Yellow)
            InfoBlock("ENVOI", formatBytes(sendBytes), Color.White)
            InfoBlock("RESTE", if (remaining >= 0) formatBytes(remaining) else if (sdFreeBytes >= 0) "INSUFFISANT" else "—", if (remaining < 0 && sdFreeBytes >= 0) Red else Green)
            InfoBlock(if (sending) "RESTANT" else "TEMPS", time, Color.White)
        }
    }

    @Composable
    private fun InfoBlock(title: String, value: String, accent: Color) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(7.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(title, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(value, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun AlbumEditor() {
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(9.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("IMPORT ALBUM", color = Yellow, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("${queue.size} / 30", color = Color.White, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        enabled = !sending && !isAnalyzing(),
                        onClick = { chooseAlbum.launch(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        modifier = Modifier.weight(1f)
                    ) { Text("CHOISIR ALBUM", fontSize = 11.sp) }
                    Button(
                        enabled = !sending && !isAnalyzing() && queue.size < 30,
                        onClick = { chooseBatchFolder.launch(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A6A15)),
                        modifier = Modifier.weight(1f)
                    ) { Text("PLUSIEURS", fontSize = 11.sp) }
                }

                if (busyText.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(busyText, color = Yellow, fontSize = 11.sp)
                }

                pendingDraft?.let { draft ->
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoverImage(draft.coverUri, Modifier.size(88.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = editArtist,
                                onValueChange = { editArtist = it },
                                label = { Text("Artiste") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = editYear,
                                    onValueChange = { editYear = it.filter(Char::isDigit).take(4) },
                                    label = { Text("Année") },
                                    singleLine = true,
                                    modifier = Modifier.width(100.dp)
                                )
                                OutlinedTextField(
                                    value = editAlbum,
                                    onValueChange = { editAlbum = it },
                                    label = { Text("Album") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("${draft.tracks.size} titre(s) · source ${formatBytes(draft.sourceBytes)} · export ≈ ${formatBytes(draft.estimatedOutputBytes)}", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(6.dp))
                    Button(
                        enabled = !sending && queue.size < 30 && editAlbum.isNotBlank(),
                        onClick = { addPendingToQueue() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A6A15)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("AJOUTER", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    @Composable
    private fun QueueSection() {
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(9.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ALBUMS À ENVOYER", color = Yellow, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (queue.isNotEmpty() && !sending) {
                        Text(
                            "ANNULER TOUT",
                            color = Red,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                queue.clear()
                                statusText = "File vidée."
                            }
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                if (queue.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .border(1.dp, Color(0xFF46515A), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Aucun album", color = Muted, fontSize = 11.sp)
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(queue, key = { it.id }) { item ->
                            QueueCard(item)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun QueueCard(item: AlbumItem) {
        Column(modifier = Modifier.width(76.dp)) {
            Box {
                CoverImage(
                    item.coverUri,
                    Modifier
                        .size(76.dp)
                        .border(if (item.alreadyOnSd) 2.dp else 1.dp, if (item.alreadyOnSd) Red else Color.White, RoundedCornerShape(5.dp))
                )
                if (!sending) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(Red, RoundedCornerShape(bottomStart = 5.dp))
                            .clickable {
                                queue.removeAll { it.id == item.id }
                                statusText = "Retiré : ${item.artist} — ${item.album}"
                            }
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) { Text("×", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                }
            }
            Text(item.artist.ifBlank { "Artiste" }, color = Color.White, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.album, color = if (item.alreadyOnSd) Red else Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun TransferSection() {
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(9.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = destinationUri != null && queue.isNotEmpty(),
                        onClick = { if (sending) cancelTransfer() else startTransfer() },
                        colors = ButtonDefaults.buttonColors(containerColor = if (sending) Red else Color(0xFF693919)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (sending) "ANNULER" else "A34G · COPIER SUR SD", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text("Dossier en cours : $currentAlbumName", color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                LinearProgressIndicator(progress = { currentAlbumProgress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(5.dp))
                Text("Progression totale : ${(overallProgress * 100).toInt()} %", color = Muted, fontSize = 10.sp)
                LinearProgressIndicator(progress = { overallProgress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(statusText, color = if (statusText.contains("erreur", true)) Red else Muted, fontSize = 10.sp)
            }
        }
    }

    @Composable
    private fun ExistingAlbumsSection() {
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(9.dp), modifier = Modifier.weight(1f, fill = true)) {
            Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = destinationUri != null) { showExistingAlbums = !showExistingAlbums }
                ) {
                    Text("EXPLORER SD", color = Yellow, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${destinationAlbums.size} album(s) ${if (showExistingAlbums) "▲" else "▼"}", color = Color.White, fontSize = 11.sp)
                }
                if (showExistingAlbums) {
                    Spacer(Modifier.height(5.dp))
                    if (destinationAlbums.isEmpty()) {
                        Text("Aucun dossier d'album détecté.", color = Muted, fontSize = 10.sp)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(destinationAlbums) { name ->
                                Text("• $name", color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CoverImage(uri: Uri?, modifier: Modifier) {
        val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
            value = uri?.let { loadBitmap(it) }
        }
        Box(modifier = modifier.background(Color(0xFF303941), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text("♪", color = Yellow, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    private fun persistTreePermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun isAnalyzing(): Boolean = busyText.isNotBlank()

    private fun refreshDestination() {
        val uri = destinationUri ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(this@MainActivity, uri)
            val names = root?.listFiles()?.filter { it.isDirectory }?.mapNotNull { it.name }?.sortedWith(String.CASE_INSENSITIVE_ORDER) ?: emptyList()
            val free = queryFreeBytes(uri)
            withContext(Dispatchers.Main) {
                destinationLabel = "${Build.MODEL} · SD · ${root?.name ?: "Music"}"
                destinationAlbums = names
                destinationAlbumKeys = names.map { normalizeKey(it) }.toSet()
                sdFreeBytes = free
                statusText = "SD prête · ${names.size} album(s) détecté(s)."
            }
        }
    }

    private fun analyzeSingleAlbum(uri: Uri) {
        lifecycleScope.launch {
            busyText = "Analyse de l'album…"
            val draft = withContext(Dispatchers.IO) { buildDraft(uri) }
            busyText = ""
            if (draft == null) {
                statusText = "Aucun fichier audio détecté dans ce dossier."
                return@launch
            }
            pendingDraft = draft
            editArtist = draft.artist
            editYear = draft.year
            editAlbum = draft.album
            statusText = "Album prêt : ${draft.tracks.size} titre(s)."
        }
    }

    private fun importBatch(parentUri: Uri) {
        lifecycleScope.launch {
            busyText = "Analyse des dossiers…"
            val drafts = withContext(Dispatchers.IO) {
                val parent = DocumentFile.fromTreeUri(this@MainActivity, parentUri) ?: return@withContext emptyList()
                parent.listFiles()
                    .filter { it.isDirectory }
                    .take(max(0, 30 - queue.size))
                    .mapNotNull { child -> buildDraft(child.uri) }
            }
            busyText = ""
            var added = 0
            var dupes = 0
            for (draft in drafts) {
                if (queue.size >= 30) break
                val item = draftToItem(draft, draft.artist, draft.year, draft.album)
                if (queue.none { normalizeKey(exportFolderName(it)) == normalizeKey(exportFolderName(item)) }) {
                    queue.add(item)
                    added++
                    if (item.alreadyOnSd) dupes++
                }
            }
            statusText = "$added album(s) ajouté(s)${if (dupes > 0) " · $dupes déjà sur SD" else ""}."
        }
    }

    private fun addPendingToQueue() {
        val draft = pendingDraft ?: return
        if (queue.size >= 30) {
            statusText = "File complète : 30 albums maximum."
            return
        }
        val item = draftToItem(draft, editArtist.trim(), editYear.trim(), editAlbum.trim())
        val same = queue.indexOfFirst { normalizeKey(exportFolderName(it)) == normalizeKey(exportFolderName(item)) }
        if (same >= 0) queue[same] = item else queue.add(item)
        pendingDraft = null
        editArtist = ""
        editYear = ""
        editAlbum = ""
        statusText = if (item.alreadyOnSd) "⚠ Déjà sur SD : ${item.artist} — ${item.album}" else "Album ajouté : ${item.artist} — ${item.album}"
        if (item.alreadyOnSd) duplicateDialogItem = item
    }

    private fun draftToItem(draft: AlbumDraft, artist: String, year: String, album: String): AlbumItem {
        val temp = AlbumItem(
            id = System.nanoTime(),
            sourceUri = draft.sourceUri,
            sourceName = draft.sourceName,
            tracks = draft.tracks,
            coverUri = draft.coverUri,
            sourceBytes = draft.sourceBytes,
            estimatedOutputBytes = draft.estimatedOutputBytes,
            artist = artist,
            year = year,
            album = album,
            alreadyOnSd = false
        )
        return temp.copy(alreadyOnSd = destinationAlbumKeys.contains(normalizeKey(exportFolderName(temp))))
    }

    private suspend fun buildDraft(uri: Uri): AlbumDraft? {
        val root = runCatching { DocumentFile.fromTreeUri(this, uri) }.getOrNull() ?: DocumentFile.fromSingleUri(this, uri) ?: return null
        val audioDocs = mutableListOf<DocumentFile>()
        collectAudio(root, audioDocs, 0, 3)
        if (audioDocs.isEmpty()) return null

        val tracks = audioDocs.mapNotNull { readTrack(it) }.sortedWith(compareBy<TrackInfo>({ it.disc }, { it.track }, { it.title.lowercase(Locale.ROOT) }))
        if (tracks.isEmpty()) return null

        val sourceName = root.name ?: "Album"
        val parsed = parseFolderName(sourceName)
        val first = tracks.first()
        val artist = parsed.first.ifBlank { readAlbumArtist(audioDocs.first()).ifBlank { first.artist } }
        val year = parsed.second.ifBlank { readAlbumYear(audioDocs.first()) }
        val album = parsed.third.ifBlank { readAlbumName(audioDocs.first()).ifBlank { sourceName } }
        val sourceBytes = treeSize(root)
        val cover = findCover(root, 0, 2) ?: extractEmbeddedCover(audioDocs.first(), sourceName)
        val coverBytes = cover?.let { uriSize(it) } ?: 0L
        val durationMs = tracks.sumOf { it.durationMs }
        val mp3Bytes = if (durationMs > 0) (durationMs / 1000.0 * 16_000.0).toLong() else sourceBytes
        val estimated = max(1L, mp3Bytes + coverBytes * tracks.size)
        return AlbumDraft(uri, sourceName, tracks, cover, sourceBytes, estimated, artist, year, album)
    }

    private fun collectAudio(node: DocumentFile, out: MutableList<DocumentFile>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        for (f in runCatching { node.listFiles().toList() }.getOrDefault(emptyList())) {
            if (f.isDirectory) collectAudio(f, out, depth + 1, maxDepth)
            else if (f.isFile && isAudioFile(f)) out.add(f)
        }
    }

    private fun isAudioFile(file: DocumentFile): Boolean {
        val type = file.type.orEmpty().lowercase(Locale.ROOT)
        if (type.startsWith("audio/")) return true
        val ext = file.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return ext in setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma")
    }

    private fun readTrack(doc: DocumentFile): TrackInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, doc.uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().ifBlank {
                doc.name?.substringBeforeLast('.') ?: "Titre"
            }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            val composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER).orEmpty()
            val track = parseNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
            val disc = parseNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)).coerceAtLeast(1)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            TrackInfo(doc.uri, doc.name ?: title, title, artist, composer, disc, track.coerceAtLeast(1), duration)
        } catch (_: Throwable) {
            TrackInfo(doc.uri, doc.name ?: "Titre", doc.name?.substringBeforeLast('.') ?: "Titre", "", "", 1, 1, 0L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readAlbumName(doc: DocumentFile): String = readMeta(doc, MediaMetadataRetriever.METADATA_KEY_ALBUM)
    private fun readAlbumArtist(doc: DocumentFile): String = readMeta(doc, MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
    private fun readAlbumYear(doc: DocumentFile): String = readMeta(doc, MediaMetadataRetriever.METADATA_KEY_YEAR).take(4)

    private fun readMeta(doc: DocumentFile, key: Int): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, doc.uri)
            retriever.extractMetadata(key).orEmpty().trim()
        } catch (_: Throwable) {
            ""
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun parseNumber(raw: String?): Int = raw?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0

    private fun parseFolderName(name: String): Triple<String, String, String> {
        val clean = name.trim()
        val full = Regex("^(.+?)\\s*[-–—]\\s*(\\d{4})\\s*[-–—]\\s*(.+)$").matchEntire(clean)
        if (full != null) return Triple(full.groupValues[1].trim(), full.groupValues[2], full.groupValues[3].trim())
        val yearTitle = Regex("^(\\d{4})\\s*[-–—]\\s*(.+)$").matchEntire(clean)
        if (yearTitle != null) return Triple("", yearTitle.groupValues[1], yearTitle.groupValues[2].trim())
        val artistTitle = Regex("^(.+?)\\s*[-–—]\\s*(.+)$").matchEntire(clean)
        if (artistTitle != null) return Triple(artistTitle.groupValues[1].trim(), "", artistTitle.groupValues[2].trim())
        return Triple("", "", clean)
    }

    private fun treeSize(node: DocumentFile): Long {
        if (node.isFile) return node.length()
        var total = 0L
        for (f in runCatching { node.listFiles().toList() }.getOrDefault(emptyList())) {
            total += if (f.isDirectory) treeSize(f) else f.length()
        }
        return total
    }

    private fun findCover(node: DocumentFile, depth: Int, maxDepth: Int): Uri? {
        if (depth > maxDepth) return null
        val files = runCatching { node.listFiles().toList() }.getOrDefault(emptyList())
        val images = files.filter { it.isFile && isImageFile(it) }
        val preferred = images.maxByOrNull { coverScore(it.name.orEmpty()) }
        if (preferred != null) return preferred.uri
        for (dir in files.filter { it.isDirectory }) {
            findCover(dir, depth + 1, maxDepth)?.let { return it }
        }
        return null
    }

    private fun isImageFile(file: DocumentFile): Boolean {
        val type = file.type.orEmpty().lowercase(Locale.ROOT)
        if (type.startsWith("image/")) return true
        val ext = file.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return ext in setOf("jpg", "jpeg", "png", "webp")
    }

    private fun coverScore(name: String): Int {
        val lower = name.lowercase(Locale.ROOT)
        var score = 0
        listOf("cover", "front", "folder", "album", "artwork", "pochette").forEachIndexed { i, key ->
            if (lower.contains(key)) score += 100 - i * 5
        }
        return score
    }

    private fun extractEmbeddedCover(audio: DocumentFile, label: String): Uri? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, audio.uri)
            val bytes = retriever.embeddedPicture ?: return null
            val dir = File(cacheDir, "covers").apply { mkdirs() }
            val f = File(dir, "${normalizeKey(label).take(40)}_${System.nanoTime()}.jpg")
            f.writeBytes(bytes)
            Uri.fromFile(f)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        if (uri.scheme == "file") {
            FileInputStream(File(requireNotNull(uri.path))).use { BitmapFactory.decodeStream(it) }
        } else {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    private fun uriSize(uri: Uri): Long = runCatching {
        if (uri.scheme == "file") File(requireNotNull(uri.path)).length()
        else contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
    }.getOrDefault(0L)

    private fun ffmpegReadPath(uri: Uri): String {
        return if (uri.scheme == "file") requireNotNull(uri.path) else FFmpegKitConfig.getSafParameterForRead(this, uri)
    }

    private fun queryFreeBytes(treeUri: Uri): Long {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val volumeId = docId.substringBefore(':')
            val sm = getSystemService(StorageManager::class.java)
            if (volumeId.equals("primary", true)) {
                StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val vol = sm.storageVolumes.firstOrNull { it.uuid.equals(volumeId, true) }
                val dir = vol?.directory
                if (dir != null) StatFs(dir.absolutePath).availableBytes else -1L
            } else {
                -1L
            }
        }.getOrDefault(-1L)
    }

    private fun startTransfer() {
        val destUri = destinationUri ?: return
        if (queue.isEmpty()) return
        if (sending) return
        val snapshot = queue.toList()
        val totalEstimate = snapshot.sumOf { it.estimatedOutputBytes }
        if (sdFreeBytes >= 0 && totalEstimate > sdFreeBytes) {
            statusText = "Espace insuffisant sur la carte SD."
            return
        }

        sending = true
        currentAlbumProgress = 0f
        overallProgress = 0f
        val started = System.nanoTime()
        var completedOutputBytes = 0L

        transferJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val root = DocumentFile.fromTreeUri(this@MainActivity, destUri)
                    ?: error("Dossier SD inaccessible")
                snapshot.forEachIndexed { albumIndex, album ->
                    ensureActive()
                    val exportName = exportFolderName(album)
                    withContext(Dispatchers.Main) {
                        currentAlbumName = exportName
                        statusText = "Album ${albumIndex + 1}/${snapshot.size} · préparation…"
                        currentAlbumProgress = 0.02f
                    }

                    val existing = root.findFile(exportName)
                    if (existing != null && existing.isDirectory) {
                        deleteTree(existing)
                    }
                    val targetDir = root.createDirectory(exportName)
                        ?: error("Impossible de créer $exportName")

                    val tracks = album.tracks.sortedWith(compareBy<TrackInfo>({ it.disc }, { it.track }, { it.title.lowercase(Locale.ROOT) }))
                    val discTotals = tracks.groupingBy { it.disc }.eachCount()
                    val maxDisc = tracks.maxOfOrNull { it.disc } ?: 1

                    for ((trackIndex, track) in tracks.withIndex()) {
                        ensureActive()
                        val titleSafe = sanitizeName(track.title)
                        val outName = if (maxDisc > 1) {
                            "CD${track.disc}-${track.track.toString().padStart(2, '0')} - $titleSafe.mp3"
                        } else {
                            "${track.track.toString().padStart(2, '0')} - $titleSafe.mp3"
                        }
                        targetDir.findFile(outName)?.delete()
                        val output = targetDir.createFile("audio/mpeg", outName)
                            ?: error("Impossible de créer $outName")
                        val inputSaf = ffmpegReadPath(track.uri)
                        val outputSaf = FFmpegKitConfig.getSafParameterForWrite(this@MainActivity, output.uri)
                        val args = mutableListOf(
                            "-y", "-hide_banner", "-loglevel", "error",
                            "-i", inputSaf
                        )
                        var coverSaf: String? = null
                        album.coverUri?.let { coverUri ->
                            coverSaf = ffmpegReadPath(coverUri)
                            args += listOf("-i", coverSaf!!)
                        }
                        args += listOf("-map", "0:a:0")
                        if (coverSaf != null) args += listOf("-map", "1:v:0")
                        args += listOf(
                            "-map_metadata", "-1",
                            "-c:a", "libmp3lame",
                            "-b:a", "128k",
                            "-metadata", "title=${track.title}",
                            "-metadata", "artist=${track.artist.ifBlank { album.artist }}",
                            "-metadata", "album=${album.album}",
                            "-metadata", "album_artist=${album.artist}",
                            "-metadata", "track=${track.track}/${discTotals[track.disc] ?: tracks.size}",
                            "-metadata", "disc=${track.disc}"
                        )
                        if (album.year.isNotBlank()) args += listOf("-metadata", "date=${album.year}")
                        if (track.composer.isNotBlank()) args += listOf("-metadata", "composer=${track.composer}")
                        if (coverSaf != null) {
                            args += listOf(
                                "-c:v", "mjpeg", "-q:v", "2",
                                "-metadata:s:v", "title=Album cover",
                                "-metadata:s:v", "comment=Cover (front)",
                                "-disposition:v", "attached_pic"
                            )
                        }
                        args += listOf("-id3v2_version", "3", "-write_id3v1", "1", outputSaf)

                        withContext(Dispatchers.Main) {
                            statusText = "Album ${albumIndex + 1}/${snapshot.size} · conversion ${trackIndex + 1}/${tracks.size} · ${track.title}"
                        }
                        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
                        if (!ReturnCode.isSuccess(session.returnCode)) {
                            output.delete()
                            throw IllegalStateException("Conversion impossible : ${track.displayName}")
                        }
                        val albumFraction = (trackIndex + 1).toFloat() / tracks.size.coerceAtLeast(1)
                        val totalFraction = (albumIndex + albumFraction) / snapshot.size.toFloat()
                        withContext(Dispatchers.Main) {
                            currentAlbumProgress = albumFraction
                            overallProgress = totalFraction
                        }
                    }

                    completedOutputBytes += album.estimatedOutputBytes
                    withContext(Dispatchers.Main) {
                        queue.removeAll { it.id == album.id }
                        currentAlbumProgress = 1f
                        sdFreeBytes = queryFreeBytes(destUri)
                    }
                }

                val elapsedSec = (System.nanoTime() - started) / 1_000_000_000.0
                if (elapsedSec > 2 && completedOutputBytes > 0) {
                    val bps = completedOutputBytes / elapsedSec
                    withContext(Dispatchers.Main) {
                        learnedBytesPerSecond = bps
                        prefs.edit().putFloat("learned_bps", bps.toFloat()).apply()
                    }
                }
                withContext(Dispatchers.Main) {
                    overallProgress = 1f
                    currentAlbumProgress = 1f
                    statusText = "Copie terminée sur la carte SD."
                    currentAlbumName = "Terminé"
                    refreshDestination()
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.Main) {
                    statusText = "Envoi annulé. La file restante est conservée."
                    currentAlbumName = "—"
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    statusText = "Erreur : ${t.message ?: "transfert impossible"}"
                    currentAlbumName = "—"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    sending = false
                    transferJob = null
                    FFmpegKit.cancel()
                }
            }
        }
    }

    private fun cancelTransfer() {
        statusText = "Arrêt en cours…"
        FFmpegKit.cancel()
        transferJob?.cancel()
    }

    private fun deleteTree(node: DocumentFile) {
        if (node.isDirectory) {
            runCatching { node.listFiles().toList() }.getOrDefault(emptyList()).forEach { deleteTree(it) }
        }
        runCatching { node.delete() }
    }

    private fun exportFolderName(item: AlbumItem): String {
        return sanitizeName(listOf(item.artist.trim(), item.year.trim(), item.album.trim()).filter { it.isNotBlank() }.joinToString(" "))
    }

    private fun sanitizeName(value: String): String {
        return value
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .ifBlank { "Album" }
    }

    private fun normalizeKey(value: String): String {
        val noAccent = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return noAccent.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        val gb = bytes / 1_073_741_824.0
        return if (gb >= 1.0) String.format(Locale.FRANCE, "%.2f Go", gb)
        else String.format(Locale.FRANCE, "%.0f Mo", bytes / 1_048_576.0)
    }

    private fun estimateTimeText(bytes: Long): String {
        if (bytes <= 0) return "—"
        val bps = if (learnedBytesPerSecond > 64_000) learnedBytesPerSecond else 1_500_000.0
        val seconds = (bytes / bps).toLong().coerceAtLeast(1)
        return when {
            seconds < 60 -> "≈ ${seconds}s"
            seconds < 3600 -> "≈ ${seconds / 60} min"
            else -> "≈ ${seconds / 3600} h ${seconds % 3600 / 60} min"
        }
    }
}
