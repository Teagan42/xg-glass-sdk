package com.example.handtracking.logic

import com.universalglasses.appcontract.AIApiSettings
import com.universalglasses.appcontract.UniversalAppContext
import com.universalglasses.appcontract.UniversalAppEntrySimple
import com.universalglasses.appcontract.UniversalCommand
import com.universalglasses.appcontract.UserSettingField
import com.universalglasses.core.DisplayOptions
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.ImageURL
import com.aallam.openai.api.chat.ListContent
import com.aallam.openai.api.chat.TextContent
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * HandTrackingEntry
 *
 * A smart glasses app that uses the glasses camera and a vision AI model to track the user's
 * hand and detect pinch gestures, allowing virtual "windows" to be grabbed and repositioned
 * in the space around the user.
 *
 * How it works:
 *  1. Captures a photo from the glasses camera in a continuous loop.
 *  2. Sends the photo to a vision-capable AI model (e.g. GPT-4o) with a prompt that asks for:
 *       • Whether a hand is visible.
 *       • The normalized (0–1) x/y position of the hand or index fingertip.
 *       • Whether a pinch gesture (thumb + index finger touching) is detected.
 *  3. Maintains a list of virtual "windows" — each has a label and a 2-D position in the
 *     same normalized coordinate space.
 *  4. On pinch start: grabs the closest window within a snap radius.
 *     While pinching: moves the grabbed window to follow the hand delta.
 *     On pinch release: drops the window at its current position.
 *  5. Renders the live state (hand position, grabbed window, all window positions) as text
 *     on the glasses display.
 *
 * Run with:
 *   xg-glass run HandTrackingEntry.kt                       # on real glasses
 *   xg-glass run --sim HandTrackingEntry.kt                 # on simulator (webcam)
 *   xg-glass run --sim --local_video video.mp4 HandTrackingEntry.kt
 */
class HandTrackingEntry : UniversalAppEntrySimple {

    override val id = "hand_tracking_windows"
    override val displayName = "Hand Tracking Windows"

    override fun userSettings(): List<UserSettingField> = AIApiSettings.fields(
        defaultBaseUrl = "https://api.openai.com/v1/",
        defaultModel = "gpt-4o",
    )

    // -----------------------------------------------------------------------
    // Tuning constants
    // -----------------------------------------------------------------------

    /** Capture-to-capture delay in milliseconds (~2 fps). Balances responsiveness vs. API cost. */
    private val frameDelayMs = 500L

    /**
     * Maximum normalized distance (0–1) between the hand and a window for a pinch to grab it.
     * 0.25 ≈ 25% of the frame width/height.
     */
    private val snapRadius = 0.25f

    // -----------------------------------------------------------------------
    // Virtual window state
    // -----------------------------------------------------------------------

    /** A virtual window that can be grabbed and repositioned. */
    private data class VirtualWindow(
        val label: String,
        var x: Float,   // normalized 0 (left) … 1 (right)
        var y: Float,   // normalized 0 (top)  … 1 (bottom)
    )

    /** Euclidean distance between a hand position and a window. */
    private fun distanceTo(window: VirtualWindow, hx: Float, hy: Float): Float {
        val dx = window.x - hx
        val dy = window.y - hy
        return sqrt(dx * dx + dy * dy)
    }

    // -----------------------------------------------------------------------
    // AI vision prompt + response parsing
    // -----------------------------------------------------------------------

    private val systemPrompt = """
        You are a hand-gesture detector for smart glasses. Analyze the image and return ONLY
        valid JSON with exactly these four fields — no extra text, no markdown fences:

        {
          "hand_detected": <true|false>,
          "hand_x": <0.0–1.0>,
          "hand_y": <0.0–1.0>,
          "pinching": <true|false>
        }

        Definitions:
        • hand_detected: true if a human hand is visible in the frame.
        • hand_x / hand_y: normalized position (0 = left/top, 1 = right/bottom) of the
          hand center (or index fingertip when clearly visible). Use 0.5/0.5 when uncertain.
        • pinching: true when the thumb tip and index fingertip are touching or nearly
          touching (pinch gesture). false otherwise.
        If no hand is visible, set hand_detected to false and pinching to false.
    """.trimIndent()

    /** Parse the JSON response from the vision model. Returns null on parse failure. */
    private fun parseHandState(raw: String): HandState? {
        return try {
            // Tolerate optional ```json … ``` fences that some models emit.
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val obj = Json.parseToJsonElement(cleaned).jsonObject
            HandState(
                detected = obj["hand_detected"]?.jsonPrimitive?.boolean ?: false,
                x = obj["hand_x"]?.jsonPrimitive?.float ?: 0.5f,
                y = obj["hand_y"]?.jsonPrimitive?.float ?: 0.5f,
                pinching = obj["pinching"]?.jsonPrimitive?.boolean ?: false,
            )
        } catch (_: Exception) {
            null
        }
    }

    private data class HandState(
        val detected: Boolean,
        val x: Float,
        val y: Float,
        val pinching: Boolean,
    )

    // -----------------------------------------------------------------------
    // Display rendering
    // -----------------------------------------------------------------------

