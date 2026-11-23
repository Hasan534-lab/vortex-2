package com.vortex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vortex.audio.AudioEngine
import com.vortex.net.Link

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkDialog(controller: Boolean, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.88f).heightIn(max = 330.dp).background(Color(0xFF26262C), RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (controller) "Connect to VORTEX" else "Link with VORTEX E", color = Color.White, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("Close", false) { onClose() }
            }
            if (controller) ControllerLink() else OperatorLink()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OperatorLink() {
    val ip by Link.localIp.collectAsState()
    val code by Link.pairCode.collectAsState()
    val clients by Link.clients.collectAsState()
    val status by Link.status.collectAsState()
    val phone by AudioEngine.usePhoneMic.collectAsState()
    val fft by AudioEngine.fftSize.collectAsState()
    val fps by AudioEngine.fps.collectAsState()
    val level by AudioEngine.levelDb.collectAsState()
    val clip by AudioEngine.clipping.collectAsState()
    val audioStatus by AudioEngine.status.collectAsState()

    Text("Both phones must be on the same Wi-Fi. On VORTEX E the address is found automatically; if not, type it.", color = C.Gray, fontSize = 11.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("Address", color = C.Gray, fontSize = 11.sp)
            Text(if (ip != null) "$ip:${Link.PORT}" else "No Wi-Fi address", color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
        }
        Column {
            Text("Pairing code", color = C.Gray, fontSize = 11.sp)
            Text(code, color = C.Amber, fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Chip("New code", false) { Link.newCode() }
    }
    Text(status + if (clients > 0) "" else "", color = if (clients > 0) C.Green else C.Amber, fontSize = 13.sp)

    Text("Audio input", color = C.Gray, fontSize = 11.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Wired jack / USB", !phone) { AudioEngine.usePhoneMic.value = false }
        Chip("Phone mic", phone) { AudioEngine.usePhoneMic.value = true }
    }
    Text(audioStatus, color = C.Txt, fontSize = 12.sp)
    Text(
        "Level ${level.toInt()} dBFS" + if (clip) "   CLIP: lower the signal" else "",
        color = if (clip) C.Red else C.Gray, fontSize = 12.sp,
    )
    Text("FFT size", color = C.Gray, fontSize = 11.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(2048, 4096, 8192, 16384).forEach { n -> Chip("$n", fft == n) { AudioEngine.fftSize.value = n } }
    }
    Text("Updates per second", color = C.Gray, fontSize = 11.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(10, 15, 30).forEach { n -> Chip("$n", fps == n) { AudioEngine.fps.value = n } }
    }
    Text(
        "The mixer's REC SEND is line level. Use a cable or attenuator made for line to phone-mic input, otherwise the signal clips.",
        color = C.Gray, fontSize = 11.sp,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControllerLink() {
    val found by Link.found.collectAsState()
    val status by Link.status.collectAsState()
    val connected by Link.connected.collectAsState()
    val savedHost by Link.savedHost.collectAsState()
    val savedCode by Link.savedCode.collectAsState()
    var host by remember(savedHost) { mutableStateOf(savedHost) }
    var code by remember(savedCode) { mutableStateOf(savedCode) }

    Text(if (connected) "Connected" else status, color = if (connected) C.Green else C.Amber, fontSize = 13.sp)
    Text("Operators found on this Wi-Fi", color = C.Gray, fontSize = 11.sp)
    if (found.isEmpty()) Text("None yet. Make sure VORTEX is open on the other phone, on the same Wi-Fi.", color = C.Gray, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        found.forEach { f -> Chip("${f.name}  ${f.ip}", host == f.ip) { host = f.ip } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(host, { host = it.trim() }, "Operator address (e.g. 192.168.1.5)", KeyboardType.Uri, Modifier.weight(1f))
        Field(code, { if (it.length <= 6) code = it.filter { c -> c.isDigit() } }, "Code", KeyboardType.Number, Modifier.width(90.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Connect", true) { if (host.isNotBlank() && code.length == 6) Link.connectController(host, code) }
        Chip("Disconnect", false) { Link.disconnectController() }
    }
    Text("If the Wi-Fi drops, your requests are kept and sent when it is back.", color = C.Gray, fontSize = 11.sp)
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.Black else Color.White, fontSize = 13.sp,
        modifier = Modifier.background(if (selected) Color(0xFFF2F2F2) else Color(0xFF3A3A42), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, kb: KeyboardType, modifier: Modifier) {
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
        keyboardOptions = KeyboardOptions(keyboardType = kb),
        cursorBrush = SolidColor(Color.White),
        modifier = modifier.background(Color.Black, RoundedCornerShape(6.dp)).padding(8.dp),
        decorationBox = { inner -> if (value.isEmpty()) Text(hint, color = Color(0xFF777777), fontSize = 13.sp); inner() },
    )
}
