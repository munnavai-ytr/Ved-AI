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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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

@Immutable
internal sealed interface MarkdownBlock {
  @Immutable data class Paragraph(val text: String) : MarkdownBlock

  @Immutable data class Heading(val level: Int, val text: String) : MarkdownBlock

  @Immutable data class CodeBlock(val language: String, val code: String) : MarkdownBlock

  @Immutable data class BulletItem(val text: String) : MarkdownBlock

  @Immutable data class OrderedItem(val number: String, val text: String) : MarkdownBlock

  @Immutable data class BlockQuote(val text: String) : MarkdownBlock
}

@Immutable
internal data class IncrementalParseResult(
  val settledCount: Int,
  val blocks: List<MarkdownBlock>,
)

/**
 * Incremental markdown block parser that caches settled blocks across append-only streaming
 * updates so parsing cost per frame scales with newly appended text only, not total length.
 */
internal class IncrementalMarkdownParser {
  private val settledBlocks = ArrayList<MarkdownBlock>()
  private var settledPrefix: String = ""
  private var settledLength: Int = 0
  private var lastInput: String? = null
  private var lastResult: IncrementalParseResult = IncrementalParseResult(0, emptyList())

  // Tracks how many times settled blocks were parsed/emitted (exposed for unit verification)
  internal var lastScannedOffset: Int = 0
    private set

  fun parse(input: String): IncrementalParseResult {
    if (input === lastInput) return lastResult
    if (input.isEmpty()) {
      reset()
      lastInput = input
      lastResult = IncrementalParseResult(0, emptyList())
      return lastResult
    }

    val canResume =
      settledLength == 0 ||
        (input.length >= settledLength && input.regionMatches(0, settledPrefix, 0, settledLength))
    if (!canResume) {
      reset()
    }

    lastScannedOffset = settledLength
    val trailingBlocks = parseFromOffset(input, settledLength)

    val combined = ArrayList<MarkdownBlock>(settledBlocks.size + trailingBlocks.size)
    combined.addAll(settledBlocks)
    combined.addAll(trailingBlocks)

    lastInput = input
    lastResult = IncrementalParseResult(settledCount = settledBlocks.size, blocks = combined)
    return lastResult
  }

  private fun reset() {
    settledBlocks.clear()
    settledPrefix = ""
    settledLength = 0
    lastScannedOffset = 0
  }

