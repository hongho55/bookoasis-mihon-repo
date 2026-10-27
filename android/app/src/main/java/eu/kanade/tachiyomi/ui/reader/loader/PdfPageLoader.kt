package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal class PdfPageLoader(
    context: Context,
    file: UniFile,
) : PageLoader() {

    override var isLocal: Boolean = true

    private val descriptor: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(file.uri, "r")
            ?: error("Unable to open PDF: ${file.name}")
    private val renderer = PdfRenderer(descriptor)
    private val renderLock = Any()

    override suspend fun getPages(): List<ReaderPage> {
        return (0 until renderer.pageCount).map { index ->
            ReaderPage(index).apply {
                stream = { renderPage(index) }
                status = Page.State.Ready
            }
        }
    }

    private fun renderPage(index: Int): InputStream = synchronized(renderLock) {
        check(!isRecycled)
        renderer.openPage(index).use { page ->
            val scale = (1600f / page.width.coerceAtLeast(1)).coerceIn(1f, 2.5f)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            ByteArrayInputStream(output.toByteArray())
        }
    }

    override fun recycle() {
        if (!isRecycled) {
            synchronized(renderLock) {
                renderer.close()
                descriptor.close()
            }
        }
        super.recycle()
    }
}
