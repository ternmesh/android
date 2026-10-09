// The firmware the project publishes, as its manifest at ternmesh.org/firmware/latest.json lists it,
// and which of it a node should run: the image for the node's board and region, and whether its
// release is newer than the one the node's INFO gives.
//
// The manifest is a contract of the site's, not of the specification, which says only that a client
// finds an image by a node's board and release:
//
//   {"release": "0.2.0",
//    "images": [{"board": "heltec-v3", "region": "EU868", "file": "tern-heltec-v3-eu868-0.2.0-app.bin",
//                "size": 1048576, "sha256": "<64 hex digits>"}]}
//
// A file is named relative to the manifest. Keys this client does not know are ignored, and so is an
// image it cannot read, so the manifest can grow without leaving this client behind.
package org.ternmesh.companion

import java.math.BigInteger
import java.util.regex.Pattern

/** One release's manifest. */
data class Manifest(val release: String, val images: List<Image>) {
    /** One image: for one board, built for one region. */
    data class Image(val board: String, val region: String, val file: String, val size: Long, val sha256: String) {
        /** Where it is published. */
        val url: String get() = BASE + file

        /** The digest the bytes downloaded must have, and the node checks them against. */
        val digest: ByteArray get() = Hex.decode(sha256)!!
    }

    /** The image for a node of [board] whose region is [region], ignoring case; null if there is none. */
    fun imageFor(board: String, region: String): Image? =
        images.firstOrNull { it.board.equals(board, ignoreCase = true) && it.region.equals(region, ignoreCase = true) }

    /** What this release is to a node running [running], its `INFO`'s `release`. */
    fun offerTo(running: String?): Offer {
        val node = running?.let(SemVer::parse) ?: return Offer.UNKNOWN
        return if (SemVer.parse(release)!! > node) Offer.NEWER else Offer.CURRENT
    }

    companion object {
        const val BASE = "https://ternmesh.org/firmware/"
        const val LATEST = BASE + "latest.json"

        /** A file name, which cannot lead out of [BASE]. */
        private val FILE = Regex("[A-Za-z0-9_][A-Za-z0-9._-]*")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")

        /** Reads a manifest, or throws IllegalArgumentException if it is not one. */
        fun parse(text: String): Manifest {
            val root = JsonText.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("not a JSON object")
            val release = root["release"] as? String ?: throw IllegalArgumentException("no release")
            require(SemVer.parse(release) != null) { "release $release is not a semantic version" }
            val images = (root["images"] as? List<*> ?: throw IllegalArgumentException("no images")).mapNotNull { e ->
                val o = e as? Map<*, *> ?: return@mapNotNull null
                val board = o["board"] as? String ?: return@mapNotNull null
                val region = o["region"] as? String ?: return@mapNotNull null
                val file = (o["file"] as? String)?.takeIf(FILE::matches) ?: return@mapNotNull null
                val size = (o["size"] as? Long)?.takeIf { it in 1..0xFFFF_FFFFL } ?: return@mapNotNull null
                val sha256 = (o["sha256"] as? String)?.takeIf(SHA256::matches)?.lowercase() ?: return@mapNotNull null
                Image(board, region, file, size, sha256)
            }
            return Manifest(release, images)
        }
    }
}

/** What a published release is to the firmware a node runs. */
enum class Offer {
    /** Later than the node's. */
    NEWER,

    /** The node's, or earlier. */
    CURRENT,

    /** The node's firmware has no release, as one built by hand may not: it can still be offered. */
    UNKNOWN,
}

