package com.hazel.android.ui.screens.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.hazel.android.R
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hazel.android.download.RemoteBridgeClient
import com.hazel.android.download.RemoteBridgeTransferService
import com.hazel.android.download.RemoteBridgeTransferStore
import com.hazel.android.download.RemoteBridgeJobStore
import com.hazel.android.download.RemoteBridgeSecrets
import com.hazel.android.ui.components.EqualWidthActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState

@Composable
fun RemoteBridgeScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember(context) { RemoteBridgeSecrets.load(context) }
    var endpoint by remember { mutableStateOf(initial?.endpoint.orEmpty()) }
    var token by remember { mutableStateOf(initial?.token.orEmpty()) }
    val savedJob = remember(context) { RemoteBridgeJobStore.load(context) }
    var magnet by remember { mutableStateOf(savedJob?.magnetUri.orEmpty()) }
    var jobId by remember { mutableStateOf<String?>(savedJob?.id) }
    var remoteStatus by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(context.getString(R.string.remote_bridge_initial_status)) }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val transferState by remember(context) {
        RemoteBridgeTransferStore.observe(context)
    }.collectAsState(initial = null)

    fun client(): RemoteBridgeClient {
        require(endpoint.isNotBlank()) { "Enter the bridge HTTPS URL" }
        require(token.length >= 32) { "Token must be at least 32 characters" }
        return RemoteBridgeClient(endpoint, token)
    }

    LaunchedEffect(transferState) {
        val transfer = transferState ?: return@LaunchedEffect
        if (transfer.isActive && !RemoteBridgeTransferService.isRunning()) {
            RemoteBridgeTransferStore.save(
                context,
                transfer.copy(
                    status = "interrupted",
                    message = context.getString(R.string.remote_bridge_transfer_interrupted),
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    LaunchedEffect(jobId, endpoint, token) {
        val id = jobId ?: return@LaunchedEffect
        if (endpoint.isBlank() || token.length < 32) return@LaunchedEffect
        var retryDelay = 5000L
        while (true) {
            try {
                val job = client().getJob(id)
                remoteStatus = job.status.lowercase()
                status = "${job.status}: ${job.message}".trim()
                files = job.files
                retryDelay = 5000L
                if (job.status.lowercase() in setOf("complete", "completed", "failed", "cancelled", "canceled", "error")) break
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val message = e.message ?: "Could not refresh remote job"
                status = message
                if (listOf("(401)", "(403)", "(404)").any(message::contains)) break
                retryDelay = (retryDelay * 2).coerceAtMost(30000L)
            }
            delay(retryDelay)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
            }
            Text(stringResource(R.string.remote_bridge_title), style = MaterialTheme.typography.headlineMedium)
        }
        Text(stringResource(R.string.remote_bridge_intro))
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(R.string.remote_bridge_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.remote_bridge_token)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        EqualWidthActions(modifier = Modifier.fillMaxWidth(), spacing = 8.dp) {
            Button(onClick = {
                scope.launch {
                    try {
                        client()
                        RemoteBridgeSecrets.save(context, endpoint, token)
                        status = context.getString(R.string.remote_bridge_settings_saved)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { status = e.message ?: "Could not save settings" }
                }
            }) { Text(stringResource(R.string.remote_bridge_save_settings)) }
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        status = context.getString(R.string.remote_bridge_connection_success, client().checkConnection())
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        status = e.message ?: "Bridge connection failed"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.remote_bridge_test_connection)) }
        }
        OutlinedButton(onClick = {
            RemoteBridgeSecrets.clear(context)
            token = ""
            endpoint = ""
            status = context.getString(R.string.remote_bridge_initial_status)
        }) { Text(stringResource(R.string.remote_bridge_clear_settings)) }
        OutlinedTextField(magnet, { magnet = it }, label = { Text(stringResource(R.string.remote_bridge_magnet_hint)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                try {
                    val bridge = client()
                    RemoteBridgeSecrets.save(context, endpoint, token)
                    val job = bridge.createMagnetJob(magnet.trim())
                    jobId = job.id
                    remoteStatus = job.status.lowercase()
                    RemoteBridgeJobStore.save(context, job.id, magnet.trim())
                    status = context.getString(R.string.remote_bridge_job_submitted, job.id, job.status)
                    files = job.files
                } catch (e: CancellationException) { throw e } catch (e: Exception) { status = e.message ?: "Could not submit job" }
                finally { busy = false }
            }
        }) { Text(if (busy) stringResource(R.string.remote_bridge_submitting) else stringResource(R.string.remote_bridge_start_download)) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.remote_bridge_job_status), style = MaterialTheme.typography.titleMedium)
                Text(status)
                jobId?.let { id ->
                    Text(stringResource(R.string.remote_bridge_job_id, id), style = MaterialTheme.typography.bodySmall)
                    EqualWidthActions(modifier = Modifier.fillMaxWidth(), spacing = 8.dp) {
                        OutlinedButton(enabled = !busy && remoteStatus?.let { it in setOf("queued", "downloading") } == true, onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    client().cancelJob(id)
                                    remoteStatus = "cancelled"
                                    status = context.getString(R.string.remote_bridge_cancel_requested)
                                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                                    status = e.message ?: "Cancel failed"
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text(stringResource(R.string.remote_bridge_cancel_job)) }
                        OutlinedButton(enabled = !busy, onClick = {
                            RemoteBridgeJobStore.clear(context)
                            jobId = null
                            remoteStatus = null
                            files = emptyList()
                            status = context.getString(R.string.remote_bridge_history_cleared)
                        }) { Text(stringResource(R.string.remote_bridge_forget_job)) }
                    }
                }
                files.forEach { relative ->
                    val displayName = relative.substringAfterLast('/').ifBlank { "remote-download.bin" }
                    OutlinedButton(
                        enabled = !busy && transferState?.isActive != true,
                        onClick = {
                            scope.launch {
                                try {
                                    RemoteBridgeSecrets.save(context, endpoint, token)
                                    RemoteBridgeTransferService.start(
                                        context = context,
                                        jobId = jobId ?: error("No job"),
                                        relativePath = relative,
                                        displayName = displayName
                                    )
                                    status = context.getString(R.string.remote_bridge_starting_transfer)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    status = e.message ?: context.getString(R.string.remote_bridge_transfer_failed)
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.remote_bridge_download_file, displayName)) }
                }
                transferState?.takeIf { it.jobId == jobId }?.let { transfer ->
                    Text(transfer.message, style = MaterialTheme.typography.bodySmall)
                    if (transfer.isActive && transfer.totalBytes > 0L) {
                        Text(
                            stringResource(
                                R.string.remote_bridge_progress_known,
                                transfer.downloadedBytes / (1024 * 1024),
                                transfer.totalBytes / (1024 * 1024)
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    transfer.fileUri?.let {
                        Text(stringResource(R.string.remote_bridge_saved_file_uri, it), style = MaterialTheme.typography.bodySmall)
                    }
                    if (transfer.isActive) {
                        OutlinedButton(
                            enabled = transfer.status != "cancelling",
                            onClick = {
                                try {
                                    RemoteBridgeTransferService.cancel(context)
                                    status = context.getString(R.string.remote_bridge_file_transfer_cancelling)
                                } catch (e: Exception) {
                                    status = e.message ?: context.getString(R.string.remote_bridge_transfer_failed)
                                }
                            }
                        ) { Text(stringResource(R.string.remote_bridge_cancel_file_transfer)) }
                    }
                    if (transfer.status in setOf("failed", "cancelled", "interrupted")) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    try {
                                        RemoteBridgeSecrets.save(context, endpoint, token)
                                        RemoteBridgeTransferService.start(
                                            context = context,
                                            jobId = transfer.jobId,
                                            relativePath = transfer.relativePath,
                                            displayName = transfer.displayName
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        status = e.message ?: context.getString(R.string.remote_bridge_transfer_failed)
                                    }
                                }
                            }
                        ) { Text(stringResource(R.string.remote_bridge_retry_transfer)) }
                    }
                }
            }
        }
        Text(stringResource(R.string.remote_bridge_footer))
    }
}
