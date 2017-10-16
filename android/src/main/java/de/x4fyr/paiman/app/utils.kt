package de.x4fyr.paiman.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.support.v4.app.DialogFragment
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.LruCache
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import de.x4fyr.paiman.R
import de.x4fyr.paiman.lib.adapter.AndroidGoogleDriveStorageAdapter
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/** An dialog Fragment that has an apply action.
 *
 * This is necessary because the parent activity handles the menu item and button onClick event from this fragment, and
 * thous needs a target to redirect the onClick events
 */
abstract class ApplyingDialogFragment: DialogFragment() {
    /** Apply action on a button which is due to material design a menu item/button */
    abstract fun onApply(menuItem: MenuItem)

    /** Actions on other ordinary buttons of this dialog */
    abstract fun onDialogButton(view: View)
}

/** BaseActivity handling background work */
abstract class BaseActivity: AppCompatActivity() {

    /** Storage adapter that might not be used outside */
    @Inject lateinit var storageAdapter: AndroidGoogleDriveStorageAdapter

    private var resultHandlerMap: MutableMap<Any, ((requestCode: Int, resultCode: Int, data: Intent?) -> Unit)> =
            HashMap()

    /** Add a handler to be invoked on [Activity.onActivityResult] */
    fun addActivityResultHandler(identifier: Any, handler: (requestCode: Int, resultCode: Int, data: Intent?) -> Unit) {
        resultHandlerMap.put(identifier, handler)
    }

    /** Remove a handler not to be invoked on [Activity.onActivityResult] */
    fun removeActivityResultHandler(identifier: Any) {
        resultHandlerMap.remove(identifier)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        for ((_, handler) in resultHandlerMap) {
            handler.invoke(requestCode, resultCode, data)
        }
    }

    override fun onStart() {
        storageAdapter.connect(this)
        super.onStart()
    }

    override fun onResume() {
        storageAdapter.connect(this)
        super.onResume()
    }

    override fun onPause() {
        storageAdapter.disconnect()
        super.onPause()
    }

    override fun onStop() {
        storageAdapter.disconnect()
        super.onStop()
    }

}

/** Safely get a valid stream from an android URL */
suspend fun Activity.getInputStreamFromUrl(url: String): InputStream? {
    val mainPictureFile = File(url)
    return if (mainPictureFile.exists() && mainPictureFile.canRead()) {
        val inputStream = mainPictureFile.inputStream()
        if (inputStream.available() > 0) {
            Log.i("IMAGE_LOADING",
                    "InputStream of size ${inputStream.available()}: ${mainPictureFile.path}")
            inputStream
        } else {
            errorDialog(R.string.error_image_not_readable)
            Log.w("IMAGE_LOADING", "Trying to read empty file: ${mainPictureFile.path}")
            null
        }
    } else {
        errorDialog(R.string.error_image_not_readable)
        Log.w("IMAGE_LOADING",
                "Error: ${mainPictureFile.path} -> ${mainPictureFile.exists()} || ${mainPictureFile.canRead()}")
        return null
    }
}

/** Error [AlertDialog] */
fun Activity.errorDialog(msg: Int) {
    launch(UI) {
        try {
            AlertDialog.Builder(this@errorDialog)
                    .setMessage(msg)
                    .create()
                    .show()
        } catch (e: RuntimeException) {
        }
    }
}

/** Error [AlertDialog] with exception message field*/
fun Activity.errorDialog(msg: Int, throwable: Throwable) {
    launch(UI) {
        try {
            val view = layoutInflater.inflate(R.layout.dialog_error, null, false)
            view.findViewById<TextView>(R.id.message).text = getString(msg)
            view.findViewById<EditText>(R.id.exception).setText(throwable.message!!)
            AlertDialog.Builder(this@errorDialog)
                    .setView(view)
                    .create()
                    .show()
        } catch (e: RuntimeException) {
        }
    }
}


/**
 * [LruCache] specified for [Bitmap] with the maxKbSize in kb as limit
 */
class BitmapCache<K>(maxKbSize: Int): LruCache<K, Bitmap>(maxKbSize) {

    override fun sizeOf(key: K, value: Bitmap): Int {
        return value.byteCount/1024
    }
}