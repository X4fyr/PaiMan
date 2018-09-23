package de.x4fyr.paiman.app.ui.views.paintingDetail

import com.google.gson.Gson
import de.x4fyr.paiman.app.ui.Model
import de.x4fyr.paiman.app.ui.jpegDataString
import de.x4fyr.paiman.lib.domain.Painting
import de.x4fyr.paiman.lib.domain.SavedPainting
import de.x4fyr.paiman.lib.services.PaintingService
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.runBlocking
import org.threeten.bp.LocalDate
import java.io.InputStream

open class PaintingDetailModel(private val paintingService: PaintingService, val id: String) : Model {

    private val gson = Gson()

    var painting: Deferred<SavedPainting> = async {
        paintingService.get(id)
    }

    open fun getHolder(): String {
        println("Model callback: getHolder()")
        return runBlocking { gson.toJson(createHolder(painting.await())) }
    }

    val mainPicture: Deferred<String> = async {
        jpegDataString(paintingService.getPictureStream(painting.await().mainPicture))
    }

    val wips: Deferred<List<String>>
        get() = async {
            painting.await().wip.map {
                jpegDataString(paintingService.getPictureThumbnailStream(it))
            }
        }

    val refs: Deferred<List<String>>
        get() = async {
            painting.await().references.map {
                jpegDataString(paintingService.getPictureThumbnailStream(it))
            }
        }

    suspend fun addWip(stream: InputStream) {
        val resultPainting = paintingService.addWipPicture(painting.await(), setOf(stream))
        painting = async { resultPainting }
    }

    suspend fun addRef(stream: InputStream) {
        val resultPainting = paintingService.addReferences(painting.await(), setOf(stream))
        painting = async { resultPainting }
    }

    suspend fun addTag(tag: String) {
        val resultPainting = paintingService.addTags(painting.await(), setOf(tag))
        painting = async { resultPainting }
    }

    suspend fun finishing(date: LocalDate) {
        val resultPainting: SavedPainting = paintingService.changePainting(painting.await().copy(finished = true, finishingDate = date))
        painting = async { resultPainting }
    }

    private data class Holder(
            val title: String,
            val mainImage: String,
            val tags: Array<String>,
            val wips: Array<String>,
            val refs: Array<String>
    )

    private suspend fun createHolder(painting: Painting): Holder = Holder(painting.title,
            jpegDataString(paintingService.getPictureStream(painting.mainPicture)),
            painting.tags.toTypedArray().sortedArrayDescending(), //Reversed order because items are prepended
            wips.await().toTypedArray(),
            refs.await().toTypedArray())
}
