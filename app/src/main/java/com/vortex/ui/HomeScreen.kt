package com.vortex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortex.BuildConfig
import com.vortex.data.ProjectEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0B0B0D)
private val CardBg = Color(0xFF15151A)
private val RowBg = Color(0xFF1D1D22)
private val Gray = Color(0xFF888888)
private val Amber = Color(0xFFF2C94C)

@Composable
fun HomeScreen(vm: ProjectsViewModel, onOpen: (Long) -> Unit, onInitialSetup: (Long) -> Unit) {
    val projects by vm.projects.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    val title = if (BuildConfig.ROLE == "CONTROLLER") "VORTEX E" else "VORTEX"

    Row(Modifier.fillMaxSize().background(Bg).padding(24.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            Column {
                // The name fades and slides in once when the app opens.
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) { appear.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
                Text(title, color = Color(0xFFF2F2F2), fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
                    modifier = Modifier.graphicsLayer {
                        alpha = appear.value
                        val sc = 0.9f + 0.1f * appear.value
                        scaleX = sc; scaleY = sc
                        translationX = -60f * (1f - appear.value)
                    })
                Text("Live audio monitoring and communication", color = Gray, fontSize = 13.sp,
                    modifier = Modifier.graphicsLayer { alpha = appear.value })
            }
        }
        Column(Modifier.weight(1.3f).fillMaxHeight()) {
            Button(
                onClick = { showCreate = true },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF2F2F2), contentColor = Color(0xFF111111)),
            ) { Text("Create new project") }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().weight(1f).background(CardBg, RoundedCornerShape(8.dp))) {
                if (projects.isEmpty()) {
                    Text("No projects yet", color = Gray, modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(projects, key = { it.id }) { p ->
                            ProjectRow(p, onOpen = { onOpen(p.id) }, onSetup = { onInitialSetup(p.id) },
                                onRename = { renameTarget = p }, onDelete = { deleteTarget = p })
                        }
                    }
                }
            }
        }
    }

    if (showCreate) NameDialog("New project", "", { showCreate = false }) { vm.create(it); showCreate = false }
    renameTarget?.let { p -> NameDialog("Rename project", p.name, { renameTarget = null }) { vm.rename(p.id, it); renameTarget = null } }
    deleteTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete project?") },
            text = { Text("\"${p.name}\" and all its data will be deleted. This cannot be undone.") },
            confirmButton = { TextButton({ vm.delete(p.id); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton({ deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProjectRow(p: ProjectEntity, onOpen: () -> Unit, onSetup: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val date = remember(p.lastOpenedAt) { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(p.lastOpenedAt)) }
    Row(
        Modifier.fillMaxWidth().background(RowBg, RoundedCornerShape(6.dp)).clickable { onOpen() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.name, color = Color(0xFFE8E8E8), fontSize = 15.sp)
            Text("Last opened $date", color = Gray, fontSize = 11.sp)
        }
        if (BuildConfig.ROLE != "CONTROLLER") {
            OutlinedButton(onClick = onSetup, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("Initial setup", color = Amber, fontSize = 12.sp)
            }
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", color = Gray, fontSize = 18.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton({ if (text.isNotBlank()) onOk(text.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
