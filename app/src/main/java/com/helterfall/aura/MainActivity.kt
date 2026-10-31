package com.helterfall.aura

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.helterfall.aura.core.archive.ArchiveCandidateDetector
import com.helterfall.aura.core.archive.ArchiveExtractor
import com.helterfall.aura.core.archive.ArchiveIndex
import com.helterfall.aura.core.archive.ContentResolverRandomAccessDataSource
import com.helterfall.aura.core.archive.ExtractionPhase
import com.helterfall.aura.core.archive.ExtractionProgress
import com.helterfall.aura.core.archive.NitroPakReader
import com.helterfall.aura.core.archive.GameFileEntry
import com.helterfall.aura.ui.theme.AuraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AuraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AuraApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuraApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var treeUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<GameFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var onlyArchives by rememberSaveable { mutableStateOf(false) }
    var selectedEntry by remember { mutableStateOf<GameFileEntry?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var openArchiveUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var openArchiveName by rememberSaveable { mutableStateOf<String?>(null) }
    var archiveIndex by remember { mutableStateOf<ArchiveIndex?>(null) }
    var archiveLoading by remember { mutableStateOf(false) }
    var archiveError by remember { mutableStateOf<String?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var extractionMessage by remember { mutableStateOf<String?>(null) }
    var extractionProgress by remember { mutableStateOf<ExtractionProgress?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                treeUriText = uri.toString()
                errorMessage = null
            } catch (exception: SecurityException) {
                errorMessage = "Не удалось сохранить доступ к папке: ${exception.localizedMessage}"
            }
        }
    }

    val outputFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val sourceText = openArchiveUriText
            val currentIndex = archiveIndex
            if (sourceText != null && currentIndex != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    scope.launch {
                        extracting = true
                        extractionMessage = null
                        extractionProgress = null
                        try {
                            val report = withContext(Dispatchers.IO) {
                                ArchiveExtractor(context).extractAll(
                                    archiveUri = Uri.parse(sourceText),
                                    outputTreeUri = uri,
                                    archiveName = openArchiveName ?: "archive.pak",
                                    archive = currentIndex,
                                    onProgress = { progress ->
                                        mainHandler.post {
                                            extractionProgress = progress
                                        }
                                    }
                                )
                            }
                            val errors = if (report.failures.isEmpty()) "" else
                                " Ошибок: ${report.failures.size}. " +
                                    report.failures.take(3).joinToString("; ")
                            extractionMessage =
                                "Готово: ${report.extractedFiles} файлов, ${formatBytes(report.extractedBytes)}.$errors"
                        } catch (exception: Exception) {
                            extractionMessage =
                                "Ошибка извлечения: ${exception.localizedMessage ?: "неизвестная ошибка"}"
                        } finally {
                            extracting = false
                        }
                    }
                } catch (exception: SecurityException) {
                    extractionMessage = "Нет доступа на запись в выбранную папку."
                }
            }
        }
    }

    LaunchedEffect(treeUriText) {
        val rawUri = treeUriText ?: return@LaunchedEffect
        loading = true
        errorMessage = null

        try {
            val loadedEntries = withContext(Dispatchers.IO) {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(rawUri))
                    ?: error("Android не смог открыть выбранную папку.")

                tree.listFiles()
                    .mapNotNull { document ->
                        val name = document.name ?: return@mapNotNull null
                        GameFileEntry(
                            name = name,
                            isDirectory = document.isDirectory,
                            sizeBytes = if (document.isFile) document.length().takeIf { it >= 0L } else null,
                            uri = document.uri.toString(),
                            archiveCandidate = if (document.isFile) {
                                ArchiveCandidateDetector.detect(name)
                            } else {
                                null
                            }
                        )
                    }
                    .sortedWith(
                        compareByDescending<GameFileEntry> { it.isDirectory }
                            .thenBy { it.name.lowercase() }
                    )
            }
            entries = loadedEntries
        } catch (exception: Exception) {
            errorMessage = exception.localizedMessage ?: "Не удалось прочитать содержимое папки."
            entries = emptyList()
        } finally {
            loading = false
        }
    }


    LaunchedEffect(openArchiveUriText) {
        val rawUri = openArchiveUriText ?: return@LaunchedEffect
        archiveLoading = true
        archiveIndex = null
        archiveError = null
        extractionMessage = null
        extractionProgress = null

        try {
            val parsed = withContext(Dispatchers.IO) {
                ContentResolverRandomAccessDataSource(
                    context.contentResolver,
                    Uri.parse(rawUri)
                ).use { source -> NitroPakReader().open(source) }
            }
            archiveIndex = parsed
        } catch (exception: Exception) {
            archiveError = exception.localizedMessage ?: "Не удалось прочитать PAK."
        } finally {
            archiveLoading = false
        }
    }

    val visibleEntries = if (onlyArchives) {
        entries.filter { !it.isDirectory && it.archiveCandidate != null }
    } else {
        entries
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Aura", fontWeight = FontWeight.Bold)
                        Text(
                            "VISUAL NOVEL RESOURCE EXPLORER",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Игровая папка", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (treeUriText == null) {
                            "Выбери папку с файлами визуальной новеллы."
                        } else {
                            "Папка подключена. Найдено элементов: ${entries.size}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { folderPicker.launch(null) }) {
                        Text(if (treeUriText == null) "Выбрать папку" else "Сменить папку")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Содержимое", style = MaterialTheme.typography.titleMedium)
                FilterChip(
                    selected = onlyArchives,
                    onClick = { onlyArchives = !onlyArchives },
                    label = { Text("Кандидаты на архивы") }
                )
            }

            when {
                loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.size(12.dp))
                        Text("Читаем список файлов…")
                    }
                }

                visibleEntries.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            if (treeUriText == null) "Папка ещё не выбрана" else "Подходящих элементов нет",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (onlyArchives) "Попробуй отключить фильтр архивов." else "Выбери другую папку с файлами игры.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleEntries, key = { it.uri }) { entry ->
                            FileEntryRow(
                                entry = entry,
                                onClick = { selectedEntry = entry }
                            )
                        }
                    }
                }
            }

            Text(
                "Сейчас доступны просмотр и извлечение Nitro+ PAK v2/v3. Остальные форматы будут подключаться отдельными обработчиками.",
                modifier = Modifier.padding(bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    selectedEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { selectedEntry = null },
            title = { Text(entry.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (entry.isDirectory) "Тип: папка" else "Тип: файл")
                    entry.sizeBytes?.let { Text("Размер: ${formatBytes(it)}") }
                    if (entry.archiveCandidate != null) {
                        Text("Кандидат по расширению: ${entry.archiveCandidate.label}")
                        Text(
                            if (entry.archiveCandidate.extension == "pak") {
                                "Aura проверит сигнатуру при открытии. Сейчас поддерживаются Nitro+ PAK v2 и v3."
                            } else {
                                "Формат пока отображается как кандидат. Обработчик этого архива ещё не подключён."
                            }
                        )
                    } else if (!entry.isDirectory) {
                        Text("Расширение пока не относится к известным кандидатам на архив.")
                    }
                }
            },
            confirmButton = {
                Row {
                    if (!entry.isDirectory && entry.archiveCandidate?.extension == "pak") {
                        TextButton(
                            onClick = {
                                openArchiveName = entry.name
                                openArchiveUriText = entry.uri
                                selectedEntry = null
                            }
                        ) {
                            Text("Открыть PAK")
                        }
                    }
                    TextButton(onClick = { selectedEntry = null }) {
                        Text("Закрыть")
                    }
                }
            }
        )
    }


    if (archiveLoading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Открытие PAK") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Проверяем заголовок и читаем индекс…")
                }
            },
            confirmButton = {}
        )
    }

    archiveError?.let { message ->
        AlertDialog(
            onDismissRequest = {
                archiveError = null
                openArchiveUriText = null
            },
            title = { Text("Не удалось открыть архив") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    archiveError = null
                    openArchiveUriText = null
                }) {
                    Text("Понятно")
                }
            }
        )
    }

    archiveIndex?.let { archive ->
        AlertDialog(
            onDismissRequest = {
                if (!extracting) {
                    archiveIndex = null
                    openArchiveUriText = null
                    extractionMessage = null
                }
            },
            title = { Text(openArchiveName ?: "PAK") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${archive.format} v${archive.version} · ${archive.entries.size} файлов",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Содержимое индекса. Ресурсы извлекаются в исходном виде, без преобразования изображений и аудио.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (extracting) 180.dp else 340.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(archive.entries.take(500), key = { "${it.offset}:${it.name}" }) { item ->
                            Column {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${formatBytes(item.unpackedSize)} · ${if (item.isCompressed) "zlib" else "без сжатия"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (archive.entries.size > 500) {
                        Text(
                            "Показаны первые 500 записей; при извлечении будут обработаны все файлы.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (extractionProgress != null) {
                        extractionProgress?.let { progress ->
                            ExtractionProgressCard(progress)
                        }
                    } else if (extracting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("Подготавливаем извлечение…")
                        }
                    }

                    extractionMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !extracting,
                    onClick = { outputFolderPicker.launch(null) }
                ) {
                    Text("Извлечь всё")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !extracting,
                    onClick = {
                        archiveIndex = null
                        openArchiveUriText = null
                        extractionMessage = null
                    }
                ) {
                    Text("Закрыть")
                }
            }
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Ошибка доступа") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text("Понятно")
                }
            }
        )
    }
}

