package com.example.data.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.Immutable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Supported attachment categories stored in `message_attachments`.
 */
object AttachmentTypes {
  const val IMAGE = "image"
  const val PDF = "pdf"
  const val AUDIO = "audio"
  const val VIDEO = "video"
  const val TEXT = "text"
}

/**
 * In-memory composer representation of a file prepared and copied into app-private storage.
 */
@Immutable
data class PendingAttachment(
  val id: String = UUID.randomUUID().toString(),
  val type: String,
  val displayName: String,
  val mimeType: String,
  val localPath: String,
  val sizeBytes: Long,
)

enum class AttachmentFailureReason {
  PERMISSION_DENIED,
  DECODE_FAILED,
  UNSUPPORTED_BINARY,
  TEXT_TOO_LONG,
  PAYLOAD_BUDGET_EXCEEDED,
  IO_ERROR,
}

sealed interface AttachmentPrepareResult {
  data class Success(val attachment: PendingAttachment) : AttachmentPrepareResult

  data class Failure(
    val reason: AttachmentFailureReason,
    val userMessage: String,
  ) : AttachmentPrepareResult
}

/**
 * Handles reading, validating, compressing/copying, and encoding multi-file attachments
 * (images, PDFs, audio, video, and UTF-8 plain-text/code files) into app-private storage off
 * the main thread.
 */
object AttachmentProcessor {

  private const val TAG = "AttachmentProcessor"
  private const val ATTACHMENTS_DIR = "chat_attachments"

  /**
   * 15 MB combined base64 budget per request (~11.25 MB raw binary bytes across inlineData files).
   */
  const val MAX_REQUEST_BASE64_BYTES = 15L * 1024L * 1024L
  const val MAX_COMBINED_BINARY_BYTES = (MAX_REQUEST_BASE64_BYTES * 3L) / 4L
  const val MAX_SINGLE_VIDEO_BYTES = 10L * 1024L * 1024L
  const val MAX_TEXT_CHARS = 100_000
  private const val MAX_RAW_TEXT_READ_BYTES = 512L * 1024L

