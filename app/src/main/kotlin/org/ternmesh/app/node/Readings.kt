// The node's battery and airtime over time, as this phone heard them. The node keeps no history:
// it sends POWER and AIRTIME when they change, no more often than every QUIET seconds, and once in
// each sync, and the records hold only the last of each. So the app keeps what it hears, stamped
// with the phone's clock, for the battery and airtime screens to draw.
//
// A reading the same as the one before is kept only once it is EVERY_MS after it, so that a value
// that holds still costs nothing; one that changes is kept at once. When the link ends, the last of
// each is kept again, marked as an end, so that a chart draws a value held up to then and no
// further: what the node did while the phone was away is not known. Readings older than KEPT_MS go.
//
// Nothing here touches Android; the repository stamps the time, and keeps the file.
package org.ternmesh.app.node

import org.ternmesh.companion.Body

/** A `POWER`, at [at] on the phone's clock (milliseconds since 1970); [end] if the link ended then. */
data class PowerReading(val at: Long, val millivolts: Int, val percent: Int, val flags: Int, val end: Boolean = false) {
    /** The node's estimate of the charge left, null when it has none. */
    val knownPercent: Int? get() = percent.takeIf { it in 0..100 }
    val charging: Boolean get() = flags and 1 != 0
    val external: Boolean get() = flags and 2 != 0

    fun sameAs(other: PowerReading) = millivolts == other.millivolts && percent == other.percent && flags == other.flags
}

/** An `AIRTIME`, at [at] on the phone's clock; [end] if the link ended then. */
data class AirtimeReading(val at: Long, val period: Long, val allowed: Long, val used: Long, val wait: Long, val end: Boolean = false) {
    /** How much of the period's allowance is used, 0 to 1; null for a profile with no limit. */
    val fraction: Float? get() = if (period == 0L) null else (used.toFloat() / allowed.coerceAtLeast(1)).coerceIn(0f, 1f)

    fun sameAs(other: AirtimeReading) = period == other.period && allowed == other.allowed && used == other.used && wait == other.wait
}

data class Readings(val power: List<PowerReading> = emptyList(), val airtime: List<AirtimeReading> = emptyList()) {

    /** These readings with [body], a `POWER` or an `AIRTIME`, heard at [now]; any other body changes nothing. */
    fun with(body: Body, now: Long): Readings = when (body) {
        is Body.Power -> {
            val r = PowerReading(now, body.millivolts, body.percent, body.flags)
            val last = power.lastOrNull()
            if (last != null && !last.end && last.sameAs(r) && now - last.at < EVERY_MS) this
            else copy(power = (power + r).trimmed(now) { it.at })
        }
        is Body.Airtime -> {
            val r = AirtimeReading(now, body.period, body.allowed, body.used, body.wait)
            val last = airtime.lastOrNull()
            if (last != null && !last.end && last.sameAs(r) && now - last.at < EVERY_MS) this
            else copy(airtime = (airtime + r).trimmed(now) { it.at })
        }
        else -> this
    }

    /** The link ended at [now]: each last reading is kept again, as an end. */
    fun ended(now: Long): Readings {
        val p = power.lastOrNull()?.takeIf { !it.end }?.let { power + it.copy(at = maxOf(now, it.at), end = true) } ?: power
        val a = airtime.lastOrNull()?.takeIf { !it.end }?.let { airtime + it.copy(at = maxOf(now, it.at), end = true) } ?: airtime
        return if (p === power && a === airtime) this else Readings(p.trimmed(now) { it.at }, a.trimmed(now) { it.at })
    }

    companion object {
        /** How long a reading that holds still goes before it is kept again: a chart's line goes on through it. */
        const val EVERY_MS = 5 * 60_000L

        /** How long a reading is kept. */
        const val KEPT_MS = 7 * 24 * 3600_000L

        /** The most of each kind kept, the newest. */
        const val MOST = 5000

        private const val HEADER = "tern-readings 1"

        private inline fun <T> List<T>.trimmed(now: Long, at: (T) -> Long): List<T> =
            dropWhile { now - at(it) > KEPT_MS }.takeLast(MOST)

        /** The readings as a file keeps them: a line for each, `p` or `a`, its time and its fields, and `e` for an end. */
        fun encode(r: Readings): ByteArray = buildString {
            appendLine(HEADER)
            for (p in r.power) appendLine("p ${p.at} ${p.millivolts} ${p.percent} ${p.flags}${if (p.end) " e" else ""}")
            for (a in r.airtime) appendLine("a ${a.at} ${a.period} ${a.allowed} ${a.used} ${a.wait}${if (a.end) " e" else ""}")
        }.toByteArray(Charsets.UTF_8)

        /** Readings as [encode] keeps them; a line that does not read is passed over, and a file that does not is none. */
        fun decode(bytes: ByteArray): Readings {
            val lines = String(bytes, Charsets.UTF_8).lines()
            if (lines.firstOrNull() != HEADER) return Readings()
            val power = mutableListOf<PowerReading>()
            val airtime = mutableListOf<AirtimeReading>()
            for (line in lines.drop(1)) {
                val f = line.split(' ')
                val end = f.lastOrNull() == "e"
                val n = (if (end) f.drop(1).dropLast(1) else f.drop(1)).map { it.toLongOrNull() ?: return@map null }
                if (n.any { it == null }) continue
                val v = n.filterNotNull()
                when {
                    f[0] == "p" && v.size == 4 -> power += PowerReading(v[0], v[1].toInt(), v[2].toInt(), v[3].toInt(), end)
                    f[0] == "a" && v.size == 5 -> airtime += AirtimeReading(v[0], v[1], v[2], v[3], v[4], end)
                }
            }
            return Readings(power.sortedBy { it.at }, airtime.sortedBy { it.at })
        }
    }
}
