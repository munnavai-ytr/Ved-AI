package com.example.data.util

import com.example.data.local.entity.SkillEntity
import java.util.Locale

data class SlashCommandSuggestion(
  val command: String,
  val slug: String,
  val skill: SkillEntity,
  val isBuiltIn: Boolean,
)

data class ParsedSlashMessage(
  val matchedSkill: SkillEntity?,
  val strippedPrompt: String,
)

sealed interface SkillCreateParseResult {
  data class NotPresent(val cleanText: String) : SkillCreateParseResult

  data class Created(
    val cleanText: String,
    val name: String,
    val description: String,
    val skillMarkdown: String,
    val files: List<Pair<String, String>>,
  ) : SkillCreateParseResult

  data class Malformed(
    val cleanText: String,
    val reason: String,
  ) : SkillCreateParseResult
}

/**
 * Pure utilities for the built-in conversational Skill Creator workflow: system skill definition,
 * `/` slash command suggestions & leading-command stripping, and `[SKILL_CREATE]` /
 * `[SKILL_FILE]` streaming sanitization and final extraction.
 */
object SkillCreatorHelper {

  const val SYSTEM_SKILL_CREATOR_NAME: String = "Skill Creator"
  const val SYSTEM_SKILL_CREATOR_DESCRIPTION: String =
    "Use when the user wants to create, build, or design a new custom skill through conversation."

  val SYSTEM_SKILL_CREATOR_MARKDOWN: String =
    """
    You are helping the user design and save a reusable custom Skill for Ved through a focused, friendly conversation.

    Follow this conversational interview workflow:
    1. If the user hasn't described what they want the skill to do yet, ask them briefly what task, persona, workflow, or expertise they want to capture.
    2. Conduct a lightweight interview — ask only 1 to 2 focused clarifying questions per turn (never overwhelm with a long questionnaire). Cover:
       - A short, memorable skill name (2 to 4 words).
       - A specific 1-sentence "when to use" description explaining which topics or user requests should trigger this skill.
       - Concrete behavior, tone, step-by-step workflow, output structure, and constraints/guardrails.
       - Optional supporting reference files (examples, checklists, templates, or domain rules) if helpful.
    3. Summarize the draft skill clearly back to the user (name, when-to-use description, key instructions, and any reference files) and ask them to confirm before saving.
    4. ONLY after the user confirms (or explicitly tells you to create/save it right away), output the final skill definition at the end of your response using this exact tagged format:

    [SKILL_CREATE]
    name: <Skill Name>
    description: <1-sentence when-to-use description>
    ---
    <Full skill instructions in clean Markdown>
    [/SKILL_CREATE]

    If the skill benefits from supporting reference files, optionally include one or more blocks right after [/SKILL_CREATE]:

    [SKILL_FILE: <filename.md>]
    <reference file content>
    [/SKILL_FILE]

    Important rules:
    - Do not emit [SKILL_CREATE] until you have enough detail and the user has confirmed the design.
    - Do not use [MEMORY_SAVE] to store the skill itself; [SKILL_CREATE] saves it directly to the user's Skills library.
    - The [SKILL_CREATE] and [SKILL_FILE] blocks are automatically parsed and hidden from the chat bubble, so always include a brief human-friendly confirmation message outside the tags.
    """.trimIndent()

  private const val SKILL_CREATE_OPEN = "[SKILL_CREATE]"
  private const val SKILL_FILE_OPEN = "[SKILL_FILE:"

  private val SKILL_CREATE_REGEX =
    Regex("\\[SKILL_CREATE\\]\\s*(.*?)\\s*\\[/SKILL_CREATE\\]", RegexOption.DOT_MATCHES_ALL)

  private val SKILL_FILE_REGEX =
    Regex("\\[SKILL_FILE:\\s*([^\\]\\r\\n]+?)\\]\\s*(.*?)\\s*\\[/SKILL_FILE\\]", RegexOption.DOT_MATCHES_ALL)

  private val SLUG_INVALID_REGEX = Regex("[^a-z0-9]+")
  private val EXCESS_NEWLINES_REGEX = Regex("\\n{3,}")
  private val WHITESPACE_SPLIT_REGEX = Regex("\\s+", RegexOption.MULTILINE)

  fun buildSystemSkillCreatorEntity(timestampMs: Long = 1_700_000_000_000L): SkillEntity {
    return SkillEntity(
      id = SkillEntity.SYSTEM_SKILL_CREATOR_ID,
      name = SYSTEM_SKILL_CREATOR_NAME,
      description = SYSTEM_SKILL_CREATOR_DESCRIPTION,
      skillMarkdown = SYSTEM_SKILL_CREATOR_MARKDOWN,
      isEnabled = true,
      source = SkillEntity.SOURCE_SYSTEM,
      createdAt = timestampMs,
      updatedAt = timestampMs,
    )
  }

