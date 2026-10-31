package com.helterfall.aura

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.helterfall.aura.core.archive.ArchiveCandidateDetector
import com.helterfall.aura.core.archive.GameFileEntry
import com.helterfall.aura.ui.theme.AuraTheme
import kotlinx.coroutines.Dispatchers
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
    var treeUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<GameFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var onlyArchives by rememberSaveable { mutableStateOf(false) }
    var selectedEntry by remember { mutableStateOf<GameFileEntry?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                "Пока доступен просмотр файлов и предварительная подсказка по расширению. Чтение игровых архивов будет добавлено отдельно.",
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
                            "Это предварительная подсказка, а не подтверждение формата. Для точного определения Aura должна проверить сигнатуру и структуру файла."
                        )
                    } else if (!entry.isDirectory) {
                        Text("Расширение пока не относится к известным кандидатам на архив.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedEntry = null }) {
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
