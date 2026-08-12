package com.kktyagi.videodownloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "Ytdlp"
private const val PREFS = "engine"
private const val KEY_LAST_UPDATE = "last_update_check"

/** yt-dlp refuses to run once its build is ~90 days old, so re-check daily. */
private val UPDATE_INTERVAL_MS = TimeUnit.DAYS.toMillis(1)

data class EngineState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val version: String? = null,
    val message: String = "Starting engine…",
)

/**
 * Thin wrapper around youtubedl-android, which bundles yt-dlp, a Python runtime
 * and ffmpeg. Unlike the desktop build there is no "is ffmpeg installed?"
 * question here — it always ships with the app, so merged high-quality formats
 * work out of the box.
 */
object Ytdlp {

    @Volatile
    private var initialised = false
    private val lock = Mutex()

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
        initialised = true
    }

    /**
     * Initialises the engine and keeps yt-dlp current.
     *
     * The version bundled inside youtubedl-android is frozen at the library's
     * release date, and yt-dlp hard-fails once its build is older than ~90 days.
     * So the first launch pulls the current release, and we re-check daily —
     * which also picks up extractor fixes when a site changes its player.
     *
     * Safe to call from anywhere; concurrent callers wait on the same update.
     */
    suspend fun ensureReady(context: Context, forceUpdate: Boolean = false) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                if (!initialised) {
                    _state.value = EngineState(message = "Starting engine…", busy = true)
                    runCatching { init(context) }.onFailure {
                        _state.value = EngineState(message = "Engine failed to start: ${it.message}")
                        return@withContext
                    }
                }

                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
                val due = forceUpdate || System.currentTimeMillis() - last > UPDATE_INTERVAL_MS

                if (due) {
                    _state.value = _state.value.copy(busy = true, message = "Updating yt-dlp…")
                    val result = runCatching {
                        YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                    }
                    result.onSuccess {
                        prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
                    }.onFailure {
                        // Offline or GitHub unreachable: carry on with what we have
                        // rather than blocking downloads entirely.
                        Log.w(TAG, "yt-dlp update failed", it)
                    }
                }

                val version = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()
                _state.value = EngineState(
                    ready = true,
                    busy = false,
                    version = version,
                    message = version?.let { "yt-dlp $it" } ?: "Ready",
                )
            }
        }
    }

    /** Working directory for in-progress files, cleaned up after export. */
    private fun workDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "work").apply { mkdirs() }

    fun fetchTitle(url: String): Pair<String?, String?> = try {
        val info = YoutubeDL.getInstance().getInfo(url)
        info.title to info.thumbnail
    } catch (e: Exception) {
        Log.w(TAG, "getInfo failed for $url", e)
        null to null
    }

    /**
     * Runs a download to completion. [onProgress] receives 0..100.
     * Returns the finished file, or throws with a message fit for the UI.
     */
    fun download(
        context: Context,
        job: Job,
        processId: String,
        onProgress: (Float, String) -> Unit,
    ): File {
        val dir = workDir(context)
        dir.listFiles()?.forEach { it.delete() }

        val request = YoutubeDLRequest(job.url).apply {
            addOption("--no-mtime")
            addOption("--no-playlist")
            addOption("-o", "${dir.absolutePath}/%(title).100B.%(ext)s")
            addOption("-f", job.quality.selector)
            if (job.quality.audioOnly) {
                addOption("-x")
                addOption("--audio-format", "mp3")
            } else {
                addOption("--merge-output-format", "mp4")
            }
        }

        YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
            onProgress(progress.coerceIn(0f, 100f), line ?: "")
        }

        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw IllegalStateException("Download finished but produced no file")
    }

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    /**
     * Copies the finished file into the public Movies/Music collection so it
     * shows up in the gallery and file manager, then removes the working copy.
     * Uses MediaStore, so no storage permission is needed on Android 10+.
     */
    fun exportToGallery(context: Context, file: File, audioOnly: Boolean): String {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (audioOnly) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            if (audioOnly) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val relative = if (audioOnly) "${Environment.DIRECTORY_MUSIC}/VideoDownloader"
        else "${Environment.DIRECTORY_MOVIES}/VideoDownloader"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (audioOnly) "audio/mpeg" else "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create an entry in the gallery")

        resolver.openOutputStream(uri).use { out ->
            requireNotNull(out) { "Could not open the destination file" }
            file.inputStream().use { it.copyTo(out) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        file.delete()
        return "$relative/${file.name}"
    }

    /** Maps yt-dlp's wording onto something a phone user can act on. */
    fun explain(raw: String): String {
        val low = raw.lowercase()
        return when {
            "sign in" in low || "log in" in low || "login" in low || "private" in low ->
                "This post needs a signed-in account, which this version can't do yet."
            "unavailable" in low || "removed" in low || "404" in low ->
                "The post looks deleted, private, or the link is wrong."
            "not available in your country" in low || "geo" in low ->
                "Blocked in your region."
            "unable to download webpage" in low || "timed out" in low || "connection" in low ->
                "Network problem — check your connection and retry."
            "cannot parse data" in low || "unable to extract" in low ->
                "The site served a page yt-dlp couldn't read. Try again in a moment."
            "older than" in low && "days" in low ->
                "The downloader is out of date. Tap the refresh icon at the top to update it."
            else -> raw.replace(Regex("\\u001B\\[[0-9;]*m"), "")
                .replace(Regex("^ERROR:\\s*"), "")
                .take(300)
        }
    }
}
