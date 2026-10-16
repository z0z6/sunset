package pl.sunsetcheck

import android.graphics.Paint as AndroidPaint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import kotlin.math.*

// ---------------------------------------------------------------- scena zachodu

private fun DrawScope.cloudLayer(
    seed: Int, count: Int, yMin: Float, yMax: Float,
    minW: Float, maxW: Float, color: Color, alpha: Float, shift: Float
) {
    val rnd = java.util.Random(seed.toLong())
    val span = size.width + 2 * maxW
    repeat(count) {
        val bx = rnd.nextFloat() * span
        val by = yMin + rnd.nextFloat() * (yMax - yMin)
        val cw = minW + rnd.nextFloat() * (maxW - minW)
        val x = (((bx + shift) % span) + span) % span - maxW
        for (k in 0..3) {
            val ox = (k - 1.5f) * cw * 0.28f
            val oy = if (k % 2 == 0) 0f else -cw * 0.06f
            drawOval(
                color.copy(alpha = alpha),
                topLeft = Offset(x + ox - cw * 0.3f, by + oy - cw * 0.1f),
                size = Size(cw * 0.6f, cw * 0.2f)
            )
        }
    }
}

@Composable
fun SunsetScene(a: Analysis, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "drift")
    val drift by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(70000, easing = LinearEasing), RepeatMode.Restart), label = "d"
    )
    var tilt by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier.pointerInput(Unit) {
            detectHorizontalDragGestures { change, dx ->
                change.consume()
                tilt = (tilt + dx / size.width * 2f).coerceIn(-1f, 1f)
            }
        }
    ) {
        val w = size.width
        val h = size.height
        val clear = (a.score / 100f).coerceIn(0f, 1f)
        val horizon = h * 0.74f

        val top = lerp(Color(0xFF2B3345), Color(0xFF1B1F4B), clear)
        val mid = lerp(Color(0xFF4A4F5E), if (a.event == SunEvent.SUNRISE) Color(0xFFA64F8A) else Color(0xFF8E3B6E), clear)
        val bot = lerp(Color(0xFF8C8F99), if (a.event == SunEvent.SUNRISE) Color(0xFFFFA9A0) else Color(0xFFFF8A3D), clear)
        drawRect(Brush.verticalGradient(listOf(top, mid, bot), 0f, horizon), size = Size(w, horizon))

        // słońce + poświata
        val sc = Offset(w * 0.5f + tilt * w * 0.03f, horizon - h * 0.05f)
        val glowR = h * 0.6f
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFFFE08A).copy(alpha = 0.25f + 0.65f * clear), Color(0xFFFF8A3D).copy(alpha = 0.18f + 0.3f * clear), Color.Transparent),
                center = sc, radius = glowR
            ),
            radius = glowR, center = sc
        )
        drawCircle(Color(0xFFFFF2B0).copy(alpha = 0.4f + 0.6f * clear), radius = h * 0.07f, center = sc)

        // chmury: wysokie, średnie, niskie (paralaksa = efekt 3D)
        val hiCount = (a.avgHigh * 16).roundToInt()
        val miCount = (a.avgMid * 16).roundToInt()
        val loCount = (a.avgLow * 16).roundToInt()
        cloudLayer(1, hiCount, h * 0.05f, h * 0.32f, w * 0.25f, w * 0.5f, Color(0xFFFFD6E0), 0.30f, tilt * w * 0.05f + drift * w * 0.6f)
        cloudLayer(2, miCount, h * 0.25f, h * 0.55f, w * 0.2f, w * 0.4f, Color(0xFFE59A8C), 0.50f, tilt * w * 0.12f + drift * w * 1.2f)
        cloudLayer(3, loCount, h * 0.45f, h * 0.72f, w * 0.3f, w * 0.6f, Color(0xFF3A3248), 0.72f, tilt * w * 0.25f + drift * w * 2.0f)

        // teren (ilustracja; wysokość grzbietu ~ horyzont terenowy)
        val ridge = min(h * 0.25f, a.horizonDeg.toFloat() * h * 0.03f) + h * 0.015f
        val path = Path().apply {
            moveTo(0f, h)
            lineTo(0f, horizon)
            var x = 0f
            while (x <= w) {
                val y = horizon - ridge * (0.6f + 0.4f * sin(x / w * 6f + tilt * 2f))
                lineTo(x, y)
                x += w / 60f
            }
            lineTo(w, horizon)
            lineTo(w, h)
            close()
        }
        drawPath(path, Brush.verticalGradient(listOf(Color(0xFF14102A), Color(0xFF05060F)), horizon - ridge, h))
    }
}

// ---------------------------------------------------------------- kopuła 3D

