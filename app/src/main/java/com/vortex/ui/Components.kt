package com.vortex.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortex.model.Marks
import com.vortex.model.Spec
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object C {
    val Bg = Color(0xFF0B0B0D)
    val Card = Color(0xFF15151A)
    val Row = Color(0xFF1D1D22)
    val Gray = Color(0xFF888888)
    val Amber = Color(0xFFF2C94C)
    val Txt = Color(0xFFE8E8E8)
    val Label = Color(0xFFCFCFCF)
    val Green = Color(0xFF6FCF97)
    val Red = Color(0xFFEB5757)
    val Blue = Color(0xFF56B6F7)
}

class CapColors(val c1: Color, val c2: Color, val line: Color)
val GreyCap = CapColors(Color(0xFFD2D2D2), Color(0xFFA6A6A6), Color(0xFF222222))
val EqCap = CapColors(Color(0xFFB8B8B8), Color(0xFFB8B8B8), Color(0xFF444444))
/** Master A pair on the real Dynacord is red. */
val RedCap = CapColors(Color(0xFFD9473A), Color(0xFFA52C22), Color.White)
/** FX1 / FX2 faders on the real Dynacord are dark green. */
val GreenCap = CapColors(Color(0xFF2F5C48), Color(0xFF1F3F31), Color.White)

/** Panel with a title row. Double-tap on the title row (or tap the corner icon) expands or restores it. */
@Composable
fun PanelFrame(
    big: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = C.Card,
    title: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.background(background, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onDoubleTap = { onToggle() }) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = title)
            Text("⤢", color = C.Gray, fontSize = if (big) 20.sp else 14.sp,
                modifier = Modifier.clickable { onToggle() }.padding(horizontal = 6.dp, vertical = 1.dp))
        }
        Spacer(Modifier.height(2.dp))
        content()
    }
}

/** Cap height is limited to 30 percent of the track so a short screen never deforms the fader. */
private fun effCap(base: Float, h: Int) = min(base, h * 0.30f)

private fun capCenter(v: Double, h: Int, capH: Float, spec: Spec): Float {
    val pad = capH / 2f
    return pad + (1.0 - (v - spec.min) / (spec.max - spec.min)).toFloat() * (h - 2 * pad)
}

private fun yToValue(y: Float, h: Int, capH: Float, spec: Spec): Double {
    val pad = capH / 2f
    val r = 1.0 - ((y - pad) / (h - 2 * pad)).coerceIn(0f, 1f)
    return spec.min + r * (spec.max - spec.min)
}

@Composable
fun Fader(
    spec: Spec,
    shown: Double,
    ghost: Pair<Double, Boolean>?,
    valueText: String,
    valueIsAsk: Boolean,
    cap: CapColors,
    big: Boolean,
    modifier: Modifier = Modifier,
    label: String = spec.label,
    geq: Boolean = false,
    onLabelClick: (() -> Unit)? = null,
    onChange: (Double) -> Unit,
    onFinish: () -> Unit,
) {
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    val capW = if (geq) (if (big) 36.dp else 22.dp) else (if (big) 46.dp else 28.dp)
    val capH = if (geq) (if (big) 28.dp else 16.dp) else (if (big) 76.dp else 46.dp)
    val baseCap = with(density) { capH.toPx() }
    val fs = if (big) 13.sp else 10.sp
    val onChangeS by rememberUpdatedState(onChange)
    val onFinishS by rememberUpdatedState(onFinish)
    val shownS by rememberUpdatedState(shown)
    val marks = if (geq) null else if (spec.min == -60.0) Marks.channel else Marks.band

    var tapDown by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    // The cap grows a little while touched. This is only visual; the value mapping does not change.
    val capScale by animateFloatAsState(if (tapDown || dragging) 1.14f else 1f, tween(110), label = "cap")

    val labelView: @Composable () -> Unit = {
        Text(label, color = C.Label, fontSize = if (geq) (if (big) 12.sp else 8.sp) else fs, maxLines = 1,
            modifier = if (onLabelClick != null) Modifier.clickable { onLabelClick() } else Modifier)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (!geq) labelView()
        Canvas(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 2.dp)
                .pointerInput(spec.key) {
                    detectTapGestures(onPress = { tapDown = true; tryAwaitRelease(); tapDown = false })
                }
                .pointerInput(spec.key) {
                    // Put a finger anywhere on the fader's travel and drag: the fader moves with the finger,
                    // starting from where it is now. It never jumps to the touch point.
                    var acc = 0f
                    var base = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            base = capCenter(shownS, size.height, effCap(baseCap, size.height), spec)
                            acc = 0f
                            dragging = true
                        },
                        onDragEnd = { dragging = false; onFinishS() },
                        onDragCancel = { dragging = false; onFinishS() },
                    ) { change, dy ->
                        change.consume()
                        acc += dy
                        onChangeS(yToValue(base + acc, size.height, effCap(baseCap, size.height), spec))
                    }
                },
        ) {
            drawFader(spec, shown, ghost, cap, marks, tm, capW.toPx(), baseCap, big, geq, capScale)
        }
        if (geq) labelView()
        Text(valueText, color = if (valueIsAsk) C.Amber else C.Gray, fontSize = if (geq) (if (big) 12.sp else 8.sp) else fs, maxLines = 1)
    }
}

