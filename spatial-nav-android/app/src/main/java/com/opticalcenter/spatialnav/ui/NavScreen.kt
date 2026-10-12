package com.opticalcenter.spatialnav.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opticalcenter.spatialnav.R
import com.opticalcenter.spatialnav.agent.PlaceResult
import com.opticalcenter.spatialnav.navigation.Phrasing
import com.opticalcenter.spatialnav.navigation.Place
import com.opticalcenter.spatialnav.navigation.TripState
import com.opticalcenter.spatialnav.spatial.SpatialEngine
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val SUGGESTIONS = listOf(
    "What's on my left?",
    "Find coffee nearby",
    "Any traffic nearby?",
    "Where am I?",
)
private val NAV_SUGGESTIONS = listOf(
    "How long until I get there?",
    "Any problems on my route?",
    "Is there a faster route?",
    "Stop navigation",
)

/** How long an assistant reply stays on screen as a caption. */
private const val CAPTION_MS = 8_000L

/** Callbacks from the screen to the view model. */
data class NavActions(
    val onToggleListening: () -> Unit,
    val onAsk: (String) -> Unit,
    val onSearch: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onNavigateTo: (Place) -> Unit,
    val onStopNavigation: () -> Unit,
    val onToggleTraffic: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onOpenSettings: () -> Unit,
)

@Composable
fun NavScreen(state: NavUiState, mapController: MapController, actions: NavActions) {
    // Overlay extents in px, used to pad the map camera so the user's arrow stays in the visible gap.
    var topOverlayPx by remember { mutableIntStateOf(0) }
    var bottomOverlayPx by remember { mutableIntStateOf(0) }
    var rootHeightPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(topOverlayPx, bottomOverlayPx) { mapController.setInsets(topOverlayPx, bottomOverlayPx) }

    var conversationOpen by rememberSaveable { mutableStateOf(false) }
    val trip = state.trip?.takeIf { !it.arrived }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .onGloballyPositioned { rootHeightPx = it.size.height },
    ) {
        NavMap(state, mapController, Modifier.fillMaxSize())

        Column(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .onGloballyPositioned { topOverlayPx = it.boundsInParent().bottom.roundToInt() },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (trip != null) ManeuverBanner(trip) else SearchBar(state, actions)
            state.notice?.let { notice ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Palette.SurfaceRaised,
                    contentColor = Palette.OnSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Warn.copy(alpha = 0.5f)),
                    onClick = if (notice.opensSettings) actions.onOpenSettings else actions.onDismissNotice,
                ) {
                    Text(notice.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 13.sp)
                }
            }
        }

        // Hidden while the conversation is open so the panel never covers them.
        AnimatedVisibility(
            visible = !conversationOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
        ) {
            MapButtons(
                state = state,
                onOpenSettings = actions.onOpenSettings,
                onRecenter = mapController::recenter,
                onToggleTraffic = actions.onToggleTraffic,
                modifier = Modifier,
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .onGloballyPositioned { bottomOverlayPx = rootHeightPx - it.boundsInParent().top.roundToInt() },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(conversationOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                ConversationPanel(
                    log = state.log,
                    suggestions = if (trip != null) NAV_SUGGESTIONS else SUGGESTIONS,
                    onAsk = actions.onAsk,
                    onClose = { conversationOpen = false },
                )
            }
            if (!conversationOpen) {
                ReplyCaption(state.log, onClick = { conversationOpen = true })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    if (trip != null) {
                        TripCard(trip, actions.onStopNavigation)
                    } else {
                        AskPill(
                            state = state,
                            conversationOpen = conversationOpen,
                            onToggleConversation = { conversationOpen = !conversationOpen },
                            onAsk = actions.onAsk,
                        )
                    }
                }
                MicButton(
                    listening = state.listening,
                    enabled = state.speechInputAvailable,
                    onClick = actions.onToggleListening,
                )
            }
        }
    }
}

// --- Top: search ------------------------------------------------------------

