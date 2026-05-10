package com.example.homeassistant.logic

import com.universalglasses.appcontract.UniversalAppContext
import com.universalglasses.appcontract.UniversalAppEntrySimple
import com.universalglasses.appcontract.UniversalCommand
import com.universalglasses.appcontract.UserSettingField
import com.universalglasses.appcontract.UserSettingInputType
import com.universalglasses.core.AudioSource
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.MicrophoneOptions
import com.universalglasses.core.MicrophoneSession
import com.universalglasses.core.PcmFormat
import com.universalglasses.core.AudioEncoding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bare-metal Home Assistant integration for xg.glass smart glasses.
 *
 * The glasses connect **directly** to HA's native WebSocket and REST APIs.
 * No cloud service, no external AI — all intelligence lives in Home Assistant.
 *
 * Features
 * --------
 *  • Wake-Word Satellite — streams mic audio to HA's openwakeword, then STT →
 *    Assist pipeline → TTS response, loops automatically.
 *  • Quick Assist        — tap to speak; full pipeline in one shot.
 *  • Device Dashboard    — display states of pinned entities from HA REST API.
 *  • Voice Toggle        — dictate a control command; HA intent engine handles it.
 *  • View To-Dos         — fetch items from a HA `todo` entity.
 *  • Add To-Do           — dictate an item; STT transcript → `todo.add_item`.
 *  • Push Display        — subscribes to `xg_glass_display` HA events so
 *    automations can push text to the glasses in real time.
 *
 * Prerequisites
 * -------------
 *  1. Install the `xg_glass` custom component from
 *     `integrations/home-assistant/custom_components/xg_glass/` into HA.
 *  2. In HA, set up the integration (Settings → Integrations → Add → xg.glass).
 *  3. Run this file: `xg-glass run XgGlassHaBridgeEntry.kt`
 *  4. Fill in the settings in the host app (URL, token, entities).
 *
 * HA prerequisites
 * ----------------
 *  • HA ≥ 2024.9
 *  • Assist pipeline configured (Settings → Voice Assistants)
 *  • For wake-word: "openwakeword" add-on installed and a wake word selected
 *    in the pipeline configuration
 */
class HomeAssistantEntry : UniversalAppEntrySimple {

    override val id = "home_assistant"
    override val displayName = "Home Assistant"

    override fun userSettings(): List<UserSettingField> = listOf(
        UserSettingField(
            key = KEY_HA_URL,
            label = "Home Assistant URL",
            hint = "http://homeassistant.local:8123",
            inputType = UserSettingInputType.URL,
        ),
        UserSettingField(
            key = KEY_HA_TOKEN,
            label = "Long-Lived Access Token",
            hint = "HA → Profile → Security → Long-lived access tokens",
            inputType = UserSettingInputType.PASSWORD,
        ),
        UserSettingField(
            key = KEY_PIPELINE_ID,
            label = "Assist Pipeline ID (optional)",
            hint = "Leave blank to use the default pipeline",
        ),
        UserSettingField(
            key = KEY_TODO_ENTITY,
            label = "To-Do Entity ID",
            defaultValue = "todo.shopping_list",
        ),
        UserSettingField(
            key = KEY_PINNED_ENTITIES,
            label = "Pinned Entities (comma-separated)",
            hint = "light.living_room,switch.fan,climate.thermostat",
        ),
    )

    override fun commands(): List<UniversalCommand> = listOf(
        wakeWordSatelliteCommand(),
        quickAssistCommand(),
        deviceDashboardCommand(),
        voiceToggleCommand(),
        viewTodosCommand(),
        addTodoCommand(),
    )

    // ─── Wake-Word Satellite ─────────────────────────────────────────────────

