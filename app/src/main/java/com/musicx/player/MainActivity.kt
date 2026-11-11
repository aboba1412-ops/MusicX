package com.musicx.player

import android.Manifest
import android.content.ContentUris
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import java.util.Locale

private val Bg = Color(0xFF000000)
private val SurfaceX = Color(0xFF160B10)
private val Violet = Color(0xFFFF3158)
private val Cyan = Color(0xFFFF8FA3)
private val Muted = Color(0xFFB9A6AD)
private const val PREFS = "musicx_data"

data class Song(val uri: Uri, val title: String, val artist: String, val album: String = "Без альбома")
data class OnlineTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artwork: String?
)

private suspend fun searchAudiusTracks(query: String): List<OnlineTrack> =
    withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://api.audius.co/v1/tracks/search?query=$encoded&limit=25")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(response).optJSONArray("data")
                ?: return@withContext emptyList()

            buildList {
                for (i in 0 until data.length()) {
                    val track = data.getJSONObject(i)
                    val id = track.optString("id")
                    if (id.isBlank()) continue

                    val artist = track.optJSONObject("user")
                        ?.optString("name") ?: "Audius"

                    val artwork = track.optJSONObject("artwork")
                        ?.optString("150x150")
                        ?.takeIf { it.startsWith("https://") }

                    add(
                        OnlineTrack(
                            id = id,
                            title = track.optString("title", "Без названия"),
                            artist = artist,
                            artwork = artwork
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }
data class ArchiveTrack(
    val id: String,
    val title: String,
    val artist: String,
    val license: String
)

private suspend fun searchArchiveTracks(query: String): List<ArchiveTrack> =
    withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(
            "mediatype:audio AND \"$query\"",
            "UTF-8"
        )

        val url = URL(
            "https://archive.org/advancedsearch.php?q=$encoded" +
                "&fl%5B%5D=identifier&fl%5B%5D=title" +
                "&fl%5B%5D=creator&fl%5B%5D=licenseurl" +
                "&rows=30&output=json"
        )

        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val response = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            val docs = JSONObject(response)
                .optJSONObject("response")
                ?.optJSONArray("docs")
                ?: return@withContext emptyList()

            buildList {
                for (i in 0 until docs.length()) {
                    val item = docs.getJSONObject(i)
                    val license = item.optString("licenseurl")
                    val lower = license.lowercase(Locale.ROOT)

                    val allowed =
                        lower.contains("creativecommons.org/licenses/by/") ||
                        lower.contains("creativecommons.org/publicdomain/zero/") ||
                        lower.contains("creativecommons.org/publicdomain/mark/")

                    if (!allowed) continue

                    val id = item.optString("identifier")
                    if (id.isBlank()) continue

                    add(
                        ArchiveTrack(
                            id = id,
                            title = item.optString("title", "Без названия"),
                            artist = item.optString("creator", "Неизвестный автор"),
                            license = license
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }
class MainActivity : ComponentActivity() {
    private suspend fun getArchiveAudioUrl(id: String): String? =
    withContext(Dispatchers.IO) {
        val url = URL("https://archive.org/metadata/$id")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val response = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            val files = JSONObject(response).optJSONArray("files")
                ?: return@withContext null

            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                val name = file.optString("name")
                val lower = name.lowercase(Locale.ROOT)

                if (
                    lower.endsWith(".mp3") ||
                    lower.endsWith(".m4a") ||
                    lower.endsWith(".ogg") ||
                    lower.endsWith(".flac") ||
                    lower.endsWith(".wav")
                ) {
                    return@withContext "https://archive.org/download/$id/${Uri.encode(name, "/")}"
                }
            }

            null
        } finally {
            connection.disconnect()
        }
    }
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
window.navigationBarColor = android.graphics.Color.BLACK
        setContent { LoadingScreen() }
        val token = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching { future.get() }.onSuccess { controller ->
                setContent { MusicXApp(controller) }
            }.onFailure {
                setContent { ErrorScreen(it.message ?: "Не удалось запустить аудиосервис") }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Cyan)
    }
}

@Composable
private fun ErrorScreen(message: String) {
    Box(Modifier.fillMaxSize().background(Bg).padding(24.dp), contentAlignment = Alignment.Center) {
        Text("Ошибка запуска MusicX\n$message", color = Color.White)
    }
}

@Composable
fun MusicXApp(player: Player) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var tab by remember { mutableStateOf("Музыка") }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var search by remember { mutableStateOf("") }
    var onlineQuery by remember { mutableStateOf("") }
var onlineTracks by remember { mutableStateOf<List<OnlineTrack>>(emptyList()) }
var onlineLoading by remember { mutableStateOf(false) }
var onlineError by remember { mutableStateOf("") }
val onlineScope = rememberCoroutineScope()
var archiveTracks by remember {
    mutableStateOf<List<ArchiveTrack>>(emptyList())
}
var archiveLoading by remember { mutableStateOf(false) }
var archiveError by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var playlists by remember { mutableStateOf(prefs.getStringSet("playlists", emptySet())?.toList() ?: emptyList()) }
    var history by remember { mutableStateOf(prefs.getString("history", "")!!.split("\n").filter { it.isNotBlank() }) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var songToAdd by remember { mutableStateOf<Song?>(null) }
    var audioSessionId by remember { mutableIntStateOf(prefs.getInt("audio_session_id", 0)) }
    var lyrics by remember { mutableStateOf("") }
    var equalizer by remember { mutableStateOf<Equalizer?>(null) }
    var eqBands by remember { mutableStateOf<List<Int>>(emptyList()) }
    var eqMin by remember { mutableIntStateOf(-1500) }
    var eqMax by remember { mutableIntStateOf(1500) }
    var eqEnabled by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var showSpeed by remember { mutableStateOf(false) }
    var showPlaybackMode by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }
BackHandler(
    enabled = showNowPlaying || showSpeed || showPlaybackMode || showCreatePlaylist ||
            songToAdd != null || selectedPlaylist != null || tab != "Музыка"
) {
    when {
        showSpeed -> showSpeed = false
        showPlaybackMode -> showPlaybackMode = false
        songToAdd != null -> songToAdd = null
        showCreatePlaylist -> showCreatePlaylist = false
        showNowPlaying -> showNowPlaying = false
        selectedPlaylist != null -> selectedPlaylist = null
        tab == "Тексты" -> {
    tab = "Музыка"
    search = ""
    showNowPlaying = player.currentMediaItem != null
}
tab != "Музыка" -> {
    tab = "Музыка"
    search = ""
}
    }
}
    fun loadLyrics() {
        val key = player.currentMediaItem?.mediaId.orEmpty()
        lyrics = prefs.getString("lyrics_$key", "") ?: ""
    }
    
fun addSongs(newSongs: List<Song>, replace: Boolean = false) {
    if (newSongs.isEmpty()) return

    val oldSongs = songs
    val merged = if (replace) {
        newSongs.distinctBy { it.uri.toString() }
    } else {
        (oldSongs + newSongs).distinctBy { it.uri.toString() }
    }

    songs = merged

    val items = (if (replace) merged else newSongs.filter { newSong ->
        oldSongs.none { it.uri == newSong.uri }
    }).map { song ->
        MediaItem.Builder()
            .setUri(song.uri)
            .setMediaId(song.uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .build()
            )
            .build()
    }

    if (items.isEmpty()) return

    if (replace || player.mediaItemCount == 0) {
        player.setMediaItems(items)
        player.prepare()
        if (player.mediaItemCount > 0) player.seekTo(0, 0)
    } else {
        player.addMediaItems(items)
    }
}

    fun scanDevice() {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM)
        val found = mutableListOf<Song>()
        runCatching {
            context.contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    found.add(Song(uri, cursor.getString(titleCol) ?: "Без названия", cursor.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Неизвестный исполнитель", cursor.getString(albumCol) ?: "Без альбома"))
                }
            }
        }
        addSongs(found)
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED && songs.isEmpty()) scanDevice()
    }
    val scanPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanDevice()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val imported = uris.map { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Аудиофайл"
            runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name } }
            Song(uri, name.substringBeforeLast('.', name), "Локальный файл")
        }
        addSongs(imported)
    }

    LaunchedEffect(player) {
        while (true) {
            audioSessionId = prefs.getInt("audio_session_id", 0)
            currentIndex = player.currentMediaItemIndex
            isPlaying = player.isPlaying
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            delay(350)
        }
    }
    LaunchedEffect(currentIndex) {
        loadLyrics()
        songs.getOrNull(currentIndex)?.uri?.toString()?.let { uri ->
            history = (listOf(uri) + history).distinct().take(40)
            prefs.edit().putString("history", history.joinToString("\n")).apply()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { loadLyrics() }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(audioSessionId) {
        val fx = runCatching { if (audioSessionId > 0) Equalizer(0, audioSessionId) else null }.getOrNull()
        equalizer = fx
        if (fx != null) {
            runCatching {
                eqMin = fx.bandLevelRange[0].toInt(); eqMax = fx.bandLevelRange[1].toInt()
                eqBands = (0 until fx.numberOfBands).map { fx.getBandLevel(it.toShort()).toInt() }
            }
        }
        onDispose { runCatching { fx?.release() }; equalizer = null }
    }

    val current = songs.getOrNull(currentIndex)
    val visibleSongs = when (tab) {
        "Избранное" -> songs.filter { it.uri.toString() in favorites }
        "История" -> history.mapNotNull { uri -> songs.firstOrNull { it.uri.toString() == uri } }
        "Коллекции" -> if (selectedPlaylist != null) songs.filter { it.uri.toString() in (prefs.getStringSet("playlist_${selectedPlaylist}", emptySet()) ?: emptySet()) } else songs
        else -> songs
    }.filter { search.isBlank() || it.title.contains(search, true) || it.artist.contains(search, true) || it.album.contains(search, true) }

    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = SurfaceX, primary = Violet, secondary = Cyan)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF260A14), Bg, Bg)))) {
            
Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
    Spacer(Modifier.height(18.dp))

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFFF3158), Color(0xFF9E1238))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "M",
                color = Color.White,
                fontSize = 25.sp,
                fontWeight = FontWeight.Black
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                "MUSICX PREMIUM",
                color = Violet,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                tab,
                color = Color.White,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "ТВОЯ МУЗЫКА. ТВОИ ПРАВИЛА.",
                color = Muted,
                fontSize = 9.sp,
                letterSpacing = 1.sp
            )
        }

        IconButton(
            onClick = { importLauncher.launch(arrayOf("audio/*")) },
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(SurfaceX)
        ) {
            Icon(
                Icons.Default.Add,
                "Добавить музыку",
                tint = Violet,
                modifier = Modifier.size(26.dp)
            )
        }
    }

    Spacer(Modifier.height(20.dp))

                when (tab) {
                    "Музыка", "Избранное" -> {
                        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("Поиск трека, исполнителя, альбома") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Violet, unfocusedBorderColor = Muted))
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${visibleSongs.size} треков", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) scanDevice() else scanPermission.launch(permission)
                            }) { Icon(Icons.Default.Refresh, null); Text("Сканировать") }
                        }
                        if (visibleSongs.isEmpty()) EmptyLibrary(onImport = { importLauncher.launch(arrayOf("audio/*")) }, onScan = {
                            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) scanDevice() else scanPermission.launch(permission)
                        }) else LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 180.dp)) {
                            items(visibleSongs, key = { it.uri.toString() }) { song ->
                                val realIndex = songs.indexOfFirst { it.uri == song.uri }
                                SongRow(song, realIndex == currentIndex, song.uri.toString() in favorites,
                                    onClick = { player.seekTo(realIndex, 0); player.play() },
                                    onFavorite = {
                                        favorites = if (song.uri.toString() in favorites) favorites - song.uri.toString() else favorites + song.uri.toString()
                                        prefs.edit().putStringSet("favorites", favorites).apply()
                                    }, onAdd = { songToAdd = song })
                            }
                        }
                    }
                    "Онлайн" -> {
    OutlinedTextField(
        value = onlineQuery,
        onValueChange = { onlineQuery = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Найти музыку в Audius") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Violet,
            unfocusedBorderColor = Muted
        )
    )

    Spacer(Modifier.height(10.dp))

    Button(
        onClick = {
            if (onlineQuery.isNotBlank()) {
                onlineScope.launch {
                    onlineLoading = true
                    onlineError = ""
                    onlineTracks = try {
                        searchAudiusTracks(onlineQuery.trim())
                    } catch (e: Exception) {
                        onlineError = "Ошибка загрузки. Проверь интернет."
                        emptyList()
                    }
                    onlineLoading = false
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Искать музыку")
    }

    if (onlineLoading) {
        CircularProgressIndicator(color = Violet)
    }

    if (onlineError.isNotEmpty()) {
        Text(onlineError, color = Color.Red)
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 180.dp)
    ) {
        items(onlineTracks) { track ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceX)
                    .clickable {
                        val song = Song(
                            Uri.parse("https://api.audius.co/v1/tracks/${track.id}/stream"),
                            track.title,
                            track.artist,
                            "Audius"
                        )
                        addSongs(listOf(song))
                        player.seekTo(player.mediaItemCount - 1, 0)
                        player.play()
                    }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Violet)
                Column(
                    Modifier.weight(1f).padding(start = 12.dp)
                ) {
                    Text(track.title, color = Color.White)
                    Text(track.artist, color = Muted, fontSize = 12.sp)
                }
                Icon(Icons.Default.PlayArrow, null, tint = Violet)
            }
        }
    }
}
                    "История" -> {
                        Text("Недавно прослушано", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        if (visibleSongs.isEmpty()) Text("История появится после прослушивания музыки.", color = Muted)
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 180.dp)) {
                            items(visibleSongs) { song ->
                                SongRow(song, songs.indexOf(song) == currentIndex, song.uri.toString() in favorites,
                                    onClick = { player.seekTo(songs.indexOf(song), 0); player.play() },
                                    onFavorite = { favorites = if (song.uri.toString() in favorites) favorites - song.uri.toString() else favorites + song.uri.toString(); prefs.edit().putStringSet("favorites", favorites).apply() },
                                    onAdd = { songToAdd = song })
                            }
                        }
                    }
                    "Тексты" -> {
                        Text(current?.title ?: "Нет выбранного трека", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(value = lyrics, onValueChange = {
                            lyrics = it
                            val key = player.currentMediaItem?.mediaId.orEmpty()
                            if (key.isNotBlank()) prefs.edit().putString("lyrics_$key", it).apply()
                        }, modifier = Modifier.fillMaxWidth().weight(1f), placeholder = { Text("Вставь или напиши текст песни…") },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Violet, unfocusedBorderColor = Muted))
                        Text("Текст сохраняется отдельно для каждого трека.", color = Muted, fontSize = 11.sp)
                    }
                    "Эквалайзер" -> EqualizerScreen(equalizer, eqBands, eqMin, eqMax, eqEnabled,
                        onEnabled = { enabled -> eqEnabled = enabled; runCatching { equalizer?.enabled = enabled } },
                        onBand = { index, value ->
                            val updated = eqBands.toMutableList(); if (index in updated.indices) updated[index] = value; eqBands = updated
                            runCatching { equalizer?.setBandLevel(index.toShort(), value.toShort()) }
                        })
                    "Коллекции" -> {
                        if (selectedPlaylist != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { selectedPlaylist = null }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                                Text(selectedPlaylist ?: "", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            if (visibleSongs.isEmpty()) Text("В этом плейлисте пока нет треков.", color = Muted)
                            else LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 180.dp)) {
                                items(visibleSongs) { song -> SongRow(song, songs.indexOf(song) == currentIndex, song.uri.toString() in favorites,
                                    onClick = { player.seekTo(songs.indexOf(song), 0); player.play() }, onFavorite = {
                                        favorites = if (song.uri.toString() in favorites) favorites - song.uri.toString() else favorites + song.uri.toString()
                                        prefs.edit().putStringSet("favorites", favorites).apply()
                                    }, onAdd = { songToAdd = song }) }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Плейлисты и альбомы", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { showCreatePlaylist = true }) { Icon(Icons.Default.Add, null); Text("Плейлист") }
                            }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 180.dp)) {
                                items(playlists) { name ->
                                    val count = (prefs.getStringSet("playlist_$name", emptySet()) ?: emptySet()).size
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceX).clickable { selectedPlaylist = name }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.QueueMusic, null, tint = Cyan)
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(name, color = Color.White, fontWeight = FontWeight.SemiBold); Text("$count треков", color = Muted, fontSize = 11.sp) }
                                    }
                                }
                                item { Spacer(Modifier.height(8.dp)); Text("Альбомы", color = Muted, fontSize = 12.sp) }
                                items(songs.groupBy { it.album }.keys.toList()) { album ->
                                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceX).clickable { search = album; tab = "Музыка" }.padding(15.dp)) {
                                        Text(album, color = Color.White, fontWeight = FontWeight.SemiBold)
                                        Text("${songs.count { it.album == album }} треков · ${songs.firstOrNull { it.album == album }?.artist ?: ""}", color = Muted, fontSize = 12.sp)
                                    }
                                }
                                item { Spacer(Modifier.height(8.dp)); Text("Исполнители", color = Muted, fontSize = 12.sp) }
                                items(songs.groupBy { it.artist }.keys.toList()) { artist ->
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceX).clickable { search = artist; tab = "Музыка" }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Person, null, tint = Violet)
                                        Column(Modifier.padding(start = 12.dp)) { Text(artist, color = Color.White, fontWeight = FontWeight.SemiBold); Text("${songs.count { it.artist == artist }} треков", color = Muted, fontSize = 11.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(118.dp))
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 9.dp)) {
                if (current != null) MiniPlayer(current, isPlaying, position, duration,
                    onOpen = { showNowPlaying = true }, onToggle = { if (isPlaying) player.pause() else player.play() },
                    onPrevious = { player.seekToPreviousMediaItem() }, onNext = { player.seekToNextMediaItem() })
                Spacer(Modifier.height(8.dp))
                NavigationBarX(tab) { tab = it; search = "" }
            }
            if (showCreatePlaylist) AlertDialog(onDismissRequest = { showCreatePlaylist = false }, title = { Text("Новый плейлист") },
                text = { OutlinedTextField(value = playlistName, onValueChange = { playlistName = it }, label = { Text("Название") }, singleLine = true) },
                confirmButton = { TextButton(onClick = {
                    val name = playlistName.trim()
                    if (name.isNotEmpty() && name !in playlists) { playlists = playlists + name; prefs.edit().putStringSet("playlists", playlists.toSet()).apply() }
                    playlistName = ""; showCreatePlaylist = false
                }) { Text("Создать") } }, dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Отмена") } })
            if (songToAdd != null) AlertDialog(onDismissRequest = { songToAdd = null }, title = { Text("Добавить в плейлист") },
                text = { if (playlists.isEmpty()) Text("Сначала создай плейлист во вкладке «Коллекции».") else Column { playlists.forEach { name -> TextButton(onClick = {
                    val key = "playlist_$name"; val set = (prefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet(); songToAdd?.let { set.add(it.uri.toString()) }; prefs.edit().putStringSet(key, set).apply(); songToAdd = null
                }) { Text(name) } } } }, confirmButton = { TextButton(onClick = { songToAdd = null }) { Text("Закрыть") } })
            if (showNowPlaying && current != null) NowPlayingScreen(
                song = current, playing = isPlaying, position = position, duration = duration,
                shuffle = player.shuffleModeEnabled, repeatMode = player.repeatMode, speed = speed,
                onClose = { showNowPlaying = false },
                onSeek = { player.seekTo(it) },
                onToggle = { if (isPlaying) player.pause() else player.play() },
                onPrevious = { player.seekToPreviousMediaItem() }, onNext = { player.seekToNextMediaItem() },
                onShuffle = { player.shuffleModeEnabled = !player.shuffleModeEnabled },
                onRepeat = { player.repeatMode = when (player.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } },
                onSpeed = { showSpeed = true },
onPlaybackMode = { showPlaybackMode = true },
onLyrics = { showNowPlaying = false; tab = "Тексты" }
            )
            if (showSpeed) AlertDialog(onDismissRequest = { showSpeed = false }, title = { Text("Скорость воспроизведения") },
                text = { Column { listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { rate -> TextButton(onClick = { speed = rate; player.setPlaybackSpeed(rate); showSpeed = false }) { Text("${rate}×", color = if (speed == rate) Cyan else Color.White) } } } },
                confirmButton = { TextButton(onClick = { showSpeed = false }) { Text("Закрыть") } })
            
if (showPlaybackMode) AlertDialog(
    onDismissRequest = { showPlaybackMode = false },
    title = {
        Text(
            "Режим воспроизведения",
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    },
    text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ПОВТОР", color = Muted, fontSize = 11.sp)

            TextButton(onClick = {
                player.repeatMode = Player.REPEAT_MODE_OFF
                showPlaybackMode = false
            }) {
                Icon(Icons.Default.Repeat, null, tint = if (player.repeatMode == Player.REPEAT_MODE_OFF) Cyan else Muted)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (player.repeatMode == Player.REPEAT_MODE_OFF) "✓ Без повтора" else "Без повтора",
                    color = if (player.repeatMode == Player.REPEAT_MODE_OFF) Cyan else Color.White
                )
            }

            TextButton(onClick = {
                player.repeatMode = Player.REPEAT_MODE_ONE
                showPlaybackMode = false
            }) {
                Icon(Icons.Default.RepeatOne, null, tint = if (player.repeatMode == Player.REPEAT_MODE_ONE) Cyan else Muted)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (player.repeatMode == Player.REPEAT_MODE_ONE) "✓ Повтор одного трека" else "Повтор одного трека",
                    color = if (player.repeatMode == Player.REPEAT_MODE_ONE) Cyan else Color.White
                )
            }

            TextButton(onClick = {
                player.repeatMode = Player.REPEAT_MODE_ALL
                showPlaybackMode = false
            }) {
                Icon(Icons.Default.Repeat, null, tint = if (player.repeatMode == Player.REPEAT_MODE_ALL) Cyan else Muted)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (player.repeatMode == Player.REPEAT_MODE_ALL) "✓ Повтор очереди" else "Повтор очереди",
                    color = if (player.repeatMode == Player.REPEAT_MODE_ALL) Cyan else Color.White
                )
            }

            HorizontalDivider(color = Muted.copy(alpha = 0.25f))
            Text("ПЕРЕМЕШИВАНИЕ", color = Muted, fontSize = 11.sp)

            TextButton(onClick = {
                player.shuffleModeEnabled = !player.shuffleModeEnabled
            }) {
                Icon(
                    Icons.Default.Shuffle,
                    null,
                    tint = if (player.shuffleModeEnabled) Cyan else Muted
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (player.shuffleModeEnabled) "✓ Перемешивание включено" else "Перемешивание выключено",
                    color = if (player.shuffleModeEnabled) Cyan else Color.White
                )
            }
        }
    },
    confirmButton = {
        TextButton(onClick = { showPlaybackMode = false }) {
            Text("Готово", color = Cyan)
        }
    }
)

        }
    }
}