@Composable
private fun SearchBar(state: NavUiState, actions: NavActions) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Surface(shape = RoundedCornerShape(26.dp), color = Palette.Surface, shadowElevation = 8.dp) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.OnSurfaceMuted)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search for a place or address", color = Palette.OnSurfaceMuted, fontSize = 16.sp)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Palette.OnSurface, fontSize = 16.sp),
                        cursorBrush = SolidColor(Palette.Blue),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focus.clearFocus()
                            actions.onSearch(query)
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Search" },
                    )
                }
                when {
                    state.searching -> CircularProgressIndicator(
                        Modifier
                            .padding(12.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                        color = Palette.Blue,
                    )
                    query.isNotEmpty() || state.searchResults.isNotEmpty() -> IconButton(onClick = {
                        query = ""
                        actions.onClearSearch()
                    }) { Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = Palette.OnSurfaceMuted) }
                    else -> Spacer(Modifier.width(12.dp))
                }
            }
            if (state.searchResults.isNotEmpty()) {
                HorizontalDivider(color = Palette.Outline.copy(alpha = 0.5f))
                Column(Modifier.heightIn(max = 300.dp)) {
                    for (result in state.searchResults) SearchResultRow(result) { actions.onNavigateTo(result.place) }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: PlaceResult, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(result.place.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(result.place.category, result.place.address).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, fontSize = 12.sp, color = Palette.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(shortDistance(result.distanceM.toDouble()), fontSize = 13.sp)
            Text(result.direction.spoken, fontSize = 11.sp, color = Color(DIRECTION_COLORS.getValue(result.direction)))
        }
    }
}

// --- Top: turn-by-turn ------------------------------------------------------