  fun slugifySkillName(skill: SkillEntity): String {
    if (skill.id == SkillEntity.SYSTEM_SKILL_CREATOR_ID) return "skill-creator"
    val slug =
      skill.name
        .lowercase(Locale.ROOT)
        .replace(SLUG_INVALID_REGEX, "-")
        .trim('-')
    return slug.ifEmpty { "skill" }
  }

  fun skillToSlashCommand(skill: SkillEntity): String = "/${slugifySkillName(skill)}"

  /**
   * Returns matching slash-command suggestions when [input] starts with `/` and does not yet
   * contain whitespace or newlines. Always pins `/skill-creator` at the top when enabled & matching.
   */
  fun findSlashCommandSuggestions(
    input: String,
    enabledSkills: List<SkillEntity>,
  ): List<SlashCommandSuggestion> {
    if (enabledSkills.isEmpty()) return emptyList()
    if (!input.startsWith("/") || input.contains(' ') || input.contains('\n') || input.contains('\t')) {
      return emptyList()
    }

    val queryLower = input.lowercase(Locale.ROOT)
    val prefixWithoutSlash = queryLower.removePrefix("/")

    return enabledSkills
      .asSequence()
      .map { skill ->
        val slug = slugifySkillName(skill)
        val cmd = "/$slug"
        val isBuiltIn = skill.id == SkillEntity.SYSTEM_SKILL_CREATOR_ID
        SlashCommandSuggestion(
          command = cmd,
          slug = slug,
          skill = skill,
          isBuiltIn = isBuiltIn,
        )
      }
      .filter { suggestion ->
        if (prefixWithoutSlash.isEmpty()) {
          true
        } else {
          suggestion.command.lowercase(Locale.ROOT).startsWith(queryLower) ||
            suggestion.skill.name.lowercase(Locale.ROOT).startsWith(prefixWithoutSlash)
        }
      }
      .sortedWith(
        compareByDescending<SlashCommandSuggestion> { it.isBuiltIn }
          .thenBy { it.command.lowercase(Locale.ROOT) }
      )
      .toList()
  }

  /**
   * Detects if a submitted message starts with a slash command matching an enabled skill (e.g.
   * `/skill-creator build a rubric` or `/kotlin-sqlite-tuner optimize this query`), returning the
   * matched skill and the remaining prompt with the slash token stripped.
   */
  fun parseLeadingSlashCommand(
    rawPrompt: String,
    enabledSkills: List<SkillEntity>,
  ): ParsedSlashMessage {
    val trimmed = rawPrompt.trimStart()
    if (!trimmed.startsWith("/") || enabledSkills.isEmpty()) {
      return ParsedSlashMessage(matchedSkill = null, strippedPrompt = rawPrompt.trim())
    }

    val match = WHITESPACE_SPLIT_REGEX.find(trimmed)
    val firstToken =
      if (match != null) trimmed.substring(0, match.range.first) else trimmed
    val remainder =
      if (match != null) trimmed.substring(match.range.last + 1).trim() else ""

    val matchedSkill =
      enabledSkills
        .sortedByDescending { it.id == SkillEntity.SYSTEM_SKILL_CREATOR_ID }
        .firstOrNull { skill ->
          skillToSlashCommand(skill).equals(firstToken, ignoreCase = true)
        }

    return if (matchedSkill != null) {
      ParsedSlashMessage(matchedSkill = matchedSkill, strippedPrompt = remainder)
    } else {
      ParsedSlashMessage(matchedSkill = null, strippedPrompt = rawPrompt.trim())
    }
  }

  /**
   * Hides partial or completed `[SKILL_CREATE]...[/SKILL_CREATE]` and `[SKILL_FILE:...]` blocks
   * while tokens are actively streaming so raw tags never flash visibly in the chat bubble.
   */
  fun stripStreamingSkillCreatorTags(snapshot: String): String {
    if (!snapshot.contains("[SKILL_")) return snapshot

    var result =
      snapshot
        .replace(SKILL_CREATE_REGEX, "")
        .replace(SKILL_FILE_REGEX, "")
        .replace(EXCESS_NEWLINES_REGEX, "\n\n")

    val createOpenIdx = result.indexOf(SKILL_CREATE_OPEN)
    val fileOpenIdx = result.indexOf(SKILL_FILE_OPEN)
    val partialPrefixIdx =
      listOf(createOpenIdx, fileOpenIdx).filter { it >= 0 }.minOrNull()
        ?: run {
          // Handle half-typed opening token right at the end of a chunk, e.g. "[SKILL_CRE"
          val trailingBracket = result.lastIndexOf("[SKILL_")
          if (trailingBracket >= 0 && !result.substring(trailingBracket).contains(']')) {
            trailingBracket
          } else {
            -1
          }
        }

    if (partialPrefixIdx >= 0) {
      result = result.substring(0, partialPrefixIdx)
    }

    return result.trimEnd()
  }

