package com.example.data.util

import com.example.data.local.entity.MemoryEntity

data class ExtractedMemory(
  val category: String,
  val content: String,
)

data class MemoryParseResult(
  val cleanText: String,
  val extractedMemories: List<ExtractedMemory>,
)

/**
 * Pure helpers for building combined system instructions with long-term memory context and
 * extracting/stripping `[MEMORY_SAVE:category] ... [/MEMORY_SAVE]` tags from model responses.
 */
object MemoryPromptHelper {

  const val MEMORY_SAVE_INSTRUCTIONS: String =
    "You have a long-term memory system. When the user shares something durable and worth remembering for future conversations (their name, preferences, ongoing projects, important facts about their life/work), you can save it by including a special tagged block in your response using this exact format on its own line:\n" +
      "[MEMORY_SAVE:category] fact text here [/MEMORY_SAVE]\n" +
      "Only use this for genuinely durable, useful facts — not every message. Do not save sensitive personal data (passwords, financial details, health information) unless the user explicitly asks you to remember it. You may include multiple [MEMORY_SAVE] tags in one response if needed. These tags will be stripped from what the user sees — write them naturally within your response at the point where the information comes up."

  private const val TAG_PREFIX = "[MEMORY_SAVE:"

  private val MEMORY_TAG_REGEX =
    Regex("\\[MEMORY_SAVE:([\\w-]+)\\]\\s*(.*?)\\s*\\[/MEMORY_SAVE\\]", RegexOption.DOT_MATCHES_ALL)

  private val EXCESS_NEWLINES_REGEX = Regex("\\n{3,}")

  fun buildCombinedSystemInstruction(
    customSystemPrompt: String,
    memoryContextBlock: String,
    leadingInstruction: String = "",
    skillsIndexBlock: String = "",
    activeSkillBlock: String = "",
  ): String {
    val parts = buildList {
      val trimmedLeading = leadingInstruction.trim()
      if (trimmedLeading.isNotEmpty()) {
        add(trimmedLeading)
      }
      val trimmedCustom = customSystemPrompt.trim()
      if (trimmedCustom.isNotEmpty()) {
        add(trimmedCustom)
      }
      add(MEMORY_SAVE_INSTRUCTIONS)
      val trimmedMemory = memoryContextBlock.trim()
      if (trimmedMemory.isNotEmpty()) {
        add(trimmedMemory)
      }
      val trimmedSkillsIndex = skillsIndexBlock.trim()
      if (trimmedSkillsIndex.isNotEmpty()) {
        add(trimmedSkillsIndex)
      }
      val trimmedActiveSkill = activeSkillBlock.trim()
      if (trimmedActiveSkill.isNotEmpty()) {
        add(trimmedActiveSkill)
      }
    }
    return parts.joinToString(separator = "\n\n")
  }

  /**
   * Formats saved memories (ordered most-recently-updated first) into a compact system prompt
   * section capped by item count and character budget.
   */
  fun formatMemoryContextBlock(
    memories: List<MemoryEntity>,
    maxCount: Int = 45,
    maxChars: Int = 4000,
  ): String {
    if (memories.isEmpty()) return ""

    val header = "## What you remember about this user (long-term memory):"
    val sb = StringBuilder(header)
    var count = 0

    for (memory in memories) {
      if (count >= maxCount) break
      val cleanContent = memory.content.replace('\n', ' ').trim()
      if (cleanContent.isEmpty()) continue
      val cleanCategory = memory.category.trim().ifEmpty { MemoryEntity.CATEGORY_FACT }
      val line = "\n- [$cleanCategory] $cleanContent"
      if (sb.length + line.length > maxChars) {
        if (count == 0) {
          val remaining = (maxChars - sb.length - 1).coerceAtLeast(0)
          if (remaining > 16) {
            sb.append(line.take(remaining)).append("…")
            count++
          }
        }
        break
      }
      sb.append(line)
      count++
    }

    return if (count == 0) "" else sb.toString()
  }

  /**
   * Extracts all `[MEMORY_SAVE:category] ... [/MEMORY_SAVE]` blocks and returns the clean
   * user-visible response text with all tags stripped.
   */
  fun extractAndStripMemories(rawText: String): MemoryParseResult {
    if (!rawText.contains(TAG_PREFIX)) {
      return MemoryParseResult(cleanText = rawText.trim(), extractedMemories = emptyList())
    }

    val extracted = mutableListOf<ExtractedMemory>()
    for (match in MEMORY_TAG_REGEX.findAll(rawText)) {
      val category = match.groupValues[1].trim().lowercase().ifEmpty { MemoryEntity.CATEGORY_FACT }
      val content = match.groupValues[2].trim()
      if (content.isNotEmpty()) {
        extracted.add(ExtractedMemory(category = category, content = content))
      }
    }

    val stripped =
      rawText
        .replace(MEMORY_TAG_REGEX, "")
        .replace(EXCESS_NEWLINES_REGEX, "\n\n")
        .trim()

    return MemoryParseResult(cleanText = stripped, extractedMemories = extracted)
  }

  /**
   * Fast-path streaming sanitizer so partial or completed `[MEMORY_SAVE:...]` tags do not
   * flicker visibly in the chat bubble while tokens stream in.
   */
  fun stripStreamingTags(snapshot: String): String {
    if (!snapshot.contains(TAG_PREFIX)) return snapshot

    var result =
      snapshot
        .replace(MEMORY_TAG_REGEX, "")
        .replace(EXCESS_NEWLINES_REGEX, "\n\n")

    val openIdx = result.lastIndexOf(TAG_PREFIX)
    if (openIdx >= 0) {
      result = result.substring(0, openIdx)
    }
    return result.trimEnd()
  }
}
