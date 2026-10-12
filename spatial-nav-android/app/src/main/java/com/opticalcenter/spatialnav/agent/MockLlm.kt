package com.opticalcenter.spatialnav.agent

import com.opticalcenter.spatialnav.navigation.Phrasing
import com.opticalcenter.spatialnav.spatial.Direction
import com.opticalcenter.spatialnav.spatial.DirectionQuery
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** A tool call the mock model decided to make. */
data class ToolCall(val name: String, val input: Map<String, String>)

/**
 * Rule-based stand-in for an LLM, used when no Claude API key is configured
 * and in tests. First turn: map the utterance to a tool call with keyword
 * patterns. Second turn: verbalise the tool result with templates.
 */
class MockLlm : LlmClient {
    private val toolUseCounter = AtomicInteger()

    override suspend fun complete(messages: List<Message>, tools: List<ToolDefinition>): Message {
        val last = messages.last()

        val toolResult = last.content.firstOrNull() as? ContentBlock.ToolResultBlock
        if (toolResult != null) {
            return assistantText(composeReply(toolResult.result))
        }

        val utterance = last.content.filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text }
        val call = parseIntent(utterance)
        if (call != null && tools.any { it.name == call.name }) {
            val use = ContentBlock.ToolUse("toolu_${toolUseCounter.incrementAndGet()}", call.name, call.input)
            return Message(Role.ASSISTANT, listOf(use))
        }
        return assistantText(HELP_TEXT)
    }

    private fun assistantText(text: String) = Message(Role.ASSISTANT, listOf(ContentBlock.Text(text)))

    companion object {
        const val HELP_TEXT =
            "I can tell you where you are, what's around you, find places, navigate somewhere, and report traffic. " +
                "Try asking: what is to my left?"

        private val STOP_PATTERN = Regex("""\b(stop|end|cancel)\b.*\b(navigation|navigating|route|directions|trip)\b""")
        private val FASTER_PATTERN = Regex("""\b(faster|quicker|better)\s+(route|way)\b""")
        private val REROUTE_PATTERN = Regex(
            """\b(?:navigate|take me|directions?|reroute|route|guide me|drive me|go)\b(?:\s+me)?\s+(?:to|towards?)\s+(?:the\s+)?(.+?)[.?!]*$""",
        )
        private val STATUS_PATTERN = Regex(
            """\b(how long|how far|eta|arrival time|when will i (get|arrive)|time left|remaining|next turn)\b""",
        )
        private val TRAFFIC_PATTERN = Regex(
            """\b(traffic|accidents?|crash(es)?|incidents?|road conditions?|closures?|closed|road ?works|construction|jams?|ice|fog|flood(ing)?)\b""",
        )
        private val LOCATION_PATTERN = Regex(
            """\b(where am i|my (?:current )?location|current location|my position|which way am i facing|what direction am i facing|my heading|what street)\b""",
        )
        private val SEARCH_PATTERN = Regex(
            """\b(?:find|search for|look for|where is|where's|nearest|closest)\s+(?:me\s+)?(?:a\s+|an\s+|the\s+)?(?:nearest\s+|closest\s+)?(.+?)[.?!]*$""",
        )
        private val NEARBY_FILLER = Regex("""\s*\b(nearby|near me|near here|around here|close by)\b""")
        private val DIRECTION_PATTERNS = listOf(
            "left" to Regex("""\bleft\b"""),
            "right" to Regex("""\bright\b(?!\s+now)"""),
            "behind" to Regex("""\b(behind|back|rear)\b"""),
            "ahead" to Regex("""\b(ahead|in front|front|forward|straight)\b"""),
            "all" to Regex("""\b(around|nearby|near me|surrounding|close by)\b"""),
        )

        /** Map an utterance to a tool call, or null if no tool applies. */
        fun parseIntent(utterance: String): ToolCall? {
            val text = utterance.lowercase(Locale.US).trim()

            if (STOP_PATTERN.containsMatchIn(text)) return ToolCall(NavigationTools.STOP_NAVIGATION, emptyMap())
            if (FASTER_PATTERN.containsMatchIn(text)) {
                return ToolCall(NavigationTools.FIND_FASTER_ROUTE, mapOf("apply" to "true"))
            }
            REROUTE_PATTERN.find(text)?.let {
                return ToolCall(NavigationTools.REROUTE_NAVIGATION, mapOf("destination" to it.groupValues[1].trim()))
            }
            if (STATUS_PATTERN.containsMatchIn(text)) return ToolCall(NavigationTools.GET_ROUTE_STATUS, emptyMap())
            if (TRAFFIC_PATTERN.containsMatchIn(text)) return ToolCall(NavigationTools.GET_TRAFFIC_INCIDENTS, emptyMap())
            if (LOCATION_PATTERN.containsMatchIn(text)) return ToolCall(NavigationTools.GET_CURRENT_LOCATION, emptyMap())
            SEARCH_PATTERN.find(text)?.let {
                val query = it.groupValues[1].replace(NEARBY_FILLER, "").trim()
                if (query.isNotEmpty()) {
                    return ToolCall(NavigationTools.SEARCH_PLACES, mapOf("query" to query, "nearby_only" to "true"))
                }
            }
            for ((direction, pattern) in DIRECTION_PATTERNS) {
                if (pattern.containsMatchIn(text)) {
                    return ToolCall(NavigationTools.GET_SURROUNDING_POIS, mapOf("direction" to direction))
                }
            }
            return null
        }

        private fun leadPhrase(query: DirectionQuery) = when (query) {
            DirectionQuery.All -> "Around you"
            is DirectionQuery.Only -> when (query.direction) {
                Direction.AHEAD -> "Ahead of you"
                Direction.RIGHT -> "To your right"
                Direction.BEHIND -> "Behind you"
                Direction.LEFT -> "To your left"
            }
        }

        fun spokenDistance(m: Number): String = Phrasing.distance(m.toDouble())

        /** "a cafe", "an ATM" — by first letter, which suits the categories we see. */
        fun withArticle(noun: String): String {
            val first = noun.firstOrNull()?.lowercaseChar() ?: return noun
            return if (first in "aeiou") "an $noun" else "a $noun"
        }

        private fun joinList(items: List<String>): String =
            if (items.size <= 1) items.joinToString("")
            else items.dropLast(1).joinToString("; ") + "; and " + items.last()

        private fun describePlace(p: PlaceResult, withDirection: Boolean): String {
            // Keep acronyms ("ATM") as-is; lower-case ordinary words ("Coffee Shop" → "coffee shop").
            val kind = p.place.category
                ?.let { c -> if (c.length > 1 && c == c.uppercase()) c else c.lowercase() }
                ?.let { ", ${withArticle(it)}" }.orEmpty()
            val side = if (withDirection) ", ${p.direction.spoken}" else ""
            return "${p.place.name}$kind about ${spokenDistance(p.distanceM)} away$side"
        }

        /** Turn a tool result into the sentence the assistant speaks. */
        fun composeReply(result: ToolResult): String = when (result) {
            is ToolResult.Surroundings -> {
                val lead = leadPhrase(result.query)
                if (result.pois.isEmpty()) {
                    "$lead, I don't see any places within ${spokenDistance(result.radiusM)}."
                } else {
                    val items = result.pois.map { describePlace(it, withDirection = result.query == DirectionQuery.All) }
                    val count = if (items.size == 1) "one place" else "${items.size} places"
                    "$lead, I found $count: ${joinList(items)}."
                }
            }
            is ToolResult.Places -> when {
                result.results.isEmpty() -> "I couldn't find anything for ${result.query}."
                else -> {
                    val items = result.results.take(3).map { describePlace(it, withDirection = true) }
                    "I found ${joinList(items)}. Say \"take me to\" and a name to start navigation."
                }
            }
            is ToolResult.Location -> {
                val where = result.address?.let { "You're near $it" }
                    ?: String.format(Locale.US, "You're at latitude %.5f, longitude %.5f", result.lat, result.lon)
                val fix = if (result.liveGps) "" else " This is a simulated position; no GPS fix yet."
                "$where, facing ${result.facing} at ${result.heading.roundToInt() % 360} degrees.$fix"
            }
            is ToolResult.NavigationStarted -> {
                val delay = if (result.trafficDelayS >= 60) {
                    ", including ${Phrasing.duration(result.trafficDelayS)} of traffic delay"
                } else {
                    ""
                }
                "Starting navigation to ${result.destination.name}: ${spokenDistance(result.distanceM)}, " +
                    "about ${Phrasing.duration(result.durationS)}$delay. Arriving around ${result.arrivalTime}."
            }
            is ToolResult.RouteStatus -> with(result.status) {
                val next = nextInstruction?.let { n ->
                    " Next, in ${spokenDistance(distanceToNextM ?: 0)}, ${Phrasing.sentenceContinue(n)}"
                }.orEmpty()
                val delay = if (trafficDelayAheadS >= 60) " Traffic is adding ${Phrasing.duration(trafficDelayAheadS)}." else ""
                "${spokenDistance(remainingM)} to ${destination.name}, about ${Phrasing.duration(remainingS)}. " +
                    "Arriving around $arrivalTime.$delay$next"
            }
            is ToolResult.Incidents -> {
                val where = if (result.scope == "route") "on your route" else "nearby"
                if (result.reports.isEmpty()) {
                    "No traffic incidents or road warnings $where right now."
                } else {
                    val items = result.reports.take(3).map { r ->
                        val what = (r.description ?: r.category.spoken).trimEnd('.')
                        val at = r.distanceAheadM?.let { " in ${spokenDistance(it)}" } ?: r.from?.let { " near $it" }.orEmpty()
                        val delay = r.delayS?.takeIf { it >= 60 }?.let { ", ${Phrasing.duration(it)} delay" }.orEmpty()
                        "$what$at$delay"
                    }
                    val count = if (result.reports.size == 1) "one incident" else "${result.reports.size} incidents"
                    "There ${if (result.reports.size == 1) "is" else "are"} $count $where: ${joinList(items)}."
                }
            }
            is ToolResult.FasterRoute -> when {
                result.switched -> "Found a faster route saving ${Phrasing.duration(result.savingS)}. Switching now."
                result.savingS >= 60 ->
                    "There's a route that saves ${Phrasing.duration(result.savingS)}. Ask me to switch if you want it."
                else -> "You're already on the fastest route."
            }
            is ToolResult.NavigationStopped ->
                if (result.wasNavigating) "Navigation to ${result.destination} ended." else "You're not navigating anywhere right now."
            is ToolResult.Error -> {
                val hint = if (result.suggestions.isNotEmpty()) {
                    " Nearby options include ${result.suggestions.take(3).joinToString(", ")}."
                } else {
                    ""
                }
                "Sorry, ${result.message}$hint"
            }
        }
    }
}
