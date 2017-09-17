package de.x4fyr.paiman

import android.app.Activity
import android.app.Dialog
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v4.app.DialogFragment
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.CardView
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.StaggeredGridLayoutManager
import android.support.v7.widget.Toolbar
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import dagger.android.AndroidInjection
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasActivityInjector
import de.x4fyr.paiman.app.ApplyingDialogFragment
import de.x4fyr.paiman.app.BaseActivity
import de.x4fyr.paiman.lib.provider.AndroidPictureProvider
import de.x4fyr.paiman.lib.provider.AndroidServiceProvider
import de.x4fyr.paiman.lib.provider.PictureProvider
import de.x4fyr.paiman.lib.services.DesignService
import de.x4fyr.paiman.lib.services.PaintingService
import de.x4fyr.paiman.lib.services.QueryService
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import kotlinx.android.synthetic.main.fragment_add_painting.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.displayMetrics
import org.jetbrains.anko.image
import org.jetbrains.anko.imageResource
import org.jetbrains.anko.support.v4.onRefresh
import java.io.File
import javax.inject.Inject

/** Main Activity of the app, which is launched first and shows a list of current paintings
 *
 * It initialises the different providers.
 */
class MainActivity: BaseActivity(), HasActivityInjector {
    /** [DispatchingAndroidInjector] for dependency injection */
    @Inject lateinit var dispatchingActivityInjector: DispatchingAndroidInjector<Activity>

    /** Initial [AndroidServiceProvider] */
    @Inject lateinit var serviceProvider: AndroidServiceProvider
    /** Initial [AndroidPictureProvider] */
    lateinit var pictureProvider: AndroidPictureProvider
    /** [QueryService] instance*/
    @Inject lateinit var queryService: QueryService
    /** [PaintingService] instance */
    @Inject lateinit var paintingService: PaintingService
    /** [DesignService] instance */
    @Inject lateinit var designService: DesignService

    private var activeDialogFragment: ApplyingDialogFragment? = null

    private lateinit var gridAdapter: GridAdapter
    private var models: MutableList<Model> = mutableListOf()
    private var selectedPositions: MutableSet<Int> = hashSetOf()

    private val actionModeCallback = object: ActionMode.Callback {
        /** See [ActionMode.Callback] */
        override fun onActionItemClicked(mode: ActionMode, menuItem: MenuItem): Boolean {
            when {
                menuItem.itemId == R.id.main_selection_delete -> {
                    launch(CommonPool) {
                        selectedPositions.forEach { paintingService.delete(models[it].id) }
                        launch(UI) {
                            mode.finish()
                            Snackbar.make(main_layout, R.string.main_delete_notification, Snackbar.LENGTH_SHORT).show()
                            reloadContent()
                        }
                    }
                    return true
                }
            }
            return false
        }

        /** See [ActionMode.Callback] */
        override fun onCreateActionMode(mode: ActionMode, menu: Menu?): Boolean {
            mode.menuInflater.inflate(R.menu.selection_menu_main, menu)
            return true
        }

        /** See [ActionMode.Callback] */
        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

        /** See [ActionMode.Callback] */
        override fun onDestroyActionMode(mode: ActionMode?) {
            selectedPositions.clear()
            gridAdapter.notifyDataSetChanged()
            actionMode = null
        }


    }
    private var actionMode: ActionMode? = null

    /** Actions that happen on long click on a painting in the grid.
     *
     * Mainly selecting the item and enter/exit selection mode
     */
    val onPaintingLongClick: (id: Int) -> Unit = { id ->
        //if (actionMode != null) return
        if (selectedPositions.contains(id))
            selectedPositions.remove(id)
        else
            selectedPositions.add(id)
        gridAdapter.notifyDataSetChanged()
        val selectedCount = selectedPositions.size
        if (selectedCount > 0) {
            if (actionMode == null) {
                actionMode = startActionMode(actionModeCallback)
            }
        } else
            actionMode?.finish()
        actionMode?.title = getString(R.string.main_selection_title, selectedCount)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        pictureProvider = AndroidPictureProvider(this)
        gridAdapter = GridAdapter(models, selectedPositions, resources)

        val spanCount = Math.floor(
                displayMetrics.widthPixels/TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 106.toFloat(),
                        displayMetrics).toDouble()).toInt()
        paintingGrid.layoutManager = StaggeredGridLayoutManager(spanCount, StaggeredGridLayoutManager.VERTICAL)
        paintingGrid.adapter = gridAdapter

