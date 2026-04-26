package com.cognitivechaos.xdownload.ui.onboarding

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cognitivechaos.xdownload.R
import com.cognitivechaos.xdownload.ui.theme.Orange500
import kotlinx.coroutines.launch

private val DarkBg = Color(0xFF181818)
private val CardBg = Color(0xFF242424)
private val CardBorder = Color(0xFF333333)
private val TextPrimary = Color(0xFFF0F0F0)
private val TextSecondary = Color(0xFFAAAAAA)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .systemBarsPadding()
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 0.dp,
            beyondViewportPageCount = 1
        ) { page ->
            // Parallax: content slides slightly slower than the swipe
            val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val parallaxOffset = pageOffset * 60f

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = parallaxOffset }
            ) {
                when (page) {
                    0 -> StepPage(
                        stepNumber = 1,
                        title = "Open any website",
                        description = "Tap the search bar and browse to any site — YouTube, Instagram, Twitter, and more.",
                        isCurrentPage = pagerState.currentPage == page,
                        content = { BrowserMockup() }
                    )
                    1 -> StepPage(
                        stepNumber = 2,
                        title = "Play the video",
                        description = "Find a video you like and tap play. XDownloader detects it automatically.",
                        isCurrentPage = pagerState.currentPage == page,
                        content = { VideoMockup() }
                    )
                    2 -> StepPage(
                        stepNumber = 3,
                        title = "Tap the download button",
                        description = "The orange button appears at the bottom right. One tap saves it to your device.",
                        isCurrentPage = pagerState.currentPage == page,
                        content = { DownloadMockup() }
                    )
                    3 -> DisclaimerPage(isCurrentPage = pagerState.currentPage == page)
                }
            }
        }

        // Bottom controls — frosted glass feel with gradient scrim
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, DarkBg.copy(alpha = 0.97f)),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
                .padding(horizontal = 24.dp)
                .padding(top = 32.dp, bottom = 28.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Animated page dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { index ->
                        val isActive = pagerState.currentPage == index
                        val width by animateDpAsState(
                            targetValue = if (isActive) 28.dp else 8.dp,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "dot_width_$index"
                        )
                        val dotAlpha by animateFloatAsState(
                            targetValue = if (isActive) 1f else 0.4f,
                            animationSpec = tween(300),
                            label = "dot_alpha_$index"
                        )
                        Box(
                            modifier = Modifier
                                .height(8.dp)
                                .width(width)
                                .clip(CircleShape)
                                .background(Orange500.copy(alpha = dotAlpha))
                        )
                    }
                }

                // Navigation row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedVisibility(
                        visible = pagerState.currentPage > 0,
                        enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.8f),
                        exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.8f)
                    ) {
                        OutlinedIconButton(
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            },
                            modifier = Modifier.size(52.dp),
                            border = BorderStroke(1.dp, Color(0xFF444444)),
                            colors = IconButtonDefaults.outlinedIconButtonColors(
                                containerColor = CardBg
                            )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextSecondary
                            )
                        }
                    }

                    val buttonLabel = when (pagerState.currentPage) {
                        3 -> "Let's Go"
                        else -> "Next"
                    }

                    Button(
                        onClick = {
                            if (pagerState.currentPage < 3) {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            } else {
                                onFinished()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Orange500,
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                    ) {
                        AnimatedContent(
                            targetState = buttonLabel,
                            transitionSpec = {
                                (slideInVertically { it } + fadeIn()) togetherWith
                                        (slideOutVertically { -it } + fadeOut())
                            },
                            label = "btn_label"
                        ) { label ->
                            Text(
                                text = label,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Step page wrapper with slide-up entrance ────────────────────────────────
@Composable
private fun StepPage(
    stepNumber: Int,
    title: String,
    description: String,
    isCurrentPage: Boolean,
    content: @Composable () -> Unit
) {
    val slideOffset by animateDpAsState(
        targetValue = if (isCurrentPage) 0.dp else 24.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "slide_offset"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (isCurrentPage) 1f else 0.6f,
        animationSpec = tween(350),
        label = "content_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 28.dp, bottom = 148.dp)
            .graphicsLayer {
                translationY = slideOffset.toPx()
                alpha = contentAlpha
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Step badge + title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Orange500, Color(0xFFE67E00))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stepNumber.toString(),
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )
            }
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                letterSpacing = (-0.3).sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = description,
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Mockup card with subtle inner shadow border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .shadow(12.dp, RoundedCornerShape(18.dp), ambientColor = Orange500.copy(alpha = 0.08f))
                .clip(RoundedCornerShape(18.dp))
                .background(CardBg)
                .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
        ) {
            content()
        }
    }
}

// ─── Mockup: Browser home page ───────────────────────────────────────────────
@Composable
private fun BrowserMockup() {
    // Use Box so we can overlay the TouchIndicator on top of the search bar
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MockupTopBar(urlText = "Search or enter website")

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color(0xFF2A2A2A))
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Quick Access",
                    color = Color(0xFF666666),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )

                val sites = listOf(
                    Triple("Facebook", R.drawable.ic_facebook, Color(0xFF1877F2)),
                    Triple("Instagram", R.drawable.ic_instagram, Color(0xFFE4405F)),
                    Triple("Vimeo", R.drawable.ic_vimeo, Color(0xFF1AB7EA)),
                    Triple("Twitter", R.drawable.ic_twitter, Color(0xFF1DA1F2)),
                    Triple("Dailymotion", R.drawable.ic_dailymotion, Color(0xFF0066DC)),
                    Triple("Google", R.drawable.ic_google, Color(0xFF4285F4)),
                )

                for (row in sites.chunked(3)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { (name, icon, color) ->
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(color.copy(alpha = 0.12f))
                                        .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(icon),
                                        contentDescription = name,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Text(name, color = Color(0xFF999999), fontSize = 9.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }

        // TouchIndicator overlaid at top — centered on the search bar
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
        ) {
            TouchIndicator()
        }
    }
}

