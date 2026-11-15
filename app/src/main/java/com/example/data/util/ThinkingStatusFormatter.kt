package com.example.data.util

/**
 * Derives a short, live-updating status title (typically <= 6-8 words) from Gemini's raw
 * accumulated thinking text so the user sees meaningful reasoning progress before the answer starts.
 */
object ThinkingStatusFormatter {

  private const val DEFAULT_MAX_CHARS = 58
  private const val MAX_WORDS = 8

  private val MARKDOWN_PREFIX_REGEX = Regex("^(?:[#>*-]+|\\d+[.)])\\s*")
  private val MARKDOWN_WRAPPER_REGEX = Regex("[*_`]+")
  private val WHITESPACE_REGEX = Regex("\\s+")
  private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+|\\n+")

  private val LEADING_FILLER_PREFIXES =
    listOf(
      "first, i need to ",
      "first, i should ",
      "first, let me ",
      "next, i need to ",
      "next, i should ",
      "then, i need to ",
      "now, let me ",
      "now let me ",
      "i need to ",
      "i should ",
      "i will ",
      "i'll ",
      "let me ",
      "let's ",
      "we need to ",
      "we should ",
    )

  private val VERB_TO_GERUND =
    mapOf(
      "analyze" to "Analyzing",
      "analyse" to "Analysing",
      "break" to "Breaking",
      "build" to "Building",
      "calculate" to "Calculating",
      "check" to "Checking",
      "choose" to "Choosing",
      "compare" to "Comparing",
      "consider" to "Considering",
      "construct" to "Constructing",
      "create" to "Creating",
      "decide" to "Deciding",
      "design" to "Designing",
      "determine" to "Determining",
      "draft" to "Drafting",
      "evaluate" to "Evaluating",
      "examine" to "Examining",
      "explain" to "Explaining",
      "explore" to "Exploring",
      "find" to "Finding",
      "format" to "Formatting",
      "gather" to "Gathering",
      "identify" to "Identifying",
      "inspect" to "Inspecting",
      "list" to "Listing",
      "look" to "Looking",
      "make" to "Making",
      "map" to "Mapping",
      "organize" to "Organizing",
      "outline" to "Outlining",
      "plan" to "Planning",
      "prepare" to "Preparing",
      "read" to "Reading",
      "recall" to "Recalling",
      "resolve" to "Resolving",
      "review" to "Reviewing",
      "search" to "Searching",
      "select" to "Selecting",
      "solve" to "Solving",
      "structure" to "Structuring",
      "summarize" to "Summarizing",
      "synthesize" to "Synthesizing",
      "think" to "Thinking",
      "trace" to "Tracing",
      "understand" to "Understanding",
      "verify" to "Verifying",
      "weigh" to "Weighing",
      "write" to "Writing",
    )

  /**
   * Formats accumulated [rawThinking] into a concise live status label (or `null` if there isn't
   * enough meaningful content yet).
   */
  fun formatStatus(
    rawThinking: String,
    maxChars: Int = DEFAULT_MAX_CHARS,
  ): String? {
    if (rawThinking.isBlank()) return null

    val segments =
      rawThinking
        .replace("\r\n", "\n")
        .split(SENTENCE_SPLIT_REGEX)
        .map { cleanSegment(it) }
        .filter { it.isNotEmpty() }

    if (segments.isEmpty()) return null

    // Prefer the most recent substantial segment so the status evolves as reasoning progresses,
    // avoiding tiny trailing fragments right after a punctuation boundary.
    val candidate =
      segments.lastOrNull { seg ->
        seg.length >= 10 || seg.count { it == ' ' } >= 1
      } ?: segments.last()

    if (candidate.count { it.isLetterOrDigit() } < 3) return null

    val phrased = rephraseToStatus(candidate)
    if (phrased.isEmpty()) return null

    return truncateToStatusBudget(phrased, maxChars)
  }

  private fun cleanSegment(raw: String): String {
    return raw
      .trim()
      .replace(MARKDOWN_PREFIX_REGEX, "")
      .replace(MARKDOWN_WRAPPER_REGEX, "")
      .replace(WHITESPACE_REGEX, " ")
      .trim()
      .trimEnd('.', ',', ';', ':', '-', '!')
  }

  private fun rephraseToStatus(segment: String): String {
    val lower = segment.lowercase()
    var body = segment
    var converted = false

    for (prefix in LEADING_FILLER_PREFIXES) {
      if (lower.startsWith(prefix)) {
        val afterPrefix = segment.substring(prefix.length).trimStart()
        if (afterPrefix.isNotEmpty()) {
          val firstWordEnd = afterPrefix.indexOf(' ')
          val firstWord =
            if (firstWordEnd > 0) afterPrefix.substring(0, firstWordEnd) else afterPrefix
          val rest =
            if (firstWordEnd > 0) afterPrefix.substring(firstWordEnd) else ""
          val gerund = VERB_TO_GERUND[firstWord.lowercase()]
          body =
            if (gerund != null) {
              "$gerund$rest"
            } else {
              afterPrefix
            }
          converted = true
        }
        break
      }
    }

    val normalized =
      if (converted) {
        body
      } else {
        segment.replaceFirstChar { ch ->
          if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }
      }

    return normalized.trim()
  }

  private fun truncateToStatusBudget(
    text: String,
    maxChars: Int,
  ): String {
    val words = text.split(' ').filter { it.isNotEmpty() }
    val wordCapped =
      if (words.size > MAX_WORDS) {
        words.take(MAX_WORDS).joinToString(" ")
      } else {
        words.joinToString(" ")
      }

    val charLimit = (maxChars - 1).coerceAtLeast(12)
    val clipped =
      if (wordCapped.length <= charLimit) {
        wordCapped
      } else {
        val cut = wordCapped.substring(0, charLimit)
        val lastSpace = cut.lastIndexOf(' ')
        if (lastSpace >= charLimit / 2) cut.substring(0, lastSpace).trimEnd()
        else cut.trimEnd()
      }

    val cleanBase = clipped.trimEnd('.', ',', ';', ':', '-', ' ')
    return if (cleanBase.isEmpty()) "" else "$cleanBase…"
  }
}
