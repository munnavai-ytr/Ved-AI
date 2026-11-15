package com.example.data.util

import com.example.data.local.entity.SkillEntity
import com.example.data.local.entity.SkillWithFiles

data class ParsedSkillImport(
  val name: String,
  val description: String,
  val skillMarkdown: String,
  val hasValidFrontmatter: Boolean,
)

/**
 * Pure utilities for parsing/exporting Claude-style `SKILL.md` YAML frontmatter, matching user
 * prompts to enabled skills deterministically, and formatting skill system prompt blocks.
 */
object SkillMatcher {

  private val STOP_WORDS =
    setOf(
      "the",
      "and",
      "for",
      "that",
      "this",
      "with",
      "from",
      "your",
      "you",
      "are",
      "was",
      "were",
      "will",
      "can",
      "could",
      "would",
      "should",
      "have",
      "has",
      "had",
      "not",
      "but",
      "use",
      "when",
      "what",
      "which",
      "how",
      "why",
      "who",
      "all",
      "any",
      "into",
      "out",
      "about",
      "than",
      "then",
      "them",
      "they",
      "their",
      "there",
      "here",
      "help",
      "please",
      "want",
      "need",
      "make",
      "like",
      "just",
      "more",
      "some",
      "only",
      "also",
      "using",
      "user",
      "asks",
    )

  private val TOKEN_REGEX = Regex("[a-z0-9]{3,}")

  /**
   * Parses an imported `.md` skill file supporting optional YAML frontmatter delimited by `---`.
   */
  fun parseSkillMarkdown(rawText: String, fallbackFileName: String = "custom-skill.md"): ParsedSkillImport {
    val normalized = rawText.replace("\r\n", "\n").trimStart()
    val fallbackName = deriveNameFromFilename(fallbackFileName)

    if (normalized.startsWith("---\n") || normalized.startsWith("---\r")) {
      val afterStart = normalized.substringAfter('\n')
      val endMarkerIdx = findFrontmatterEndIndex(afterStart)
      if (endMarkerIdx >= 0) {
        val frontmatterBlock = afterStart.substring(0, endMarkerIdx)
        val body =
          afterStart
            .substring(endMarkerIdx)
            .substringAfter('\n', missingDelimiterValue = "")
            .trim()

        var parsedName: String? = null
        var parsedDescription: String? = null
        for (rawLine in frontmatterBlock.lines()) {
          val line = rawLine.trim()
          if (line.isEmpty() || line.startsWith("#")) continue
          val colonIdx = line.indexOf(':')
          if (colonIdx <= 0) continue
          val key = line.substring(0, colonIdx).trim().lowercase()
          val value = line.substring(colonIdx + 1).trim().stripSurroundingQuotes()
          when (key) {
            "name" -> if (value.isNotBlank()) parsedName = value
            "description" -> if (value.isNotBlank()) parsedDescription = value
          }
        }

        val hasBoth = !parsedName.isNullOrBlank() && !parsedDescription.isNullOrBlank()
        return ParsedSkillImport(
          name = parsedName?.takeIf { it.isNotBlank() } ?: fallbackName,
          description = parsedDescription.orEmpty(),
          skillMarkdown = body,
          hasValidFrontmatter = hasBoth,
        )
      }
    }

    return ParsedSkillImport(
      name = fallbackName,
      description = "",
      skillMarkdown = normalized.trim(),
      hasValidFrontmatter = false,
    )
  }

  /**
   * Serializes a [SkillEntity] as a portable Claude-style `SKILL.md` document with YAML frontmatter.
   */
  fun formatSkillForExport(skill: SkillEntity): String {
    val cleanName = skill.name.trim().replace('\n', ' ')
    val cleanDesc = skill.description.trim().replace('\n', ' ')
    return buildString {
      appendLine("---")
      appendLine("name: $cleanName")
      appendLine("description: $cleanDesc")
      appendLine("---")
      appendLine()
      append(skill.skillMarkdown.trim())
      appendLine()
    }
  }

