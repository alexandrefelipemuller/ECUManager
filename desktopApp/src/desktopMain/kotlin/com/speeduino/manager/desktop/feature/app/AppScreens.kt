package com.speeduino.manager.desktop.feature.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speeduino.manager.desktop.ConnectionState
import com.speeduino.manager.desktop.DesktopSpeeduinoController
import io.ecucore.EcuLiveData
import io.ecucore.shared.formatDecimal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Apex" dashboard theme, ported from the mobile apps (Android ApexDashboard / iOS
 * IosApexDashboardScreen): flat motorsport layout with big kicker+value tiles, an analog
 * tachometer with digital RPM hub, an AFR bar, shift LEDs and status chips. Rearranged into a
 * fixed landscape split (gauge column + tile column) instead of the mobile single-column
 * portrait stack, to fit the kiosk's 800x480 screen without scrolling.
 */
private val APEX_BG = Color(0xFFF3F2F2)
private val APEX_PANEL_BG = Color(0xFFF7F6F5)
private val APEX_TEXT_PRIMARY = Color(0xFF201E1D)
private val APEX_TEXT_SECONDARY = Color(0xFF7D7979)
private val APEX_NEUTRAL_200 = Color(0xFFEAE7E7)
private val APEX_ACCENT = Color(0xFFEC3013)
private val APEX_ACCENT_700 = Color(0xFFAE1800)
private val APEX_RICH = Color(0xFF1F8A6E)
private val APEX_WARN = Color(0xFFD1A52A)
private val APEX_DARK_TOP = Color(0xFF2C2926)
private val APEX_DARK_BOTTOM = Color(0xFF191715)
private val APEX_GAUGE_BEZEL = Color(0xFF201E1D)
private val APEX_GAUGE_FACE = Color(0xFFEFEDEB)

@Composable
internal fun DashboardScreen(
    controller: DesktopSpeeduinoController,
    liveData: EcuLiveData?,
    connectionState: ConnectionState,
    onOpenSettings: () -> Unit
) {
    val importedId = controller.desktopSettings.collectAsState().value.importedDashboardId
    val store = remember { com.speeduino.manager.desktop.realdash.ImportedDashboardStore() }
    val imported = remember(importedId) { importedId?.let(store::load) }
    if (imported != null) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0C))) {
            com.speeduino.manager.desktop.realdash.ImportedDashboardView(
                dash = imported,
                assetKey = importedId.orEmpty(),
                liveData = liveData,
                modifier = Modifier.fillMaxSize().padding(10.dp),
            )
        }
    } else {
        BuiltInDashboardScreen(controller, liveData, connectionState, onOpenSettings)
    }
}

