package com.vortex.ui

import android.app.Application
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vortex.audio.AudioEngine
import com.vortex.data.MessageEntity
import com.vortex.data.VocalLineEntity
import com.vortex.model.Spec
import com.vortex.model.Specs
import com.vortex.net.Link
import kotlinx.coroutines.flow.StateFlow

private val GreyKnob = Triple(Color(0xFFCFCCC4), Color(0xFFBDBAB2), Color(0xFF8C8982))
private val BlackKnob = Triple(Color(0xFF141414), Color(0xFF3B3B3B), Color(0xFF202020))
private val RedKnob = Triple(Color(0xFFC0392B), Color(0xFFBDBAB2), Color(0xFF8C8982))

/**
 * Page 1: messages, FX status, vocal faders, spectrum, graphic EQ.
 * Page 2: vocal-line EQ knobs, Master A pair, FX1 / FX2 faders, Master B knob, band volumes.
 * Double-tap a panel title to expand it; double-tap again to restore.
 */
@Composable
fun ProjectScreen(projectId: Long, setup: Boolean, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ProjectViewModel = viewModel(key = "project$projectId", factory = ProjectViewModel.factory(app, projectId))
    var page by rememberSaveable { mutableIntStateOf(1) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var showLink by remember { mutableStateOf(false) }
    var setupOn by rememberSaveable { mutableStateOf(setup) }
    val toggle: (String) -> Unit = { id -> expanded = if (expanded == id) null else id }

    Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (setupOn && !vm.controller) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF3A3220), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Initial setup: set every control to match the real Dynacord", color = C.Amber, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("Done", color = Color.Black, fontSize = 12.sp,
                        modifier = Modifier.background(C.Amber, RoundedCornerShape(10.dp)).clickable { vm.finishSetup(); setupOn = false }.padding(horizontal = 10.dp, vertical = 2.dp))
                }
                Spacer(Modifier.height(4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(targetState = expanded to page, animationSpec = tween(170), label = "screen") { (ex, pg) ->
                    when {
                        ex != null -> PanelContent(ex, true, vm, toggle, Modifier.fillMaxSize())
                        pg == 1 -> Page1(vm, toggle)
                        else -> Page2(vm, toggle)
                    }
                }
            }
        }
        SideStrip(page, vm.controller, onPage = { page = if (page == 1) 2 else 1; expanded = null }, onBack = onBack, onLink = { showLink = true })
    }
    if (showLink) LinkDialog(vm.controller) { showLink = false }
}