private fun DrawScope.drawFader(
    spec: Spec, v: Double, ghost: Pair<Double, Boolean>?, cap: CapColors,
    marks: List<Pair<Double, String>>?, tm: TextMeasurer, capW: Float, baseCap: Float, big: Boolean, geq: Boolean, scale: Float,
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val capH = min(baseCap, h * 0.30f)
    val pad = capH / 2f
    fun yOf(x: Double) = pad + (1.0 - (x - spec.min) / (spec.max - spec.min)).toFloat() * (h - 2 * pad)
    val trackW = (if (geq) (if (big) 9 else 5) else (if (big) 12 else 8)).dp.toPx()

    if (geq) Marks.geq.forEach { (m, _) ->
        val y = yOf(m)
        drawLine(if (m == 0.0) Color(0xFFA0A2A9) else Color(0xFF6A6C73), Offset(0f, y), Offset(w, y), if (m == 0.0) 2f else 1f)
    }
    drawRoundRect(Color(0xFF050505), Offset(cx - trackW / 2, pad), Size(trackW, h - 2 * pad), CornerRadius(3f))

    // Scale numbers sit clearly outside the cap so the cap never hides them.
    marks?.forEach { (m, t) ->
        val y = yOf(m)
        val near = (if (big) 22 else 12).dp.toPx()
        val len = (if (big) 10 else 6).dp.toPx()
        val labelGap = (if (big) 28 else 16).dp.toPx()
        val tick = Color(0xFF8D8D92)
        drawLine(tick, Offset(cx - trackW / 2 - near - len, y), Offset(cx - trackW / 2 - near, y), 1f)
        drawLine(tick, Offset(cx + trackW / 2 + near, y), Offset(cx + trackW / 2 + near + len, y), 1f)
        val r = tm.measure(t, TextStyle(color = Color(0xFFCFCFCF), fontSize = if (big) 12.sp else 8.sp))
        drawText(r, topLeft = Offset(cx - trackW / 2 - labelGap - r.size.width, y - r.size.height / 2f))
    }

    ghost?.let { (g, white) ->
        drawRoundRect(
            if (white) Color.White else C.Amber, Offset(cx - capW / 2, yOf(g) - capH / 2), Size(capW, capH), CornerRadius(6f),
            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
        )
    }

    val cy = yOf(v)
    val sw = capW * scale
    val sh = capH * (1f + (scale - 1f) * 0.6f)
    val top = cy - sh / 2
    drawRoundRect(cap.c1, Offset(cx - sw / 2, top), Size(sw, sh), CornerRadius(6f))
    if (geq) {
        drawRect(cap.line, Offset(cx - 1.dp.toPx(), top), Size(2.dp.toPx(), sh))
    } else {
        var y = top + 3.dp.toPx()
        while (y < top + sh - 2.dp.toPx()) {
            drawRect(cap.c2, Offset(cx - sw / 2, y), Size(sw, 2.dp.toPx()))
            y += 5.dp.toPx()
        }
        drawRect(cap.line, Offset(cx - sw / 2, cy - 1.dp.toPx()), Size(sw, 2.dp.toPx()))
    }
}

