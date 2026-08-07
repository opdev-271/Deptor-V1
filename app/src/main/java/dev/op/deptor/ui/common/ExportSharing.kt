package dev.op.deptor.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Uloží export do cache a nabídne ho ke sdílení.
 *
 * Soubor se předává přes [FileProvider]: od Androidu 7 skončí předání `file://` URI
 * jiné aplikaci výjimkou, takže přímá cesta není jen nevkusná, ale nefunkční.
 *
 * Cache je schválně — export je jednorázová příloha, ne dokument, který má uživatel
 * spravovat. Když dojde místo, systém si ji uklidí sám.
 */
fun shareCsv(context: Context, fileName: String, content: String) {
    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(directory, fileName)

    // BOM, aby Excel poznal UTF-8 a nezobrazil háčky jako otazníky.
    file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + content.toByteArray())

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(Intent.createChooser(share, "Sdílet export"))
}
