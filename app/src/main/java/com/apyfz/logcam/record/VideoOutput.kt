package com.apyfz.logcam.record

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.apyfz.logcam.camera.RecordingProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Creates the destination file in the gallery and closes it out afterwards.
 *
 * The file is published through MediaStore with `IS_PENDING` set, so a half-written take is
 * invisible to the gallery and to any backup that might otherwise upload a truncated MP4.
 */
class VideoOutput private constructor(
    private val context: Context,
    val uri: Uri,
    private val descriptor: ParcelFileDescriptor,
    val displayName: String,
) {

    val fileDescriptor get() = descriptor.fileDescriptor

    /** Publishes the finished take, correcting its colour tags first. */
    fun commit() {
        // Strip the HDR claim before anyone sees the file. Done while it is still pending, so no
        // player or gallery ever observes it tagged as HDR.
        runCatching { ColrPatcher.patch(descriptor.fileDescriptor) }
        runCatching { descriptor.close() }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
            null,
            null,
        )
    }

    /** Discards a take that failed, so a zero-byte entry is not left in the gallery. */
    fun abandon() {
        runCatching { descriptor.close() }
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    companion object {

        fun create(context: Context, profile: RecordingProfile): VideoOutput {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            // The profile is in the filename because nothing in an MP4 header can record which log
            // curve was used, and picking the wrong LUT three weeks later is a real cost.
            val name = "LogCam_${stamp}_${profile.name}.mp4"

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LogCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: error("MediaStore refused to create $name")

            val descriptor = resolver.openFileDescriptor(uri, "rw")
                ?: error("could not open $name for writing")

            return VideoOutput(context, uri, descriptor, name)
        }
    }
}