@Composable
private fun ManeuverBanner(trip: TripState) {
    val progress = trip.progress
    val next = progress?.nextInstruction
    Surface(shape = RoundedCornerShape(18.dp), color = Palette.BlueDeep, contentColor = Color.White, shadowElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(maneuverGlyph(next?.maneuver), fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Column {
                when {
                    trip.rerouting -> Text("Rerouting…", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    next == null -> Text("Head to ${trip.destination.name}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    else -> {
                        Text(shortDistance(progress.distanceToNextM ?: 0.0), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(next.message, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun maneuverGlyph(maneuver: String?): String = when {
    maneuver == null -> "↑"
    maneuver.startsWith("ARRIVE") -> "◎"
    maneuver.contains("UTURN") -> "↶"
    maneuver.startsWith("ROUNDABOUT") -> "↻"
    maneuver.contains("SHARP_LEFT") -> "↙"
    maneuver.contains("SHARP_RIGHT") -> "↘"
    maneuver.contains("LEFT") && (maneuver.startsWith("KEEP") || maneuver.startsWith("BEAR")) -> "↖"
    maneuver.contains("RIGHT") && (maneuver.startsWith("KEEP") || maneuver.startsWith("BEAR")) -> "↗"
    maneuver.contains("LEFT") -> "←"
    maneuver.contains("RIGHT") -> "→"
    else -> "↑"
}

private fun shortDistance(m: Double): String = when {
    m >= 10_000 -> "${(m / 1000).roundToInt()} km"
    m >= 1000 -> String.format(Locale.US, "%.1f km", m / 1000)
    m >= 100 -> "${(m / 50).roundToInt() * 50} m"
    else -> "${(m / 10).roundToInt() * 10} m"
}

// --- Bottom ------------------------------------------------------------------

/** The slim "ask anything" pill: tap to type, arrow to show the conversation. */
@Composable
private fun AskPill(state: NavUiState, conversationOpen: Boolean, onToggleConversation: () -> Unit, onAsk: (String) -> Unit) {
    var typing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val send = {
        if (text.isNotBlank()) onAsk(text)
        text = ""
        typing = false
        focus.clearFocus()
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Palette.Surface,
        shadowElevation = 8.dp,
        modifier = Modifier.height(56.dp),
    ) {
        Row(Modifier.padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .clickable(enabled = !typing && !state.listening) { typing = true },
            ) {
                when {
                    typing -> {
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        if (text.isEmpty()) Text("Ask anything…", color = Palette.OnSurfaceMuted, fontSize = 16.sp)
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Palette.OnSurface, fontSize = 16.sp),
                            cursorBrush = SolidColor(Palette.Blue),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                        )
                    }
                    state.listening -> Text(
                        state.partialTranscript.ifEmpty { "Listening…" },
                        color = Palette.OnSurface,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.thinking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Blue)
                        Spacer(Modifier.width(10.dp))
                        Text("Thinking…", color = Palette.OnSurfaceMuted, fontSize = 16.sp)
                    }
                    else -> Column {
                        Text("Ask anything…", color = Palette.OnSurfaceMuted, fontSize = 16.sp)
                        if (state.assistantLabel.isNotEmpty()) {
                            Text(state.assistantLabel, color = Palette.OnSurfaceMuted.copy(alpha = 0.6f), fontSize = 11.sp)
                        }
                    }
                }
            }
            when {
                typing -> IconButton(onClick = send) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Palette.Blue)
                }
                else -> IconButton(onClick = onToggleConversation) {
                    Icon(
                        if (conversationOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                        contentDescription = if (conversationOpen) "Hide conversation" else "Show conversation",
                        tint = Palette.OnSurfaceMuted,
                    )
                }
            }
        }
    }
}

/** Round tap-to-talk button. Pulses while listening; tap again to finish early. */
@Composable
private fun MicButton(listening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "mic-pulse")
    val ring by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "ring",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
        if (listening) {
            Box(
                Modifier
                    .size(56.dp)
                    .scale(ring)
                    .background(Palette.Blue.copy(alpha = (1.35f - ring) * 0.9f), CircleShape),
            )
        }
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (enabled) Palette.Blue else Palette.SurfaceHigh,
            contentColor = Color.White,
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(56.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = if (listening) "Stop listening" else "Talk to the assistant"
                    stateDescription = when {
                        !enabled -> "Voice input unavailable"
                        listening -> "Listening"
                        else -> "Idle"
                    }
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (listening) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .background(Color.White, RoundedCornerShape(4.dp)),
                    )
                } else {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

/** The latest assistant reply, shown briefly above the bar. Tap to open the conversation. */
@Composable
private fun ReplyCaption(log: List<LogEntry>, onClick: () -> Unit) {
    val last = log.lastOrNull { it.speaker == Speaker.ASSISTANT }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(last?.id) {
        if (last == null) return@LaunchedEffect
        visible = true
        delay(CAPTION_MS)
        visible = false
    }
    AnimatedVisibility(visible && last != null, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(18.dp),
            color = Palette.SurfaceRaised.copy(alpha = 0.96f),
            shadowElevation = 6.dp,
        ) {
            Text(
                last?.text.orEmpty(),
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                fontSize = 15.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConversationPanel(log: List<LogEntry>, suggestions: List<String>, onAsk: (String) -> Unit, onClose: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = Palette.Surface, shadowElevation = 10.dp) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(Modifier.padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Conversation", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Hide conversation", tint = Palette.OnSurfaceMuted)
                }
            }
            if (log.isEmpty()) {
                Text(
                    "Tap the mic and ask about what's around you, places, routes or traffic.",
                    Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    color = Palette.OnSurfaceMuted,
                    fontSize = 14.sp,
                )
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(log.size) { listState.scrollToItem(log.lastIndex) }
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .padding(horizontal = 12.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(log, key = { it.id }) { entry -> LogRow(entry) }
                }
            }
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (prompt in suggestions) {
                    AssistChip(
                        onClick = { onAsk(prompt) },
                        label = { Text(prompt) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = Palette.SurfaceRaised, labelColor = Palette.OnSurface),
                        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Palette.Outline),
                    )
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    when (entry.speaker) {
        Speaker.TOOL -> Text(
            entry.text,
            Modifier.padding(horizontal = 6.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Palette.OnSurfaceMuted.copy(alpha = 0.7f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Speaker.USER, Speaker.ASSISTANT -> {
            val isUser = entry.speaker == Speaker.USER
            Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
                Text(
                    entry.text,
                    Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isUser) Palette.BlueContainer else Palette.SurfaceRaised)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (isUser) Palette.OnBlueContainer else Palette.OnSurface,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/** Compact trip summary that replaces the ask pill while navigating. */
@Composable
private fun TripCard(trip: TripState, onEnd: () -> Unit) {
    val progress = trip.progress
    val remainingS = progress?.remainingS ?: trip.route.travelTimeS
    val remainingM = progress?.remainingM ?: trip.route.lengthM.toDouble()
    val delayS = progress?.trafficDelayAheadS ?: trip.route.trafficDelayS
    val arrival = LocalTime.now().plusSeconds(remainingS.toLong()).format(DateTimeFormatter.ofPattern("h:mm a"))
    Surface(shape = RoundedCornerShape(28.dp), color = Palette.Surface, shadowElevation = 8.dp) {
        Row(
            Modifier
                .heightIn(min = 56.dp)
                .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Phrasing.duration(remainingS).replace("minutes", "min").replace("minute", "min"),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (delayS >= 300) Palette.Bad else Palette.Good,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${shortDistance(remainingM)} · $arrival", fontSize = 13.sp, color = Palette.OnSurfaceMuted)
                }
                val extras = buildList {
                    if (delayS >= 60) add("+${Phrasing.duration(delayS)} traffic")
                    val n = trip.incidentsAhead.size
                    if (n > 0) add("$n incident${if (n == 1) "" else "s"} ahead")
                }
                if (extras.isNotEmpty()) Text(extras.joinToString(" · "), fontSize = 12.sp, color = Palette.Warn)
            }
            TextButton(onClick = onEnd) { Text("End", color = Palette.Bad, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// --- Right side: map buttons ------------------------------------------------

@Composable
private fun MapButtons(
    state: NavUiState,
    onOpenSettings: () -> Unit,
    onRecenter: () -> Unit,
    onToggleTraffic: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RoundMapButton(onClick = onOpenSettings, description = "Settings") {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(22.dp))
        }
        Surface(shape = CircleShape, color = Palette.Surface, shadowElevation = 6.dp) {
            CompassDial(state.telemetry.pose.heading, Modifier.padding(3.dp))
        }
        if (state.liveData) {
            RoundMapButton(
                onClick = onToggleTraffic,
                description = "Traffic layer",
                active = state.showTraffic,
            ) { Icon(painterResource(R.drawable.ic_traffic), contentDescription = null, modifier = Modifier.size(22.dp)) }
        }
        RoundMapButton(onClick = onRecenter, description = "Recenter map on me") {
            Icon(painterResource(R.drawable.ic_my_location), contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun RoundMapButton(onClick: () -> Unit, description: String, active: Boolean? = null, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active == true) Palette.BlueContainer else Palette.Surface,
        contentColor = if (active == true) Palette.Blue else Palette.OnSurface,
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(46.dp)
            .semantics {
                contentDescription = description
                if (active != null) stateDescription = if (active) "On" else "Off"
            },
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun CompassDial(heading: Double, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .border(1.5.dp, Palette.Outline, CircleShape)
            .semantics {
                contentDescription = "Heading ${heading.roundToInt() % 360} degrees ${SpatialEngine.compassName(heading)}"
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Text("N", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Palette.Bad)
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(8.dp)
                .rotate(heading.toFloat()),
        ) {
            val path = Path().apply {
                moveTo(size.width / 2, 0f)
                lineTo(size.width / 2 + 5.dp.toPx(), size.height / 2 + 3.dp.toPx())
                lineTo(size.width / 2 - 5.dp.toPx(), size.height / 2 + 3.dp.toPx())
                close()
            }
            drawPath(path, Palette.Blue)
            drawCircle(Palette.Blue, radius = 2.5.dp.toPx(), center = Offset(size.width / 2, size.height / 2))
        }
    }
}