@Composable
private fun NowPlayingScreen(
    song: Song, playing: Boolean, position: Long, duration: Long, shuffle: Boolean, repeatMode: Int, speed: Float,
    onClose: () -> Unit, onSeek: (Long) -> Unit, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onShuffle: () -> Unit, onRepeat: () -> Unit, onSpeed: () -> Unit,
    onPlaybackMode: () -> Unit, onLyrics: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF24143D), Color(0xFF090610), Color.Black)
                )
            )
            .padding(horizontal = 24.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.KeyboardArrowDown, "Закрыть", tint = Color.White)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("СЕЙЧАС ИГРАЕТ", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("MusicX Premium", color = Color.White, fontSize = 12.sp)
            }
            IconButton(onClick = onLyrics) {
                Icon(Icons.Default.Lyrics, "Текст песни", tint = Cyan)
            }
        }

        Spacer(Modifier.weight(0.7f))

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFB66DFF), Color(0xFF5925A8), Color(0xFF171020))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.GraphicEq,
                null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(100.dp)
            )
        }

        Spacer(Modifier.height(30.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(song.artist, color = Muted, fontSize = 14.sp)
            }
            Icon(Icons.Default.Favorite, null, tint = Violet, modifier = Modifier.size(25.dp))
        }

        Spacer(Modifier.height(22.dp))

        Slider(
            value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Violet,
                inactiveTrackColor = Color(0xFF40334F)
            )
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = Muted, fontSize = 11.sp)
            Text(formatTime(duration), color = Muted, fontSize = 11.sp)
        }

        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onShuffle) {
                Icon(Icons.Default.Shuffle, null, tint = if (shuffle) Cyan else Muted)
            }
            IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(68.dp).clip(CircleShape).background(Violet)
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    tint = Color.Black,
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = onRepeat) {
                Icon(
                    if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    null,
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) Cyan else Muted
                )
            }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                (if (shuffle) "Перемешивание • " else "") + when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Повтор трека"
                    Player.REPEAT_MODE_ALL -> "Повтор очереди"
                    else -> "Без повтора"
                },
                color = Muted,
                fontSize = 11.sp
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onPlaybackMode) {
                Icon(Icons.Default.Repeat, null, tint = Cyan)
                Text("Режим", color = Color.White)
            }
            TextButton(onClick = onSpeed) {
                Icon(Icons.Default.Speed, null, tint = Cyan)
                Text("${speed}×", color = Color.White)
            }
        }

        Spacer(Modifier.weight(0.5f))
    }
}


