package com.example.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background image compression, persistence, thumbnail loading, and Base64 encoding utilities
 * for Gemini multimodal attachments.
 */
object ImageProcessor {

  private const val TAG = "ImageProcessor"
  const val MAX_DIMENSION_PX = 1024
  const val JPEG_QUALITY = 80
  private const val IMAGE_DIR = "chat_images"

  /**
   * Decodes, downscales (max 1024px longest side), compresses to JPEG (~80%), and persists
   * the image into app-private storage so thumbnail rendering and retries never lose URI access.
   */
  suspend fun prepareChatImage(context: Context, sourceUri: Uri): String? =
    withContext(Dispatchers.IO) {
      val dir = File(context.applicationContext.filesDir, IMAGE_DIR).apply { mkdirs() }
      val outFile = File(dir, "${UUID.randomUUID()}.jpg")
      val ok = decodeAndCompressToJpegFile(context.applicationContext, sourceUri, outFile).isSuccess
      if (ok) Uri.fromFile(outFile).toString() else null
    }

  /**
   * Decodes any readable image URI (using [ImageDecoder] on API 28+ with [BitmapFactory]
   * fallback on API 24–27), downscales to <= [MAX_DIMENSION_PX], and writes a compressed JPEG
   * into [targetFile]. Logs detailed errors at every failure point.
   */
  internal fun decodeAndCompressToJpegFile(
    appContext: Context,
    sourceUri: Uri,
    targetFile: File,
  ): Result<Long> {
    return try {
      val bitmap = decodeSampledBitmap(appContext, sourceUri, MAX_DIMENSION_PX)
      if (bitmap == null) {
        Log.e(TAG, "Failed to decode bitmap (null result) for URI: $sourceUri")
        return Result.failure(IllegalArgumentException("Unsupported or corrupted image format."))
      }
      try {
        val scaled = scaleDownIfNeeded(bitmap, MAX_DIMENSION_PX)
        try {
          targetFile.parentFile?.mkdirs()
          FileOutputStream(targetFile).use { fos ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)) {
              Log.e(TAG, "Bitmap.compress returned false for URI: $sourceUri")
              return Result.failure(IllegalStateException("Could not compress selected image."))
            }
          }
          val writtenBytes = targetFile.length()
          if (writtenBytes <= 0L) {
            targetFile.delete()
            Log.e(TAG, "Compressed JPEG file is empty for URI: $sourceUri")
            return Result.failure(IllegalStateException("Compressed image was empty."))
          }
          Result.success(writtenBytes)
        } finally {
          if (scaled !== bitmap) scaled.recycle()
        }
      } finally {
        bitmap.recycle()
      }
    } catch (se: SecurityException) {
      Log.e(TAG, "SecurityException reading image URI: $sourceUri", se)
      targetFile.delete()
      Result.failure(se)
    } catch (oom: OutOfMemoryError) {
      Log.e(TAG, "OutOfMemoryError decoding image URI: $sourceUri", oom)
      targetFile.delete()
      Result.failure(IllegalStateException("Selected image is too large to decode.", oom))
    } catch (t: Throwable) {
      Log.e(TAG, "Error preparing image URI: $sourceUri", t)
      targetFile.delete()
      Result.failure(t)
    }
  }

  /**
   * Encodes an attached image path or URI into a Base64 JPEG string (compressed to <=1024px) for
   * Gemini `inlineData` payloads.
   */
  suspend fun encodeUriToBase64(context: Context, pathOrUri: String): String? =
    withContext(Dispatchers.IO) {
      val appContext = context.applicationContext
      val directFile = resolveDirectFile(pathOrUri)
      if (directFile != null && directFile.exists()) {
        return@withContext runCatching {
            Base64.encodeToString(directFile.readBytes(), Base64.NO_WRAP)
          }
          .onFailure { Log.e(TAG, "Failed to read cached image bytes: ${directFile.path}", it) }
          .getOrNull()
      }

      val uri = Uri.parse(pathOrUri)
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
    pathOrUri: String,
    maxPx: Int = 512,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      runCatching {
          val directFile = resolveDirectFile(pathOrUri)
          val uri = if (directFile != null) Uri.fromFile(directFile) else Uri.parse(pathOrUri)
          decodeSampledBitmap(context.applicationContext, uri, maxPx)
        }
        .onFailure { Log.e(TAG, "Thumbnail load failed for $pathOrUri", it) }
        .getOrNull()
    }

  private fun resolveDirectFile(pathOrUri: String): File? {
    if (pathOrUri.startsWith("/")) {
      return File(pathOrUri)
    }
    val parsed = Uri.parse(pathOrUri)
    if (parsed.scheme == "file" && parsed.path != null) {
      return File(parsed.path!!)
    }
    return null
  }

  private fun decodeSampledBitmap(context: Context, uri: Uri, reqMaxPx: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && uri.scheme != "file") {
      return decodeWithImageDecoder(context, uri, reqMaxPx)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && uri.scheme == "file" && uri.path != null) {
      val file = File(uri.path!!)
      if (file.exists()) {
        return decodeFileWithImageDecoder(file, reqMaxPx)
      }
    }
    return decodeWithBitmapFactory(context, uri, reqMaxPx)
  }

  private fun decodeWithImageDecoder(context: Context, uri: Uri, reqMaxPx: Int): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return try {
      val source = ImageDecoder.createSource(context.contentResolver, uri)
      ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val sampleSize = computeSampleSize(info.size.width, info.size.height, reqMaxPx)
        if (sampleSize > 1) {
          decoder.setTargetSampleSize(sampleSize)
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
    } catch (se: SecurityException) {
      throw se
    } catch (t: Throwable) {
      Log.w(TAG, "ImageDecoder failed for $uri, falling back to BitmapFactory", t)
      decodeWithBitmapFactory(context, uri, reqMaxPx)
    }
  }

  private fun decodeFileWithImageDecoder(file: File, reqMaxPx: Int): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return try {
      val source = ImageDecoder.createSource(file)
      ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val sampleSize = computeSampleSize(info.size.width, info.size.height, reqMaxPx)
        if (sampleSize > 1) {
          decoder.setTargetSampleSize(sampleSize)
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
    } catch (t: Throwable) {
      Log.w(TAG, "ImageDecoder failed for file ${file.path}, falling back to BitmapFactory", t)
      decodeFileWithBitmapFactory(file, reqMaxPx)
    }
  }

  private fun decodeWithBitmapFactory(context: Context, uri: Uri, reqMaxPx: Int): Bitmap? {
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openUriStream(context, uri)?.use { stream ->
      BitmapFactory.decodeStream(stream, null, boundsOpts)
    } ?: run {
      Log.e(TAG, "ContentResolver.openInputStream returned null during bounds decode for $uri")
      return null
    }

    if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
      Log.e(TAG, "Invalid image bounds (${boundsOpts.outWidth}x${boundsOpts.outHeight}) for $uri")
      return null
    }

    val decodeOpts =
      BitmapFactory.Options().apply {
        inSampleSize = computeSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, reqMaxPx)
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
    return openUriStream(context, uri)?.use { stream ->
      BitmapFactory.decodeStream(stream, null, decodeOpts)
    }
  }

  private fun decodeFileWithBitmapFactory(file: File, reqMaxPx: Int): Bitmap? {
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, boundsOpts)
    if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

    val decodeOpts =
      BitmapFactory.Options().apply {
        inSampleSize = computeSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, reqMaxPx)
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
    return BitmapFactory.decodeFile(file.path, decodeOpts)
  }

  private fun computeSampleSize(width: Int, height: Int, reqMaxPx: Int): Int {
    var sampleSize = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= reqMaxPx) {
      sampleSize *= 2
      longest /= 2
    }
    return sampleSize
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

  private fun openUriStream(context: Context, uri: Uri): InputStream? =
    if (uri.scheme == "file" && uri.path != null) {
      File(uri.path!!).takeIf { it.exists() }?.inputStream()
    } else {
      context.contentResolver.openInputStream(uri)
    }
}