  /**
   * Formats the compact index of all enabled skills (`name` + `description` only).
   */
  fun formatSkillsIndexBlock(enabledSkills: List<SkillEntity>): String {
    if (enabledSkills.isEmpty()) return ""
    return buildString {
      append("## Available skills (load one if relevant to this request):")
      for (skill in enabledSkills) {
        val name = skill.name.replace('\n', ' ').trim()
        val desc = skill.description.replace('\n', ' ').trim()
        if (name.isNotEmpty()) {
          append("\n- ").append(name)
          if (desc.isNotEmpty()) {
            append(": ").append(desc)
          }
        }
      }
    }
  }

  /**
   * Formats an activated skill's full instructions and supporting reference files.
   */
  fun formatActiveSkillBlock(skillWithFiles: SkillWithFiles): String {
    val skill = skillWithFiles.skill
    return buildString {
      append("## Active skill: ").append(skill.name.trim())
      val body = skill.skillMarkdown.trim()
      if (body.isNotEmpty()) {
        append("\n").append(body)
      }
      for (file in skillWithFiles.files) {
        val content = file.content.trim()
        if (content.isNotEmpty()) {
          append("\n\n### Reference: ").append(file.fileName.trim())
          append("\n").append(content)
        }
      }
    }
  }

  /**
   * Deterministic Kotlin-side keyword matcher selecting the best enabled skill for [userPrompt].
   * Matches normalized keywords (`length >= 3`, excluding stop words) across skill `name` and
   * `description`. Returns `null` when no skill crosses the relevance threshold.
   */
  fun findBestMatchingSkill(
    userPrompt: String,
    enabledSkills: List<SkillEntity>,
  ): SkillEntity? {
    if (userPrompt.isBlank() || enabledSkills.isEmpty()) return null

    val normalizedPrompt = userPrompt.lowercase()
    val promptTokens = tokenize(normalizedPrompt)
    if (promptTokens.isEmpty()) return null

    var bestSkill: SkillEntity? = null
    var bestScore = 0

    for (skill in enabledSkills) {
      if (
        skill.id == SkillEntity.SYSTEM_SKILL_CREATOR_ID &&
          !normalizedPrompt.contains("skill-creator") &&
          "skill" !in promptTokens &&
          "skills" !in promptTokens
      ) {
        continue
      }
      val nameLower = skill.name.lowercase().trim()
      val nameTokens = tokenize(nameLower)
      val descTokens = tokenize(skill.description)

      var score = 0
      // Direct phrase match against the skill name is a very strong signal
      if (nameLower.length >= 3 && normalizedPrompt.contains(nameLower)) {
        score += 4
      }

      val matchedNameTokens = promptTokens.count { it in nameTokens }
      val matchedDescTokens = promptTokens.count { it in descTokens }
      score += matchedNameTokens * 2 + matchedDescTokens

      // Require either >= 2 matching weighted points with at least 1 token match,
      // or explicit skill-name mention.
      val totalMatchedTokens = (promptTokens intersect (nameTokens + descTokens)).size
      val qualifies = score >= 4 || totalMatchedTokens >= 2
      if (qualifies && score > bestScore) {
        bestScore = score
        bestSkill = skill
      }
    }

    return bestSkill
  }

  internal fun tokenize(text: String): Set<String> {
    if (text.isBlank()) return emptySet()
    val out = HashSet<String>()
    for (match in TOKEN_REGEX.findAll(text.lowercase())) {
      val token = match.value
      if (token !in STOP_WORDS) {
        out.add(token)
        // Simple plural normalization so "animations" matches "animation"
        if (token.length >= 5 && token.endsWith('s')) {
          out.add(token.dropLast(1))
        }
      }
    }
    return out
  }

  private fun findFrontmatterEndIndex(afterOpeningLine: String): Int {
    var cursor = 0
    for (line in afterOpeningLine.lines()) {
      if (line.trim() == "---") {
        return cursor
      }
      cursor += line.length + 1
    }
    return -1
  }

  private fun deriveNameFromFilename(fileName: String): String {
    val base = fileName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Custom Skill" }
    return base
      .replace('-', ' ')
      .replace('_', ' ')
      .split(' ')
      .filter { it.isNotBlank() }
      .joinToString(" ") { word ->
        word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
      }
      .ifBlank { "Custom Skill" }
  }

  private fun String.stripSurroundingQuotes(): String {
    if (length >= 2 && ((startsWith('"') && endsWith('"')) || (startsWith('\'') && endsWith('\'')))) {
      return substring(1, length - 1).trim()
    }
    return this
  }
}