@Composable
private fun BuiltInDashboardScreen(
    controller: DesktopSpeeduinoController,
    liveData: EcuLiveData?,
    connectionState: ConnectionState,
    onOpenSettings: () -> Unit
) {
    val settings by controller.desktopSettings.collectAsState()
    val shiftLightRpm = settings.shiftLightRpm
    val maxRpm = (shiftLightRpm + 500).coerceAtLeast(7000)

    val isConnected = connectionState.isConnected
    val rpm = liveData?.rpm ?: 0
    val speedKph = liveData?.candidateSpeedKph ?: 0
    val gear = liveData?.candidateGear
    val mapKpa = liveData?.mapPressure ?: 100
    val tps = liveData?.tps ?: 0
    val coolant = liveData?.coolantTemp ?: 0
    val intake = liveData?.intakeTemp ?: 0
    val battery = liveData?.batteryVoltage ?: 0.0
    val afr = (liveData?.o2 ?: 147) / 10.0

    val revLimit = rpm >= shiftLightRpm
    val warmup = coolant in 1 until 80
    val closedLoop = isConnected && !warmup && tps < 55
    val boostRatio = (((mapKpa - 100).coerceAtLeast(0)) / 60f).coerceIn(0f, 1f)

    Box(modifier = Modifier.fillMaxSize().background(APEX_BG)) {
        Row(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ApexTachometer(
                    rpm = rpm,
                    maxRpm = maxRpm,
                    modifier = Modifier.weight(1f).fillMaxWidth().aspectRatio(1f)
                )
                ApexShiftLedRow(
                    rpm = rpm,
                    shiftLightRpm = shiftLightRpm,
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
                ApexAfrPanel(afr = afr)
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApexFieldTile("SPEED", speedKph.toString(), "KM/H", modifier = Modifier.weight(1f))
                    ApexGearTile(gear = gear, revLimit = revLimit, modifier = Modifier.weight(0.6f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApexFieldTile(
                        "BOOST",
                        ((mapKpa - 100).coerceAtLeast(0)).toString(),
                        "kPa",
                        showProgressBar = true,
                        progressRatio = boostRatio,
                        modifier = Modifier.weight(1f)
                    )
                    ApexFieldTile("TPS", tps.toString(), "%", modifier = Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ApexMiniTempTile("COOLANT", "$coolant°C", modifier = Modifier.weight(1f))
                    ApexMiniTempTile("INTAKE", "$intake°C", modifier = Modifier.weight(1f))
                    ApexMiniTempTile("BATTERY", formatDecimal(battery, 1) + "V", modifier = Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ApexStatusChip("WARMUP", on = warmup)
                    ApexStatusChip("CLOSED LOOP", on = closedLoop)
                    ApexStatusChip("REV LIMIT", on = revLimit, alarm = true)
                }
                if (!isConnected) {
                    Text(
                        text = "Conecte-se a uma ECU para ver dados ao vivo.",
                        color = APEX_TEXT_SECONDARY,
                        fontSize = 9.sp
                    )
                }
            }
        }

        IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd).size(26.dp)) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = APEX_TEXT_SECONDARY,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun ApexFieldTile(
    title: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    showProgressBar: Boolean = false,
    progressRatio: Float = 0f,
) {
    Column(
        modifier = modifier.background(APEX_PANEL_BG).padding(top = 4.dp, bottom = 3.dp, start = 6.dp, end = 6.dp),
    ) {
        Text(text = title, color = APEX_ACCENT_700, fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 1.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 1.dp)) {
            Text(text = value, color = APEX_TEXT_PRIMARY, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 1)
            Spacer(Modifier.width(3.dp))
            Text(text = unit, color = APEX_TEXT_SECONDARY, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
        }
        if (showProgressBar) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(5.dp).clip(RoundedCornerShape(50)).background(APEX_NEUTRAL_200),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(progressRatio.coerceIn(0f, 1f)).fillMaxSize()
                        .background(Brush.horizontalGradient(colors = listOf(APEX_ACCENT_700, APEX_ACCENT))),
                )
            }
        }
    }
}

@Composable
private fun ApexGearTile(gear: Int?, revLimit: Boolean, modifier: Modifier = Modifier) {
    val gearText = if (gear == null || gear == 0) "N" else gear.toString()
    val gearColor = if (revLimit) APEX_ACCENT else APEX_TEXT_PRIMARY
    Column(
        modifier = modifier.background(APEX_PANEL_BG).padding(top = 3.dp, end = 4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(text = "GEAR", color = APEX_TEXT_SECONDARY, fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 1.sp)
        Text(text = gearText, color = gearColor, fontWeight = FontWeight.Black, fontSize = 34.sp, maxLines = 1)
    }
}

@Composable
private fun ApexShiftLedRow(rpm: Int, shiftLightRpm: Int, modifier: Modifier = Modifier) {
    val revLimit = rpm >= shiftLightRpm
    val ledCount = 14
    val window = 2200
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)) {
        repeat(ledCount) { index ->
            val color = when {
                index < 6 -> APEX_RICH
                index < 10 -> APEX_WARN
                else -> APEX_ACCENT
            }
            val threshold = shiftLightRpm - window + (index + 1) * (window / ledCount)
            val on = revLimit || rpm >= threshold
            Box(
                modifier = Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(2.dp))
                    .background(if (on) color else APEX_NEUTRAL_200),
            )
        }
    }
}