    private fun renderDisplay(
        handState: HandState?,
        windows: List<VirtualWindow>,
        grabbedIndex: Int?,
    ): String = buildString {
        // Status line
        val statusLine = when {
            handState == null -> "⚠ Waiting for frame…"
            !handState.detected -> "✋ No hand in frame"
            handState.pinching && grabbedIndex != null ->
                "🤏 Holding: ${windows[grabbedIndex].label}"
            handState.pinching -> "🤏 Pinching (nothing grabbed)"
            else -> "🖐 Tracking hand"
        }
        appendLine(statusLine)

        // Hand position (skip when no hand)
        if (handState?.detected == true) {
            val hx = (handState.x * 100).roundToInt()
            val hy = (handState.y * 100).roundToInt()
            appendLine("Hand  x:${hx}%  y:${hy}%")
        }

        appendLine("─────────────────")

        // Window list
        windows.forEachIndexed { i, w ->
            val marker = if (i == grabbedIndex) "►" else " "
            val wx = (w.x * 100).roundToInt()
            val wy = (w.y * 100).roundToInt()
            appendLine("$marker${w.label}  x:${wx}%  y:${wy}%")
        }
    }.trimEnd()

    // -----------------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------------

    override fun commands(): List<UniversalCommand> = listOf(

        object : UniversalCommand {
            override val id = "start_tracking"
            override val title = "Start Hand Tracking"

            override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
                val openAI = OpenAI(AIApiSettings.apiKey(ctx.settings)) {
                    host = OpenAIHost(AIApiSettings.baseUrl(ctx.settings))
                }
                val model = AIApiSettings.model(ctx.settings).ifBlank { "gpt-4o" }

                // Initial virtual windows arranged in a 3-column layout.
                val windows = mutableListOf(
                    VirtualWindow("📋 Notes",  0.20f, 0.30f),
                    VirtualWindow("🗂 Files",  0.50f, 0.50f),
                    VirtualWindow("📊 Stats",  0.80f, 0.30f),
                    VirtualWindow("🎵 Music",  0.20f, 0.70f),
                    VirtualWindow("🌐 Browser",0.80f, 0.70f),
                )

                var grabbedIndex: Int? = null
                var lastHandX = 0.5f
                var lastHandY = 0.5f
                var wasPinching = false

                ctx.client.display(
                    "🖐 Hand Tracking Windows\nPosition your hand in frame to begin.",
                    DisplayOptions(),
                )

                while (coroutineContext.isActive) {
                    try {
                        // 1. Capture frame
                        val img = ctx.client.capturePhoto().getOrNull() ?: run {
                            delay(500)
                            continue
                        }

                        ctx.onCapturedImage?.invoke(img)

                        val b64 = Base64.getEncoder().encodeToString(img.jpegBytes)

                        // 2. Ask vision model for hand position + pinch state
                        val response = openAI.chatCompletion(
                            ChatCompletionRequest(
                                model = ModelId(model),
                                messages = listOf(
                                    ChatMessage(
                                        role = ChatRole.System,
                                        messageContent = TextContent(systemPrompt),
                                    ),
                                    ChatMessage(
                                        role = ChatRole.User,
                                        messageContent = ListContent(
                                            listOf(
                                                TextPart("Detect the hand gesture in this image."),
                                                ImagePart(
                                                    imageUrl = ImageURL(
                                                        url = "data:image/jpeg;base64,$b64",
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                                maxTokens = 120,
                            ),
                        )

                        val rawJson = response.choices
                            .firstOrNull()?.message?.messageContent
                            ?.let { (it as? TextContent)?.content }
                            .orEmpty()

                        val handState = parseHandState(rawJson)

                        // 3. Update window positions based on gesture
                        if (handState != null && handState.detected) {
                            val dx = handState.x - lastHandX
                            val dy = handState.y - lastHandY

                            // Pinch start → grab nearest window within snap radius
                            if (handState.pinching && !wasPinching) {
                                val nearest = windows.indices.minByOrNull { i ->
                                    distanceTo(windows[i], handState.x, handState.y)
                                }
                                grabbedIndex = nearest?.takeIf { i ->
                                    distanceTo(windows[i], handState.x, handState.y) <= snapRadius
                                }
                                val grabbed = grabbedIndex
                                ctx.log(
                                    if (grabbed != null)
                                        "Grabbed: ${windows[grabbed].label}"
                                    else
                                        "Pinch — no window in range"
                                )
                            }

                            // Pinch release → drop
                            if (!handState.pinching && wasPinching) {
                                val grabbed = grabbedIndex
                                if (grabbed != null) {
                                    ctx.log("Released: ${windows[grabbed].label}")
                                }
                                grabbedIndex = null
                            }

                            // Move grabbed window with hand
                            grabbedIndex?.let { idx ->
                                windows[idx].x = (windows[idx].x + dx).coerceIn(0f, 1f)
                                windows[idx].y = (windows[idx].y + dy).coerceIn(0f, 1f)
                            }

                            lastHandX = handState.x
                            lastHandY = handState.y
                            wasPinching = handState.pinching
                        }

                        // 4. Render and display
                        val displayText = renderDisplay(handState, windows, grabbedIndex)
                        ctx.client.display(displayText, DisplayOptions(force = true))

                    } catch (e: Exception) {
                        ctx.log("Error: ${e.message}")
                        ctx.client.display(
                            "⚠ ${e.message?.take(80)}",
                            DisplayOptions(),
                        )
                        delay(1_000)
                    }

                    // ~2 fps — balances responsiveness against API cost & latency.
                    delay(frameDelayMs)
                }

                return Result.success(Unit)
            }
        },

        object : UniversalCommand {
            override val id = "reset_windows"
            override val title = "Reset Window Positions"

            override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
                return ctx.client.display(
                    "🔄 Window positions reset.\nStart hand tracking to begin.",
                    DisplayOptions(),
                )
            }
        },
    )
}