@Composable
private fun EmptyLibrary(onImport: () -> Unit, onScan: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 35.dp).clip(RoundedCornerShape(22.dp)).background(SurfaceX).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.LibraryMusic, null, tint = Violet, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(10.dp)); Text("Музыка пока не найдена", color = Color.White, fontWeight = FontWeight.SemiBold)
        Text("Просканируй телефон или добавь аудиофайлы вручную.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp)); Button(onClick = onScan) { Icon(Icons.Default.Refresh, null); Text("Сканировать телефон") }
        TextButton(onClick = onImport) { Text("Выбрать файлы вручную") }
    }
}

@Composable
private fun SongRow(song: Song, active: Boolean, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(if (active) Violet.copy(alpha = .20f) else SurfaceX)
        .clickable(onClick = onClick).padding(start = 12.dp, end = 5.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Violet, Cyan))), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Bg) }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text("${song.artist} · ${song.album}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onAdd) { Icon(Icons.Default.PlaylistAdd, "В плейлист", tint = Muted) }
        IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favorite) Color(0xFFFF6B9A) else Muted) }
    }
}

@Composable
private fun MiniPlayer(song: Song, playing: Boolean, position: Long, duration: Long, onOpen: () -> Unit, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xF21A1D30)).clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Violet, Cyan))), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Bg) }
            Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                Text(song.title, color = Color.White, maxLines = 1, fontWeight = FontWeight.SemiBold)
                Text(song.artist, color = Muted, fontSize = 10.sp, maxLines = 1)
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
            IconButton(onClick = onToggle) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Cyan) }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
        }
        LinearProgressIndicator(progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Violet, trackColor = Muted.copy(alpha = .25f))
    }
}

