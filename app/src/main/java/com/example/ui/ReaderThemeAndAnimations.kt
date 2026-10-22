package com.example.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex

enum class ReaderTheme(
    val id: String,
    val label: String,
    val backgroundColor: Color,
    val textColor: Color,
    val secondaryTextColor: Color,
    val barColor: Color,
    val dividerColor: Color
) {
    WHITE(
        id = "WHITE",
        label = "White",
        backgroundColor = Color(0xFFFFFFFF),
        textColor = Color(0xFF18181B),
        secondaryTextColor = Color(0xFF71717A),
        barColor = Color(0xFFFFFFFF),
        dividerColor = Color(0xFFE4E4E7)
    ),
    CREAM(
        id = "CREAM",
        label = "Cream White",
        backgroundColor = Color(0xFFFBF0D9),
        textColor = Color(0xFF26211C),
        secondaryTextColor = Color(0xFF7D7365),
        barColor = Color(0xFFFBF0D9),
        dividerColor = Color(0xFFE5DDD0)
    ),
    DARK(
        id = "DARK",
        label = "Black & White",
        backgroundColor = Color(0xFF000000),
        textColor = Color(0xFFFFFFFF),
        secondaryTextColor = Color(0xFFA1A1AA),
        barColor = Color(0xFF121212),
        dividerColor = Color(0xFF27272A)
    )
}

/**
 * Physical 3D Page Turn & Curl Effect:
 * Renders a genuine physical book page curl without ever squishing, tilting, or distorting the text:
 * - Both pages stay locked in full screen-space alignment (translationX = size.width * pageOffset)
 * - The upper page is clipped cleanly along a curved vertical fold line (foldX = width * (1 - p))
 *   so the text on the page stays 100% crisp, un-stretched, and opaque as the fold sweeps across
 * - Along the curved fold line, a 3D cylindrical rolled paper back-of-page flap arches over with
 *   realistic paper-back coloring, specular highlight, spine crease, and cast shadow onto the revealed page underneath.
 */
