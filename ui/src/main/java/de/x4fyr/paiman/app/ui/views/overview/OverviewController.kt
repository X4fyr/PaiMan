package de.x4fyr.paiman.app.ui.views.overview

import de.x4fyr.paiman.app.services.PictureSelectorService
import de.x4fyr.paiman.app.services.WebViewService
import de.x4fyr.paiman.app.ui.Controller
import de.x4fyr.paiman.app.ui.views.addPainting.AddPaintingFactory
import de.x4fyr.paiman.app.ui.views.paintingDetail.PaintingDetailFactory
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch

/** Overview Controller */
open class OverviewController(private val webViewService: WebViewService,
                              private val addPaintingFactory: AddPaintingFactory,
                              private val model: OverviewModel,
                              private val paintingDetailFactory: PaintingDetailFactory,
                              private val pictureSelectorService: PictureSelectorService) : Controller {

    companion object {
        private const val html = "html/overview.html"
    }

    /** See [Controller.loadView] */
    override suspend fun loadView() {
        reload()
    }

    /** Reload view */
    suspend fun reload() {
        //TODO: Reload only when visible
        webViewService.loadHtml(html, this, model)
    }

    /** Callback: Open add painting dialog */
    open fun openAddPainting() {
        println("Callback: openAddPainting()")
        launch(CommonPool) {
            addPaintingFactory.createAddPaintingController(this@OverviewController).loadView()
        }
    }

    /** Callback: Refresh previews */
    open fun refresh() {
        println("Callback: refresh()")
        webViewService.executeJS("refreshPreviews()")
    }

    /** Callback: Open detail view of given painting by [id] */
    open fun openPainting(id: String) {
        println("Callback: openPainting($id)")
        launch(CommonPool) {
            paintingDetailFactory.createPaintingDetailController(id, this@OverviewController).loadView()
        }
    }

    open fun addPainting(title: String?) {
        println("Callback: addPainting($title)")
        if (title.isNullOrBlank()) {
            webViewService.showError("Title missing or invalid")
        } else if (model.addPaintingModel.image == null) {
            webViewService.showError("Image missing")
        } else launch(CommonPool) {
            model.addPaintingModel.title = title
            val newId = model.saveNewPainting()
            if (newId != null) {
                paintingDetailFactory.createPaintingDetailController(newId, this@OverviewController).loadView()
            } else {
                println("Error: Tried to save unfinished painting")
            }
        }
    }

    open fun selectImage() {
        println("Callback: selectImage()")
        launch(CommonPool) {
            pictureSelectorService.pickPicture {
                if (it != null) {
                    val jpegData =  model.addPaintingModel.setImage(it)
                    println("selected image")
                    webViewService.executeJS("addDialogSetPicture('$jpegData')")
                } else {
                    webViewService.showError("Couldn\\'t get image")
                }
            }
        }
    }
}