package io.github.ethanbird.senseime.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String, val orderedIndex: Int? = null) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String, val text: String) : MarkdownBlock
}

internal object SimpleMarkdownParser {
    private val orderedBullet = Regex("^(\\d+)\\.\\s+(.+)$")

    fun parse(source: String): List<MarkdownBlock> {
        if (source.isBlank()) return emptyList()
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        var inCode = false
        var codeLanguage = ""
        val code = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }

        source.replace("\r\n", "\n").lineSequence().forEach { line ->
            if (line.startsWith("```")) {
                if (inCode) {
                    blocks += MarkdownBlock.Code(codeLanguage, code.joinToString("\n"))
                    code.clear()
                    codeLanguage = ""
                    inCode = false
                } else {
                    flushParagraph()
                    codeLanguage = line.removePrefix("```").trim()
                    inCode = true
                }
                return@forEach
            }
            if (inCode) {
                code += line
                return@forEach
            }
            if (line.isBlank()) {
                flushParagraph()
                return@forEach
            }
            val headingLevel = line.takeWhile { it == '#' }.length
            when {
                headingLevel in 1..3 && line.getOrNull(headingLevel) == ' ' -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Heading(
                        headingLevel,
                        line.drop(headingLevel + 1).trim(),
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Bullet(line.drop(2).trim())
                }
                orderedBullet.matches(line) -> {
                    flushParagraph()
                    val match = checkNotNull(orderedBullet.matchEntire(line))
                    blocks += MarkdownBlock.Bullet(
                        text = match.groupValues[2].trim(),
                        orderedIndex = match.groupValues[1].toIntOrNull(),
                    )
                }
                line.startsWith("> ") -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Quote(line.drop(2).trim())
                }
                else -> paragraph += line
            }
        }
        if (inCode) blocks += MarkdownBlock.Code(codeLanguage, code.joinToString("\n"))
        flushParagraph()
        return blocks
    }
}

@Composable
internal fun MarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val palette = LocalAgentPalette.current
    var blocks by remember { mutableStateOf<List<MarkdownBlock>>(emptyList()) }
    LaunchedEffect(markdown) {
        if (streaming) {
            delay(
                when {
                    markdown.length < 500 -> 100L
                    markdown.length < 2_000 -> 180L
                    markdown.length < 32_000 -> 300L
                    else -> 500L
                },
            )
        }
        blocks = withContext(Dispatchers.Default) {
            SimpleMarkdownParser.parse(markdown)
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = inlineMarkdown(block.text, palette.inlineCodeText),
                    color = palette.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = when (block.level) {
                        1 -> 23.sp
                        2 -> 20.sp
                        else -> 17.sp
                    },
                    lineHeight = when (block.level) {
                        1 -> 29.sp
                        2 -> 26.sp
                        else -> 23.sp
                    },
                    modifier = Modifier.padding(top = if (block.level == 1) 5.dp else 2.dp),
                )
                is MarkdownBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text, palette.inlineCodeText),
                    color = palette.textPrimary,
                    fontSize = 16.5.sp,
                    lineHeight = 24.sp,
                )
                is MarkdownBlock.Bullet -> Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = block.orderedIndex?.let { "$it." } ?: "•",
                        color = palette.textPrimary,
                        fontSize = 16.5.sp,
                        lineHeight = 24.sp,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        text = inlineMarkdown(block.text, palette.inlineCodeText),
                        color = palette.textPrimary,
                        fontSize = 16.5.sp,
                        lineHeight = 24.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MarkdownBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(24.dp)
                            .background(palette.accent, RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = inlineMarkdown(block.text, palette.inlineCodeText),
                        color = palette.textSecondary,
                        fontSize = 15.5.sp,
                        lineHeight = 23.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MarkdownBlock.Code -> Column(
                    Modifier
                        .fillMaxWidth()
                        .background(palette.codeBackground, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (block.language.isNotBlank()) {
                        Text(
                            text = block.language,
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                    Text(
                        text = block.text,
                        color = palette.codeText,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

internal fun inlineMarkdown(
    source: String,
    inlineCodeColor: Color,
): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < source.length) {
        when {
            source.startsWith("**", index) -> {
                val end = source.indexOf("**", index + 2)
                if (end > index + 2) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(source.substring(index + 2, end))
                    pop()
                    index = end + 2
                } else {
                    append(source[index++])
                }
            }
            source[index] == '`' -> {
                val end = source.indexOf('`', index + 1)
                if (end > index + 1) {
                    pushStyle(
                        SpanStyle(
                            color = inlineCodeColor,
                            fontFamily = FontFamily.Monospace,
                            background = inlineCodeColor.copy(alpha = 0.10f),
                        ),
                    )
                    append(source.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append(source[index++])
                }
            }
            source.startsWith("~~", index) -> {
                val end = source.indexOf("~~", index + 2)
                if (end > index + 2) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(source.substring(index + 2, end))
                    pop()
                    index = end + 2
                } else {
                    append(source[index++])
                }
            }
            else -> append(source[index++])
        }
    }
}
