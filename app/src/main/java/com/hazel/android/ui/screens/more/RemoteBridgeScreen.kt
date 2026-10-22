package com.hazel.android.ui.screens.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hazel.android.download.RemoteBridgeClient
import com.hazel.android.download.RemoteBridgeDownloads
import com.hazel.android.download.RemoteBridgeSecrets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun RemoteBridgeScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember(context) { RemoteBridgeSecrets.load(context) }
    var endpoint by remember { mutableStateOf(initial?.endpoint.orEmpty()) }
    var token by remember { mutableStateOf(initial?.token.orEmpty()) }
    var magnet by remember { mutableStateOf("") }
    var jobId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Configure your bridge, then submit a magnet link.") }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var savedPath by remember { mutableStateOf<String?>(null) }

    fun client(): RemoteBridgeClient {
        require(endpoint.isNotBlank()) { "Enter the bridge HTTPS URL" }
        require(token.length >= 32) { "Token must be at least 32 characters" }
        return RemoteBridgeClient(endpoint, token)
    }

    LaunchedEffect(jobId, endpoint, token) {
        val id = jobId ?: return@LaunchedEffect
        if (endpoint.isBlank() || token.length < 32) return@LaunchedEffect
        while (true) {
            try {
                val job = client().getJob(id)
                status = "${job.status}: ${job.message}".trim()
                files = job.files
                if (job.status.lowercase() in setOf("complete", "completed", "failed", "cancelled", "canceled", "error")) break
            } catch (e: Exception) {
                status = e.message ?: "Could not refresh remote job"
            }
            delay(5000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Remote Bridge", style = MaterialTheme.typography.headlineMedium)
        Text("Use a PC-Free bridge on a network where torrent traffic is permitted. Remote endpoints must use HTTPS.")
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Bridge URL (HTTPS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Bearer token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    try {
                        client()
                        RemoteBridgeSecrets.save(context, endpoint, token)
                        status = "Bridge settings saved securely on this device."
                    } catch (e: Exception) { status = e.message ?: "Could not save settings" }
                }
            }) { Text("Save settings") }
            OutlinedButton(onClick = {
                RemoteBridgeSecrets.clear(context)
                token = ""
                endpoint = ""
                status = "Saved bridge settings cleared."
            }) { Text("Clear") }
        }
        OutlinedTextField(magnet, { magnet = it }, label = { Text("BitTorrent magnet link") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                try {
                    val job = client().createMagnetJob(magnet.trim())
                    jobId = job.id
                    status = "Submitted job ${job.id}: ${job.status}"
                    files = job.files
                    savedPath = null
                } catch (e: Exception) { status = e.message ?: "Could not submit job" }
                finally { busy = false }
            }
        }) { Text(if (busy) "Submitting…" else "Start remote download") }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Job status", style = MaterialTheme.typography.titleMedium)
                Text(status)
                jobId?.let { id ->
                    Text("Job ID: $id", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try { client().cancelJob(id); status = "Cancellation requested." }
                            catch (e: Exception) { status = e.message ?: "Cancel failed" }
                            finally { busy = false }
                        }
                    }) { Text("Cancel job") }
                }
                files.forEach { relative ->
                    OutlinedButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val safeName = relative.substringAfterLast('/').ifBlank { "remote-download.bin" }
                                val temporary = File(context.cacheDir, "remote-bridge-${System.currentTimeMillis()}.part")
                                client().downloadFile(jobId ?: error("No job"), relative, temporary)
                                val published = RemoteBridgeDownloads.publish(context, temporary, safeName)
                                temporary.delete()
                                savedPath = published.toString()
                                status = "File saved to public Downloads/Hazel."
                            } catch (e: Exception) { status = e.message ?: "File download failed" }
                            finally { busy = false }
                        }
                    }) { Text("Download: ${relative.substringAfterLast('/')}") }
                }
                savedPath?.let { Text("Saved file URI: $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text("Files are published to the public Downloads/Hazel folder. Keep the bridge URL private and use HTTPS for remote connections.")
    }
}
