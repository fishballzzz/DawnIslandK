package com.laotoua.dawnislandk.util

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.widget.ImageView
import androidx.fragment.app.Fragment
import timber.log.Timber
import java.io.*


object ImageUtil {

    private val cachedImages = mutableSetOf<String>()
    fun imageExistInGalleryBasedOnFilenameAndExt(
        callerActivity: Activity,
        fileName: String,
        relativeLocation: String
    ): Boolean {
        if (cachedImages.contains(fileName)) return true
        val selection = "${MediaStore.Images.ImageColumns.DISPLAY_NAME}=?"
        val selectionArgs = arrayOf(fileName)
        val projection = arrayOf(MediaStore.Images.ImageColumns.DISPLAY_NAME)
        var res = false
        callerActivity.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor -> res = (cursor.count > 0) }

        if (res) cachedImages.add(fileName)
        return res
    }

    private fun copyFromFileToImageUri(callerActivity: Activity, uri: Uri, file: File): Boolean {
        try {
            val stream = callerActivity.contentResolver.openOutputStream(uri)
                ?: throw IOException("Failed to get output stream.")
            stream.write(file.readBytes())
            stream.close()
        } catch (e: Exception) {
            Timber.e(e)
            return false
        }
        return true
    }

    fun copyImageFileToGallery(
        callerActivity: Activity,
        fileName: String,
        relativeLocation: String,
        file: File
    ): Boolean {
        try {
            val uri = addPlaceholderImageUriToGallery(
                callerActivity,
                fileName,
                relativeLocation
            )
            return try {
                copyFromFileToImageUri(
                    callerActivity,
                    uri,
                    file
                )
            } catch (writeException: Exception) {
                Timber.e(writeException)
                removePlaceholderImageUriToGallery(callerActivity, uri)
                false
            }
        } catch (uriException: Exception) {
            Timber.e(uriException)
            return false
        }
    }

    fun writeBitmapToGallery(
        callerActivity: Activity, fileName: String, relativeLocation: String,
        bitmap: Bitmap
    ): Uri? {
        return try {
            val uri = addPlaceholderImageUriToGallery(
                callerActivity,
                fileName,
                relativeLocation
            )
            try {
                callerActivity.contentResolver.openOutputStream(uri)?.use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                uri
            } catch (e: Exception) {
                removePlaceholderImageUriToGallery(callerActivity, uri)
                null
            }
        } catch (e: FileNotFoundException) {
            null
        }
    }

    fun addPlaceholderImageUriToGallery(
        callerActivity: Activity,
        fileName: String,
        relativeLocation: String
    ): Uri {
        val contentValues = ContentValues().apply {
            val mimeType = fileName.substringAfterLast(".")
            put(MediaStore.Images.ImageColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/$mimeType")
            // without this part causes "Failed to create new MediaStore record" exception to be invoked (uri is null below)
            // https://stackoverflow.com/questions/56904485/how-to-save-an-image-in-android-q-using-mediastore
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.ImageColumns.RELATIVE_PATH, relativeLocation)
            }
        }

        return callerActivity.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: throw IOException("Failed to create new MediaStore record.")
    }

    fun removePlaceholderImageUriToGallery(
        callerActivity: Activity,
        uri: Uri
    ): Int {
        return callerActivity.contentResolver.delete(uri, null, null)
    }

    fun loadImageThumbnailToImageView(
        caller: Fragment,
        uri: Uri,
        width: Int,
        height: Int,
        imageView: ImageView
    ) {
        if (Build.VERSION.SDK_INT >= 29) {
            caller.requireActivity().contentResolver
                .loadThumbnail(uri, Size(width, height), null)
                .run { imageView.setImageBitmap(this) }
        } else {
            GlideApp.with(imageView)
                .load(uri).override(width, height).into(imageView)
        }
    }

    fun getImageFileFromUri(
        fragment: Fragment? = null,
        activity: Activity? = null,
        uri: Uri
    ): File? {
        val caller = fragment?.requireActivity() ?: activity!!
        val parcelFileDescriptor =
            caller.contentResolver.openFileDescriptor(uri, "r", null)

        parcelFileDescriptor?.let {
            val inputStream = FileInputStream(parcelFileDescriptor.fileDescriptor)
            val file = File(
                caller.cacheDir,
                caller.contentResolver.getFileName(uri)
            )
            if (file.exists()) {
                Timber.i("File exists..Reusing the old file")
                return file
            }
            Timber.i("File not found. Making a new one...")
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            return file
        }
        return null
    }

    fun getFileFromDrawable(caller: Fragment, fileName: String, resId: Int): File {
        val file = File(
            caller.requireContext().cacheDir,
            "$fileName.png"
        )
        if (file.exists()) {
            Timber.i("File exists..Reusing the old file")
            return file
        }
        Timber.i("File not found. Making a new one...")
        val inputStream: InputStream = caller.requireContext().resources.openRawResource(resId)

        val outputStream = FileOutputStream(file)
        inputStream.copyTo(outputStream)
        inputStream.close()
        outputStream.close()
        return file
    }

    private fun ContentResolver.getFileName(fileUri: Uri): String {
        var name = ""
        val projection = arrayOf(MediaStore.Images.ImageColumns.DISPLAY_NAME)
        this.query(fileUri, projection, null, null, null)?.use {
            it.moveToFirst()
            name = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
        }
        return name
    }
}