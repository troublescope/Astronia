package com.antoniegil.astronia.ui.page.settings.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.antoniegil.astronia.R
import com.antoniegil.astronia.ui.component.BackButton
import com.antoniegil.astronia.ui.component.SwipeableCard
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.content.edit
import android.widget.Toast

data class LogcatRecord(
    val fileName: String,
    val timestamp: Long,
    val duration: Long,
    val filePath: String
)

class LogcatViewModel : ViewModel() {
    var isRecording by mutableStateOf(false)
    var recordStartTime by mutableLongStateOf(0L)
    var logcatRecords by mutableStateOf(listOf<LogcatRecord>())
    
    private fun getPrefs(context: Context) = context.getSharedPreferences("logcat_data", Context.MODE_PRIVATE)
    
    fun loadData(context: Context) {
        val prefs = getPrefs(context)
        isRecording = prefs.getBoolean("is_recording", false)
        recordStartTime = prefs.getLong("start_time", 0L)
        
        val savedRecords = mutableListOf<LogcatRecord>()
        val recordCount = prefs.getInt("record_count", 0)
        for (i in 0 until recordCount) {
            val fileName = prefs.getString("record_${i}_fileName", "") ?: ""
            val timestamp = prefs.getLong("record_${i}_timestamp", 0L)
            val duration = prefs.getLong("record_${i}_duration", 0L)
            val filePath = prefs.getString("record_${i}_filePath", "") ?: ""
            if (fileName.isNotEmpty() && File(filePath).exists()) {
                savedRecords.add(LogcatRecord(fileName, timestamp, duration, filePath))
            }
        }
        logcatRecords = savedRecords
    }
    
    fun saveRecords(context: Context) {
        getPrefs(context).edit {
            putInt("record_count", logcatRecords.size)
            logcatRecords.forEachIndexed { index, record ->
                putString("record_${index}_fileName", record.fileName)
                putLong("record_${index}_timestamp", record.timestamp)
                putLong("record_${index}_duration", record.duration)
                putString("record_${index}_filePath", record.filePath)
            }
        }
    }
    
    fun startRecording(context: Context) {
        recordStartTime = System.currentTimeMillis()
        isRecording = true
        getPrefs(context).edit {
            putBoolean("is_recording", true)
            putLong("start_time", recordStartTime)
        }
    }
    
    fun stopRecording(context: Context, locale: Locale, onComplete: () -> Unit) {
        if (!isRecording) return
        
        viewModelScope.launch {
            val duration = System.currentTimeMillis() - recordStartTime
            val fileName = "logcat_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", locale).format(Date(recordStartTime))}.txt"
            
            val logcatContent = withContext(Dispatchers.IO) {
                Runtime.getRuntime().exec("logcat -d -v time").inputStream.bufferedReader().readText()
                    .let { if (it.length > 20 * 1024 * 1024) it.takeLast(20 * 1024 * 1024) else it }
            }
            
            val file = File(context.getExternalFilesDir(null), fileName)
            withContext(Dispatchers.IO) { file.writeText(logcatContent) }
            
            logcatRecords = listOf(LogcatRecord(fileName, recordStartTime, duration, file.absolutePath)) + logcatRecords
            isRecording = false
            
            getPrefs(context).edit {
                putBoolean("is_recording", false)
                putLong("start_time", 0L)
            }
            saveRecords(context)
            onComplete()
        }
    }
}

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatPage(onNavigateBack: () -> Unit) {
    val viewModel: LogcatViewModel = viewModel()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val successMsg = stringResource(R.string.export_success)
    val failedMsg = stringResource(R.string.export_failed)
    
    var exportContent by remember { mutableStateOf("") }
    
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(exportContent.toByteArray())
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, failedMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadData(context)
        if (viewModel.isRecording && System.currentTimeMillis() - viewModel.recordStartTime > 30 * 60 * 1000) {
            viewModel.stopRecording(context, configuration.locales[0]) {}
        }
    }

    Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                        title = { Text(text = "Logcat") },
                        navigationIcon = { BackButton(onClick = onNavigateBack) }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Row(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .clickable { if (!viewModel.isRecording) viewModel.startRecording(context) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                            text = if (viewModel.isRecording) stringResource(R.string.recording) else stringResource(R.string.logcat_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { viewModel.stopRecording(context, configuration.locales[0]) {} },
                    enabled = viewModel.isRecording
                ) {
                    if (viewModel.isRecording) {
                        Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = stringResource(R.string.stop_recording),
                                tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (viewModel.logcatRecords.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.history),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }

                    items(
                        items = viewModel.logcatRecords,
                        key = { it.filePath }
                    ) { record ->
                        val dateFormat = SimpleDateFormat("yy-MM-dd HH:mm:ss", configuration.locales[0])
                        
                        SwipeableCard(
                            name = dateFormat.format(Date(record.timestamp)),
                            url = "${record.duration / 1000}s",
                            onDelete = {
                                val deletedIndex = viewModel.logcatRecords.indexOf(record)
                                viewModel.logcatRecords -= record
                                File(record.filePath).delete()
                                viewModel.saveRecords(context)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = context.getString(R.string.item_deleted),
                                        actionLabel = context.getString(R.string.undo),
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.logcatRecords = viewModel.logcatRecords.toMutableList().apply {
                                            add(deletedIndex.coerceAtMost(size), record)
                                        }
                                        viewModel.saveRecords(context)
                                    }
                                }
                            },
                            onClick = {},
                            trailingIcon = {
                                IconButton(onClick = {
                                    val file = File(record.filePath)
                                    if (file.exists()) {
                                        scope.launch(Dispatchers.IO) {
                                            val content = file.readText()
                                            withContext(Dispatchers.Main) {
                                                exportContent = content
                                                exportFileName = record.fileName
                                                exportLauncher.launch(record.fileName)
                                            }
                                        }
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.DriveFileMove,
                                        contentDescription = stringResource(R.string.save),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
