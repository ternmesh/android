// What the dashboard's battery and airtime meters open: each as it is now, and as this phone heard
// it over the last hour, day or week, drawn as a line that holds each value until the next. The
// node keeps no history, so the line has a gap wherever the phone was not connected.
package org.ternmesh.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.ternmesh.app.R
import org.ternmesh.app.node.AirtimeReading
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Phase
import kotlin.math.roundToInt

/** How far back a chart looks. */
enum class ReadingSpan(val ms: Long, val label: Int, val short: Int) {
    HOUR(3600_000L, R.string.reading_span_hour, R.string.reading_span_hour_short),
    SIX_HOURS(6 * 3600_000L, R.string.reading_span_six_hours, R.string.reading_span_six_hours_short),
    DAY(24 * 3600_000L, R.string.reading_span_day, R.string.reading_span_day_short),
    WEEK(7 * 24 * 3600_000L, R.string.reading_span_week, R.string.reading_span_week_short),
}

/** One point of a chart: [value] from [at] until the next, or, if [end], nothing after it. */
data class ChartPoint(val at: Long, val value: Float, val end: Boolean)

/**
 * The points of [points] that a chart from [from] needs: those after it, and the one before it,
 * which holds at its start. One that ended before [from] holds nothing.
 */
internal fun inSpan(points: List<ChartPoint>, from: Long): List<ChartPoint> {
    val firstIn = points.indexOfFirst { it.at >= from }.let { if (it < 0) points.size else it }
    val before = points.getOrNull(firstIn - 1)?.takeIf { !it.end }?.copy(at = from)
    return listOfNotNull(before) + points.drop(firstIn)
}

/** The phone's clock, moving on each half minute while a screen shows it, so a chart's right edge is now. */
@Composable
private fun rememberWallClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun SpanChips(span: ReadingSpan, choose: (ReadingSpan) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReadingSpan.entries.forEach { s ->
            FilterChip(selected = s == span, onClick = { choose(s) }, label = { Text(stringResource(s.short), maxLines = 1) })
        }
    }
}

/** The big figure at the head of a readings screen, on the dashboard's own colours. */
@Composable
private fun ReadingHead(value: String, line: String?, sub: String?) {
    val extra = LocalTernColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(extra.heroTop, extra.heroBottom)))
            .padding(18.dp),
    ) {
        Text(value, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold), color = extra.onHero)
        line?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = extra.onHero.copy(alpha = 0.9f)) }
        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = extra.onHero.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp)) }
    }
}

/**
 * A line through [points] from [from] to [to], each value held until the next, broken after an
 * end; the area under it shaded. [lo] to [hi] is the height, [guides] are faint lines across it,
 * and [limit] a dashed one, each labelled by [label].
 */
@Composable
fun ReadingsChart(
    points: List<ChartPoint>,
    from: Long,
    to: Long,
    lo: Float,
    hi: Float,
    color: Color,
    label: (Float) -> String,
    description: String,
    guides: List<Float> = emptyList(),
    limit: Float? = null,
    limitColor: Color = MaterialTheme.colorScheme.error,
) {
    val context = LocalContext.current
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.labelSmall
    SettingsGroup {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.height(160.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(label(hi), style = small, color = ink)
                    Text(label((hi + lo) / 2), style = small, color = ink)
                    Text(label(lo), style = small, color = ink)
                }
                Canvas(Modifier.weight(1f).height(160.dp).semantics { contentDescription = description }) {
                    val w = size.width
                    val h = size.height
                    fun x(t: Long) = ((t - from).toFloat() / (to - from).coerceAtLeast(1)) * w
                    fun y(v: Float) = h - ((v - lo) / (hi - lo).coerceAtLeast(1e-6f)).coerceIn(0f, 1f) * h
                    // A line at the top, the middle and the bottom, then the screen's own.
                    for (g in listOf(hi, (hi + lo) / 2, lo) + guides) {
                        drawLine(grid, Offset(0f, y(g)), Offset(w, y(g)), strokeWidth = 1.dp.toPx())
                    }
                    limit?.let {
                        drawLine(
                            limitColor.copy(alpha = 0.7f), Offset(0f, y(it)), Offset(w, y(it)), strokeWidth = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                        )
                    }
                    // Each run of points without an end is one stretch of line, held to the next point or to [to].
                    var run = mutableListOf<ChartPoint>()
                    val runs = mutableListOf<List<ChartPoint>>()
                    for (p in points) {
                        run += p
                        if (p.end) {
                            runs += run
                            run = mutableListOf()
                        }
                    }
                    if (run.isNotEmpty()) runs += run + run.last().copy(at = to, end = true)
                    for (r in runs) {
                        if (r.isEmpty()) continue
                        val line = Path()
                        line.moveTo(x(r.first().at), y(r.first().value))
                        for (i in 1 until r.size) {
                            line.lineTo(x(r[i].at), y(r[i - 1].value))
                            line.lineTo(x(r[i].at), y(r[i].value))
                        }
                        val area = Path().apply {
                            addPath(line)
                            lineTo(x(r.last().at), h)
                            lineTo(x(r.first().at), h)
                            close()
                        }
                        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f))))
                        drawPath(line, color, style = Stroke(width = 2.5.dp.toPx()))
                        if (r.size == 2 && r[0].at == r[1].at) drawCircle(color, 3.dp.toPx(), Offset(x(r[0].at), y(r[0].value)))
                    }
                }
            }
            val flags = if (to - from > 24 * 3600_000L) DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
            else DateUtils.FORMAT_SHOW_TIME
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(DateUtils.formatDateTime(context, from, flags), style = small, color = ink)
                Text(stringResource(R.string.reading_now), style = small, color = ink)
            }
        }
    }
}