  private fun parseFromOffset(input: String, startOffset: Int): List<MarkdownBlock> {
    val trailing = ArrayList<MarkdownBlock>(4)
    val len = input.length
    var pos = startOffset

    var openParaStart = -1
    val paraBuffer = StringBuilder()

    fun flushOpenParagraph(settleOffset: Int, settle: Boolean) {
      if (paraBuffer.isEmpty()) {
        openParaStart = -1
        return
      }
      val text = paraBuffer.toString().trim()
      paraBuffer.clear()
      if (text.isNotEmpty()) {
        val block = MarkdownBlock.Paragraph(text)
        if (settle) {
          settledBlocks.add(block)
          settledLength = settleOffset
          settledPrefix = input
        } else {
          trailing.add(block)
        }
      } else if (settle) {
        settledLength = settleOffset
        settledPrefix = input
      }
      openParaStart = -1
    }

    while (pos < len) {
      val nlIndex = input.indexOf('\n', pos)
      val hasNewline = nlIndex != -1
      val rawLineEnd = if (hasNewline) nlIndex else len
      val lineEnd =
        if (rawLineEnd > pos && input[rawLineEnd - 1] == '\r') rawLineEnd - 1 else rawLineEnd
      val nextLineStart = if (hasNewline) nlIndex + 1 else len

      val trimmedStart = skipLeadingWhitespace(input, pos, lineEnd)
      val isBlankLine = trimmedStart >= lineEnd

      // 1. Fenced code block ```...```
      if (!isBlankLine && startsWithFence(input, trimmedStart, lineEnd)) {
        // Any preceding paragraph is now definitely closed by this code block.
        flushOpenParagraph(settleOffset = pos, settle = true)

        val fenceStartOffset = pos
        val lang = input.substring(trimmedStart + 3, lineEnd).trim()
        val codeBuilder = StringBuilder()
        var scanPos = nextLineStart
        var closed = false
        var afterFencePos = len

        while (scanPos < len) {
          val codeNl = input.indexOf('\n', scanPos)
          val codeHasNl = codeNl != -1
          val codeRawEnd = if (codeHasNl) codeNl else len
          val codeLineEnd =
            if (codeRawEnd > scanPos && input[codeRawEnd - 1] == '\r') codeRawEnd - 1
            else codeRawEnd
          val codeTrimStart = skipLeadingWhitespace(input, scanPos, codeLineEnd)
          val codeNextStart = if (codeHasNl) codeNl + 1 else len

          if (startsWithFence(input, codeTrimStart, codeLineEnd)) {
            if (codeHasNl) {
              closed = true
              afterFencePos = codeNextStart
            } else {
              // Closing fence has no trailing newline yet; keep in trailing until newline confirms.
              afterFencePos = codeNextStart
            }
            break
          }

          if (codeBuilder.isNotEmpty()) {
            codeBuilder.append('\n')
          }
          codeBuilder.append(input, scanPos, codeLineEnd)
          scanPos = codeNextStart
        }

        val codeBlock = MarkdownBlock.CodeBlock(language = lang, code = codeBuilder.toString())
        if (closed) {
          settledBlocks.add(codeBlock)
          settledLength = afterFencePos
          settledPrefix = input
          pos = afterFencePos
          continue
        } else {
          trailing.add(codeBlock)
          // Do not advance settledLength past fenceStartOffset because fence is still unclosed.
          settledLength = minOf(settledLength, fenceStartOffset)
          return trailing
        }
      }

      // 2. Blank line
      if (isBlankLine) {
        if (paraBuffer.isNotEmpty()) {
          flushOpenParagraph(settleOffset = nextLineStart, settle = hasNewline)
        } else if (hasNewline && openParaStart == -1 && trailing.isEmpty()) {
          settledLength = nextLineStart
          settledPrefix = input
        }
        pos = nextLineStart
        continue
      }

      // 3. Heading (#, ##, ###)
      val headingEnd = parseHeadingPrefixEnd(input, trimmedStart, lineEnd)
      if (headingEnd > 0) {
        flushOpenParagraph(settleOffset = pos, settle = true)
        val level = (headingEnd - trimmedStart).coerceIn(1, 3)
        val headingText = input.substring(headingEnd, lineEnd).trim()
        val block = MarkdownBlock.Heading(level = level, text = headingText)
        if (hasNewline && trailing.isEmpty()) {
          settledBlocks.add(block)
          settledLength = nextLineStart
          settledPrefix = input
        } else {
          trailing.add(block)
        }
        pos = nextLineStart
        continue
      }

      // 4. Blockquote (> )
      if (trimmedStart + 1 < lineEnd && input[trimmedStart] == '>' && input[trimmedStart + 1] == ' ') {
        flushOpenParagraph(settleOffset = pos, settle = true)
        val quoteText = input.substring(trimmedStart + 2, lineEnd).trim()
        val block = MarkdownBlock.BlockQuote(quoteText)
        if (hasNewline && trailing.isEmpty()) {
          settledBlocks.add(block)
          settledLength = nextLineStart
          settledPrefix = input
        } else {
          trailing.add(block)
        }
        pos = nextLineStart
        continue
      }

      // 5. Unordered list item (- or * followed by space)
      if (
        trimmedStart + 1 < lineEnd &&
          (input[trimmedStart] == '-' || input[trimmedStart] == '*') &&
          input[trimmedStart + 1] == ' '
      ) {
        flushOpenParagraph(settleOffset = pos, settle = true)
        val itemText = input.substring(trimmedStart + 2, lineEnd).trim()
        val block = MarkdownBlock.BulletItem(itemText)
        if (hasNewline && trailing.isEmpty()) {
          settledBlocks.add(block)
          settledLength = nextLineStart
          settledPrefix = input
        } else {
          trailing.add(block)
        }
        pos = nextLineStart
        continue
      }

      // 6. Ordered list item (digits + ". ")
      val orderedSplit = parseOrderedListPrefixEnd(input, trimmedStart, lineEnd)
      if (orderedSplit != null) {
        flushOpenParagraph(settleOffset = pos, settle = true)
        val (numEnd, textStart) = orderedSplit
        val block =
          MarkdownBlock.OrderedItem(
            number = input.substring(trimmedStart, numEnd),
            text = input.substring(textStart, lineEnd).trim(),
          )
        if (hasNewline && trailing.isEmpty()) {
          settledBlocks.add(block)
          settledLength = nextLineStart
          settledPrefix = input
        } else {
          trailing.add(block)
        }
        pos = nextLineStart
        continue
      }

      // 7. Paragraph continuation
      if (openParaStart == -1) {
        openParaStart = pos
      }
      if (paraBuffer.isNotEmpty()) {
        paraBuffer.append('\n')
      }
      paraBuffer.append(input, pos, lineEnd)
      pos = nextLineStart
    }

    flushOpenParagraph(settleOffset = len, settle = false)
    return trailing
  }
}

private fun skipLeadingWhitespace(s: String, start: Int, end: Int): Int {
  var i = start
  while (i < end && (s[i] == ' ' || s[i] == '\t')) i++
  return i
}

private fun startsWithFence(s: String, start: Int, end: Int): Boolean =
  start + 2 < end && s[start] == '`' && s[start + 1] == '`' && s[start + 2] == '`'