        fab.setOnClickListener { _ ->
            val fragment: DialogFragment = AddPaintingFragment()
            // if (designService.isLargeDevice) {
            fragment.show(supportFragmentManager, "dialog")
            /*} else {
                supportFragmentManager.beginTransaction().apply {
                    setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                    add(android.R.id.content, fragment)
                    addToBackStack(null)
                    commit()
                }
            }*/
        }
        // Register query listener
        queryService.allPaintingsQuery.toLiveQuery().apply {
            addChangeListener {
                async(UI) {
                    swipeRefreshLayout.isRefreshing = true
                    val newModels = async(CommonPool) {
                        if (it.rows != null) {
                            paintingService.getFromQueryResult(it.rows!!).map {
                                Model(id = it.id, mainImage = async(CommonPool) {
                                    Bitmap.createScaledBitmap(BitmapFactory.decodeStream(
                                            paintingService.getPictureStream(it.mainPicture)), 100, 100, false)
                                },
                                        title = it.title, mainImageId = it.mainPicture.id)
                            }
                        } else listOf()
                    }
                    models.clear()
                    models.addAll(newModels.await())
                    gridAdapter.notifyDataSetChanged()
                    swipeRefreshLayout.isRefreshing = false
                }
            }
            start()
        }
        swipeRefreshLayout.onRefresh { reloadContent(completionHandler = { swipeRefreshLayout.isRefreshing = false }) }
    }

    /** [HasActivityInjector] for this [Activity] */
    override fun activityInjector(): AndroidInjector<Activity> = dispatchingActivityInjector

    /**
     *
     */
    private fun reloadContent(completionHandler: () -> Unit = {}) {
        async(UI) {
            val newModels = async(CommonPool) {
                paintingService.getFromQueryResult(queryService.allPaintingsQuery.run()).map {
                    Model(id = it.id,
                            mainImage = async(CommonPool) {
                                Bitmap.createScaledBitmap(
                                        BitmapFactory.decodeStream(paintingService.getPictureStream(it.mainPicture)),
                                        100, 100, false)
                            },
                            title = it.title, mainImageId = it.mainPicture.id)
                }
            }
            models.clear()
            models.addAll(newModels.await())
            gridAdapter.notifyDataSetChanged()
            completionHandler()
        }
    }

    /** See [AppCompatActivity] */
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    /** Set the current active dialog fragment */
    fun setActiveDialogFragment(dialogFragment: ApplyingDialogFragment) {
        activeDialogFragment = dialogFragment
    }

    /** The apply action on the current active dialog fragment */
    fun onApply(menuItem: MenuItem) {
        activeDialogFragment?.onApply(menuItem)
    }

    /** Button action on the current active dialog fragment */
    fun onDialogButton(view: View) {
        activeDialogFragment?.onDialogButton(view)
    }

    /** Handler for menu clicks */
    fun onMenuClick(item: MenuItem) {
        when {
            item.itemId == R.id.action_settings -> TODO("Not implemented") //TODO: Not implemented
        }
    }

}


private class GridAdapter(val models: MutableList<Model>, val selectedPositions: Set<Int>, val resources: Resources)
    : RecyclerView.Adapter<GridAdapter.ModelViewHolder>() {

    /** See [RecyclerView.Adapter] */
    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        val model = models[position]
        holder.title.text = model.title
        if (selectedPositions.contains(position)) {
            holder.container.cardElevation = holder.container.maxCardElevation
            //holder.container.setCardBackgroundColor(resources.getColor(R.color.cardview_dark_background))
        } else {
            holder.container.cardElevation = 2.toFloat()
            //holder.container.setCardBackgroundColor(resources.getColor(R.color.cardview_light_background))
        }
        launch(CommonPool) {
            val image = BitmapDrawable(resources, model.mainImage.await())
            launch(UI) {
                holder.mainImage.image = image
            }
        }
    }

    /** See [RecyclerView.Adapter] */
    override fun getItemCount(): Int = models.size

    /** See [RecyclerView.Adapter] */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder
            = ModelViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.card_main, parent, false))

    private class ModelViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {

        /** [CardView] container */
        var container: CardView = itemView.findViewById(R.id.painting_preview_card)
        /** [ImageView] containing the main image preview */
        var mainImage: ImageView = itemView.findViewById(R.id.painting_preview_image)
        /** [TextView] containing the title */
        var title: TextView = itemView.findViewById(R.id.painting_preview_title)

        init {
            container.setOnLongClickListener {
                (container.context as MainActivity).onPaintingLongClick(adapterPosition)
                false
            }
        }

    }

}


