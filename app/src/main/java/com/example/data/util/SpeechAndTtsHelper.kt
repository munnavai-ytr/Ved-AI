package com.example.data.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.os.ConfigurationCompat
import com.example.R
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure utilities for voice recognition transcript appending and Text-to-Speech locale/chunk
 * preparation.
 */
object SpeechAndTtsUtils {

  fun appendRecognizedSpeech(existingText: String, recognized: String): String {
    val cleaned = recognized.trim()
    if (cleaned.isEmpty()) return existingText
    if (existingText.isBlank()) return cleaned
    return if (existingText.last().isWhitespace()) {
      existingText + cleaned
    } else {
      "$existingText $cleaned"
    }
  }

  /**
   * Resolves the best recognition language tag prioritizing Bengali or English based on the
   * device's active input/system locale.
   */
  fun resolveSpeechLocaleTag(context: Context): String {
    val configLocale =
      ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
    val lang = configLocale.language.lowercase()
    return when {
      lang == "bn" -> if (configLocale.country.isNotBlank()) configLocale.toLanguageTag() else "bn-BD"
      lang == "en" -> if (configLocale.country.isNotBlank()) configLocale.toLanguageTag() else "en-US"
      else -> configLocale.toLanguageTag().ifBlank { "en-US" }
    }
  }

  /**
   * Detects Bengali script vs default locale for Text-to-Speech playback.
   */
  fun detectTtsLocale(text: String, defaultLocale: Locale = Locale.getDefault()): Locale {
    val hasBengali = text.any { ch -> ch in '\u0980'..'\u09FF' }
    if (hasBengali) {
      return Locale.forLanguageTag("bn-BD")
    }
    return if (defaultLocale.language.equals("bn", ignoreCase = true)) {
      Locale.US
    } else {
      defaultLocale
    }
  }

  /**
   * Splits [text] cleanly into chunks under [maxChunkLength] on sentence/whitespace boundaries
   * so long AI responses never exceed `TextToSpeech.getMaxSpeechInputLength()`.
   */
  fun splitTextForTts(
    text: String,
    maxChunkLength: Int = 3500,
  ): List<String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.length <= maxChunkLength) return listOf(trimmed)

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < trimmed.length) {
      val remaining = trimmed.length - start
      if (remaining <= maxChunkLength) {
        chunks.add(trimmed.substring(start).trim())
        break
      }
      val windowEnd = start + maxChunkLength
      val sentenceBreak =
        maxOf(
          trimmed.lastIndexOf('.', windowEnd),
          trimmed.lastIndexOf('!', windowEnd),
          trimmed.lastIndexOf('?', windowEnd),
          trimmed.lastIndexOf('\n', windowEnd),
          trimmed.lastIndexOf('।', windowEnd),
        )
      val splitAt =
        if (sentenceBreak > start + maxChunkLength / 3) {
          sentenceBreak + 1
        } else {
          val spaceBreak = trimmed.lastIndexOf(' ', windowEnd)
          if (spaceBreak > start) spaceBreak else windowEnd
        }
      val piece = trimmed.substring(start, splitAt).trim()
      if (piece.isNotEmpty()) {
        chunks.add(piece)
      }
      start = splitAt
      while (start < trimmed.length && trimmed[start].isWhitespace()) start++
    }
    return chunks
  }
}

/**
 * Lifecycle-aware wrapper around Android's built-in [SpeechRecognizer] supporting Bengali and
 * English free-form dictation.
 */
class VoiceInputController(
  private val context: Context,
  private val onFinalText: (String) -> Unit,
  private val onErrorMessage: (String) -> Unit,
) {
  private val appContext = context.applicationContext
  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

  private var speechRecognizer: SpeechRecognizer? = null

  fun toggleListening() {
    if (_isListening.value) {
      stopListening()
    } else {
      startListening()
    }
  }

  fun startListening() {
    if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
      _isListening.value = false
      onErrorMessage(appContext.getString(R.string.chat_speech_unavailable))
      return
    }

    runCatching {
        val recognizer =
          speechRecognizer
            ?: SpeechRecognizer.createSpeechRecognizer(appContext).also { created ->
              created.setRecognitionListener(createListener())
              speechRecognizer = created
            }

        val localeTag = SpeechAndTtsUtils.resolveSpeechLocaleTag(appContext)
        val intent =
          Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
              RecognizerIntent.EXTRA_LANGUAGE_MODEL,
              RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
          }
        _isListening.value = true
        recognizer.startListening(intent)
      }
      .onFailure {
        _isListening.value = false
        onErrorMessage(appContext.getString(R.string.chat_speech_error))
      }
  }

  fun stopListening() {
    _isListening.value = false
    runCatching { speechRecognizer?.stopListening() }
  }

  fun destroy() {
    _isListening.value = false
    runCatching { speechRecognizer?.destroy() }
    speechRecognizer = null
  }

  private fun createListener(): RecognitionListener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        _isListening.value = true
      }

      override fun onBeginningOfSpeech() {}

      override fun onRmsChanged(rmsdB: Float) {}

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {
        _isListening.value = false
      }

      override fun onError(error: Int) {
        _isListening.value = false
        val message =
          when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
              appContext.getString(R.string.chat_speech_no_match)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
              appContext.getString(R.string.chat_mic_permission_denied)
            else -> appContext.getString(R.string.chat_speech_error)
          }
        onErrorMessage(message)
      }

      override fun onResults(results: Bundle?) {
        _isListening.value = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val best = matches?.firstOrNull()?.trim().orEmpty()
        if (best.isBlank()) {
          onErrorMessage(appContext.getString(R.string.chat_speech_no_match))
        } else {
          onFinalText(best)
        }
      }

      override fun onPartialResults(partialResults: Bundle?) {}

      override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

