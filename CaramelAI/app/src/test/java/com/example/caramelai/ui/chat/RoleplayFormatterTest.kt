package com.example.caramelai.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplayFormatterTest {
    private val colors = RoleplayColors(
        dialogue = Color.Blue,
        action = Color.Magenta,
        bold = Color.White,
        normal = Color.Gray,
    )

    @Test
    fun formatsItalicAction() {
        val result = RoleplayFormatter.format("*smiles*", colors)
        assertEquals("smiles", result.text)
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(
            result.spanStyles.any {
                it.item.fontStyle == FontStyle.Italic && it.item.color == colors.action
            },
        )
    }

    @Test
    fun formatsBold() {
        val result = RoleplayFormatter.format("**loud**", colors)
        assertEquals("loud", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun formatsDialogueQuotes() {
        val result = RoleplayFormatter.format("\"Hello there.\"", colors)
        assertEquals("\"Hello there.\"", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun mixedRoleplayText() {
        val result = RoleplayFormatter.format(
            "*waves* \"Hi!\" **really**",
            colors,
        )
        assertEquals("waves \"Hi!\" really", result.text)
    }

    @Test
    fun unclosedAsteriskStaysLiteral() {
        val result = RoleplayFormatter.format("*she walks", colors)
        assertEquals("*she walks", result.text)
        assertFalse(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun unclosedAsteriskDoesNotSwallowLaterBold() {
        val result = RoleplayFormatter.format("*no close **bold** still", colors)
        assertEquals("*no close bold still", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertFalse(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun boldInsideItalic() {
        val result = RoleplayFormatter.format("*she **really** means it*", colors)
        assertEquals("she really means it", result.text)
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun italicInsideBold() {
        val result = RoleplayFormatter.format("**he said *quietly* then left**", colors)
        assertEquals("he said quietly then left", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun italicInsideDialogue() {
        val result = RoleplayFormatter.format("\"Wait, *please* stop.\"", colors)
        assertEquals("\"Wait, please stop.\"", result.text)
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun consecutiveActions() {
        val result = RoleplayFormatter.format("*looks* *smiles*", colors)
        assertEquals("looks smiles", result.text)
    }
}