@Composable
internal fun Modifier.applyPhysicalPageTurnEffect(
    pagerState: androidx.compose.foundation.pager.PagerState,
    pageIndex: Int,
    pageTurnAnimation: String,
    activeTheme: ReaderTheme
): Modifier {
    if (!pageTurnAnimation.equals("PAGE_CURL", ignoreCase = true)) {
        return this.background(activeTheme.backgroundColor)
    }
    val liveOffset = (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
    val zIdx = when {
        liveOffset >= 0f && liveOffset < 1f -> 3f
        liveOffset < 0f && liveOffset > -1f -> 1f
        else -> 0f
    }
    return this
        .zIndex(zIdx)
        .graphicsLayer {
            val pageOffset = (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
            if (pageOffset > -1f && pageOffset < 1f) {
                // Counteract HorizontalPager's sliding translation so both the curling upper page
                // and the revealed lower page occupy the exact same book frame without sliding or squishing!
                translationX = size.width * pageOffset
                alpha = 1f
            } else {
                alpha = 0f
            }
        }
        .drawWithContent {
            val pageOffset = (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
            val w = size.width
            val h = size.height

            when {
                pageOffset <= 0.001f && pageOffset >= -0.001f -> {
                    // Resting active page: draw crisp and untouched
                    drawRect(color = activeTheme.backgroundColor)
                    drawContent()
                }

                pageOffset > 0.001f && pageOffset < 0.999f -> {
                    // UPPER PAGE CURLING AWAY (or uncurling back):
                    // p goes from 0f (flat) to 1f (completely turned over to the left spine)
                    val p = pageOffset.coerceIn(0f, 1f)
                    val sinP = kotlin.math.sin(p * Math.PI.toFloat())

                    // The fold line sweeps smoothly from the right edge (w) to the left spine (0)
                    val foldX = (w * (1f - p)).coerceIn(0f, w)
                    // Natural bow/curve of a physical sheet of paper being lifted from its middle/edge
                    val bowPx = w * 0.055f * sinP

                    // 1. Clip the upper page to the left of the curved fold line so the revealed page
                    //    underneath is visible to the right of the fold, while the upper page text stays 100% un-distorted!
                    val visiblePagePath = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(foldX, 0f)
                        quadraticTo(
                            foldX - bowPx,
                            h * 0.5f,
                            foldX,
                            h
                        )
                        lineTo(0f, h)
                        close()
                    }

                    clipPath(visiblePagePath) {
                        drawRect(color = activeTheme.backgroundColor)
                        this@drawWithContent.drawContent()

                        // Subtle upward-curving shadow on the front of the page right before the paper lifts into the curl
                        val frontLiftWidth = (w * 0.10f * sinP).coerceAtLeast(16f)
                        val liftStart = (foldX - bowPx - frontLiftWidth).coerceAtLeast(0f)
                        if (foldX > liftStart) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.16f * sinP)
                                    ),
                                    startX = liftStart,
                                    endX = foldX
                                ),
                                topLeft = Offset(liftStart, 0f),
                                size = Size(foldX - liftStart, h)
                            )
                        }

                        // Subtle book spine crease on the far left edge
                        val spineW = (w * 0.06f).coerceAtLeast(14f)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.22f * sinP),
                                    Color.Transparent
                                ),
                                startX = 0f,
                                endX = spineW
                            ),
                            topLeft = Offset.Zero,
                            size = Size(spineW, h)
                        )
                    }

                    // 2. Cast shadow thrown onto the revealed lower page just to the right of the curled paper roll
                    val curlWidth = (w * (0.08f + 0.18f * sinP) * (1f - p * 0.25f)).coerceIn(12f, w * 0.30f)
                    val curlRightX = (foldX + curlWidth * 0.38f).coerceAtMost(w)
                    val curlLeftX = (foldX - curlWidth * 0.62f).coerceAtLeast(0f)

                    val dropShadowWidth = (curlWidth * 0.85f).coerceAtLeast(18f)
                    val shadowRightX = (curlRightX + dropShadowWidth).coerceAtMost(w)
                    if (shadowRightX > foldX - bowPx) {
                        val shadowPath = Path().apply {
                            moveTo(foldX, 0f)
                            lineTo(shadowRightX, 0f)
                            quadraticTo(
                                shadowRightX - bowPx * 0.6f,
                                h * 0.5f,
                                shadowRightX,
                                h
                            )
                            lineTo(foldX, h)
                            quadraticTo(
                                foldX - bowPx,
                                h * 0.5f,
                                foldX,
                                0f
                            )
                            close()
                        }
                        drawPath(
                            path = shadowPath,
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.38f * sinP),
                                    Color.Black.copy(alpha = 0.16f * sinP),
                                    Color.Transparent
                                ),
                                startX = foldX - bowPx,
                                endX = shadowRightX
                            )
                        )
                    }

                    // 3. Draw the 3D cylindrical curled back-of-page paper roll along the fold line
                    val backPaperMain = when (activeTheme) {
                        ReaderTheme.DARK -> Color(0xFF1F1F23)
                        ReaderTheme.CREAM -> Color(0xFFF4E6C8)
                        ReaderTheme.WHITE -> Color(0xFFF3F4F6)
                    }
                    val backPaperSpecular = when (activeTheme) {
                        ReaderTheme.DARK -> Color(0xFF3F3F46)
                        ReaderTheme.CREAM -> Color(0xFFFFFCF5)
                        ReaderTheme.WHITE -> Color(0xFFFFFFFF)
                    }
                    val backPaperShadow = when (activeTheme) {
                        ReaderTheme.DARK -> Color(0xFF09090B)
                        ReaderTheme.CREAM -> Color(0xFFD6C5A3)
                        ReaderTheme.WHITE -> Color(0xFFD4D4D8)
                    }

                    val archLiftY = h * 0.018f * sinP
                    val curledBackPath = Path().apply {
                        moveTo(curlLeftX, archLiftY)
                        // Top curved edge of the rolled paper cylinder
                        quadraticTo(
                            (curlLeftX + curlRightX) * 0.5f,
                            -archLiftY,
                            curlRightX,
                            0f
                        )
                        // Right outer fold edge (where the paper bends backward)
                        quadraticTo(
                            curlRightX - bowPx,
                            h * 0.5f,
                            curlRightX,
                            h
                        )
                        // Bottom curved edge of the rolled paper cylinder
                        quadraticTo(
                            (curlLeftX + curlRightX) * 0.5f,
                            h + archLiftY,
                            curlLeftX,
                            h - archLiftY
                        )
                        // Left trailing edge of the rolled paper back
                        quadraticTo(
                            curlLeftX - bowPx * 1.15f,
                            h * 0.5f,
                            curlLeftX,
                            archLiftY
                        )
                        close()
                    }

                    drawPath(
                        path = curledBackPath,
                        brush = Brush.horizontalGradient(
                            0.0f to backPaperShadow,
                            0.18f to backPaperMain,
                            0.62f to backPaperSpecular,
                            0.88f to backPaperMain,
                            1.0f to backPaperShadow,
                            startX = (curlLeftX - bowPx * 1.15f).coerceAtLeast(0f),
                            endX = curlRightX.coerceAtLeast(1f)
                        )
                    )
                }

                pageOffset < -0.001f && pageOffset > -0.999f -> {
                    // LOWER PAGE BEING REVEALED UNDERNEATH:
                    // Stays 100% stationary, crisp, and opaque while the upper page curls off of it
                    drawRect(color = activeTheme.backgroundColor)
                    drawContent()
                }
            }
        }
}

internal suspend fun androidx.compose.foundation.pager.PagerState.turnToPageWithAnimation(
    targetPage: Int,
    pageTurnAnimation: String
) {
    val safeTarget = targetPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    if (safeTarget == currentPage) return
    val pageDistance = kotlin.math.abs(safeTarget - currentPage)
    // When jumping across multiple pages (e.g. from TOC chapter menu or 'Tilbake til tekst' from Notes),
    // jump directly with scrollToPage so HorizontalPager does not compose and lay out dozens of intermediate pages!
    if (pageDistance > 2 || pageTurnAnimation.equals("NONE", ignoreCase = true)) {
        scrollToPage(safeTarget)
        return
    }
    when (pageTurnAnimation.uppercase()) {
        "PAGE_CURL" -> animateScrollToPage(
            page = safeTarget,
            animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing)
        )
        else -> animateScrollToPage(
            page = safeTarget,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
        )
    }
}