/**
 * Single-utterance Text-to-Speech controller ensuring at most one AI message plays at a time
 * and handling unsupported language checks gracefully.
 */
class ChatTtsManager(
  context: Context,
  private val onErrorMessage: (String) -> Unit,
) {
  private val appContext = context.applicationContext
  private val _speakingMessageId = MutableStateFlow<String?>(null)
  val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

  private var tts: TextToSpeech? = null
  private var isReady: Boolean = false
  private var initFailed: Boolean = false
  private var pendingMessageId: String? = null
  private var pendingText: String? = null
  private var activeFinalUtteranceId: String? = null

  private fun ensureInitialized() {
    if (tts != null || initFailed) return
    tts =
      TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
          isReady = true
          tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
              override fun onStart(utteranceId: String?) {}

              override fun onDone(utteranceId: String?) {
                if (utteranceId == activeFinalUtteranceId) {
                  _speakingMessageId.value = null
                }
              }

              @Deprecated("Deprecated in Java")
              override fun onError(utteranceId: String?) {
                if (utteranceId == activeFinalUtteranceId || utteranceId != null) {
                  _speakingMessageId.value = null
                  onErrorMessage(appContext.getString(R.string.chat_tts_unavailable))
                }
              }

              override fun onError(utteranceId: String?, errorCode: Int) {
                _speakingMessageId.value = null
                onErrorMessage(appContext.getString(R.string.chat_tts_unavailable))
              }
            }
          )
          val msgId = pendingMessageId
          val text = pendingText
          pendingMessageId = null
          pendingText = null
          if (msgId != null && text != null) {
            speakInternal(msgId, text)
          }
        } else {
          initFailed = true
          _speakingMessageId.value = null
          pendingMessageId = null
          pendingText = null
          onErrorMessage(appContext.getString(R.string.chat_tts_unavailable))
        }
      }
  }

  fun toggleSpeak(messageId: String, plainText: String) {
    if (_speakingMessageId.value == messageId) {
      stop()
      return
    }
    if (plainText.isBlank()) return

    stop()
    if (initFailed) {
      onErrorMessage(appContext.getString(R.string.chat_tts_unavailable))
      return
    }

    _speakingMessageId.value = messageId
    if (!isReady) {
      pendingMessageId = messageId
      pendingText = plainText
      ensureInitialized()
      return
    }
    speakInternal(messageId, plainText)
  }

  private fun speakInternal(messageId: String, plainText: String) {
    val engine = tts
    if (engine == null) {
      _speakingMessageId.value = null
      onErrorMessage(appContext.getString(R.string.chat_tts_unavailable))
      return
    }

    val targetLocale = SpeechAndTtsUtils.detectTtsLocale(plainText)
    val langStatus = runCatching { engine.setLanguage(targetLocale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
    if (langStatus == TextToSpeech.LANG_MISSING_DATA || langStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
      _speakingMessageId.value = null
      onErrorMessage(
        appContext.getString(
          R.string.chat_tts_lang_unavailable,
          targetLocale.displayLanguage.ifBlank { targetLocale.language },
        )
      )
      return
    }

    val maxLen = TextToSpeech.getMaxSpeechInputLength().coerceIn(500, 3800)
    val chunks = SpeechAndTtsUtils.splitTextForTts(plainText, maxChunkLength = maxLen)
    if (chunks.isEmpty()) {
      _speakingMessageId.value = null
      return
    }

    val groupToken = UUID.randomUUID().toString()
    activeFinalUtteranceId = "${groupToken}_${chunks.lastIndex}"
    _speakingMessageId.value = messageId

    chunks.forEachIndexed { index, chunk ->
      val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
      engine.speak(chunk, mode, null, "${groupToken}_$index")
    }
  }

  fun stop() {
    pendingMessageId = null
    pendingText = null
    activeFinalUtteranceId = null
    _speakingMessageId.value = null
    runCatching { tts?.stop() }
  }

  fun shutdown() {
    stop()
    runCatching { tts?.shutdown() }
    tts = null
    isReady = false
  }
}
