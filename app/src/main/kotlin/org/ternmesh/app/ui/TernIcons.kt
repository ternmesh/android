// The app's own line icons, drawn on a 24-unit grid with round ends: Material's core set has no
// speech bubbles or antenna, and the extended set is several megabytes for a dozen pictures.
package org.ternmesh.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object TernIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                pathData = addPathNodes(p),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

    val Chats by lazy { icon("Chats", "M4 5h16v11H9l-5 4z", "M8 9.5h8M8 12.5h5") }
    val People by lazy {
        icon(
            "People", circle(9f, 8f, 3.2f), "M3 19c.6-3.3 3-5 6-5s5.4 1.7 6 5",
            circle(17f, 9f, 2.4f), "M16 14.2c2.4.1 4.3 1.6 4.8 4.8",
        )
    }
    val Map by lazy { icon("Map", "M9 4 3 6v14l6-2 6 2 6-2V4l-6 2z", "M9 4v14M15 6v14") }
    // A radio with its antenna up and its signal going out.
    val Node by lazy {
        icon("Node", "M5 13h14v7H5z", "M8 13V5", "M15 16.5h1", "M11 4.5a4 4 0 0 1 0 5", "M13.5 2.5a7 7 0 0 1 0 9")
    }
    val Qr by lazy {
        icon(
            "Qr", "M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z",
            "M14 14h2v2h-2zM18 18h2v2h-2zM14 18h2M18 14h2",
        )
    }
    val Radio by lazy {
        icon("Radio", circle(12f, 12f, 2f), "M8 8a5.5 5.5 0 0 0 0 8M16 8a5.5 5.5 0 0 1 0 8M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14")
    }
    val Eye by lazy { icon("Eye", "M2 12s3.6-6 10-6 10 6 10 6-3.6 6-10 6S2 12 2 12z", circle(12f, 12f, 3f)) }
    val Lock by lazy { icon("Lock", "M5 11h14v9H5z", "M8 11V8a4 4 0 0 1 8 0v3") }
    val Chip by lazy { icon("Chip", "M6 6h12v12H6z", "M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4") }
    val Power by lazy { icon("Power", "M12 3v8", "M6.3 7a8 8 0 1 0 11.4 0") }
    val Swap by lazy { icon("Swap", "M7 7h12l-3-3", "M17 17H5l3 3") }
    val Wave by lazy { icon("Wave", "M3 12h3l3-7 4 14 3-7h5") }
    val Link by lazy { icon("Link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1") }
    val Unlink by lazy {
        icon("Unlink", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1", "M3 3l18 18")
    }
    val Trash by lazy { icon("Trash", "M4 7h16", "M10 11v6M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3") }
    val Pin by lazy { icon("Pin", "M12 21s7-6.2 7-12a7 7 0 0 0-14 0c0 5.8 7 12 7 12z", circle(12f, 9f, 2.5f)) }
    val Share by lazy { icon("Share", "M12 4v11M7 9l5-5 5 5", "M5 14v5h14v-5") }
    val Copy by lazy { icon("Copy", "M8 8h12v12H8z", "M16 8V4H4v12h4") }
    val Send by lazy { icon("Send", "M5 12h13", "M13 6l6 6-6 6") }
    val Search by lazy { icon("Search", circle(11f, 11f, 6f), "M20 20l-4.5-4.5") }
    val Chevron by lazy { icon("Chevron", "M9 5l7 7-7 7") }
    val Edit by lazy { icon("Edit", "M4 20h4L19 9l-4-4L4 16z", "M13 7l4 4") }
}