    private fun wakeWordSatelliteCommand() = object : UniversalCommand {
        override val id = "wake_word_satellite"
        override val title = "Wake-Word Satellite"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            val (haUrl, token) = ctx.haSettings()
                ?: return ctx.failDisplay("Configure HA URL and token first.")
            val pipelineId = ctx.settings[KEY_PIPELINE_ID]?.takeIf { it.isNotBlank() }
            if (!ctx.client.capabilities.canRecordAudio) {
                return ctx.failDisplay("This device does not have a microphone.")
            }

            val ws = HaWebSocket(haUrl, token)
            return try {
                ws.connect()
                ctx.client.display("Satellite ready — listening for wake word…")

                // Subscribe to xg_glass_display so HA automations can push text.
                val displaySubId = ws.nextId()
                val displayChannel = ws.subscribe(displaySubId)
                ws.send(
                    JSONObject()
                        .put("type", "subscribe_events")
                        .put("id", displaySubId)
                        .put("event_type", EVENT_DISPLAY)
                )
                displayChannel.receive() // consume subscription-confirmed result

                coroutineScope {
                    val displayJob = launch {
                        for (msg in displayChannel) {
                            if (msg.optString("type") != "event") continue
                            val text = msg.optJSONObject("event")
                                ?.optJSONObject("data")
                                ?.optString("message") ?: continue
                            ctx.client.display(text).getOrNull()
                        }
                    }
                    try {
                        while (isActive) {
                            val mic = ctx.client.startMicrophone(HA_MIC_OPTIONS).getOrElse { e ->
                                ctx.client.display("Mic error: ${e.message}")
                                return@coroutineScope
                            }
                            try {
                                val result = ws.runAssistPipeline(
                                    startStage = "wake_word",
                                    endStage = "tts",
                                    micSession = mic,
                                    pipelineId = pipelineId,
                                ) { status -> ctx.client.display(status).getOrNull() }

                                if (result != null) {
                                    ctx.playTts(result, ws, haUrl)
                                }
                                delay(300)
                                ctx.client.display("Listening for wake word…")
                            } finally {
                                mic.stop()
                            }
                        }
                    } finally {
                        displayJob.cancel()
                        ws.unsubscribe(displaySubId)
                    }
                }
                Result.success(Unit)
            } catch (_: CancellationException) {
                ctx.client.display("Satellite stopped.")
                Result.success(Unit)
            } catch (e: Exception) {
                ctx.client.display("Satellite error: ${e.message}")
                Result.failure(e)
            } finally {
                ws.close()
            }
        }
    }

    // ─── Quick Assist ────────────────────────────────────────────────────────

    private fun quickAssistCommand() = object : UniversalCommand {
        override val id = "quick_assist"
        override val title = "Quick Assist"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            val (haUrl, token) = ctx.haSettings()
                ?: return ctx.failDisplay("Configure HA URL and token first.")
            val pipelineId = ctx.settings[KEY_PIPELINE_ID]?.takeIf { it.isNotBlank() }
            if (!ctx.client.capabilities.canRecordAudio) {
                return ctx.failDisplay("This device does not have a microphone.")
            }

            ctx.client.display("Listening…")
            val ws = HaWebSocket(haUrl, token)
            return try {
                ws.connect()
                val mic = ctx.client.startMicrophone(HA_MIC_OPTIONS).getOrThrow()
                try {
                    val result = withTimeoutOrNull(20_000L) {
                        ws.runAssistPipeline(
                            startStage = "stt",
                            endStage = "tts",
                            micSession = mic,
                            pipelineId = pipelineId,
                        ) { status -> ctx.client.display(status).getOrNull() }
                    }
                    if (result != null) {
                        ctx.playTts(result, ws, haUrl)
                        ctx.client.display(result.response)
                    } else {
                        ctx.client.display("No response. Try again.")
                    }
                } finally {
                    mic.stop()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                ctx.client.display("Error: ${e.message}")
                Result.failure(e)
            } finally {
                ws.close()
            }
        }
    }

    // ─── Device Dashboard ────────────────────────────────────────────────────

    private fun deviceDashboardCommand() = object : UniversalCommand {
        override val id = "device_dashboard"
        override val title = "Device Dashboard"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            val (haUrl, token) = ctx.haSettings()
                ?: return ctx.failDisplay("Configure HA URL and token first.")
            val pinned = ctx.settings[KEY_PINNED_ENTITIES]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()
            if (pinned.isEmpty()) {
                return ctx.client.display(
                    "No pinned entities configured.\nAdd comma-separated entity IDs in settings."
                )
            }

            ctx.client.display("Fetching states…")
            val ws = HaWebSocket(haUrl, token)
            return try {
                val statesJson = JSONArray(ws.restGet("/api/states"))
                val stateMap = buildMap<String, JSONObject> {
                    for (i in 0 until statesJson.length()) {
                        val obj = statesJson.getJSONObject(i)
                        put(obj.getString("entity_id"), obj)
                    }
                }
                val text = buildString {
                    for (entityId in pinned) {
                        val entity = stateMap[entityId]
                        if (entity != null) {
                            val name = entity.optJSONObject("attributes")
                                ?.optString("friendly_name", entityId) ?: entityId
                            val state = entity.getString("state")
                            val unit = entity.optJSONObject("attributes")
                                ?.optString("unit_of_measurement", "") ?: ""
                            appendLine("$name: $state$unit")
                        } else {
                            appendLine("$entityId: not found")
                        }
                    }
                }.trim()
                ctx.client.display(text)
                Result.success(Unit)
            } catch (e: Exception) {
                ctx.client.display("Error: ${e.message}")
                Result.failure(e)
            } finally {
                ws.close()
            }
        }
    }

    // ─── Voice Toggle ────────────────────────────────────────────────────────

    private fun voiceToggleCommand() = object : UniversalCommand {
        override val id = "voice_toggle"
        override val title = "Voice Toggle"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            val (haUrl, token) = ctx.haSettings()
                ?: return ctx.failDisplay("Configure HA URL and token first.")
            val pipelineId = ctx.settings[KEY_PIPELINE_ID]?.takeIf { it.isNotBlank() }
            if (!ctx.client.capabilities.canRecordAudio) {
                return ctx.failDisplay("This device does not have a microphone.")
            }

            ctx.client.display("Say a command…\n(e.g. \"Turn on living room lights\")")
            val ws = HaWebSocket(haUrl, token)
            return try {
                ws.connect()
                val mic = ctx.client.startMicrophone(HA_MIC_OPTIONS).getOrThrow()
                try {
                    val result = withTimeoutOrNull(15_000L) {
                        ws.runAssistPipeline(
                            startStage = "stt",
                            endStage = "tts",
                            micSession = mic,
                            pipelineId = pipelineId,
                        ) { status -> ctx.client.display(status).getOrNull() }
                    }
                    if (result != null) {
                        ctx.playTts(result, ws, haUrl)
                        ctx.client.display(result.response)
                    } else {
                        ctx.client.display("No response. Try again.")
                    }
                } finally {
                    mic.stop()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                ctx.client.display("Error: ${e.message}")
                Result.failure(e)
            } finally {
                ws.close()
            }
        }
    }

    // ─── View To-Dos ─────────────────────────────────────────────────────────

    private fun viewTodosCommand() = object : UniversalCommand {
        override val id = "view_todos"
        override val title = "View To-Dos"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            val (haUrl, token) = ctx.haSettings()
                ?: return ctx.failDisplay("Configure HA URL and token first.")
            val todoEntity = ctx.settings[KEY_TODO_ENTITY]?.takeIf { it.isNotBlank() }
                ?: return ctx.failDisplay("Configure the To-Do entity ID in settings.")

            ctx.client.display("Fetching to-do list…")
            val ws = HaWebSocket(haUrl, token)
            return try {
                val body = JSONObject().put("entity_id", todoEntity)
                val responseStr =
                    ws.restPost("/api/services/todo/get_items?return_response=true", body)
                val items =
                    JSONObject(responseStr).optJSONObject(todoEntity)?.optJSONArray("items")

                if (items == null || items.length() == 0) {
                    return ctx.client.display("✓ To-do list is empty!")
                }
                val text = buildString {
                    appendLine("To-Do (${items.length()}):")
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        appendLine("• ${item.optString("summary", "(unnamed)")}")
                    }
                }.trim()
                ctx.client.display(text)
                Result.success(Unit)
            } catch (e: Exception) {
                ctx.client.display("Error: ${e.message}")
                Result.failure(e)
            } finally {
                ws.close()
            }
        }
    }

    // ─── Add To-Do ───────────────────────────────────────────────────────────

    private fun addTodoCommand() = object : UniversalCommand {
        override val id = "add_todo"
        override val title = "Add To-Do"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            val (haUrl, token) = ctx.haSettings()
                ?: return ctx.failDisplay("Configure HA URL and token first.")
            val todoEntity = ctx.settings[KEY_TODO_ENTITY]?.takeIf { it.isNotBlank() }
                ?: return ctx.failDisplay("Configure the To-Do entity ID in settings.")
            if (!ctx.client.capabilities.canRecordAudio) {
                return ctx.failDisplay("This device does not have a microphone.")
            }

            ctx.client.display("Say the item to add…")
            val ws = HaWebSocket(haUrl, token)
            return try {
                ws.connect()
                val mic = ctx.client.startMicrophone(HA_MIC_OPTIONS).getOrThrow()
                var transcript: String? = null
                try {
                    val result = withTimeoutOrNull(10_000L) {
                        // STT only — we handle the action ourselves.
                        ws.runAssistPipeline(
                            startStage = "stt",
                            endStage = "stt",
                            micSession = mic,
                            pipelineId = null,
                        ) { status -> ctx.client.display(status).getOrNull() }
                    }
                    transcript = result?.transcript
                } finally {
                    mic.stop()
                }

                if (transcript.isNullOrBlank()) {
                    return ctx.client.display("Didn't catch that. Try again.")
                }
                ws.restPost(
                    "/api/services/todo/add_item",
                    JSONObject().put("entity_id", todoEntity).put("item", transcript),
                )
                ctx.client.display("✓ Added: $transcript")
                if (ctx.client.capabilities.canPlayTts) {
                    ctx.client.playAudio(AudioSource.Tts("Added $transcript to your list."))
                }
                Result.success(Unit)
            } catch (e: Exception) {
                ctx.client.display("Error: ${e.message}")
                Result.failure(e)
            } finally {
                ws.close()
            }
        }
    }

    // ─── Companion ────────────────────────────────────────────────────────────

    companion object {
        const val KEY_HA_URL = "ha_url"
        const val KEY_HA_TOKEN = "ha_token"
        const val KEY_PIPELINE_ID = "ha_pipeline_id"
        const val KEY_TODO_ENTITY = "ha_todo_entity"
        const val KEY_PINNED_ENTITIES = "ha_pinned_entities"

        /** Event type that the HA xg_glass component fires when display text is pushed. */
        internal const val EVENT_DISPLAY = "xg_glass_display"

        /** Microphone options matching HA's expected PCM-16 / 16 kHz / mono format. */
        internal val HA_MIC_OPTIONS = MicrophoneOptions(
            preferredEncoding = AudioEncoding.PCM_S16_LE,
            preferredSampleRateHz = 16_000,
            preferredChannelCount = 1,
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Shared helpers
// ═════════════════════════════════════════════════════════════════════════════

private fun UniversalAppContext.haSettings(): Pair<String, String>? {
    val url = settings[HomeAssistantEntry.KEY_HA_URL]?.takeIf { it.isNotBlank() } ?: return null
    val token = settings[HomeAssistantEntry.KEY_HA_TOKEN]?.takeIf { it.isNotBlank() } ?: return null
    return url to token
}

private suspend fun UniversalAppContext.failDisplay(message: String): Result<Unit> {
    client.display(message)
    return Result.failure(IllegalStateException(message))
}

/**
 * Play the TTS result from an Assist pipeline on the glasses speaker.
 *
 * Prefers raw audio bytes (fetched from HA's TTS proxy URL) because that
 * preserves HA's chosen voice.  Falls back to on-device TTS using the
 * response text if the glasses don't support raw audio playback.
 */
private suspend fun UniversalAppContext.playTts(
    result: PipelineResult,
    ws: HaWebSocket,
    haUrl: String,
) {
    try {
        if (result.ttsUrl != null && client.capabilities.canPlayAudioBytes) {
            val fullUrl = if (result.ttsUrl.startsWith("http")) result.ttsUrl
            else "${haUrl.trimEnd('/')}${result.ttsUrl}"
            val bytes = ws.fetchBytes(fullUrl)
            client.playAudio(AudioSource.RawBytes(bytes)).getOrNull()
        } else if (client.capabilities.canPlayTts) {
            client.playAudio(AudioSource.Tts(result.response)).getOrNull()
        }
    } catch (e: Exception) {
        log("TTS playback failed: ${e.message}")
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Pipeline result
// ═════════════════════════════════════════════════════════════════════════════

private data class PipelineResult(
    /** Transcribed speech from the STT stage (null if not yet reached). */
    val transcript: String?,
    /** Assistant's spoken/text response from the intent stage. */
    val response: String,
    /** Relative or absolute URL of the HA TTS audio file (null when no audio). */
    val ttsUrl: String?,
)

// ═════════════════════════════════════════════════════════════════════════════
// HA WebSocket client
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Lightweight HA WebSocket + REST client.
 *
 * WebSocket message routing
 * -------------------------
 * Each outgoing command gets a unique [nextId].  Incoming messages that carry
 * an `id` field are dispatched to the [Channel] registered under that id via
 * [subscribe] / [unsubscribe].  Auth handshake messages (id absent) are handled
 * inside [connect].
 *
 * REST calls
 * ----------
 * [restGet] and [restPost] use a separate OkHttp client with a normal read
 * timeout.  They don't require [connect] to have been called.
 */
private class HaWebSocket(haUrl: String, private val token: String) {

    private val wsUrl = haUrl.trimEnd('/')
        .replace("http://", "ws://")
        .replace("https://", "wss://") + "/api/websocket"
    private val httpUrl = haUrl.trimEnd('/')

    /** Shared HTTP client for REST calls (normal timeouts). */
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Dedicated client for the WebSocket (no read timeout; keep-alive pings). */
    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val msgId = AtomicInteger(0)
    private val subscribers = ConcurrentHashMap<Int, Channel<JSONObject>>()
    private var ws: WebSocket? = null

    fun nextId(): Int = msgId.incrementAndGet()

    /**
     * Open the WebSocket and authenticate with HA.
     * Suspends until `auth_ok` is received or throws on failure.
     */
    suspend fun connect() = suspendCancellableCoroutine<Unit> { cont ->
        val request = Request.Builder().url(wsUrl).build()
        ws = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "auth_required" -> webSocket.send(
                        JSONObject()
                            .put("type", "auth")
                            .put("access_token", token)
                            .toString()
                    )
                    "auth_ok" -> if (cont.isActive) cont.resume(Unit)
                    "auth_invalid" -> if (cont.isActive) cont.resumeWithException(
                        IllegalStateException(
                            "HA authentication failed — " +
                                "check the long-lived access token in settings"
                        )
                    )
                    else -> {
                        val id = msg.optInt("id", -1)
                        if (id > 0) subscribers[id]?.trySend(msg)
                    }
                }
            }

            override fun onFailure(
                webSocket: WebSocket, t: Throwable, response: Response?
            ) {
                if (cont.isActive) cont.resumeWithException(t)
                else subscribers.values.forEach { it.close(IOException(t)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                subscribers.values.forEach { it.close() }
            }
        })
        cont.invokeOnCancellation { ws?.close(1000, "Cancelled") }
    }

    /** Register a channel that will receive all messages tagged with [id]. */
    fun subscribe(id: Int): Channel<JSONObject> =
        Channel<JSONObject>(Channel.UNLIMITED).also { subscribers[id] = it }

    /** Remove the subscription and close its channel. */
    fun unsubscribe(id: Int) {
        subscribers.remove(id)?.close()
    }

    /** Send a JSON command frame to HA. */
    fun send(json: JSONObject) {
        ws?.send(json.toString())
    }

    /** Send a raw binary frame (audio chunk) to HA. */
    fun sendBinary(bytes: ByteArray) {
        ws?.send(bytes.toByteString(0, bytes.size))
    }

    suspend fun restGet(path: String): String = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder()
                .url("$httpUrl$path")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        ).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IOException("GET $path → ${resp.code}: $body")
            body
        }
    }

    suspend fun restPost(path: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder()
                .url("$httpUrl$path")
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            val responseBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IOException("POST $path → ${resp.code}: $responseBody")
            }
            responseBody
        }
    }

    /** Fetch raw bytes from a URL (used to download HA TTS audio). */
    suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
        ).execute().use { resp ->
            resp.body?.bytes() ?: throw IOException("Empty body from $url")
        }
    }

    fun close() {
        ws?.close(1000, "Done")
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Assist pipeline runner
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Run a single HA Assist pipeline pass over WebSocket.
 *
 * Audio from [micSession] is streamed to HA as binary frames until the
 * pipeline signals end-of-run.  Pipeline events update the glasses display
 * via [onStatus].
 *
 * @param startStage `"wake_word"` for the always-on satellite, `"stt"` for
 *   tap-to-speak / voice-toggle / add-to-do.
 * @param endStage   `"tts"` for a full response, `"stt"` to get only the
 *   transcript (used when the app handles the action itself).
 * @param pipelineId Optional HA pipeline UUID; null uses the HA default.
 * @param onStatus   Suspend callback invoked whenever there is a displayable
 *   status update (listening, transcript, response text).
 * @return [PipelineResult] on success, or null if the pipeline produced no
 *   usable response (e.g., wake word not detected within timeout).
 */
private suspend fun HaWebSocket.runAssistPipeline(
    startStage: String,
    endStage: String = "tts",
    micSession: MicrophoneSession,
    pipelineId: String?,
    onStatus: suspend (String) -> Unit,
): PipelineResult? = coroutineScope {
    val id = nextId()
    val channel = subscribe(id)
    try {
        send(
            JSONObject().apply {
                put("type", "assist_pipeline/run")
                put("id", id)
                put("start_stage", startStage)
                put("end_stage", endStage)
                put("input", JSONObject().apply {
                    put("timeout", if (startStage == "wake_word") 3 else 10)
                    put("audio_seconds_to_buffer", 2)
                    put("no_vad_timeout", 3.0)
                    put("sample_rate", 16_000)
                })
                if (!pipelineId.isNullOrBlank()) put("pipeline", pipelineId)
            }
        )

        // Wait for HA to confirm the pipeline subscription.
        val startResult = channel.receive()
        if (!startResult.optBoolean("success", true)) return@coroutineScope null

        // Stream microphone audio to HA in parallel.
        val audioJob = launch(Dispatchers.IO) {
            micSession.audio.collect { chunk ->
                if (!chunk.endOfStream && chunk.bytes.isNotEmpty()) {
                    sendBinary(chunk.bytes)
                }
            }
        }

        var transcript: String? = null
        var response: String? = null
        var ttsUrl: String? = null

        try {
            for (msg in channel) {
                if (msg.optString("type") != "event") continue
                val event = msg.optJSONObject("event") ?: continue
                val data = event.optJSONObject("data") ?: JSONObject()

                when (event.optString("type")) {
                    "wake_word-detected" -> onStatus("Listening…")
                    "stt-start" -> if (startStage == "stt") onStatus("Listening…")
                    "stt-end" -> {
                        transcript =
                            data.optJSONObject("stt_output")?.optString("text")
                        transcript?.let { onStatus("You: $it") }
                    }
                    "intent-end" -> {
                        val speech = data
                            .optJSONObject("intent_output")
                            ?.optJSONObject("response")
                            ?.optJSONObject("speech")
                            ?.optJSONObject("plain")
                            ?.optString("speech")
                        if (speech != null) {
                            response = speech
                            onStatus(speech)
                        }
                    }
                    "tts-end" ->
                        ttsUrl = data.optJSONObject("tts_output")?.optString("url")
                    "run-end", "error" -> break
                }
            }
        } catch (_: Exception) {
            // Channel closed early (connection dropped); proceed with whatever we have.
        } finally {
            audioJob.cancel()
        }

        // For STT-only runs the "response" is the transcript itself.
        val finalResponse = response ?: if (endStage == "stt") transcript else null
        finalResponse?.let { PipelineResult(transcript, it, ttsUrl) }
    } finally {
        unsubscribe(id)
    }
}