/** Scale column drawn left and right of the graphic EQ faders (+10, +3, 0, -3, -10). */
@Composable
fun GeqScale(big: Boolean, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val baseCap = with(LocalDensity.current) { (if (big) 28.dp else 16.dp).toPx() }
    val fs = if (big) 12.sp else 8.sp
    Column(modifier.width(if (big) 40.dp else 24.dp), horizontalAlignment = Alignment.End) {
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(vertical = 2.dp)) {
            val pad = min(baseCap, size.height * 0.30f) / 2f
            Marks.geq.forEach { (m, t) ->
                val y = pad + (1.0 - (m + 10.0) / 20.0).toFloat() * (size.height - 2 * pad)
                val r = tm.measure(t, TextStyle(color = Color(0xFFE4E4E4), fontSize = fs))
                drawText(r, topLeft = Offset(size.width - r.size.width - 2.dp.toPx(), y - r.size.height / 2f))
            }
        }
        Text(" ", fontSize = fs)
        Text(" ", fontSize = fs)
    }
}

@Composable
fun Knob(
    spec: Spec,
    shown: Double,
    valueText: String,
    valueIsAsk: Boolean,
    cap: Color,
    ribs: Pair<Color, Color>,
    knobSize: Dp,
    big: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Double) -> Unit,
    onFinish: () -> Unit,
) {
    val tm = rememberTextMeasurer()
    val onChangeS by rememberUpdatedState(onChange)
    val onFinishS by rememberUpdatedState(onFinish)
    val shownS by rememberUpdatedState(shown)
    var pressed by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (pressed) 1.1f else 1f, tween(110), label = "knob")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier.size(knobSize).graphicsLayer { scaleX = sc; scaleY = sc }.pointerInput(spec.key) {
                var start = 0.0
                var acc = 0f
                // 260 dp of finger travel = the full range, so the knob moves in very small steps and rotates smoothly.
                val travel = 260.dp.toPx()
                detectDragGestures(
                    onDragStart = { start = shownS; acc = 0f; pressed = true },
                    onDragEnd = { pressed = false; onFinishS() },
                    onDragCancel = { pressed = false; onFinishS() },
                ) { change, d ->
                    change.consume()
                    acc += d.y
                    onChangeS(start - acc / travel * (spec.max - spec.min))
                }
            },
        ) { drawKnob(spec, shown, ribs.first, ribs.second, cap, tm, big) }
        Column {
            Text(spec.label, color = C.Label, fontSize = if (big) 16.sp else 11.sp)
            Text(valueText, color = if (valueIsAsk) C.Amber else C.Gray, fontSize = if (big) 14.sp else 10.sp)
        }
    }
}

private fun DrawScope.drawKnob(spec: Spec, v: Double, rib1: Color, rib2: Color, cap: Color, tm: TextMeasurer, big: Boolean) {
    val k = size.minDimension / 56f
    val c = Offset(size.width / 2, size.height / 2)
    val ratio = ((v - spec.min) / (spec.max - spec.min)).toFloat().coerceIn(0f, 1f)
    for (i in 0..10) {
        val a = Math.toRadians(-135.0 + i * 27.0)
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        drawLine(Color(0xFF9A9A9E), c + Offset(dx * 21 * k, dy * 21 * k), c + Offset(dx * 25 * k, dy * 25 * k), 1.5f)
    }
    val r = 17 * k
    drawCircle(rib2, r, c)
    for (i in 0 until 30) drawArc(rib1, i * 12f, 6f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
    drawCircle(cap, 11 * k, c)
    val th = Math.toRadians(-135.0 + ratio * 270.0)
    val dx = sin(th).toFloat()
    val dy = -cos(th).toFloat()
    drawLine(Color.White, c + Offset(dx * 3 * k, dy * 3 * k), c + Offset(dx * 15 * k, dy * 15 * k), 3 * k, StrokeCap.Round)
    val st = TextStyle(color = Color(0xFFCFCFCF), fontSize = if (big) 10.sp else 7.sp)
    fun f(x: Double) = (if (spec.unit == "dB" && x > 0) "+" else "") + (if (x % 1.0 == 0.0) x.toInt().toString() else x.toString())
    val a = tm.measure(f(spec.min), st)
    val b = tm.measure(f(spec.max), st)
    drawText(a, topLeft = Offset(0f, size.height - a.size.height))
    drawText(b, topLeft = Offset(size.width - b.size.width, size.height - b.size.height))
}
