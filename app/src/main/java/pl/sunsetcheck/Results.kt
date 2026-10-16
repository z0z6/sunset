package pl.sunsetcheck

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val HM = DateTimeFormatter.ofPattern("HH:mm").withZone(Solar.ZONE)
fun fmtTime(ms: Long): String = HM.format(Instant.ofEpochMilli(ms))
private val Dim = Color(0xFFB8C0E0)

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = SunYellow)
            content()
        }
    }
}

@Composable
fun Results(a: Analysis) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hero(a)

        SectionCard("Wskaźniki") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PercentGauge(a.score, "szansa", 92.dp, 8.dp)
                PercentGauge(a.confidencePct, "pewność", 92.dp, 8.dp)
                PercentGauge(a.colorPotential, "barwy", 92.dp, 8.dp)
                PercentGauge(((1 - a.avgLow) * 100).roundToInt().coerceIn(0, 100), "czyste niskie", 92.dp, 8.dp)
            }
            Text(
                "Szansa = prawdopodobieństwo widoczności tarczy słońca. Pewność = zgodność modeli i horyzont prognozy. " +
                    "Barwy = potencjał kolorowego nieba. Nigdy nie pokazujemy 100% – to prognoza, nie gwarancja.",
                color = Dim, fontSize = 12.sp
            )
        }

        SectionCard("Dlaczego nie ma „100%”?") {
            Text(
                "Prognoza chmur to zawsze prawdopodobieństwo. Wynik to średnia z ${a.models.size} modeli " +
                    "(rozrzut: ${a.scoreMin}–${a.scoreMax}), policzona dla linii widzenia w stronę słońca. " +
                    "Nie widzi on pojedynczych chmur, drzew ani budynków. Przed wyjściem sprawdź zdjęcia satelitarne/radar.",
                color = Dim, fontSize = 13.sp
            )
            a.reasons.forEach { Text("• $it", fontSize = 13.sp) }
        }

        SectionCard("Prognozy modeli") {
            a.models.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.name, modifier = Modifier.width(105.dp), fontSize = 13.sp)
                    LinearProgressIndicator(
                        progress = { m.probability.toFloat() },
                        modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = barColor(m.probability)
                    )
                    Text("${(m.probability * 100).roundToInt()}%", modifier = Modifier.width(40.dp), fontSize = 13.sp)
                }
            }
            if (a.failedModels.isNotEmpty()) {
                Text("Niedostępne: ${a.failedModels.joinToString()}", color = Dim, fontSize = 12.sp)
            }
        }

        SectionCard("Zachmurzenie na linii widzenia") {
            LayerBar("Niskie (0–2 km)", a.avgLow)
            LayerBar("Średnie (2–6 km)", a.avgMid)
            LayerBar("Wysokie (6–12 km)", a.avgHigh)
            Text("Potencjał barw nieba: ${a.colorPotential}/100", color = Dim, fontSize = 13.sp)
        }

        SectionCard("Kopuła nieba 3D") {
            Text("Przeciągnij, aby obracać. Suwak przesuwa Słońce w ciągu dnia.", color = Dim, fontSize = 12.sp)
            SkyDome(a)
        }

        SectionCard("Przekrój wzdłuż promienia słońca") {
            Text("Krzywizna Ziemi i warstwy chmur (skala przesadzona w pionie).", color = Dim, fontSize = 12.sp)
            CrossSection(a)
        }

        SectionCard("Szczegóły") {
            Text("${a.event.label.cap()}: ${fmtTime(a.eventEpochMs)}, azymut ${a.eventAzimuth.roundToInt()}°", fontSize = 13.sp)
            Text(
                "Horyzont terenowy: ${"%.1f".format(a.horizonDeg)}°" +
                    (a.observerElevationM?.let { ", wysokość punktu: ${it.roundToInt()} m n.p.m." } ?: ""),
                fontSize = 13.sp
            )
            Text("Czas do zachodu: ${a.leadHours.roundToInt()} h", fontSize = 13.sp)
            Text(
                "Dane: Open-Meteo (ECMWF, ICON, GFS, Météo-France), mapa © OpenStreetMap. " +
                    "Teren: model 90 m – bez budynków i drzew.",
                color = Dim, fontSize = 11.sp
            )
        }
    }
}

private fun barColor(p: Double): Color = when {
    p >= 0.7 -> Color(0xFF7BD88F)
    p >= 0.4 -> Color(0xFFFFB300)
    else -> Color(0xFFFF6B4A)
}

@Composable
private fun LayerBar(label: String, v: Double) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.width(125.dp), fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { v.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Color(0xFF9FB4FF)
        )
        Text("${(v * 100).roundToInt()}%", modifier = Modifier.width(40.dp), fontSize = 13.sp)
    }
}

@Composable
fun PercentGauge(percent: Int, label: String, diameter: Dp, stroke: Dp, big: Boolean = false) {
    val anim by animateFloatAsState(percent / 100f, tween(900), label = "gauge")
    val col = barColor(percent / 100.0)
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            val tl = Offset(sw / 2, sw / 2)
            drawArc(Color(0x33FFFFFF), 135f, 270f, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(col, 135f, 270f * anim, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$percent%",
                style = TextStyle(fontSize = if (big) 30.sp else 16.sp, fontWeight = FontWeight.Black, color = Color.White)
            )
            Text(label, style = TextStyle(fontSize = if (big) 11.sp else 10.sp, color = Color(0xFFD5DAF0)))
        }
    }
}

private fun String.cap() = replaceFirstChar { it.uppercase() }

@Composable
private fun Hero(a: Analysis) {
    Box(Modifier.fillMaxWidth().height(270.dp).clip(RoundedCornerShape(24.dp))) {
        SunsetScene(a, Modifier.fillMaxSize())
        val shadow = Shadow(Color.Black, blurRadius = 10f)
        Box(
            Modifier.align(Alignment.TopStart).padding(14.dp)
                .background(Color(0x66000000), CircleShape).padding(6.dp)
        ) { PercentGauge(a.score, "szansa", 112.dp, 10.dp, big = true) }
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(a.verdict, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, shadow = shadow))
            Text(
                "${a.event.label.cap()} ${fmtTime(a.eventEpochMs)} • azymut ${a.eventAzimuth.roundToInt()}° • pewność prognozy: ${a.confidence.label}",
                style = TextStyle(fontSize = 12.sp, color = Color.White, shadow = shadow)
            )
        }
    }
}
