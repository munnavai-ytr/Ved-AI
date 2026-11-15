package com.example.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background image compression, persistence, thumbnail loading, and Base64 encoding utilities
 * for Gemini multimodal attachments.
 */
object ImageProcessor {

  private const val MAX_DIMENSION_PX = 1024
  private const val JPEG_QUALITY = 80
  private const val IMAGE_DIR = "chat_images"

  /**
   * Decodes, downscales (max 1024px longest side), compresses to JPEG (~80%), and persists
   * the image into app-private storage so thumbnail rendering and retries never lose URI access.
   */
  suspend fun prepareChatImage(context: Context, sourceUri: Uri): String? =
    withContext(Dispatchers.IO) {
      val appContext = context.applicationContext
      val bitmap = decodeSampledBitmap(appContext, sourceUri, MAX_DIMENSION_PX) ?: return@withContext null
      try {
        val scaled = scaleDownIfNeeded(bitmap, MAX_DIMENSION_PX)
        try {
          val dir = File(appContext.filesDir, IMAGE_DIR).apply { mkdirs() }
          val outFile = File(dir, "${UUID.randomUUID()}.jpg")
          FileOutputStream(outFile).use { fos ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
          }
          Uri.fromFile(outFile).toString()
        } finally {
          if (scaled !== bitmap) scaled.recycle()
        }
      } finally {
        bitmap.recycle()
      }
    }

  /**
   * Encodes an attached image URI into a Base64 JPEG string (compressed to <=1024px) for
   * Gemini `inlineData` payloads.
   */
  suspend fun encodeUriToBase64(context: Context, uriString: String): String? =
    withContext(Dispatchers.IO) {
      val appContext = context.applicationContext
      val uri = Uri.parse(uriString)

      // Fast path: if already saved by prepareChatImage in our private JPEG directory, read bytes directly.
      if (uri.scheme == "file") {
        val path = uri.path
        if (path != null) {
          val file = File(path)
          if (file.exists() && file.parentFile?.name == IMAGE_DIR) {
            return@withContext Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
          }
        }
      }

      val bitmap = decodeSampledBitmap(appContext, uri, MAX_DIMENSION_PX) ?: return@withContext null
      try {
        val scaled = scaleDownIfNeeded(bitmap, MAX_DIMENSION_PX)
        try {
          val out = ByteArrayOutputStream()
          scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
          Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
          if (scaled !== bitmap) scaled.recycle()
        }
      } finally {
        bitmap.recycle()
      }
    }

  /**
   * Loads a memory-bounded thumbnail [Bitmap] off the main thread for UI display.
   */
  suspend fun loadThumbnailBitmap(
    context: Context,
    uriString: String,
    maxPx: Int = 512,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      runCatching {
          decodeSampledBitmap(context.applicationContext, Uri.parse(uriString), maxPx)
        }
        .getOrNull()
    }

  private fun decodeSampledBitmap(context: Context, uri: Uri, reqMaxPx: Int): Bitmap? {
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openUriStream(context, uri)?.use { stream ->
      BitmapFactory.decodeStream(stream, null, boundsOpts)
    } ?: return null

    if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

    var sampleSize = 1
    var longest = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
    while (longest / 2 >= reqMaxPx) {
      sampleSize *= 2
      longest /= 2
    }

    val decodeOpts =
      BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.RGB_565
      }
    return openUriStream(context, uri)?.use { stream ->
      BitmapFactory.decodeStream(stream, null, decodeOpts)
    }
  }

  private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val longest = maxOf(width, height)
    if (longest <= maxDim) return bitmap

    val scale = maxDim.toFloat() / longest.toFloat()
    val targetW = (width * scale).toInt().coerceAtLeast(1)
    val targetH = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
  }

  private fun openUriStream(context: Context, uri: Uri) =
    if (uri.scheme == "file" && uri.path != null) {
      File(uri.path!!).takeIf { it.exists() }?.inputStream()
    } else {
      context.contentResolver.openInputStream(uri)
    }
}
