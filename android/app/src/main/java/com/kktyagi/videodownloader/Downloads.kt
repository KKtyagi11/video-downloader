package com.kktyagi.videodownloader

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Status { QUEUED, RESOLVING, DOWNLOADING, PROCESSING, DONE, FAILED, CANCELLED }

val Status.isActive: Boolean
    get() = this == Status.QUEUED || this == Status.RESOLVING ||
        this == Status.DOWNLOADING || this == Status.PROCESSING

/** Quality choices, mirroring the desktop app's format selectors. */
enum class Quality(val label: String, val selector: String, val audioOnly: Boolean = false) {
    BEST("Best quality", "bv*+ba/b"),
    P1080("1080p or lower", "bv*[height<=1080]+ba/b[height<=1080]"),
    P720("720p or lower", "bv*[height<=720]+ba/b[height<=720]"),
    P480("480p or lower", "bv*[height<=480]+ba/b[height<=480]"),
    MP3("MP3 (audio only)", "ba/b", audioOnly = true);

    companion object {
        fun fromName(name: String?): Quality =
            entries.firstOrNull { it.name == name } ?: BEST
    }
}

data class Job(
    val id: Long,
    val url: String,
    val quality: Quality,
    val title: String = url,
    val thumbnail: String? = null,
    val status: Status = Status.QUEUED,
    val percent: Float = 0f,
    val detail: String = "",
    val outputPath: String? = null,
)

/**
 * In-memory queue shared between the UI and the download service. Deliberately
 * not persisted: a queue that survives a kill would need to reconcile partial
 * files on restart, which is more machinery than a first release needs.
 */
object Downloads {
    private val ids = AtomicLong(0)
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    fun add(url: String, quality: Quality): Job {
        val job = Job(id = ids.incrementAndGet(), url = url.trim(), quality = quality)
        _jobs.update { listOf(job) + it }
        return job
    }

    fun update(id: Long, transform: (Job) -> Job) {
        _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun get(id: Long): Job? = _jobs.value.firstOrNull { it.id == id }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.status.isActive } }
    }

    fun activeCount(): Int = _jobs.value.count { it.status.isActive }
}