@Composable
private fun NavigationBarX(selected: String, onSelect: (String) -> Unit) {
    val tabs = listOf("Музыка" to Icons.Default.MusicNote, "Избранное" to Icons.Default.Favorite, "Онлайн" to Icons.Default.Cloud, "Коллекции" to Icons.Default.QueueMusic, "История" to Icons.Default.History, "Тексты" to Icons.Default.Lyrics, "Эквалайзер" to Icons.Default.Equalizer)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xF21A1D30)).padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        tabs.forEach { (name, icon) ->
            val active = selected == name
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (active) Violet.copy(alpha = .25f) else Color.Transparent).clickable { onSelect(name) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = if (active) Cyan else Muted, modifier = Modifier.size(19.dp))
                Text(name, color = if (active) Color.White else Muted, fontSize = 8.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EqualizerScreen(eq: Equalizer?, bands: List<Int>, min: Int, max: Int, enabled: Boolean, onEnabled: (Boolean) -> Unit, onBand: (Int, Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Эквалайзер", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("Настройка звучания", color = Muted, fontSize = 12.sp) }
        Switch(checked = enabled, onCheckedChange = onEnabled)
    }
    Spacer(Modifier.height(18.dp))
    if (eq == null || bands.isEmpty()) {
        Text("Запусти трек, чтобы подключить эквалайзер.", color = Muted)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            bands.forEachIndexed { index, value ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${eq.getCenterFreq(index.toShort()) / 1000} кГц", color = Muted, fontSize = 11.sp, modifier = Modifier.width(54.dp))
                    Slider(value = value.toFloat(), onValueChange = { onBand(index, it.toInt()) }, valueRange = min.toFloat()..max.toFloat(), enabled = enabled, modifier = Modifier.weight(1f))
                    Text("${value / 100} dB", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(42.dp))
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}
