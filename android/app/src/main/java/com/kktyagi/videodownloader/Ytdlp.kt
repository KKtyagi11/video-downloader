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

private const val TAG = "Ytdlp"

/**
 * Thin wrapper around youtubedl-android, which bundles yt-dlp, a Python runtime
 * and ffmpeg. Unlike the desktop build there is no "is ffmpeg installed?"
 * question here — it always ships with the app, so merged high-quality formats
 * work out of the box.
 */
object Ytdlp {

    @Volatile
    private var initialised = false

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
        initialised = true
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
            else -> raw.replace(Regex("\\u001B\\[[0-9;]*m"), "")
                .replace(Regex("^ERROR:\\s*"), "")
                .take(300)
        }
    }
}