// ─── Mockup: Video playing — looks like a video embedded in a webpage ────────
@Composable
private fun VideoMockup() {
    Column(modifier = Modifier.fillMaxSize()) {
        MockupTopBar(urlText = "www.example.com")

        // Webpage background — scrollable content feel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF1A1A1A))
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Embedded video player — full width, no side padding
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
                    .clip(RoundedCornerShape(0.dp))
            ) {
                Image(
                    painter = painterResource(R.drawable.ai_woman),
                    contentDescription = "Video preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Bottom scrim for controls
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xEE000000))
                            )
                        )
                )

                // Play button — very transparent so the face shows through
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(Color(0x33000000))
                        .border(1.5.dp, Color(0x55FFFFFF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Touch indicator offset slightly from center (pointing at play button)
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = 20.dp, y = 20.dp)
                ) {
                    TouchIndicator()
                }

                // Video controls bar
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("0:00", color = Color.White, fontSize = 9.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.VolumeUp, null, tint = Color.White, modifier = Modifier.size(12.dp))
                        Icon(Icons.Default.Fullscreen, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                }

                // Progress bar at very bottom of video
                LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp),
                    color = Orange500,
                    trackColor = Color(0x44FFFFFF)
                )
            }

            // Video title below the player — like a real webpage
            Text(
                "Amazing Beach Video - HD Quality",
                color = Color(0xFFE0E0E0),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 10.dp)
            )

            // Views + date row
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("1.2M views", color = Color(0xFF888888), fontSize = 10.sp)
                Text("•", color = Color(0xFF555555), fontSize = 10.sp)
                Text("2 days ago", color = Color(0xFF888888), fontSize = 10.sp)
            }

            // Divider
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A2A2A)))

            // Suggested video thumbnails (fake)
            repeat(2) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 80.dp, height = 48.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2E2E2E))
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(modifier = Modifier.width(120.dp).height(9.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)))
                        Box(modifier = Modifier.width(80.dp).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2A2A)))
                    }
                }
            }
        }

        MockupBottomBar()
    }
}

// ─── Mockup: Download button highlighted ─────────────────────────────────────
@Composable
private fun DownloadMockup() {
    // Box so we can float the FAB over the bottom-right of the whole content area
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MockupTopBar(urlText = "www.example.com")

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF1A1A1A))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Embedded video thumbnail
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    Image(
                        painter = painterResource(R.drawable.ai_woman),
                        contentDescription = "Video preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color(0xEE000000))))
                    )
                    // Play button — transparent
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(Color(0x33000000))
                            .border(1.5.dp, Color(0x55FFFFFF), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, "Play", tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(28.dp))
                    }
                    // Controls
                    Row(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("0:00", color = Color.White, fontSize = 9.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.VolumeUp, null, tint = Color.White, modifier = Modifier.size(12.dp))
                            Icon(Icons.Default.Fullscreen, null, tint = Color.White, modifier = Modifier.size(12.dp))
                        }
                    }
                    LinearProgressIndicator(
                        progress = { 0f },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
                        color = Orange500,
                        trackColor = Color(0x44FFFFFF)
                    )
                }

                // Video title + meta
                Text("Amazing Beach Video - HD Quality", color = Color(0xFFE0E0E0), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("1.2M views", color = Color(0xFF888888), fontSize = 10.sp)
                    Text("•", color = Color(0xFF555555), fontSize = 10.sp)
                    Text("2 days ago", color = Color(0xFF888888), fontSize = 10.sp)
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A2A2A)))
                repeat(2) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(width = 80.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2E2E2E)))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(modifier = Modifier.width(120.dp).height(9.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)))
                            Box(modifier = Modifier.width(80.dp).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2A2A)))
                        }
                    }
                }
            }

            MockupBottomBar()
        }

        // Download FAB — floated over bottom-right above the bottom bar (like the real app)
        // No bobbing — just the orange pulse ring animation
        val infiniteTransition = rememberInfiniteTransition(label = "fab_pulse")
        val glowScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glow_scale"
        )
        val glowAlpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(700),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glow_alpha"
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // 48dp = approximate height of MockupBottomBar, so FAB sits just above it
                .padding(end = 12.dp, bottom = 52.dp)
        ) {
            // Expanding orange glow ring
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .align(Alignment.Center)
                    .graphicsLayer { scaleX = glowScale; scaleY = glowScale; alpha = glowAlpha }
                    .clip(CircleShape)
                    .background(Orange500)
            )
            // FAB — stays still
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Orange500)
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Download, "Download", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }

        // Touch indicator just to the right/below the FAB
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 2.dp, bottom = 36.dp)
        ) {
            TouchIndicator()
        }
    }
}