@Composable
private fun ApexAfrPanel(afr: Double) {
    val minAfr = 10f
    val maxAfr = 20f
    val ratio = (((afr.toFloat() - minAfr) / (maxAfr - minAfr))).coerceIn(0f, 1f)
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(APEX_PANEL_BG).padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(text = "AFR", color = APEX_ACCENT_700, fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 1.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = formatDecimal(afr, 1), color = APEX_TEXT_PRIMARY, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Spacer(Modifier.width(4.dp))
                Text(text = "λ " + formatDecimal(afr / 14.7, 2), color = APEX_TEXT_SECONDARY, fontWeight = FontWeight.SemiBold, fontSize = 8.sp)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(7.dp).clip(RoundedCornerShape(50))) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        0.0f to APEX_RICH,
                        0.30f to Color(0xFF4EA23E),
                        0.58f to APEX_WARN,
                        1.0f to APEX_ACCENT,
                    ),
                )
                val stoichX = size.width * 0.4875f
                drawLine(color = Color.White.copy(alpha = 0.85f), start = Offset(stoichX, 0f), end = Offset(stoichX, size.height), strokeWidth = 2f)
                val markerX = size.width * ratio
                drawLine(
                    color = APEX_TEXT_PRIMARY,
                    start = Offset(markerX, -size.height * 0.25f),
                    end = Offset(markerX, size.height * 1.25f),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun ApexMiniTempTile(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(APEX_PANEL_BG).padding(top = 4.dp, end = 3.dp, start = 4.dp)) {
        Text(text = title, color = APEX_TEXT_SECONDARY, fontWeight = FontWeight.Bold, fontSize = 7.sp, letterSpacing = 0.6.sp, maxLines = 1)
        Text(text = value, color = APEX_TEXT_PRIMARY, fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun ApexStatusChip(label: String, on: Boolean, alarm: Boolean = false) {
    val background = when {
        !on -> Color.Transparent
        alarm -> APEX_ACCENT
        else -> APEX_TEXT_PRIMARY
    }
    val foreground = if (on) Color.White else APEX_TEXT_SECONDARY
    Box(
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(background).padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(text = label, color = foreground, fontWeight = FontWeight.Bold, fontSize = 7.sp, letterSpacing = 0.6.sp, maxLines = 1)
    }
}

@Composable
private fun ApexTachometer(rpm: Int, maxRpm: Int, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val radius = minOf(size.width, size.height) * 0.46f
        val startAngle = 145f
        val sweep = 250f
        val rpmRatio = (rpm.toFloat() / maxRpm).coerceIn(0f, 1f)
        val needleAngle = startAngle + sweep * rpmRatio
        val faceRadius = radius * 0.96f
        val redlineStartFraction = 0.786f
        val redStartAngle = startAngle + sweep * redlineStartFraction
        val redSweep = sweep * (1f - redlineStartFraction)

        drawCircle(color = APEX_GAUGE_BEZEL, radius = radius, center = center)
        drawCircle(color = APEX_GAUGE_FACE, radius = faceRadius, center = center)

        val redRingOuter = faceRadius
        val redRingInner = radius * 0.62f
        val redRingWidth = redRingOuter - redRingInner
        val redRingCenterRadius = (redRingOuter + redRingInner) / 2f
        drawArc(
            color = APEX_ACCENT,
            startAngle = redStartAngle,
            sweepAngle = redSweep,
            useCenter = false,
            topLeft = Offset(center.x - redRingCenterRadius, center.y - redRingCenterRadius),
            size = Size(redRingCenterRadius * 2f, redRingCenterRadius * 2f),
            style = Stroke(width = redRingWidth, cap = StrokeCap.Butt),
        )

        val majorTicks = 7
        val minorPerStep = 5
        val totalSteps = majorTicks * minorPerStep
        repeat(totalSteps + 1) { idx ->
            val angleDeg = startAngle + idx * (sweep / totalSteps)
            val angleRad = angleDeg * PI / 180.0
            val inRed = angleDeg >= redStartAngle
            val isMajor = idx % minorPerStep == 0
            val outer = faceRadius * 0.92f
            val inner = if (isMajor) outer - radius * 0.10f else outer - radius * 0.05f
            drawLine(
                color = if (inRed) Color.White else APEX_TEXT_PRIMARY,
                start = Offset(center.x + cos(angleRad).toFloat() * inner, center.y + sin(angleRad).toFloat() * inner),
                end = Offset(center.x + cos(angleRad).toFloat() * outer, center.y + sin(angleRad).toFloat() * outer),
                strokeWidth = if (isMajor) radius * 0.02f else radius * 0.01f,
                cap = StrokeCap.Round,
            )
        }

        (1..majorTicks).forEach { n ->
            val angleDeg = startAngle + n * (sweep / majorTicks)
            val angleRad = angleDeg * PI / 180.0
            val labelRadius = faceRadius * 0.72f
            val lx = center.x + cos(angleRad).toFloat() * labelRadius
            val ly = center.y + sin(angleRad).toFloat() * labelRadius
            val inRed = angleDeg >= redStartAngle
            val measured = textMeasurer.measure(
                text = n.toString(),
                style = TextStyle(color = if (inRed) Color.White else APEX_TEXT_PRIMARY, fontSize = (radius.toSp() * 0.145f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            )
            drawText(textLayoutResult = measured, topLeft = Offset(lx - measured.size.width / 2f, ly - measured.size.height / 2f))
        }

        val discRadius = radius * 0.48f
        drawCircle(color = APEX_DARK_TOP, radius = discRadius, center = center)
        drawCircle(color = APEX_DARK_BOTTOM.copy(alpha = 0.4f), radius = discRadius, center = center, style = Stroke(width = radius * 0.01f))

        val rpmMeasured = textMeasurer.measure(
            text = rpm.toString(),
            style = TextStyle(color = Color.White, fontSize = (radius.toSp() * 0.22f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        )
        drawText(textLayoutResult = rpmMeasured, topLeft = Offset(center.x - rpmMeasured.size.width / 2f, center.y - rpmMeasured.size.height / 2f + discRadius * 0.03f))
        val rpmUnitMeasured = textMeasurer.measure(
            text = "RPM",
            style = TextStyle(color = Color(0xFFB9B4AE), fontSize = (radius.toSp() * 0.075f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        )
        drawText(textLayoutResult = rpmUnitMeasured, topLeft = Offset(center.x - rpmUnitMeasured.size.width / 2f, center.y + discRadius * 0.32f))

        val needleRad = needleAngle * PI / 180.0
        val dirX = cos(needleRad).toFloat()
        val dirY = sin(needleRad).toFloat()
        val perpX = -dirY
        val perpY = dirX
        val tailBase = Offset(center.x + dirX * discRadius * 0.94f, center.y + dirY * discRadius * 0.94f)
        val tip = Offset(center.x + dirX * faceRadius * 0.88f, center.y + dirY * faceRadius * 0.88f)
        val tailHalf = radius * 0.028f
        val tipHalf = radius * 0.008f
        val needlePath = Path().apply {
            moveTo(tailBase.x + perpX * tailHalf, tailBase.y + perpY * tailHalf)
            lineTo(tip.x + perpX * tipHalf, tip.y + perpY * tipHalf)
            lineTo(tip.x - perpX * tipHalf, tip.y - perpY * tipHalf)
            lineTo(tailBase.x - perpX * tailHalf, tailBase.y - perpY * tailHalf)
            close()
        }
        drawPath(path = needlePath, color = APEX_ACCENT)
    }
}

private fun Float.toSp() = TextUnit(this, TextUnitType.Sp)
