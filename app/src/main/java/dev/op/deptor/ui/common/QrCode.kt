package dev.op.deptor.ui.common

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Vygeneruje QR kód z textu.
 *
 * Používá se jen jádro ZXing, ne androidová nadstavba — generování QR je čistý
 * výpočet a kamera ani skener k tomu nejsou potřeba.
 *
 * Barvy jsou schválně natvrdo černá na bílé, ne z tématu: čtečky potřebují kontrast
 * a QR na tmavém podkladu se řadě z nich načíst nepodaří.
 */
fun generateQrCode(content: String, sizePx: Int): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        ),
    )

    val bitmap = createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap[x, y] = if (matrix[x, y]) BLACK else WHITE
        }
    }
    bitmap.asImageBitmap()
}.getOrNull()

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