private fun parseHeadingPrefixEnd(s: String, start: Int, end: Int): Int {
  var i = start
  var hashes = 0
  while (i < end && s[i] == '#' && hashes < 6) {
    hashes++
    i++
  }
  if (hashes == 0 || i >= end || (s[i] != ' ' && s[i] != '\t')) return -1
  return i
}

private fun parseOrderedListPrefixEnd(s: String, start: Int, end: Int): Pair<Int, Int>? {
  var i = start
  while (i < end && s[i] in '0'..'9') i++
  if (i == start || i + 1 >= end) return null
  if (s[i] == '.' && s[i + 1] == ' ') {
    return i to (i + 2)
  }
  return null
}

/**
 * Reuses [IncrementalMarkdownParser] to extract clean spoken/plain text from Markdown without
 * raw syntax markers (`**`, `#`, code-fence backticks, `~~`, `>`).
 */
internal fun extractPlainTextFromMarkdown(markdown: String): String {
  if (markdown.isBlank()) return ""
  val blocks = IncrementalMarkdownParser().parse(markdown).blocks
  if (blocks.isEmpty()) return stripInlineMarkdown(markdown)

  return blocks
    .mapNotNull { block ->
      when (block) {
        is MarkdownBlock.Heading -> stripInlineMarkdown(block.text)
        is MarkdownBlock.Paragraph -> stripInlineMarkdown(block.text)
        is MarkdownBlock.BulletItem -> stripInlineMarkdown(block.text)
        is MarkdownBlock.OrderedItem -> "${block.number}. ${stripInlineMarkdown(block.text)}"
        is MarkdownBlock.BlockQuote -> stripInlineMarkdown(block.text)
        is MarkdownBlock.CodeBlock -> block.code.trim()
      }.takeIf { it.isNotBlank() }
    }
    .joinToString(separator = "\n")
}

private fun stripInlineMarkdown(text: String): String {
  val sb = StringBuilder(text.length)
  var index = 0
  val length = text.length
  while (index < length) {
    val ch = text[index]
    when {
      ch == '`' -> {
        val end = text.indexOf('`', index + 1)
        if (end != -1) {
          sb.append(text, index + 1, end)
          index = end + 1
        } else {
          sb.append(ch)
          index++
        }
      }
      ch == '*' && text.startsWith("***", index) -> {
        val end = text.indexOf("***", index + 3)
        if (end != -1) {
          sb.append(text, index + 3, end)
          index = end + 3
        } else {
          index += 3
        }
      }
      ch == '*' && text.startsWith("**", index) -> {
        val end = text.indexOf("**", index + 2)
        if (end != -1) {
          sb.append(text, index + 2, end)
          index = end + 2
        } else {
          index += 2
        }
      }
      ch == '~' && text.startsWith("~~", index) -> {
        val end = text.indexOf("~~", index + 2)
        if (end != -1) {
          sb.append(text, index + 2, end)
          index = end + 2
        } else {
          index += 2
        }
      }
      ch == '[' -> {
        val closeBracket = text.indexOf(']', index + 1)
        if (closeBracket != -1 && closeBracket + 1 < length && text[closeBracket + 1] == '(') {
          val closeParen = text.indexOf(')', closeBracket + 2)
          if (closeParen != -1) {
            sb.append(text, index + 1, closeBracket)
            index = closeParen + 1
            continue
          }
        }
        sb.append(ch)
        index++
      }
      ch == '*' && index + 1 < length && text[index + 1] != ' ' -> {
        val end = text.indexOf('*', index + 1)
        if (end != -1 && end > index + 1) {
          sb.append(text, index + 1, end)
          index = end + 1
        } else {
          sb.append('*')
          index++
        }
      }
      else -> {
        sb.append(ch)
        index++
      }
    }
  }
  return sb.toString().trim()
}

/**
 * Lightweight, incremental Markdown renderer supporting headings, bold, italic,
 * strikethrough, inline code, fenced code blocks with copy action, lists, and blockquotes.
 */
@Composable
fun MarkdownText(
  markdown: String,
  modifier: Modifier = Modifier,
  textColor: Color = MaterialTheme.colorScheme.onSurface,
  isStreaming: Boolean = false,
) {
  val parser = remember { IncrementalMarkdownParser() }
  val parsed = parser.parse(markdown)
  val blocks = parsed.blocks
  val codeBg = LocalVedColors.current.codeBackground
  val accentColor = MaterialTheme.colorScheme.primary

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    val lastIndex = blocks.lastIndex
    blocks.forEachIndexed { index, block ->
      val appendCursor = isStreaming && index == lastIndex
      key(index) {
        MarkdownBlockItem(
          block = block,
          textColor = textColor,
          codeBg = codeBg,
          accentColor = accentColor,
          appendCursor = appendCursor,
        )
      }
    }
  }
}