private data class Model(val id: String, val mainImage: Deferred<Bitmap>, val title: String, val mainImageId:
String)


/** Dialog to add new paintings */
class AddPaintingFragment: ApplyingDialogFragment() {

    private lateinit var paintingService: PaintingService
    private lateinit var pictureProvider: PictureProvider
    private lateinit var currentView: View

    private var model = Model(null, null)

    private val picturePickHandler: (String?) -> Unit = {
        Log.i("IMAGE_SELECT", "Got image $it")
        model.imageUrl = it
        if (it != null) {
            add_painting_main_image.image = Drawable.createFromPath(it)
        } else {
            add_painting_main_image.imageResource = android.R.drawable.ic_menu_report_image
        }
    }

    /** See [ApplyingDialogFragment] */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_add_painting, container, false).also {
            val toolbar = it.findViewById<Toolbar>(R.id.add_painting_toolbar)
            toolbar.inflateMenu(R.menu.menu_add_painting)
            toolbar.setTitle(R.string.add_painting_title)
        }
    }

    /** See [ApplyingDialogFragment] */
    override fun onStart() {
        (context as MainActivity).setActiveDialogFragment(this)
        if (model.imageUrl == null) pictureProvider.pickPicture(picturePickHandler)
        super.onStart()
    }

    /** See [ApplyingDialogFragment] */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = super.onCreateDialog(savedInstanceState)
            .also {
                it.requestWindowFeature(Window.FEATURE_NO_TITLE)
                paintingService = (context as MainActivity).serviceProvider.paintingService
                pictureProvider = (context as MainActivity).pictureProvider
            }

    /** See [ApplyingDialogFragment] */
    override fun onCreate(savedInstanceState: Bundle?) {
        paintingService = (context as MainActivity).serviceProvider.paintingService
        pictureProvider = (context as MainActivity).pictureProvider
        super.onCreate(savedInstanceState)
    }

    override fun onApply(menuItem: MenuItem) {
        model.title = add_painting_name.text!!.toString()

        when {
            model.title.isNullOrEmpty() -> errorDialog(R.string.add_painting_warning_name_missing)
            model.imageUrl.isNullOrEmpty() -> errorDialog(R.string.add_painting_warning_image_missing)
            else -> launch(CommonPool) {
                var id: String? = null
                val mainPictureFile = File(model.imageUrl!!)
                if (mainPictureFile.exists() && mainPictureFile.canRead()) {
                    val inputStream = mainPictureFile.inputStream()
                    if (inputStream.available() > 0) {
                        Log.i("IMAGE_LOADING",
                                "InputStream of size ${inputStream.available()}: ${mainPictureFile.path}")
                        id = paintingService.composeNewPainting(title = model.title!!, mainPicture = inputStream).id
                    } else {
                        errorDialog(R.string.add_painting_warning_image_not_readable)
                        Log.w("IMAGE_LOADING", "Trying to read empty file: ${mainPictureFile.path}")
                    }
                } else {
                    errorDialog(R.string.add_painting_warning_image_not_readable)
                    Log.w("IMAGE_LOADING",
                            "Error: ${mainPictureFile.path} -> ${mainPictureFile.exists()} || ${mainPictureFile.canRead()}")
                }
                if (id != null) {
                    dismiss()
                } else {
                    errorDialog(R.string.add_painting_warning_no_id)
                    Log.w("IMAGE_LOADING", "Saving finished unsuccessful")
                }
            }
        }
    }

    override fun onDialogButton(view: View) {
        when {
            view.id == R.id.add_painting_main_image -> pictureProvider.pickPicture(picturePickHandler)
        }
    }

    private fun errorDialog(msg: Int) {
        AlertDialog.Builder(activity)
                .setMessage(msg)
                .create()
                .show()
    }

    /** Model containing data of the to be created painting */
    data class Model(var title: String?, var imageUrl: String?)

}