@Composable
fun SkyDome(a: Analysis) {
    var viewAz by remember(a) { mutableFloatStateOf(a.eventAzimuth.toFloat()) }
    var pitch by remember(a) { mutableFloatStateOf(0.55f) }
    val sunsetIdx = a.sunPath.indexOfLast { it.epochMs <= a.eventEpochMs }.coerceAtLeast(0)
    var tSlider by remember(a) { mutableFloatStateOf(sunsetIdx.toFloat() / max(1, a.sunPath.lastIndex)) }
    val paint = remember {
        AndroidPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textAlign = AndroidPaint.Align.CENTER
        }
    }
    val selIdx = (tSlider * a.sunPath.lastIndex).roundToInt().coerceIn(0, a.sunPath.lastIndex)
    val sel = a.sunPath[selIdx]

    Column {
        Canvas(
            Modifier.fillMaxWidth().height(300.dp).clipToBounds().pointerInput(a) {
                detectDragGestures { change, drag ->
                    change.consume()
                    viewAz = (viewAz - drag.x * 0.4f + 360f) % 360f
                    pitch = (pitch + drag.y * 0.004f).coerceIn(0.15f, 1.2f)
                }
            }
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h * 0.68f
            val rad = min(w * 0.42f, h * 0.62f)
            val va = Math.toRadians(viewAz.toDouble())
            val p = pitch.toDouble()

            fun pr(az: Double, el: Double, r: Double = 1.0): Offset {
                val rel = Math.toRadians(az) - va
                val e = Math.toRadians(el)
                val x = cos(e) * sin(rel) * r
                val z = cos(e) * cos(rel) * r
                val y = sin(e)
                val yy = y * cos(p) + z * sin(p)
                val zz = -y * sin(p) + z * cos(p)
                val s = 1.0 / (1.0 + 0.25 * zz)
                return Offset((cx + rad * x * s).toFloat(), (cy - rad * yy * s).toFloat())
            }

            fun ring(el: Double): Path = Path().apply {
                for (i in 0..72) {
                    val o = pr(i * 5.0, el)
                    if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                }
            }

            // podłoga
            val floor = ring(0.0).apply { close() }
            drawPath(floor, Brush.radialGradient(listOf(Color(0x66324A8F), Color(0x111A2350)), Offset(cx, cy), rad))
            drawPath(floor, Color(0x88FFFFFF), style = Stroke(2f))

            // siatka
            for (el in listOf(30.0, 60.0)) drawPath(ring(el), Color(0x22FFFFFF), style = Stroke(1.5f))
            for (az in 0 until 360 step 45) {
                val m = Path()
                for (k in 0..18) {
                    val o = pr(az.toDouble(), k * 5.0)
                    if (k == 0) m.moveTo(o.x, o.y) else m.lineTo(o.x, o.y)
                }
                drawPath(m, Color(0x22FFFFFF), style = Stroke(1.5f))
            }

            // kierunek zachodu
            val tip = pr(a.eventAzimuth, 0.0)
            drawLine(Color(0xFFFF8A3D), Offset(cx, cy), tip, strokeWidth = 5f, cap = StrokeCap.Round)

            // ścieżka słońca
            val sp = Path()
            var started = false
            for (s in a.sunPath) {
                val o = pr(s.azimuth, s.elevation)
                if (!started) { sp.moveTo(o.x, o.y); started = true } else sp.lineTo(o.x, o.y)
            }
            drawPath(sp, Color(0xFFFFC857), style = Stroke(5f, cap = StrokeCap.Round))
            paint.textSize = 10.sp.toPx()
            paint.color = android.graphics.Color.argb(200, 255, 255, 255)
            for (s in a.sunPath) {
                val z = Instant.ofEpochMilli(s.epochMs).atZone(Solar.ZONE)
                if (z.minute == 0 && s.elevation >= 0 && z.hour % 2 == 0) {
                    val o = pr(s.azimuth, s.elevation)
                    drawCircle(Color.White, 4f, o)
                    drawContext.canvas.nativeCanvas.drawText("${z.hour}", o.x, o.y - 12f, paint)
                }
            }

            // kompas
            paint.textSize = 14.sp.toPx()
            paint.color = android.graphics.Color.WHITE
            val labels = listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)
            for ((t, az) in labels) {
                val o = pr(az, 0.0, 1.14)
                drawContext.canvas.nativeCanvas.drawText(t, o.x, o.y + 5f, paint)
            }
            paint.color = android.graphics.Color.rgb(255, 138, 61)
            paint.textSize = 12.sp.toPx()
            val lbl = pr(a.eventAzimuth, 0.0, 1.3)
            drawContext.canvas.nativeCanvas.drawText("${a.event.label} ${a.eventAzimuth.roundToInt()}°", lbl.x, lbl.y + 4f, paint)

            // wybrane położenie słońca + rzut pionowy na horyzont
            val sunO = pr(sel.azimuth, sel.elevation)
            val foot = pr(sel.azimuth, 0.0)
            drawLine(Color(0x99FFC857), sunO, foot, strokeWidth = 2f)
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFFFF2B0), Color(0x66FFB300), Color.Transparent), sunO, 34f),
                radius = 34f, center = sunO
            )
            drawCircle(Color(0xFFFFF2B0), 10f, sunO)
        }
        Slider(value = tSlider, onValueChange = { tSlider = it })
        Text(
            "${fmtTime(sel.epochMs)} • wysokość ${"%.1f".format(sel.elevation)}° • azymut ${sel.azimuth.roundToInt()}°",
            fontSize = 12.sp, color = Color(0xFFB8C0E0)
        )
    }
}

