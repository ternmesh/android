// A QR code of an address's link (draft/sharing.md) or a group's join code (draft/groups.md), and the
// camera that reads one back.
package org.ternmesh.app.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.ternmesh.app.R
import org.ternmesh.companion.Address
import org.ternmesh.companion.Sharing

/** The light margin round a code, in modules: what ISO/IEC 18004 asks for. */
private const val QUIET = 4

/** The address's link as a QR code. */
@Composable
fun QrCode(address: Address, modifier: Modifier = Modifier) = QrCode(Sharing.link(address), modifier)

/**
 * A link as a QR code. Black on white whatever the theme, since some scanners will not read a code
 * the other way round; level L, the lowest, which keeps an address's link to a version 3 code. In
 * the fewest bits zxing finds, which for a join code is alphanumeric on either side of its `#` and
 * that one character as a byte, as draft/groups.md asks: version 4 at most.
 */
@Composable
fun QrCode(link: String, modifier: Modifier = Modifier) {
    val matrix = remember(link) {
        Encoder.encode(link, ErrorCorrectionLevel.L, mapOf(EncodeHintType.QR_COMPACT to true)).matrix
    }
    Canvas(modifier.widthIn(max = 280.dp).aspectRatio(1f).background(Color.White)) {
        val cells = matrix.width + 2 * QUIET
        val module = size.width / cells
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y).toInt() == 1) {
                    // A hair over a module, so neighbours meet with no seam between them.
                    drawRect(Color.Black, Offset((x + QUIET) * module, (y + QUIET) * module), Size(module + 0.5f, module + 0.5f))
                }
            }
        }
    }
}

/** Opens the camera to read a code; [found] is given what it held, or nothing if it was closed. */
@Composable
fun rememberScanner(found: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(found) }
    return remember(launcher) {
        {
            launcher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(context.getString(R.string.scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        }
    }
}

/** Offers the address's link to whatever the person picks to send it with. */
fun shareLink(context: Context, address: Address) = shareText(context, Sharing.link(address))