  /**
   * Parses `[SKILL_CREATE]` and any accompanying `[SKILL_FILE: <name>]` blocks from a completed
   * assistant response and strips all creation tags from the user-visible message text.
   */
  fun extractAndStripSkillCreate(rawText: String): SkillCreateParseResult {
    val hasCreateTag = rawText.contains(SKILL_CREATE_OPEN)
    val hasFileTag = rawText.contains(SKILL_FILE_OPEN)

    if (!hasCreateTag && !hasFileTag) {
      return SkillCreateParseResult.NotPresent(cleanText = rawText.trim())
    }

    val createMatch = SKILL_CREATE_REGEX.find(rawText)
    val extractedFiles =
      SKILL_FILE_REGEX.findAll(rawText)
        .mapNotNull { fileMatch ->
          val fileName = fileMatch.groupValues[1].trim().substringAfterLast('/').substringAfterLast('\\')
          val content = fileMatch.groupValues[2].trim()
          if (fileName.isNotEmpty() && content.isNotEmpty()) {
            fileName to content
          } else {
            null
          }
        }
        .toList()

    var stripped =
      rawText
        .replace(SKILL_CREATE_REGEX, "")
        .replace(SKILL_FILE_REGEX, "")

    // Strip any unclosed/trailing tag remnants so raw tag syntax is never left in chat UI
    val leftoverIdx =
      listOf(stripped.indexOf(SKILL_CREATE_OPEN), stripped.indexOf(SKILL_FILE_OPEN))
        .filter { it >= 0 }
        .minOrNull()
    if (leftoverIdx != null) {
      stripped = stripped.substring(0, leftoverIdx)
    }

    val cleanText = stripped.replace(EXCESS_NEWLINES_REGEX, "\n\n").trim()

    if (createMatch == null) {
      return if (hasCreateTag) {
        SkillCreateParseResult.Malformed(
          cleanText = cleanText,
          reason = "Incomplete [SKILL_CREATE] block.",
        )
      } else {
        // Only stray [SKILL_FILE] without [SKILL_CREATE]
        SkillCreateParseResult.Malformed(
          cleanText = cleanText,
          reason = "Missing [SKILL_CREATE] block.",
        )
      }
    }

    val blockBody = createMatch.groupValues[1].replace("\r\n", "\n").trim()
    val parsedBlock = parseSkillCreateBlockBody(blockBody)
    if (parsedBlock == null) {
      return SkillCreateParseResult.Malformed(
        cleanText = cleanText,
        reason = "Invalid skill fields or missing --- separator.",
      )
    }

    return SkillCreateParseResult.Created(
      cleanText = cleanText,
      name = parsedBlock.first,
      description = parsedBlock.second,
      skillMarkdown = parsedBlock.third,
      files = extractedFiles,
    )
  }

  private fun parseSkillCreateBlockBody(body: String): Triple<String, String, String>? {
    if (body.isBlank()) return null

    var lines = body.lines()
    // Tolerate an optional leading --- line inside [SKILL_CREATE]
    while (lines.isNotEmpty() && lines.first().trim().isEmpty()) {
      lines = lines.drop(1)
    }
    if (lines.isNotEmpty() && lines.first().trim() == "---") {
      lines = lines.drop(1)
    }

    val separatorIndex = lines.indexOfFirst { it.trim() == "---" }
    if (separatorIndex <= 0) return null

    val headerLines = lines.subList(0, separatorIndex)
    val markdownBody = lines.subList(separatorIndex + 1, lines.size).joinToString("\n").trim()
    if (markdownBody.isBlank()) return null

    var name: String? = null
    var description: String? = null

    for (rawLine in headerLines) {
      val line = rawLine.trim()
      if (line.isEmpty() || line.startsWith("#")) continue
      val colonIdx = line.indexOf(':')
      if (colonIdx <= 0) continue
      val key = line.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
      val value = line.substring(colonIdx + 1).trim().stripWrappingQuotes()
      when (key) {
        "name" -> if (value.isNotBlank()) name = value
        "description" -> if (value.isNotBlank()) description = value
      }
    }

    val validName = name?.takeIf { it.isNotBlank() } ?: return null
    val validDesc = description?.takeIf { it.isNotBlank() } ?: return null
    return Triple(validName, validDesc, markdownBody)
  }

  private fun String.stripWrappingQuotes(): String {
    if (length >= 2 && ((startsWith('"') && endsWith('"')) || (startsWith('\'') && endsWith('\'')))) {
      return substring(1, length - 1).trim()
    }
    return this
  }
}
