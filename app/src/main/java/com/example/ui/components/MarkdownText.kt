package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalVedColors
import kotlinx.coroutines.delay

private sealed interface MarkdownBlock {
  data class Paragraph(val text: String) : MarkdownBlock

  data class Heading(val level: Int, val text: String) : MarkdownBlock

  data class CodeBlock(val language: String, val code: String) : MarkdownBlock

  data class BulletItem(val text: String) : MarkdownBlock

  data class OrderedItem(val number: String, val text: String) : MarkdownBlock

  data class BlockQuote(val text: String) : MarkdownBlock
}

/**
 * Lightweight, allocation-conscious Markdown renderer supporting headings, bold, italic,
 * strikethrough, inline code, fenced code blocks with copy action, lists, and blockquotes.
 */
@Composable
fun MarkdownText(
  markdown: String,
  modifier: Modifier = Modifier,
  textColor: Color = MaterialTheme.colorScheme.onSurface,
  isStreaming: Boolean = false,
) {
  val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
  val codeBg = LocalVedColors.current.codeBackground
  val accentColor = MaterialTheme.colorScheme.primary

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    blocks.forEachIndexed { index, block ->
      val appendCursor = isStreaming && index == blocks.lastIndex
      when (block) {
        is MarkdownBlock.Heading -> {
          val style =
            when (block.level) {
              1 -> MaterialTheme.typography.titleLarge
              2 -> MaterialTheme.typography.titleMedium
              else -> MaterialTheme.typography.titleSmall
            }
          Text(
            text = formatInlineMarkdown(block.text, codeBg, accentColor, appendCursor),
            style = style,
            color = textColor,
          )
        }
        is MarkdownBlock.Paragraph -> {
          Text(
            text = formatInlineMarkdown(block.text, codeBg, accentColor, appendCursor),
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
          )
        }
        is MarkdownBlock.BulletItem -> {
          Row(modifier = Modifier.fillMaxWidth()) {
            Text(
              text = "•",
              style = MaterialTheme.typography.bodyLarge,
              color = accentColor,
              modifier = Modifier.padding(end = 10.dp),
            )
            Text(
              text = formatInlineMarkdown(block.text, codeBg, accentColor, appendCursor),
              style = MaterialTheme.typography.bodyLarge,
              color = textColor,
              modifier = Modifier.weight(1f),
            )
          }
        }
        is MarkdownBlock.OrderedItem -> {
          Row(modifier = Modifier.fillMaxWidth()) {
            Text(
              text = "${block.number}.",
              style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
              color = accentColor,
              modifier = Modifier.padding(end = 8.dp),
            )
            Text(
              text = formatInlineMarkdown(block.text, codeBg, accentColor, appendCursor),
              style = MaterialTheme.typography.bodyLarge,
              color = textColor,
              modifier = Modifier.weight(1f),
            )
          }
        }
        is MarkdownBlock.BlockQuote -> {
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(end = 12.dp, top = 8.dp, bottom = 8.dp)
          ) {
            Box(
              modifier =
                Modifier.width(3.dp)
                  .fillMaxHeight()
                  .background(accentColor)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              text = formatInlineMarkdown(block.text, codeBg, accentColor, appendCursor),
              style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        is MarkdownBlock.CodeBlock -> {
          CodeBlockCard(language = block.language, code = block.code)
        }
      }
    }
  }
}

@Composable
private fun CodeBlockCard(language: String, code: String) {
  val clipboardManager = LocalClipboardManager.current
  val haptic = LocalHapticFeedback.current
  var copied by remember { mutableStateOf(false) }

  LaunchedEffect(copied) {
    if (copied) {
      delay(1600)
      copied = false
    }
  }

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = LocalVedColors.current.codeBackground,
    tonalElevation = 1.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = language.ifBlank { "code" }.lowercase(),
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            clipboardManager.setText(AnnotatedString(code))
            copied = true
          }
        ) {
          Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = stringResource(R.string.cd_copy_message),
            tint =
              if (copied) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
          )
        }
      }

      Box(
        modifier =
          Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp)
      ) {
        Text(
          text = code,
          style =
            MaterialTheme.typography.bodyMedium.copy(
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 13.sp,
              lineHeight = 19.sp,
            ),
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

private fun parseMarkdownBlocks(input: String): List<MarkdownBlock> {
  if (input.isEmpty()) return emptyList()
  val lines = input.replace("\r\n", "\n").split('\n')
  val blocks = ArrayList<MarkdownBlock>(lines.size / 2 + 1)

  var i = 0
  val paragraphBuffer = StringBuilder()

  fun flushParagraph() {
    val text = paragraphBuffer.toString().trim()
    if (text.isNotEmpty()) {
      blocks.add(MarkdownBlock.Paragraph(text))
    }
    paragraphBuffer.clear()
  }

  while (i < lines.size) {
    val line = lines[i]
    val trimmed = line.trimStart()

    // Fenced code block (handles unclosed fences smoothly during streaming)
    if (trimmed.startsWith("```")) {
      flushParagraph()
      val lang = trimmed.removePrefix("```").trim()
      val codeLines = StringBuilder()
      i++
      while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
        if (codeLines.isNotEmpty()) codeLines.append('\n')
        codeLines.append(lines[i])
        i++
      }
      blocks.add(MarkdownBlock.CodeBlock(language = lang, code = codeLines.toString()))
      i++
      continue
    }

    if (trimmed.isBlank()) {
      flushParagraph()
      i++
      continue
    }

    // Headings (#, ##, ###)
    val headingMatch = HEADING_REGEX.matchEntire(trimmed)
    if (headingMatch != null) {
      flushParagraph()
      val level = headingMatch.groupValues[1].length.coerceIn(1, 3)
      blocks.add(MarkdownBlock.Heading(level = level, text = headingMatch.groupValues[2].trim()))
      i++
      continue
    }

    // Blockquote
    if (trimmed.startsWith("> ")) {
      flushParagraph()
      blocks.add(MarkdownBlock.BlockQuote(trimmed.removePrefix("> ").trim()))
      i++
      continue
    }

    // Unordered list item
    if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
      flushParagraph()
      blocks.add(MarkdownBlock.BulletItem(trimmed.drop(2).trim()))
      i++
      continue
    }

    // Ordered list item
    val orderedMatch = ORDERED_LIST_REGEX.matchEntire(trimmed)
    if (orderedMatch != null) {
      flushParagraph()
      blocks.add(
        MarkdownBlock.OrderedItem(
          number = orderedMatch.groupValues[1],
          text = orderedMatch.groupValues[2].trim(),
        )
      )
      i++
      continue
    }

    if (paragraphBuffer.isNotEmpty()) {
      paragraphBuffer.append('\n')
    }
    paragraphBuffer.append(line)
    i++
  }

  flushParagraph()
  return blocks
}