  private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
  private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "opus")
  private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "webm", "mkv", "3gp", "avi")
  private val TEXT_EXTENSIONS =
    setOf(
      "txt",
      "md",
      "markdown",
      "json",
      "csv",
      "tsv",
      "kt",
      "kts",
      "java",
      "py",
      "js",
      "jsx",
      "ts",
      "tsx",
      "xml",
      "html",
      "htm",
      "css",
      "yaml",
      "yml",
      "toml",
      "ini",
      "conf",
      "sh",
      "bash",
      "sql",
      "c",
      "cc",
      "cpp",
      "h",
      "hpp",
      "rs",
      "go",
      "rb",
      "php",
      "swift",
      "gradle",
      "properties",
      "log",
      "env",
    )

  /**
   * Prepares a picked [sourceUri], validates its format and size budget, and copies a
   * ready-to-send file into app-private storage (`filesDir/chat_attachments/`).
   */
  suspend fun prepareAttachment(
    context: Context,
    sourceUri: Uri,
    existingAttachments: List<PendingAttachment> = emptyList(),
  ): AttachmentPrepareResult =
    withContext(Dispatchers.IO) {
      val appContext = context.applicationContext
      val resolver = appContext.contentResolver

      val displayName = resolveDisplayName(resolver, sourceUri)
      val reportedMime = runCatching { resolver.getType(sourceUri) }.getOrNull()
      val ext = displayName.substringAfterLast('.', "").lowercase()
      val currentBinaryBytes =
        existingAttachments
          .filter { it.type != AttachmentTypes.TEXT }
          .sumOf { it.sizeBytes }

      when {
        isImageFile(reportedMime, ext) ->
          prepareImageAttachment(appContext, sourceUri, displayName, currentBinaryBytes)
        isPdfFile(reportedMime, ext) ->
          preparePdfAttachment(appContext, resolver, sourceUri, displayName, currentBinaryBytes)
        isAudioFile(reportedMime, ext) ->
          prepareAudioAttachment(
            appContext = appContext,
            resolver = resolver,
            sourceUri = sourceUri,
            displayName = displayName,
            reportedMime = reportedMime,
            ext = ext,
            currentBinaryBytes = currentBinaryBytes,
          )
        isVideoFile(reportedMime, ext) ->
          prepareVideoAttachment(
            appContext = appContext,
            resolver = resolver,
            sourceUri = sourceUri,
            displayName = displayName,
            reportedMime = reportedMime,
            ext = ext,
            currentBinaryBytes = currentBinaryBytes,
          )
        isKnownTextFile(reportedMime, ext) ->
          prepareTextAttachment(
            appContext = appContext,
            resolver = resolver,
            sourceUri = sourceUri,
            displayName = displayName,
            reportedMime = reportedMime,
          )
        else ->
          // Unknown MIME/extension: safely attempt strict UTF-8 decode and reject binary data.
          prepareTextAttachment(
            appContext = appContext,
            resolver = resolver,
            sourceUri = sourceUri,
            displayName = displayName,
            reportedMime = reportedMime,
          )
      }
    }

  private fun prepareImageAttachment(
    appContext: Context,
    sourceUri: Uri,
    displayName: String,
    currentBinaryBytes: Long,
  ): AttachmentPrepareResult {
    val dir = File(appContext.filesDir, ATTACHMENTS_DIR).apply { mkdirs() }
    val id = UUID.randomUUID().toString()
    val outFile = File(dir, "$id.jpg")
    val decodeResult = ImageProcessor.decodeAndCompressToJpegFile(appContext, sourceUri, outFile)

    decodeResult.onFailure { err ->
      return when (err) {
        is SecurityException ->
          AttachmentPrepareResult.Failure(
            AttachmentFailureReason.PERMISSION_DENIED,
            "Permission denied reading \"$displayName\". Please select the file again.",
          )
        is IllegalArgumentException ->
          AttachmentPrepareResult.Failure(
            AttachmentFailureReason.DECODE_FAILED,
            err.message ?: "Could not decode image \"$displayName\".",
          )
        else ->
          AttachmentPrepareResult.Failure(
            AttachmentFailureReason.IO_ERROR,
            err.message ?: "Could not read image \"$displayName\".",
          )
      }
    }

    val compressedBytes = outFile.length()
    if (currentBinaryBytes + compressedBytes > MAX_COMBINED_BINARY_BYTES) {
      outFile.delete()
      return AttachmentPrepareResult.Failure(
        AttachmentFailureReason.PAYLOAD_BUDGET_EXCEEDED,
        "Attachments exceed the 15 MB request limit.",
      )
    }

    val safeName =
      if (displayName.isBlank()) "image.jpg" else displayName
    return AttachmentPrepareResult.Success(
      PendingAttachment(
        id = id,
        type = AttachmentTypes.IMAGE,
        displayName = safeName,
        mimeType = "image/jpeg",
        localPath = outFile.absolutePath,
        sizeBytes = compressedBytes,
      )
    )
  }

  private fun preparePdfAttachment(
    appContext: Context,
    resolver: ContentResolver,
    sourceUri: Uri,
    displayName: String,
    currentBinaryBytes: Long,
  ): AttachmentPrepareResult {
    val dir = File(appContext.filesDir, ATTACHMENTS_DIR).apply { mkdirs() }
    val id = UUID.randomUUID().toString()
    val outFile = File(dir, "$id.pdf")

    return try {
      val reportedSize = queryFileSize(resolver, sourceUri)
      if (reportedSize != null && currentBinaryBytes + reportedSize > MAX_COMBINED_BINARY_BYTES) {
        return AttachmentPrepareResult.Failure(
          AttachmentFailureReason.PAYLOAD_BUDGET_EXCEEDED,
          "Adding \"$displayName\" would exceed the 15 MB request limit.",
        )
      }

      val input = resolver.openInputStream(sourceUri)
      if (input == null) {
        Log.e(TAG, "openInputStream returned null for PDF URI: $sourceUri")
        return AttachmentPrepareResult.Failure(
          AttachmentFailureReason.IO_ERROR,
          "Could not open PDF \"$displayName\".",
        )
      }

      var totalCopied = 0L
      input.use { src ->
        FileOutputStream(outFile).use { dst ->
          val buffer = ByteArray(16 * 1024)
          while (true) {
            val read = src.read(buffer)
            if (read <= 0) break
            totalCopied += read
            if (currentBinaryBytes + totalCopied > MAX_COMBINED_BINARY_BYTES) {
              outFile.delete()
              return AttachmentPrepareResult.Failure(
                AttachmentFailureReason.PAYLOAD_BUDGET_EXCEEDED,
                "Adding \"$displayName\" exceeds the 15 MB request limit.",
              )
            }
            dst.write(buffer, 0, read)
          }
        }
      }

      if (totalCopied <= 0L) {
        outFile.delete()
        return AttachmentPrepareResult.Failure(
          AttachmentFailureReason.DECODE_FAILED,
          "Selected PDF \"$displayName\" is empty.",
        )
      }

      AttachmentPrepareResult.Success(
        PendingAttachment(
          id = id,
          type = AttachmentTypes.PDF,
          displayName = displayName.ifBlank { "document.pdf" },
          mimeType = "application/pdf",
          localPath = outFile.absolutePath,
          sizeBytes = totalCopied,
        )
      )
    } catch (se: SecurityException) {
      Log.e(TAG, "SecurityException reading PDF URI: $sourceUri", se)
      outFile.delete()
      AttachmentPrepareResult.Failure(
        AttachmentFailureReason.PERMISSION_DENIED,
        "Permission denied reading \"$displayName\". Please select the file again.",
      )
    } catch (t: Throwable) {
      Log.e(TAG, "Error preparing PDF attachment $sourceUri", t)
      outFile.delete()
      AttachmentPrepareResult.Failure(
        AttachmentFailureReason.IO_ERROR,
        "Could not read PDF \"$displayName\": ${t.message ?: "I/O error"}",
      )
    }
  }

  private fun prepareAudioAttachment(
    appContext: Context,
    resolver: ContentResolver,
    sourceUri: Uri,
    displayName: String,
    reportedMime: String?,
    ext: String,
    currentBinaryBytes: Long,
  ): AttachmentPrepareResult {
    val resolvedMime =
      reportedMime
        ?.takeIf { it.startsWith("audio/", ignoreCase = true) && it != "audio/*" }
        ?.lowercase()
        ?: inferAudioMime(ext)
    val fileExt = ext.takeIf { it in AUDIO_EXTENSIONS } ?: "mp3"
    return prepareBinaryStreamAttachment(
      appContext = appContext,
      resolver = resolver,
      sourceUri = sourceUri,
      displayName = displayName.ifBlank { "audio.$fileExt" },
      attachmentType = AttachmentTypes.AUDIO,
      resolvedMime = resolvedMime,
      fileExtension = fileExt,
      typeLabel = "audio file",
      currentBinaryBytes = currentBinaryBytes,
      maxSingleFileBytes = null,
      singleFileLimitMessage = null,
    )
  }

  private fun prepareVideoAttachment(
    appContext: Context,
    resolver: ContentResolver,
    sourceUri: Uri,
    displayName: String,
    reportedMime: String?,
    ext: String,
    currentBinaryBytes: Long,
  ): AttachmentPrepareResult {
    val resolvedMime =
      reportedMime
        ?.takeIf { it.startsWith("video/", ignoreCase = true) && it != "video/*" }
        ?.lowercase()
        ?: inferVideoMime(ext)
    val fileExt = ext.takeIf { it in VIDEO_EXTENSIONS } ?: "mp4"
    return prepareBinaryStreamAttachment(
      appContext = appContext,
      resolver = resolver,
      sourceUri = sourceUri,
      displayName = displayName.ifBlank { "video.$fileExt" },
      attachmentType = AttachmentTypes.VIDEO,
      resolvedMime = resolvedMime,
      fileExtension = fileExt,
      typeLabel = "video file",
      currentBinaryBytes = currentBinaryBytes,
      maxSingleFileBytes = MAX_SINGLE_VIDEO_BYTES,
      singleFileLimitMessage =
        "This video is too large to attach (max ~10 MB). Try a shorter clip.",
    )
  }

  private fun prepareBinaryStreamAttachment(
    appContext: Context,
    resolver: ContentResolver,
    sourceUri: Uri,
    displayName: String,
    attachmentType: String,
    resolvedMime: String,
    fileExtension: String,
    typeLabel: String,
    currentBinaryBytes: Long,
    maxSingleFileBytes: Long?,
    singleFileLimitMessage: String?,
  ): AttachmentPrepareResult {
    val dir = File(appContext.filesDir, ATTACHMENTS_DIR).apply { mkdirs() }
    val id = UUID.randomUUID().toString()
    val outFile = File(dir, "$id.$fileExtension")

    return try {
      val reportedSize = queryFileSize(resolver, sourceUri)
      if (reportedSize != null) {
        if (maxSingleFileBytes != null && reportedSize > maxSingleFileBytes) {
          return AttachmentPrepareResult.Failure(
            AttachmentFailureReason.PAYLOAD_BUDGET_EXCEEDED,
            singleFileLimitMessage ?: "\"$displayName\" is too large to attach.",
          )
        }
        if (currentBinaryBytes + reportedSize > MAX_COMBINED_BINARY_BYTES) {
          return AttachmentPrepareResult.Failure(
            AttachmentFailureReason.PAYLOAD_BUDGET_EXCEEDED,
            "Adding \"$displayName\" would exceed the 15 MB request limit.",
          )
        }
      }

      val input = resolver.openInputStream(sourceUri)
      if (input == null) {
        Log.e(TAG, "openInputStream returned null for $typeLabel URI: $sourceUri")
        return AttachmentPrepareResult.Failure(
          AttachmentFailureReason.IO_ERROR,
          "Could not open $typeLabel \"$displayName\".",
        )
      }

      var totalCopied = 0L
      input.use { src ->
        FileOutputStream(outFile).use { dst ->
          val buffer = ByteArray(16 * 1024)
          while (true) {
            val read = src.read(buffer)
            if (read <= 0) break
            totalCopied += read
            if (maxSingleFileBytes != null && totalCopied > maxSingleFileBytes) {
              outFile.delete()
              return AttachmentPrepareResult.Failure(
                AttachmentFailureReason.PAYLOAD_BUDGET_EXCEEDED,
                singleFileLimitMessage ?: "\"$displayName\" is too large to attach.",
              )
            }
            if (currentBinaryBytes + totalCopied > MAX_COMBINED_BINARY_BYTES) {
              outFile.delete()
              return AttachmentPrepareResult.Failure(
                AttachmentFailureReason.PAYLOAD_BUDGET_EXCEEDED,
                "Adding \"$displayName\" exceeds the 15 MB request limit.",
              )
            }
            dst.write(buffer, 0, read)
          }
        }
      }

      if (totalCopied <= 0L) {
        outFile.delete()
        return AttachmentPrepareResult.Failure(
          AttachmentFailureReason.DECODE_FAILED,
          "Selected $typeLabel \"$displayName\" is empty.",
        )
      }

      AttachmentPrepareResult.Success(
        PendingAttachment(
          id = id,
          type = attachmentType,
          displayName = displayName,
          mimeType = resolvedMime,
          localPath = outFile.absolutePath,
          sizeBytes = totalCopied,
        )
      )
    } catch (se: SecurityException) {
      Log.e(TAG, "SecurityException reading $typeLabel URI: $sourceUri", se)
      outFile.delete()
      AttachmentPrepareResult.Failure(
        AttachmentFailureReason.PERMISSION_DENIED,
        "Permission denied reading \"$displayName\". Please select the file again.",
      )
    } catch (t: Throwable) {
      Log.e(TAG, "Error preparing $typeLabel attachment $sourceUri", t)
      outFile.delete()
      AttachmentPrepareResult.Failure(
        AttachmentFailureReason.IO_ERROR,
        "Could not read $typeLabel \"$displayName\": ${t.message ?: "I/O error"}",
      )
    }
  }

  private fun prepareTextAttachment(
    appContext: Context,
    resolver: ContentResolver,
    sourceUri: Uri,
    displayName: String,
    reportedMime: String?,
  ): AttachmentPrepareResult {
    return try {
      val input = resolver.openInputStream(sourceUri)
      if (input == null) {
        Log.e(TAG, "openInputStream returned null for text URI: $sourceUri")
        return AttachmentPrepareResult.Failure(
          AttachmentFailureReason.IO_ERROR,
          "Could not open \"$displayName\".",
        )
      }

      val rawBytes =
        input.use { stream ->
          val out = ByteArrayOutputStream()
          val buffer = ByteArray(16 * 1024)
          var total = 0L
          while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_RAW_TEXT_READ_BYTES) {
              return AttachmentPrepareResult.Failure(
                AttachmentFailureReason.TEXT_TOO_LONG,
                "\"$displayName\" is too large (max $MAX_TEXT_CHARS characters).",
              )
            }
            out.write(buffer, 0, read)
          }
          out.toByteArray()
        }

      val decodedText =
        decodeStrictUtf8OrNull(rawBytes)
          ?: return AttachmentPrepareResult.Failure(
            AttachmentFailureReason.UNSUPPORTED_BINARY,
            "\"$displayName\" is not a readable plain-text file.",
          )

      if (decodedText.length > MAX_TEXT_CHARS) {
        return AttachmentPrepareResult.Failure(
          AttachmentFailureReason.TEXT_TOO_LONG,
          "\"$displayName\" has ${decodedText.length} characters (max $MAX_TEXT_CHARS).",
        )
      }

      val dir = File(appContext.filesDir, ATTACHMENTS_DIR).apply { mkdirs() }
      val id = UUID.randomUUID().toString()
      val outFile = File(dir, "$id.txt")
      outFile.writeText(decodedText, Charsets.UTF_8)

      AttachmentPrepareResult.Success(
        PendingAttachment(
          id = id,
          type = AttachmentTypes.TEXT,
          displayName = displayName.ifBlank { "notes.txt" },
          mimeType = reportedMime?.takeIf { it.startsWith("text/") } ?: "text/plain",
          localPath = outFile.absolutePath,
          sizeBytes = outFile.length(),
        )
      )
    } catch (se: SecurityException) {
      Log.e(TAG, "SecurityException reading text URI: $sourceUri", se)
      AttachmentPrepareResult.Failure(
        AttachmentFailureReason.PERMISSION_DENIED,
        "Permission denied reading \"$displayName\".",
      )
    } catch (t: Throwable) {
      Log.e(TAG, "Error preparing text attachment $sourceUri", t)
      AttachmentPrepareResult.Failure(
        AttachmentFailureReason.IO_ERROR,
        "Could not read \"$displayName\": ${t.message ?: "I/O error"}",
      )
    }
  }

  /**
   * Decodes [bytes] strictly as UTF-8, rejecting binary files (malformed UTF-8 or NUL bytes).
   */
  fun decodeStrictUtf8OrNull(bytes: ByteArray): String? {
    for (b in bytes) {
      if (b.toInt() == 0) return null
    }
    return try {
      val decoder =
        Charsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
      decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
      null
    }
  }

  /**
   * Encodes a local binary file (JPEG image or PDF) to Base64 without newlines, returning null on error.
   */
  suspend fun readFileAsBase64(context: Context, localPathOrUri: String): String? =
    withContext(Dispatchers.IO) {
      val directFile =
        when {
          localPathOrUri.startsWith("/") -> File(localPathOrUri)
          localPathOrUri.startsWith("file://") -> Uri.parse(localPathOrUri).path?.let(::File)
          else -> null
        }
      if (directFile != null && directFile.exists()) {
        return@withContext runCatching {
            Base64.encodeToString(directFile.readBytes(), Base64.NO_WRAP)
          }
          .onFailure { Log.e(TAG, "Failed to Base64 encode ${directFile.path}", it) }
          .getOrNull()
      }
      // Fallback for legacy content URIs
      ImageProcessor.encodeUriToBase64(context, localPathOrUri)
    }

  suspend fun readTextAttachmentContent(localPath: String): String? =
    withContext(Dispatchers.IO) {
      runCatching {
          val file = File(localPath)
          if (!file.exists()) return@runCatching null
          file.readText(Charsets.UTF_8).take(MAX_TEXT_CHARS)
        }
        .onFailure { Log.e(TAG, "Failed to read text attachment $localPath", it) }
        .getOrNull()
    }

  fun readUriAsValidatedUtf8(
    context: Context,
    uri: Uri,
    maxChars: Int = MAX_TEXT_CHARS,
  ): Result<Pair<String, String>> {
    val resolver = context.applicationContext.contentResolver
    val name = resolveDisplayName(resolver, uri)
    return try {
      val input = resolver.openInputStream(uri) ?: return Result.failure(IllegalStateException("Could not open $name"))
      val bytes =
        input.use { stream ->
          val out = ByteArrayOutputStream()
          val buf = ByteArray(16 * 1024)
          var total = 0L
          while (true) {
            val read = stream.read(buf)
            if (read <= 0) break
            total += read
            if (total > MAX_RAW_TEXT_READ_BYTES) {
              return Result.failure(IllegalArgumentException("$name exceeds size limit."))
            }
            out.write(buf, 0, read)
          }
          out.toByteArray()
        }
      val text =
        decodeStrictUtf8OrNull(bytes)
          ?: return Result.failure(IllegalArgumentException("$name is not a valid UTF-8 text file."))
      if (text.length > maxChars) {
        return Result.failure(IllegalArgumentException("$name exceeds $maxChars characters."))
      }
      Result.success(name to text)
    } catch (t: Throwable) {
      Result.failure(t)
    }
  }

  fun formatTextAttachmentForPrompt(displayName: String, content: String): String =
    "[Attached file: $displayName]\n```\n$content\n```"

  fun resolveDisplayName(resolver: ContentResolver, uri: Uri): String {
    if (uri.scheme == "file") {
      return uri.lastPathSegment ?: "file"
    }
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) {
              val value = cursor.getString(idx)
              if (!value.isNullOrBlank()) return value
            }
          }
        }
      }
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "file" } ?: "file"
  }

  private fun queryFileSize(resolver: ContentResolver, uri: Uri): Long? {
    if (uri.scheme == "file" && uri.path != null) {
      return File(uri.path!!).takeIf { it.exists() }?.length()
    }
    return runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
          } else {
            null
          }
        }
      }
      .getOrNull()
  }

  internal fun isImageFile(mimeType: String?, ext: String): Boolean =
    mimeType?.startsWith("image/", ignoreCase = true) == true || ext in IMAGE_EXTENSIONS

  internal fun isPdfFile(mimeType: String?, ext: String): Boolean =
    mimeType.equals("application/pdf", ignoreCase = true) || ext == "pdf"

  internal fun isAudioFile(mimeType: String?, ext: String): Boolean =
    mimeType?.startsWith("audio/", ignoreCase = true) == true || ext in AUDIO_EXTENSIONS

  internal fun isVideoFile(mimeType: String?, ext: String): Boolean =
    mimeType?.startsWith("video/", ignoreCase = true) == true || ext in VIDEO_EXTENSIONS

  private fun inferAudioMime(ext: String): String =
    when (ext.lowercase()) {
      "wav" -> "audio/wav"
      "m4a" -> "audio/mp4"
      "aac" -> "audio/aac"
      "ogg" -> "audio/ogg"
      "flac" -> "audio/flac"
      "opus" -> "audio/opus"
      else -> "audio/mpeg"
    }

  private fun inferVideoMime(ext: String): String =
    when (ext.lowercase()) {
      "mov" -> "video/quicktime"
      "webm" -> "video/webm"
      "mkv" -> "video/x-matroska"
      "3gp" -> "video/3gpp"
      "avi" -> "video/x-msvideo"
      else -> "video/mp4"
    }

  private fun isKnownTextFile(mimeType: String?, ext: String): Boolean =
    mimeType?.startsWith("text/", ignoreCase = true) == true ||
      mimeType.equals("application/json", ignoreCase = true) ||
      mimeType.equals("application/xml", ignoreCase = true) ||
      mimeType.equals("application/javascript", ignoreCase = true) ||
      ext in TEXT_EXTENSIONS
}