@Composable
private fun ExtractionProgressCard(progress: ExtractionProgress) {
    val percent = (progress.fraction * 100f).toInt().coerceIn(0, 100)
    val phaseLabel = when (progress.phase) {
        ExtractionPhase.PREPARING -> "Подготовка файлов"
        ExtractionPhase.EXTRACTING -> "Распаковка"
        ExtractionPhase.FINISHED -> "Распаковка завершена"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(phaseLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("$percent%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Файлы: ${progress.completedFiles} / ${progress.totalFiles}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Потоки: ${progress.activeFiles.size} / ${progress.workerCount}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                "${formatBytes(progress.processedBytes)} / ${formatBytes(progress.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            if (progress.activeFiles.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Сейчас распаковывается", style = MaterialTheme.typography.labelMedium)
                    progress.activeFiles.take(3).forEach { fileName ->
                        Text(
                            "• $fileName",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                    if (progress.activeFiles.size > 3) {
                        Text(
                            "и ещё ${progress.activeFiles.size - 3}…",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            } else if (progress.phase == ExtractionPhase.PREPARING) {
                Text("Подготавливаем структуру папок…", style = MaterialTheme.typography.bodySmall)
            } else if (progress.phase == ExtractionPhase.EXTRACTING) {
                Text("Подготавливаем следующий файл…", style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (progress.bytesPerSecond > 0L) "${formatBytes(progress.bytesPerSecond)}/с" else "Скорость —",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    when {
                        progress.phase == ExtractionPhase.FINISHED -> "Осталось: 0 с"
                        progress.estimatedRemainingMillis != null ->
                            "Осталось ~${formatDuration(progress.estimatedRemainingMillis)}"
                        else -> "Осталось: расчёт…"
                    },
                    style = MaterialTheme.typography.labelMedium
                )
            }

            if (progress.failedFiles > 0) {
                Text(
                    "Не удалось обработать: ${progress.failedFiles}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    return if (seconds < 60L) {
        "$seconds с"
    } else {
        "${seconds / 60L} мин ${seconds % 60L} с"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEntryRow(
    entry: GameFileEntry,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (entry.isDirectory) "📁" else if (entry.archiveCandidate != null) "📦" else "📄",
                style = MaterialTheme.typography.headlineSmall
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        entry.isDirectory -> "Папка"
                        entry.archiveCandidate != null -> "Кандидат: ${entry.archiveCandidate.label}"
                        entry.sizeBytes != null -> formatBytes(entry.sizeBytes)
                        else -> "Файл"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "${bytes} Б"
    val kilobytes = bytes / 1024.0
    if (kilobytes < 1024.0) return "%.1f КБ".format(kilobytes)
    val megabytes = kilobytes / 1024.0
    if (megabytes < 1024.0) return "%.1f МБ".format(megabytes)
    return "%.2f ГБ".format(megabytes / 1024.0)
}