/** A version as Semantic Versioning 2.0.0 writes one, ordered as it orders them: build metadata is ignored. */
class SemVer private constructor(
    private val core: List<BigInteger>,
    private val pre: List<String>,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        for (i in 0..2) core[i].compareTo(other.core[i]).let { if (it != 0) return it }
        // A pre-release comes before the release it leads to.
        if (pre.isEmpty() || other.pre.isEmpty()) return other.pre.size.coerceAtMost(1) - pre.size.coerceAtMost(1)
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val c = comparePart(pre[i], other.pre[i])
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun equals(other: Any?) = other is SemVer && compareTo(other) == 0
    override fun hashCode() = 31 * core.hashCode() + pre.hashCode()
    override fun toString() = core.joinToString(".") + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        private val NUMBER = Regex("0|[1-9][0-9]*")
        private val PART = Regex("[0-9A-Za-z-]+")

        /** [text] as a version, or null if it is not one: no leading `v`, no leading zeros. */
        fun parse(text: String): SemVer? {
            val plus = text.indexOf('+')
            val head = if (plus < 0) text else text.substring(0, plus)
            if (plus >= 0 && text.substring(plus + 1).split('.').any { !PART.matches(it) }) return null
            val dash = head.indexOf('-')
            val numbers = (if (dash < 0) head else head.substring(0, dash)).split('.')
            if (numbers.size != 3 || numbers.any { !NUMBER.matches(it) }) return null
            val pre = if (dash < 0) emptyList() else head.substring(dash + 1).split('.')
            if (pre.any { !PART.matches(it) || it.all(Char::isDigit) && !NUMBER.matches(it) }) return null
            return SemVer(numbers.map(::BigInteger), pre)
        }

        /** Numbers compare as numbers and before anything with a letter or hyphen, which compares in ASCII order. */
        private fun comparePart(a: String, b: String): Int {
            val an = a.all(Char::isDigit)
            val bn = b.all(Char::isDigit)
            return when {
                an && bn -> BigInteger(a).compareTo(BigInteger(b))
                an -> -1
                bn -> 1
                else -> a.compareTo(b)
            }
        }
    }
}

/**
 * Just enough JSON to read a manifest: objects as maps, arrays as lists, strings, integers as Long,
 * other numbers as Double, true, false and null. Throws IllegalArgumentException for anything else.
 */
internal object JsonText {
    fun parse(text: String): Any? {
        val r = JsonReader(text)
        val v = r.value()
        r.space()
        require(r.atEnd) { "more after the value" }
        return v
    }
}

private class JsonReader(private val s: String) {
    private var at = 0
    val atEnd get() = at == s.length

    fun space() {
        while (at < s.length && s[at] in " \t\r\n") at++
    }

    private fun peek(): Char {
        space()
        require(at < s.length) { "the text ends early" }
        return s[at]
    }

    private fun expect(c: Char) {
        require(peek() == c) { "expected $c at $at" }
        at++
    }

    fun value(): Any? = when (val c = peek()) {
        '{' -> obj()
        '[' -> array()
        '"' -> string()
        't' -> word("true", true)
        'f' -> word("false", false)
        'n' -> word("null", null)
        else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("unexpected $c at $at")
    }

    private fun word(w: String, v: Any?): Any? {
        require(s.startsWith(w, at)) { "unexpected text at $at" }
        at += w.length
        return v
    }

    private fun obj(): Map<String, Any?> {
        expect('{')
        val out = LinkedHashMap<String, Any?>()
        if (peek() == '}') return out.also { at++ }
        while (true) {
            if (peek() != '"') throw IllegalArgumentException("expected a key at $at")
            val k = string()
            expect(':')
            out[k] = value()
            when (peek()) {
                ',' -> at++
                '}' -> return out.also { at++ }
                else -> throw IllegalArgumentException("expected , or } at $at")
            }
        }
    }

    private fun array(): List<Any?> {
        expect('[')
        val out = ArrayList<Any?>()
        if (peek() == ']') return out.also { at++ }
        while (true) {
            out += value()
            when (peek()) {
                ',' -> at++
                ']' -> return out.also { at++ }
                else -> throw IllegalArgumentException("expected , or ] at $at")
            }
        }
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            require(at < s.length) { "a string does not end" }
            when (val c = s[at++]) {
                '"' -> return out.toString()
                '\\' -> {
                    require(at < s.length) { "a string does not end" }
                    when (val e = s[at++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(at + 4 <= s.length) { "a short \\u escape" }
                            out.append(s.substring(at, at + 4).toIntOrNull(16)?.toChar() ?: throw IllegalArgumentException("a bad \\u escape"))
                            at += 4
                        }
                        else -> throw IllegalArgumentException("a bad escape \\$e")
                    }
                }
                else -> {
                    require(c >= ' ') { "a control character in a string" }
                    out.append(c)
                }
            }
        }
    }

    private fun number(): Any {
        val m = NUMBER.matcher(s).region(at, s.length)
        require(m.lookingAt()) { "a bad number at $at" }
        val t = m.group()
        at += t.length
        return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
    }

    private companion object {
        val NUMBER: Pattern = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}