// ---------------------------------------------------------------- przekrój linii widzenia

@Composable
fun CrossSection(a: Analysis) {
    val paint = remember {
        AndroidPaint().apply { isAntiAlias = true; color = android.graphics.Color.WHITE }
    }
    Canvas(Modifier.fillMaxWidth().height(210.dp).clipToBounds()) {
        val w = size.width
        val h = size.height
        val maxD = 300.0
        val yTop = 11.0
        val yBot = -8.0
        fun px(d: Double) = (d / maxD * w).toFloat()
        fun py(y: Double) = ((yTop - y) / (yTop - yBot) * h).toFloat()
        fun drop(d: Double) = d * d / (2 * Geo.R_KM)

        drawRect(
            Brush.verticalGradient(listOf(Color(0xFF10163A), Color(0xFF5A2E6B), Color(0xFFE8703A)), 0f, py(0.0)),
            size = Size(w, h)
        )

        val layers = listOf(
            Triple(0.0 to 2.0, Color(0xFF3A3248), 0),
            Triple(2.0 to 6.0, Color(0xFFE59A8C), 1),
            Triple(6.0 to 12.0, Color(0xFFFFD6E0), 2)
        )
        val rnd = java.util.Random(7)
        for ((range, color, key) in layers) {
            // pas warstwy (podąża za krzywizną)
            val band = Path()
            var d = 0.0
            band.moveTo(px(0.0), py(range.second))
            while (d <= maxD) { band.lineTo(px(d), py(range.second - drop(d))); d += 10.0 }
            d = maxD
            while (d >= 0.0) { band.lineTo(px(d), py(range.first - drop(d))); d -= 10.0 }
            band.close()
            drawPath(band, color.copy(alpha = 0.07f))

            // chmury w punktach próbkowania
            for (s in a.cross) {
                val cover = when (key) { 0 -> s.low; 1 -> s.mid; else -> s.high }
                val n = (cover * 7).roundToInt()
                repeat(n) {
                    val dd = (s.distKm + (rnd.nextFloat() - 0.5f) * 40.0).coerceIn(0.0, maxD)
                    val hh = range.first + (range.second - range.first) * (0.2 + 0.6 * rnd.nextFloat())
                    val cxp = px(dd)
                    val cyp = py(hh - drop(dd))
                    val ow = w * (0.035f + 0.03f * rnd.nextFloat())
                    drawOval(color.copy(alpha = if (key == 2) 0.35f else 0.7f), Offset(cxp - ow / 2, cyp - ow * 0.2f), Size(ow, ow * 0.4f))
                }
            }
        }

        // grunt (krzywizna Ziemi)
        val ground = Path().apply {
            moveTo(0f, h)
            var d = 0.0
            while (d <= maxD) { lineTo(px(d), py(-drop(d))); d += 5.0 }
            lineTo(w, h)
            close()
        }
        drawPath(ground, Color(0xFF05060F))
        drawPath(ground, Color(0x55FFFFFF), style = Stroke(1.5f))

        // promień słońca
        val e = a.evalElevationDeg
        val t = tan(Math.toRadians(e))
        val rayEnd = Offset(px(maxD), py(maxD * t))
        drawLine(Color(0xFFFFC857), Offset(px(0.0), py(0.0)), rayEnd, strokeWidth = 4f, cap = StrokeCap.Round)
        drawCircle(Color(0xFFFFF2B0), 9f, rayEnd)
        drawCircle(Color(0xFFFF8A3D), 7f, Offset(px(0.0), py(0.0)))

        // opisy
        paint.textSize = 10.sp.toPx()
        paint.color = android.graphics.Color.argb(220, 255, 255, 255)
        paint.textAlign = AndroidPaint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText("wysokie 6–12 km", 6f, py(9.0), paint)
        drawContext.canvas.nativeCanvas.drawText("średnie 2–6 km", 6f, py(4.0) - 28f, paint)
        drawContext.canvas.nativeCanvas.drawText("niskie 0–2 km", 6f, py(1.0) - 14f, paint)
        paint.textAlign = AndroidPaint.Align.CENTER
        for (dkm in listOf(100.0, 200.0)) {
            drawContext.canvas.nativeCanvas.drawText("${dkm.roundToInt()} km", px(dkm), h - 8f, paint)
        }
    }
}
