package com.example.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The tablet's design tokens, taken from the web view's style.css so both look the same:
 * ink on paper, square corners, a 1.5 dp outer rule and a 1 dp inner rule, and a hard
 * offset shadow (no blur) on menus, pop-ups and dialogs. Tuned for e-ink: no translucent
 * fills, no soft shadows, and greys dark enough to survive the panel.
 *
 * New and changed screens use these instead of hard-coded colours, shapes and sizes.
 */
object Ink {
    // Colours (web: --ink, --paper, --grey, --soft, --rule, --wash)
    val Ink = Color(0xFF000000)
    val Paper = Color(0xFFFFFFFF)
    /** Secondary text: descriptions, places, help lines. Dark enough for e-ink. */
    val Grey = Color(0xFF3F3F46)
    /** Disabled text and hints only; too light for anything that must be read. */
    val Soft = Color(0xFF71717A)
    /** Rules between rows inside a panel. A shade darker than the web's #E4E4E7 so it shows on e-ink. */
    val Rule = Color(0xFFD4D4D8)
    /** Pressed or selected row background where inverting would be too loud. */
    val Wash = Color(0xFFF4F4F5)
    val Danger = Color(0xFFB91C1C)

    // Rules
    val OuterRule: Dp = 1.5.dp
    val InnerRule: Dp = 1.dp
    val StrongRule: Dp = 2.dp

    // Shapes: everything is square
    val Shape: Shape = RectangleShape
    /** Square corners as a CornerBasedShape, which Material's Shapes requires. */
    private val Square = RoundedCornerShape(0.dp)

    /** Material shapes with every corner square, so stock dialogs, menus, cards and chips match. */
    val MaterialShapes = Shapes(
        extraSmall = Square,
        small = Square,
        medium = Square,
        large = Square,
        extraLarge = Square
    )

    // Touch targets
    /** For a finger: buttons, rows and cells. */
    val TouchTarget: Dp = 44.dp
    /** For the pen, and for dense rows where a finger is not expected. */
    val PenTarget: Dp = 34.dp

    // Shadow
    val ShadowOffset: Dp = 4.dp
}

/** Text styles shared by the tablet and the web view (serif titles, mono labels, sans body). */
object InkType {
    /** Dialog and screen titles (web: .modal h2, .set-head h1). */
    val Title = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 26.sp, color = Ink.Ink)
    /** Section and panel headings (web: .set-group h2, .rside h3). */
    val Heading = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 23.sp, color = Ink.Ink)
    /** Small uppercase labels above fields and groups (web: .label). */
    val Label = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.05.em, color = Ink.Grey)
    /** Button and chip text (web: .btn, .chip). */
    val Control = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Ink.Ink)
    /** Running text in dialogs and panels. */
    val Body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 21.sp, color = Ink.Ink)
    /** Pages, places, counts, times (web: .mono, .rbar .rt span). */
    val Meta = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Ink.Grey)
    /** Quotations from the book. */
    val Quote = TextStyle(fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 21.sp, color = Ink.Ink)
}

/** The outer rule of a panel, menu or dialog. */
fun Modifier.inkFrame(width: Dp = Ink.OuterRule, color: Color = Ink.Ink): Modifier =
    this.border(width, color, Ink.Shape)

/**
 * The web view's hard offset shadow (box-shadow: 4px 4px 0 #000): a solid block drawn behind
 * the element, down and to the right. No blur, so e-ink draws it in one clean pass.
 * Put it before the background and the frame in the modifier chain.
 */
fun Modifier.inkShadow(offset: Dp = Ink.ShadowOffset, color: Color = Ink.Ink): Modifier =
    this.drawBehind {
        val o = offset.toPx()
        drawRect(color = color, topLeft = Offset(o, o), size = Size(size.width, size.height))
    }