/** How long [seconds] is, said as briefly as is exact: "1 h", "10 min", "36 s". */
internal fun periodText(seconds: Long): String = when {
    seconds >= 3600 && seconds % 3600 == 0L -> "${seconds / 3600} h"
    seconds >= 60 && seconds % 60 == 0L -> "${seconds / 60} min"
    else -> "$seconds s"
}

/** When the reading at [at] was heard: up to date while the node is connected, or how long ago. */
@Composable
private fun heardText(state: NodeState, at: Long?, now: Long): String = when {
    at == null -> stringResource(R.string.unknown)
    state.phase == Phase.READY -> stringResource(R.string.reading_live)
    else -> stringResource(R.string.ago, spanText(((now - at) / 1000).coerceAtLeast(0)))
}

/** The node's battery: now, and over time. */
@Composable
fun BatteryScreen(state: NodeState, back: () -> Unit) {
    val extra = LocalTernColors.current
    val now = rememberWallClock()
    var span by rememberSaveable { mutableStateOf(ReadingSpan.DAY) }
    val p = state.records.power
    val readings = state.readings.power
    // Charge where the node estimates it; its voltage where it only measures that.
    val hasPercent = p?.percent?.let { it in 0..100 } ?: readings.any { it.knownPercent != null }
    var voltage by rememberSaveable { mutableStateOf(false) }
    val showVolts = voltage || !hasPercent
    val hasVolts = (p?.millivolts ?: 0) > 0 || readings.any { it.millivolts > 0 }

    NodeSubScreen(stringResource(R.string.battery), back) {
        val source = when {
            p == null -> null
            p.flags and 1 != 0 -> stringResource(R.string.charging)
            p.flags and 2 != 0 -> stringResource(R.string.external_power)
            else -> stringResource(R.string.on_battery)
        }
        ReadingHead(
            value = when {
                p == null -> stringResource(R.string.unknown)
                p.percent in 0..100 -> "${p.percent}%"
                p.millivolts > 0 -> stringResource(R.string.battery_volts, p.millivolts / 1000.0)
                else -> stringResource(R.string.unknown)
            },
            line = source,
            sub = p?.takeIf { it.percent in 0..100 && it.millivolts > 0 }?.let { stringResource(R.string.battery_volts, it.millivolts / 1000.0) },
        )

        Row(Modifier.padding(top = 12.dp)) { SpanChips(span) { span = it } }
        val from = now - span.ms
        val all = readings.mapNotNull { r ->
            val v = if (showVolts) r.millivolts.takeIf { it > 0 }?.let { it / 1000f } else r.knownPercent?.toFloat()
            v?.let { ChartPoint(r.at, it, r.end) }
        }
        val points = inSpan(all, from)
        if (hasPercent && hasVolts) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showVolts, onClick = { voltage = false }, label = { Text(stringResource(R.string.charge)) })
                FilterChip(selected = showVolts, onClick = { voltage = true }, label = { Text(stringResource(R.string.voltage)) })
            }
        }
        SectionHeader(stringResource(if (showVolts) R.string.voltage else R.string.charge))
        if (points.isEmpty()) {
            Footnote(stringResource(R.string.reading_none))
        } else {
            // Volts: a little room either side of what was heard. Charge: the whole of 0 to 100.
            val (lo, hi) = if (showVolts) {
                val vs = points.map { it.value }
                val pad = maxOf(0.05f, (vs.max() - vs.min()) * 0.15f)
                (vs.min() - pad) to (vs.max() + pad)
            } else 0f to 100f
            val latest = points.last().value
            ReadingsChart(
                points, from, now, lo, hi,
                color = if (!showVolts && latest < 20f) extra.warn else extra.good,
                label = { if (showVolts) "%.2f V".format(it) else "${it.roundToInt()}%" },
                description = stringResource(R.string.battery_chart_described, stringResource(span.label)),
                guides = if (showVolts) emptyList() else listOf(20f),
            )
            val vs = points.map { it.value }
            fun shown(v: Float) = if (showVolts) "%.2f V".format(v) else "${v.roundToInt()}%"
            SectionHeader(stringResource(R.string.reading_over, stringResource(span.label)))
            SettingsGroup {
                SettingsRow(stringResource(R.string.reading_lowest), value = shown(vs.min()))
                GroupDivider()
                SettingsRow(stringResource(R.string.reading_highest), value = shown(vs.max()))
                GroupDivider()
                val change = vs.last() - vs.first()
                SettingsRow(
                    stringResource(R.string.reading_change),
                    value = (if (change > 0) "+" else if (change < 0) "−" else "") + shown(kotlin.math.abs(change)),
                )
            }
        }

        SectionHeader(stringResource(R.string.reading_now_header))
        SettingsGroup {
            SettingsRow(stringResource(R.string.charge), value = p?.percent?.takeIf { it in 0..100 }?.let { "$it%" } ?: stringResource(R.string.unknown))
            GroupDivider()
            SettingsRow(
                stringResource(R.string.voltage),
                value = p?.millivolts?.takeIf { it > 0 }?.let { stringResource(R.string.battery_volts, it / 1000.0) } ?: stringResource(R.string.unknown),
            )
            GroupDivider()
            SettingsRow(stringResource(R.string.power_source), value = source ?: stringResource(R.string.unknown))
            GroupDivider()
            SettingsRow(stringResource(R.string.reading_heard), value = heardText(state, readings.lastOrNull()?.at, now))
        }
        Footnote(stringResource(R.string.battery_readings_explained), Modifier.padding(top = 8.dp))
    }
}