private fun formatInlineMarkdown(
  text: String,
  inlineCodeBg: Color,
  accentColor: Color,
  appendStreamingCursor: Boolean,
): AnnotatedString = buildAnnotatedString {
  var index = 0
  val length = text.length

  while (index < length) {
    when {
      // Inline code `...`
      text[index] == '`' -> {
        val end = text.indexOf('`', index + 1)
        if (end != -1) {
          withStyle(
            SpanStyle(
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 13.5.sp,
              background = inlineCodeBg,
            )
          ) {
            append(text.substring(index + 1, end))
          }
          index = end + 1
        } else {
          append(text[index])
          index++
        }
      }
      // Bold+Italic ***...***
      text.startsWith("***", index) -> {
        val end = text.indexOf("***", index + 3)
        if (end != -1) {
          withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
            append(text.substring(index + 3, end))
          }
          index = end + 3
        } else {
          append("***")
          index += 3
        }
      }
      // Bold **...**
      text.startsWith("**", index) -> {
        val end = text.indexOf("**", index + 2)
        if (end != -1) {
          withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(text.substring(index + 2, end))
          }
          index = end + 2
        } else {
          append("**")
          index += 2
        }
      }
      // Strikethrough ~~...~~
      text.startsWith("~~", index) -> {
        val end = text.indexOf("~~", index + 2)
        if (end != -1) {
          withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            append(text.substring(index + 2, end))
          }
          index = end + 2
        } else {
          append("~~")
          index += 2
        }
      }
      // Italic *...* (avoid matching stray list stars with spaces)
      text[index] == '*' && index + 1 < length && text[index + 1] != ' ' -> {
        val end = text.indexOf('*', index + 1)
        if (end != -1 && end > index + 1) {
          withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            append(text.substring(index + 1, end))
          }
          index = end + 1
        } else {
          append('*')
          index++
        }
      }
      else -> {
        append(text[index])
        index++
      }
    }
  }

  if (appendStreamingCursor) {
    withStyle(SpanStyle(color = accentColor, fontWeight = FontWeight.Bold)) {
      append(" ▍")
    }
  }
}

private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
private val ORDERED_LIST_REGEX = Regex("^(\\d+)\\.\\s+(.+)$")