// ─── Disclaimer page ─────────────────────────────────────────────────────────
@Composable
private fun DisclaimerPage(isCurrentPage: Boolean) {
    val slideOffset by animateDpAsState(
        targetValue = if (isCurrentPage) 0.dp else 24.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "disclaimer_slide"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (isCurrentPage) 1f else 0.6f,
        animationSpec = tween(350),
        label = "disclaimer_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .padding(top = 48.dp, bottom = 148.dp)
            .graphicsLayer { translationY = slideOffset.toPx(); alpha = contentAlpha },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Animated shield icon
        val infiniteTransition = rememberInfiniteTransition(label = "shield")
        val shieldScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shield_scale"
        )

        Box(
            modifier = Modifier
                .size(80.dp)
                .graphicsLayer { scaleX = shieldScale; scaleY = shieldScale }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Orange500.copy(alpha = 0.25f), Orange500.copy(alpha = 0.05f))
                    )
                )
                .border(1.dp, Orange500.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = Orange500,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Disclaimer",
            color = Orange500,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 30.sp,
            letterSpacing = (-0.5).sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Please read before continuing",
            color = Color(0xFF666666),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(24.dp))

        DisclaimerItem(
            icon = Icons.Default.Copyright,
            text = "Always get PERMISSION from the content owner before reposting or sharing videos and photos."
        )
        Spacer(modifier = Modifier.height(12.dp))
        DisclaimerItem(
            icon = Icons.Default.Gavel,
            text = "Unauthorized downloading or re-uploading of copyrighted content is the sole responsibility of the user."
        )
        Spacer(modifier = Modifier.height(12.dp))
        DisclaimerItem(
            icon = Icons.Default.PersonalVideo,
            text = "XDownloader is for personal use only. Respect creators and their work."
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = buildAnnotatedString {
                append("By tapping Let's Go, you agree to use this app ")
                withStyle(SpanStyle(color = Orange500, fontWeight = FontWeight.SemiBold)) {
                    append("responsibly and legally")
                }
                append(".")
            },
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp
        )
    }
}

@Composable
private fun DisclaimerItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E1E1E))
            .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(14.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Orange500.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Orange500, modifier = Modifier.size(15.dp))
        }
        Text(text, color = TextSecondary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

// ─── Shared mockup components ─────────────────────────────────────────────────
@Composable
private fun MockupTopBar(urlText: String) {
    // Matches the real app: surface color top bar with orange search icon + MoreVert
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1C))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Rounded search field — matches OutlinedTextField with RoundedCornerShape(24.dp)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(34.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF2A2A2A))
                .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = Orange500,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    urlText,
                    color = Color(0xFF777777),
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
        // MoreVert menu button
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color(0xFF2A2A2A)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = null,
                tint = Color(0xFF888888),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun MockupBottomBar() {
    // Matches the real app's bottom navigation bar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1C))
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        data class NavItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String)
        listOf(
            NavItem(Icons.AutoMirrored.Filled.ArrowBack, "Back"),
            NavItem(Icons.AutoMirrored.Filled.ArrowForward, "Forward"),
            NavItem(Icons.Default.Home, "Home"),
            NavItem(Icons.Default.Download, "Files"),
            NavItem(Icons.Default.Tab, "Tabs"),
        ).forEach { item ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    item.icon,
                    contentDescription = item.label,
                    tint = Color(0xFF666666),
                    modifier = Modifier.size(17.dp)
                )
                Text(
                    item.label,
                    color = Color(0xFF666666),
                    fontSize = 8.sp
                )
            }
        }
    }
}

// ─── Touch/tap indicator — outline-only ripple with finger icon ──────────────
@Composable
private fun TouchIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "touch")

    // Ripple expands and fades out
    val rippleScale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple_scale"
    )
    val rippleAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple_alpha"
    )
    // Icon subtle pulse
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_scale"
    )

    Box(
        modifier = modifier.size(52.dp),
        contentAlignment = Alignment.Center
    ) {
        // Expanding ripple ring — outline only, no fill
        Box(
            modifier = Modifier
                .size(52.dp)
                .graphicsLayer { scaleX = rippleScale; scaleY = rippleScale; alpha = rippleAlpha }
                .clip(CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
        )
        // Inner circle — transparent background, white border
        Box(
            modifier = Modifier
                .size(30.dp)
                .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                .clip(CircleShape)
                .background(Color(0x33000000))
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.TouchApp,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