/** The node's airtime: how much of the region's limit it has used, now and over time. */
@Composable
fun AirtimeScreen(state: NodeState, back: () -> Unit) {
    val extra = LocalTernColors.current
    val now = rememberWallClock()
    var span by rememberSaveable { mutableStateOf(ReadingSpan.DAY) }
    val a = state.records.airtime
    val readings = state.readings.airtime

    NodeSubScreen(stringResource(R.string.airtime_label), back) {
        ReadingHead(
            value = when {
                a == null -> stringResource(R.string.unknown)
                a.period == 0L -> stringResource(R.string.airtime_no_limit_short)
                else -> "${(100 * a.used / a.allowed.coerceAtLeast(1)).coerceIn(0, 100)}%"
            },
            line = when {
                a == null -> null
                a.period == 0L -> stringResource(R.string.airtime_unlimited)
                else -> stringResource(R.string.airtime_value, a.used / 1000.0, a.allowed / 1000.0, periodText(a.period))
            },
            sub = a?.takeIf { it.wait > 0 }?.let { stringResource(R.string.airtime_wait, it.wait / 1000.0) },
        )

        if (a?.period != 0L) {
            Row(Modifier.padding(top = 12.dp)) { SpanChips(span) { span = it } }
            val from = now - span.ms
            val points = inSpan(readings.mapNotNull { r -> r.fraction?.let { ChartPoint(r.at, it * 100f, r.end) } }, from)
            SectionHeader(stringResource(R.string.airtime_used_header))
            if (points.isEmpty()) {
                Footnote(stringResource(R.string.reading_none))
            } else {
                val peak = points.maxOf { it.value }
                ReadingsChart(
                    points, from, now, 0f, 100f,
                    color = if (peak >= 90f) extra.warn else MaterialTheme.colorScheme.primary,
                    label = { "${it.roundToInt()}%" },
                    description = stringResource(R.string.airtime_chart_described, stringResource(span.label)),
                    limit = 100f,
                )
                SectionHeader(stringResource(R.string.reading_over, stringResource(span.label)))
                SettingsGroup {
                    SettingsRow(stringResource(R.string.airtime_peak), value = "${peak.roundToInt()}%")
                    GroupDivider()
                    SettingsRow(
                        stringResource(R.string.airtime_waited),
                        value = readingsWaited(readings, from).let { if (it == 0) stringResource(R.string.airtime_never_waited) else it.toString() },
                    )
                }
            }
        }

        SectionHeader(stringResource(R.string.reading_now_header))
        SettingsGroup {
            if (a != null && a.period != 0L) {
                SettingsRow(stringResource(R.string.airtime_allowed), value = stringResource(R.string.airtime_allowed_value, a.allowed / 1000.0, periodText(a.period)))
                GroupDivider()
                SettingsRow(stringResource(R.string.airtime_used), value = stringResource(R.string.seconds_value, a.used / 1000.0))
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.airtime_next_send),
                    value = if (a.wait > 0) stringResource(R.string.airtime_next_send_in, a.wait / 1000.0) else stringResource(R.string.airtime_next_send_now),
                    valueColor = if (a.wait > 0) extra.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GroupDivider()
            }
            SettingsRow(stringResource(R.string.reading_heard), value = heardText(state, readings.lastOrNull()?.at, now))
        }
        Footnote(stringResource(R.string.airtime_explained), Modifier.padding(top = 8.dp))
        Footnote(stringResource(R.string.airtime_readings_explained))
    }
}

/** How many times since [from] the node said it had to wait to send: each run of readings with a wait, once. */
internal fun readingsWaited(readings: List<AirtimeReading>, from: Long): Int {
    var count = 0
    var waiting = false
    for (r in readings) {
        if (r.at < from) continue
        if (r.end) {
            waiting = false
            continue
        }
        if (r.wait > 0 && !waiting) count++
        waiting = r.wait > 0
    }
    return count
}