@Composable
private fun Page1(vm: ProjectViewModel, toggle: (String) -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelContent("msg", false, vm, toggle, Modifier.weight(1f).fillMaxWidth())
            PanelContent("fx", false, vm, toggle, Modifier.weight(1f).fillMaxWidth())
        }
        PanelContent("voc", false, vm, toggle, Modifier.fillMaxHeight().width((vm.vocals.size.coerceAtLeast(1) * 62 + 20).dp))
        Column(Modifier.weight(3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelContent("sp", false, vm, toggle, Modifier.weight(1.15f).fillMaxWidth())
            PanelContent("geq", false, vm, toggle, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun Page2(vm: ProjectViewModel, toggle: (String) -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelContent("vq", false, vm, toggle, Modifier.weight(0.9f * vm.vocals.size.coerceAtLeast(1)).fillMaxHeight())
        PanelContent("fa", false, vm, toggle, Modifier.weight(2.1f).fillMaxHeight())
        PanelContent("mb", false, vm, toggle, Modifier.weight(0.8f).fillMaxHeight())
        PanelContent("bv", false, vm, toggle, Modifier.weight(2f).fillMaxHeight())
    }
}

@Composable
private fun PanelContent(id: String, big: Boolean, vm: ProjectViewModel, toggle: (String) -> Unit, modifier: Modifier) {
    val t = { toggle(id) }
    when (id) {
        "msg" -> MessagesPanel(vm, big, t, modifier)
        "fx" -> FxPanel(vm, big, t, modifier)
        "voc" -> VocalPanel(vm, big, t, modifier)
        "sp" -> SpectrumPanel(vm, big, t, modifier)
        "geq" -> GeqPanel(vm, big, t, modifier)
        "vq" -> VocalEqPanel(vm, big, t, modifier)
        "fa" -> FadersPanel(vm, big, t, modifier)
        "mb" -> MasterBPanel(vm, big, t, modifier)
        "bv" -> BandsPanel(vm, big, t, modifier)
    }
}

@Composable
private fun PTitle(text: String, big: Boolean) =
    Text(text, color = Color.White, fontSize = if (big) 16.sp else 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)

@Composable
private fun VFader(
    vm: ProjectViewModel, spec: Spec, cap: CapColors, big: Boolean, modifier: Modifier,
    label: String = spec.label, geq: Boolean = false, onLabelClick: (() -> Unit)? = null,
) {
    val (txt, ask) = vm.valueText(spec)
    Fader(spec, vm.shown(spec), vm.ghost(spec), txt, ask, cap, big, modifier, label, geq, onLabelClick,
        { vm.change(spec, it) }, { vm.finish(spec) })
}

@Composable
private fun VKnob(vm: ProjectViewModel, spec: Spec, k: Triple<Color, Color, Color>, size: Dp, big: Boolean) {
    val (txt, ask) = vm.valueText(spec)
    Knob(spec, vm.shown(spec), txt, ask, k.first, k.second to k.third, size, big, Modifier,
        { vm.change(spec, it) }, { vm.finish(spec) })
}

// ---------------- page 2 panels ----------------

@Composable
private fun VocalEqPanel(vm: ProjectViewModel, big: Boolean, t: () -> Unit, modifier: Modifier) =
    PanelFrame(big, t, modifier, title = { PTitle("Vocal line EQ", big) }) {
        // One column per vocal line, side by side. Adding a vocal line on page 1 adds a column here.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val header = if (big) 26.dp else 16.dp
            // knob size adapts to the height of the screen so nothing falls below it
            val knob = ((maxHeight - header) / 4 - 2.dp).coerceIn(30.dp, if (big) 104.dp else 56.dp)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceAround) {
                vm.vocals.forEach { l ->
                    key(l.id) {
                        val s = Specs.eq(l.id, l.name)
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("EQ: ${l.name}", color = C.Blue, fontSize = if (big) 16.sp else 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            VKnob(vm, s[0], GreyKnob, knob, big)
                            VKnob(vm, s[1], BlackKnob, knob, big) // kHz knob is black on the real mixer
                            VKnob(vm, s[2], GreyKnob, knob, big)
                            VKnob(vm, s[3], GreyKnob, knob, big)
                        }
                    }
                }
            }
        }
    }

@Composable
private fun FadersPanel(vm: ProjectViewModel, big: Boolean, t: () -> Unit, modifier: Modifier) =
    PanelFrame(big, t, modifier, title = { PTitle("Master A (L/R) and FX", big) }) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            // Master A is a red pair on the real mixer
            VFader(vm, Specs.masterL, RedCap, big, Modifier.weight(1f).fillMaxHeight(), label = "A L")
            VFader(vm, Specs.masterR, RedCap, big, Modifier.weight(1f).fillMaxHeight(), label = "A R")
            VFader(vm, Specs.fx1, GreenCap, big, Modifier.weight(1f).fillMaxHeight())
            VFader(vm, Specs.fx2, GreenCap, big, Modifier.weight(1f).fillMaxHeight())
        }
    }

@Composable
private fun MasterBPanel(vm: ProjectViewModel, big: Boolean, t: () -> Unit, modifier: Modifier) =
    PanelFrame(big, t, modifier, title = { PTitle("Master B", big) }) {
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            VKnob(vm, Specs.masterB, RedKnob, if (big) 104.dp else 52.dp, big)
            Spacer(Modifier.height(8.dp))
            Text("Double-tap a panel title to expand it", color = C.Gray, fontSize = if (big) 13.sp else 9.sp)
        }
    }

@Composable
private fun BandsPanel(vm: ProjectViewModel, big: Boolean, t: () -> Unit, modifier: Modifier) =
    PanelFrame(big, t, modifier, title = { PTitle("Band volume", big) }) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Specs.bands.forEach { VFader(vm, it, GreyCap, big, Modifier.weight(1f).fillMaxHeight()) }
        }
        if (big) Text("Monitor = Evo 2 Max, Active = Yamaha. Scale not verified yet.", color = C.Gray, fontSize = 13.sp)
    }

// ---------------- page 1 panels ----------------