@Composable
private fun MarkdownBlockItem(
  block: MarkdownBlock,
  textColor: Color,
  codeBg: Color,
  accentColor: Color,
  appendCursor: Boolean,
) {
  when (block) {
    is MarkdownBlock.Heading -> {
      val style =
        when (block.level) {
          1 -> MaterialTheme.typography.titleLarge
          2 -> MaterialTheme.typography.titleMedium
          else -> MaterialTheme.typography.titleSmall
        }
      val annotated =
        remember(block.text, codeBg, accentColor, appendCursor) {
          formatInlineMarkdown(block.text, codeBg, accentColor, appendCursor)
        }
      Text(text = annotated, style = style, color = textColor)
    }
    is MarkdownBlock.Paragraph -> {
      val annotated =
        remember(block.text, codeBg, accentColor, appendCursor) {
          formatInlineMarkdown(block.text, codeBg, accentColor, appendCursor)
        }
      Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge,
        color = textColor,
      )
    }
    is MarkdownBlock.BulletItem -> {
      val annotated =
        remember(block.text, codeBg, accentColor, appendCursor) {
          formatInlineMarkdown(block.text, codeBg, accentColor, appendCursor)
        }
      Row(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "•",
          style = MaterialTheme.typography.bodyLarge,
          color = accentColor,
          modifier = Modifier.padding(end = 10.dp),
        )
        Text(
          text = annotated,
          style = MaterialTheme.typography.bodyLarge,
          color = textColor,
          modifier = Modifier.weight(1f),
        )
      }
    }
    is MarkdownBlock.OrderedItem -> {
      val annotated =
        remember(block.text, codeBg, accentColor, appendCursor) {
          formatInlineMarkdown(block.text, codeBg, accentColor, appendCursor)
        }
      Row(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "${block.number}.",
          style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
          color = accentColor,
          modifier = Modifier.padding(end = 8.dp),
        )
        Text(
          text = annotated,
          style = MaterialTheme.typography.bodyLarge,
          color = textColor,
          modifier = Modifier.weight(1f),
        )
      }
    }
    is MarkdownBlock.BlockQuote -> {
      val annotated =
        remember(block.text, codeBg, accentColor, appendCursor) {
          formatInlineMarkdown(block.text, codeBg, accentColor, appendCursor)
        }
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(end = 12.dp, top = 8.dp, bottom = 8.dp)
      ) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(accentColor))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
          text = annotated,
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

private fun formatInlineMarkdown(
  text: String,
  inlineCodeBg: Color,
  accentColor: Color,
  appendStreamingCursor: Boolean,
): AnnotatedString = buildAnnotatedString {
  var index = 0
  val length = text.length

  while (index < length) {
    val ch = text[index]
    when {
      // Inline code `...`
      ch == '`' -> {
        val end = text.indexOf('`', index + 1)
        if (end != -1) {
          withStyle(
            SpanStyle(
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 13.5.sp,
              background = inlineCodeBg,
            )
          ) {
            append(text, index + 1, end)
          }
          index = end + 1
        } else {
          append(ch)
          index++
        }
      }
      // Bold+Italic ***...*** or Bold **...**
      ch == '*' && text.startsWith("***", index) -> {
        val end = text.indexOf("***", index + 3)
        if (end != -1) {
          withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
            append(text, index + 3, end)
          }
          index = end + 3
        } else {
          append("***")
          index += 3
        }
      }
      ch == '*' && text.startsWith("**", index) -> {
        val end = text.indexOf("**", index + 2)
        if (end != -1) {
          withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(text, index + 2, end)
          }
          index = end + 2
        } else {
          append("**")
          index += 2
        }
      }
      // Strikethrough ~~...~~
      ch == '~' && text.startsWith("~~", index) -> {
        val end = text.indexOf("~~", index + 2)
        if (end != -1) {
          withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            append(text, index + 2, end)
          }
          index = end + 2
        } else {
          append("~~")
          index += 2
        }
      }
      // Italic *...*
      ch == '*' && index + 1 < length && text[index + 1] != ' ' -> {
        val end = text.indexOf('*', index + 1)
        if (end != -1 && end > index + 1) {
          withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            append(text, index + 1, end)
          }
          index = end + 1
        } else {
          append('*')
          index++
        }
      }
      else -> {
        // Scan plain run until the next special markdown marker to reduce append() calls
        var plainEnd = index + 1
        while (plainEnd < length) {
          val next = text[plainEnd]
          if (next == '`' || next == '*' || next == '~') break
          plainEnd++
        }
        append(text, index, plainEnd)
        index = plainEnd
      }
    }
  }

  if (appendStreamingCursor) {
    withStyle(SpanStyle(color = accentColor, fontWeight = FontWeight.Bold)) {
      append(" ▍")
    }
  }
}
