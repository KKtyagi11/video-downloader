package com.kktyagi.videodownloader

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val shared = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeShare(intent)

        setContent {
            VideoDownloaderTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen(sharedUrl = shared)
                }
            }
        }
    }

    // launchMode=singleTask, so a second share arrives here rather than in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeShare(intent)
    }

    private fun consumeShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        // Share sheets often send "Look at this <url>", so pull the URL out.
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        shared.value = url
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(sharedUrl: MutableState<String?>) {
    val context = LocalContext.current
    val jobs by Downloads.jobs.collectAsStateWithLifecycle()

    var url by rememberSaveable { mutableStateOf("") }
    var quality by rememberSaveable { mutableStateOf(Quality.BEST) }
    var qualityOpen by remember { mutableStateOf(false) }

    val engine by Ytdlp.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Warm the engine while the user is still pasting, so the first
        // download doesn't stall behind a yt-dlp update.
        scope.launch { Ytdlp.ensureReady(context) }
    }

    // A link arriving from the share sheet starts downloading immediately —
    // making someone re-confirm what they just shared is pure friction.
    LaunchedEffect(sharedUrl.value) {
        sharedUrl.value?.let { incoming ->
            sharedUrl.value = null
            val job = Downloads.add(incoming, quality)
            DownloadService.enqueue(context, job.id)
        }
    }

    fun startDownload() {
        val links = url.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (links.isEmpty()) return
        links.forEach { link ->
            val job = Downloads.add(link, quality)
            DownloadService.enqueue(context, job.id)
        }
        url = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Video Downloader", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text(
                            engine.message,
                            color = if (engine.ready) TextMuted else Accent,
                            fontSize = 11.sp,
                        )
                    }
                },
                actions = {
                    if (engine.busy) {
                        CircularProgressIndicator(
                            Modifier.padding(end = 16.dp).size(20.dp),
                            color = Accent,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = {
                            scope.launch { Ytdlp.ensureReady(context, forceUpdate = true) }
                        }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Update yt-dlp",
                                tint = TextMuted,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Surface2,
                    titleContentColor = TextPrimary,
                ),
            )
        },
        containerColor = BackgroundDark,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Surface2)
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Paste a link") },
                    placeholder = { Text("YouTube, X, Instagram, TikTok…") },
                    minLines = 1,
                    maxLines = 4,
                )

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExposedDropdownMenuBox(
                        expanded = qualityOpen,
                        onExpandedChange = { qualityOpen = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = quality.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Quality") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(qualityOpen) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(qualityOpen, { qualityOpen = false }) {
                            Quality.entries.forEach { q ->
                                DropdownMenuItem(
                                    text = { Text(q.label) },
                                    onClick = { quality = q; qualityOpen = false },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Button(
                        onClick = { startDownload() },
                        enabled = url.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) { Text("Download") }
                }
            }

            if (jobs.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(jobs, key = { it.id }) { job -> JobCard(job) }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing queued", color = TextMuted, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Paste a link above — or share one straight from the YouTube, " +
                "Instagram or X app and it starts here automatically.",
            color = TextMuted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun JobCard(job: Job) {
    val context = LocalContext.current

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.title,
                    Modifier.weight(1f),
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    job.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    color = when (job.status) {
                        Status.DONE -> Ok
                        Status.FAILED -> Err
                        Status.CANCELLED -> TextMuted
                        else -> TextMuted
                    },
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(10.dp))

            when (job.status) {
                Status.QUEUED, Status.RESOLVING, Status.PROCESSING ->
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Accent,
                        trackColor = Surface3,
                    )
                else ->
                    LinearProgressIndicator(
                        progress = { (job.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (job.status == Status.FAILED) Err else if (job.status == Status.DONE) Ok else Accent,
                        trackColor = Surface3,
                    )
            }

            if (job.detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        job.detail,
                        color = if (job.status == Status.FAILED) Err else TextMuted,
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (job.status.isActive) {
                    TextButton(onClick = { DownloadService.cancel(context, job.id) }) {
                        Text("Cancel", color = TextMuted)
                    }
                }
                if (job.status == Status.FAILED || job.status == Status.CANCELLED) {
                    TextButton(onClick = {
                        val retry = Downloads.add(job.url, job.quality)
                        DownloadService.enqueue(context, retry.id)
                    }) { Text("Retry", color = Accent) }
                }
            }
        }
    }
}