@Composable
private fun VocalPanel(vm: ProjectViewModel, big: Boolean, t: () -> Unit, modifier: Modifier) {
    var renaming by remember { mutableStateOf<VocalLineEntity?>(null) }
    PanelFrame(big, t, modifier, title = {
        PTitle("Vocal", big)
        Text("+", color = Color.White, fontSize = if (big) 20.sp else 14.sp,
            modifier = Modifier.padding(start = 6.dp).background(Color(0xFF26262C), RoundedCornerShape(10.dp))
                .clickable { vm.addVocal() }.padding(horizontal = 8.dp))
    }) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            vm.vocals.forEach { l ->
                key(l.id) {
                    VFader(vm, Specs.vocal(l.id, l.name), GreyCap, big, Modifier.weight(1f).fillMaxHeight(),
                        label = l.name, onLabelClick = { renaming = l })
                }
            }
        }
        if (big) Text("Tap a name to rename a line. Use + to add one.", color = C.Gray, fontSize = 13.sp)
    }
    renaming?.let { l ->
        var name by remember(l.id) { mutableStateOf(l.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Vocal line") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.renameVocal(l, name); renaming = null }) { Text("OK") } },
            dismissButton = {
                Row {
                    if (vm.vocals.size > 1) TextButton({ vm.removeVocal(l); renaming = null }) { Text("Remove") }
                    TextButton({ renaming = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun GeqPanel(vm: ProjectViewModel, big: Boolean, t: () -> Unit, modifier: Modifier) =
    PanelFrame(big, t, modifier, background = Color(0xFF3A3C42), title = { PTitle("Stereo graphic equalizer", big) }) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            GeqScale(big, Modifier.fillMaxHeight())
            Specs.geq.forEach { VFader(vm, it, EqCap, big, Modifier.weight(1f).fillMaxHeight(), geq = true) }
            GeqScale(big, Modifier.fillMaxHeight())
        }
    }

@Composable
private fun SpectrumPanel(vm: ProjectViewModel, big: Boolean, t: () -> Unit, modifier: Modifier) {
    var showPeaks by remember { mutableStateOf(true) }
    var demo by remember { mutableStateOf(false) }
    var peakText by remember { mutableStateOf("") }
    var markText by remember { mutableStateOf("") }
    val src: StateFlow<FloatArray?> = if (vm.controller) Link.remoteSpectrum else AudioEngine.spectrum
    val frame by src.collectAsState()
    val lvl by (if (vm.controller) Link.remoteLevel else AudioEngine.levelDb).collectAsState()
    val clip by (if (vm.controller) Link.remoteClip else AudioEngine.clipping).collectAsState()
    val status by (if (vm.controller) Link.status else AudioEngine.status).collectAsState()
    val live = frame != null
    PanelFrame(big, t, modifier, title = {
        PTitle("Spectrum", big)
        Text(
            if (demo) "DEMO DATA" else if (live) "LIVE" else "NO SIGNAL",
            color = if (demo) Color(0xFFE0A030) else if (live) C.Green else C.Red,
            fontSize = 9.sp, modifier = Modifier.padding(start = 6.dp),
        )
        Text("Peaks", color = if (showPeaks) C.Blue else C.Gray, fontSize = if (big) 14.sp else 11.sp,
            modifier = Modifier.padding(start = 8.dp).clickable { showPeaks = !showPeaks })
        Text("Demo", color = if (demo) C.Amber else C.Gray, fontSize = if (big) 14.sp else 11.sp,
            modifier = Modifier.padding(start = 8.dp).clickable { demo = !demo })
    }) {
        SpectrumView(frame, demo, big, showPeaks, { peakText = it }, { markText = it }, Modifier.weight(1f).fillMaxWidth())
        val fs = if (big) 13.sp else 10.sp
        val line1 = if (markText.isNotEmpty()) markText else if (showPeaks) peakText else ""
        if (line1.isNotEmpty()) Text(line1, color = C.Gray, fontSize = fs, maxLines = if (big) 2 else 1)
        val levelText = if (demo) "Demo signal" else if (live) "Level ${lvl.toInt()} dBFS" + (if (clip) "  CLIP" else "") else status
        Text(levelText, color = if (clip && !demo) C.Red else C.Gray, fontSize = fs, maxLines = 1)
        if (big && markText.isNotEmpty()) Text("Tap the graph to move the marker", color = C.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun FxPanel(vm: ProjectViewModel, big: Boolean, t: () -> Unit, modifier: Modifier) =
    PanelFrame(big, t, modifier, title = { PTitle("FX status", big) }) {
        Row(
            Modifier.fillMaxWidth().weight(1f).then(if (big) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(if (big) 14.dp else 6.dp),
        ) {
            for (n in 1..2) {
                Column(Modifier.weight(1f)) {
                    FxCard(vm, n, big)
                    if (big) Specs.fx(n).forEach { spec ->
                        val (_, ask) = vm.valueText(spec)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(spec.title, color = C.Label, fontSize = 13.sp, modifier = Modifier.width(100.dp))
                            StepButton("−", true) { vm.step(spec, -1) }
                            Slider(
                                value = vm.shown(spec).toFloat(),
                                onValueChange = { vm.change(spec, it.toDouble()) },
                                onValueChangeFinished = { vm.finish(spec) },
                                valueRange = spec.min.toFloat()..spec.max.toFloat(),
                                modifier = Modifier.weight(1f),
                            )
                            StepButton("+", true) { vm.step(spec, 1) }
                            Spacer(Modifier.width(6.dp))
                            NumberField(vm, spec, ask)
                            Text(" ${spec.unit}", color = C.Gray, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

/** Delay time and feedback are shown larger than the effect name. Effect 60 = Mono Delay. */
@Composable
private fun FxCard(vm: ProjectViewModel, n: Int, big: Boolean) {
    val specs = Specs.fx(n)
    Column(Modifier.fillMaxWidth().background(C.Row, RoundedCornerShape(6.dp)).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(if (big) 8.dp else 2.dp)) {
        Text("FX$n · 60 Mono Delay", color = C.Gray, fontSize = if (big) 13.sp else 9.sp, maxLines = 1)
        listOf(specs[0], specs[1]).forEach { spec ->
            val (txt, ask) = vm.valueText(spec)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec.label.substringBefore(" time"), color = C.Gray, fontSize = if (big) 13.sp else 9.sp, maxLines = 1)
                    Text(if (txt == "not set") "—" else txt, color = if (ask) C.Amber else Color.White,
                        fontSize = if (big) 30.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
                StepButton("−", big) { vm.step(spec, -1) }
                Spacer(Modifier.width(3.dp))
                StepButton("+", big) { vm.step(spec, 1) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessagesPanel(vm: ProjectViewModel, big: Boolean, t: () -> Unit, modifier: Modifier) {
    var input by remember { mutableStateOf("") }
    val quick = listOf("این تغییر نظرم نیست", "فید بک میگیره", "صدا تیزه", "صدای مانیتور کمه", "هواست به رکورد باشه",
        "اکو زیاده", "اکو کمه", "مداح راضی نیست", "پیشنهاد میدم تغییری ایجاد نکنی")
    val fs = if (big) 14.sp else 11.sp
    PanelFrame(big, t, modifier, title = { PTitle("Messages", big) }) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (vm.messages.isEmpty()) item { Text("No messages yet", color = C.Gray, fontSize = fs) }
            items(vm.messages.reversed(), key = { it.id }) { m -> MessageRow(m, vm, fs) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicTextField(
                input, { input = it }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = fs), cursorBrush = SolidColor(Color.White),
                modifier = Modifier.weight(1f).background(Color.Black, RoundedCornerShape(5.dp)).padding(5.dp),
                decorationBox = { inner -> if (input.isEmpty()) Text("Type a short message", color = Color(0xFF777777), fontSize = fs); inner() },
            )
            Text("Send", color = Color.White, fontSize = 11.sp,
                modifier = Modifier.background(Color(0xFF378ADD), RoundedCornerShape(10.dp)).clickable { vm.sendText(input); input = "" }.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        if (big) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            quick.forEach { q ->
                Text(q, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.background(Color(0xFF26262C), RoundedCornerShape(10.dp)).clickable { vm.sendText(q) }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
private fun MessageRow(m: MessageEntity, vm: ProjectViewModel, fs: androidx.compose.ui.unit.TextUnit) {
    Row(Modifier.fillMaxWidth().background(C.Row, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // Request messages are "label: current → requested": section blue, current value green, requested value red.
        val parts = remember(m.text, m.status) {
            if (m.status != "TEXT" && " → " in m.text) {
                val head = m.text.substringBefore(" → ")
                Triple(head.substringBeforeLast(": "), head.substringAfterLast(": "), m.text.substringAfter(" → "))
            } else null
        }
        Column(Modifier.weight(1f)) {
            Text(m.section, color = if (parts != null) C.Blue else Color.White, fontSize = fs, fontWeight = FontWeight.SemiBold)
            if (parts != null) {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = C.Txt)) { append(parts.first + ": ") }
                    withStyle(SpanStyle(color = C.Green)) { append(parts.second) }
                    withStyle(SpanStyle(color = C.Txt)) { append(" → ") }
                    withStyle(SpanStyle(color = C.Red)) { append(parts.third) }
                }, fontSize = fs)
            } else Text(m.text, color = C.Txt, fontSize = fs)
        }
        when (m.status) {
            "PENDING" -> if (vm.controller) Text("Pending", color = C.Amber, fontSize = fs)
            else Text("Accept", color = Color.White, fontSize = 11.sp,
                modifier = Modifier.background(Color(0xFF2F9E6A), RoundedCornerShape(10.dp)).clickable { vm.accept(m) }.padding(horizontal = 10.dp, vertical = 4.dp))
            "APPLIED" -> Text("Applied", color = C.Green, fontSize = fs)
            else -> Text(m.sender, color = C.Gray, fontSize = fs)
        }
    }
}

// ---------------- side strip ----------------

@Composable
private fun SideStrip(page: Int, controller: Boolean, onPage: () -> Unit, onBack: () -> Unit, onLink: () -> Unit) {
    val conn by Link.connected.collectAsState()
    val n by Link.clients.collectAsState()
    val ok = if (controller) conn else n > 0
    Column(
        Modifier.width(32.dp).fillMaxHeight().background(Color(0xFF17171A), RoundedCornerShape(8.dp)).padding(vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StripButton("Page ${if (page == 1) 2 else 1}", 58.dp, onPage)
        StripButton("Projects", 58.dp, onBack)
        StripButton("Link", 40.dp, onLink)
        Box(Modifier.size(10.dp).background(if (ok) C.Green else if (controller) C.Red else C.Amber, CircleShape))
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(width = 26.dp, height = 56.dp), contentAlignment = Alignment.Center) {
            Text(if (controller) "Controller" else "Operator", color = C.Gray, fontSize = 9.sp, softWrap = false,
                modifier = Modifier.wrapContentSize(unbounded = true).rotate(90f))
        }
    }
}

@Composable
private fun StripButton(text: String, height: Dp, onClick: () -> Unit) {
    Box(Modifier.size(width = 26.dp, height = height).background(Color(0xFF26262C), RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 10.sp, softWrap = false,
            modifier = Modifier.wrapContentSize(unbounded = true).rotate(90f))
    }
}

@Composable
private fun StepButton(text: String, big: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(if (big) 34.dp else 22.dp).background(Color(0xFF26262C), RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = if (big) 18.sp else 14.sp)
    }
}

/** Type an exact value with the keyboard. Applied on Done or when the field loses focus; the value snaps to the control's step. */
@Composable
private fun NumberField(vm: ProjectViewModel, spec: Spec, ask: Boolean) {
    val focus = LocalFocusManager.current
    val shown = vm.shown(spec)
    var focused by remember { mutableStateOf(false) }
    var text by remember(spec.key) { mutableStateOf("") }
    LaunchedEffect(shown, focused) {
        if (!focused) text = if (spec.step < 1.0) String.format(java.util.Locale.US, "%.1f", shown) else shown.toInt().toString()
    }
    fun apply() { text.toDoubleOrNull()?.let { vm.change(spec, it); vm.finish(spec) } }
    BasicTextField(
        text, { text = it }, singleLine = true,
        textStyle = TextStyle(color = if (ask) C.Amber else Color.White, fontSize = 14.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { apply(); focus.clearFocus() }),
        cursorBrush = SolidColor(Color.White),
        modifier = Modifier.width(76.dp).background(Color.Black, RoundedCornerShape(5.dp)).padding(6.dp)
            .onFocusChanged { if (focused && !it.isFocused) apply(); focused = it.isFocused },
    )
}
