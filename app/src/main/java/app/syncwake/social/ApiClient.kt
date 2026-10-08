package app.syncwake.social

import app.syncwake.domain.social.PublicStatus
import app.syncwake.domain.social.PublicStatusMapper
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/** A server error with the API's error code (e.g. "group_full", "invite_expired"). */
class ApiException(val status: Int, val code: String) : IOException("HTTP $status: $code")

data class RemoteParticipant(val userId: String, val displayName: String, val role: String)

data class RemoteAlarm(
    val id: String,
    val ownerId: String,
    val label: String,
    val hour: Int,
    val minute: Int,
    val repeatDaysMask: Int,
    val oneTimeDate: String?,
    val zoneId: String,
    val status: String,
    val version: Int,
    val myRole: String?,
    val restricted: Boolean,
    val maxGroupSize: Int,
    val participants: List<RemoteParticipant>,
) {
    val isActive: Boolean get() = status == "ACTIVE"
    val ownerName: String get() = participants.firstOrNull { it.role == "OWNER" }?.displayName.orEmpty()
}

data class AlarmInput(
    val label: String,
    val hour: Int,
    val minute: Int,
    val repeatDaysMask: Int,
    val oneTimeDate: String?,
    val zoneId: String,
)

data class Invite(val code: String, val url: String, val expiresAt: Long)

data class OutgoingEvent(val eventId: String, val occurrenceId: String, val type: String, val status: PublicStatus?, val clientAt: Long)

data class EventResult(val eventId: String, val result: String, val reason: String?) {
    /** Accepted, already known, or permanently refused: either way, stop resending it. */
    val settled: Boolean get() = result == "accepted" || result == "duplicate" || result == "rejected"
}

data class ParticipantStatus(val userId: String, val displayName: String, val status: PublicStatus)

data class OccurrenceStatus(val occurrenceId: String, val awake: Int, val total: Int, val participants: List<ParticipantStatus>)

data class SignInResult(val token: String, val userId: String, val displayName: String)

/**
 * Thin client for the SyncWake API (backend/src/app.ts). All calls are blocking-free suspend
 * functions on the IO dispatcher and throw [IOException] (or [ApiException]) on failure.
 */
class ApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val http: OkHttpClient = defaultClient,
) {
    suspend fun signInWithGoogle(idToken: String, deviceId: String, appVersion: String): SignInResult {
        val json = call(
            "POST", "/v1/auth/google",
            JSONObject().put("idToken", idToken).put("deviceId", deviceId).put("platform", "android").put("appVersion", appVersion),
            authenticated = false,
        )!!
        val user = json.getJSONObject("user")
        return SignInResult(json.getString("token"), user.getString("id"), user.getString("displayName"))
    }

    suspend fun logout() {
        call("POST", "/v1/auth/logout")
    }

    suspend fun registerPushToken(token: String) {
        call("PUT", "/v1/devices/current/push-token", JSONObject().put("fcmToken", token))
    }

    suspend fun listAlarms(): List<RemoteAlarm> {
        val arr = call("GET", "/v1/alarms")!!.getJSONArray("alarms")
        return (0 until arr.length()).map { parseAlarm(arr.getJSONObject(it)) }
    }

    suspend fun getAlarm(id: String): RemoteAlarm = parseAlarm(call("GET", "/v1/alarms/$id")!!)

    suspend fun createAlarm(input: AlarmInput): RemoteAlarm = parseAlarm(call("POST", "/v1/alarms", input.toJson())!!)

    suspend fun updateAlarm(id: String, input: AlarmInput, expectedVersion: Int): RemoteAlarm =
        parseAlarm(call("PUT", "/v1/alarms/$id", input.toJson().put("expectedVersion", expectedVersion))!!)

    suspend fun deleteAlarm(id: String) {
        call("DELETE", "/v1/alarms/$id")
    }

    suspend fun createInvite(alarmId: String): Invite {
        val json = call("POST", "/v1/alarms/$alarmId/invites")!!
        return Invite(json.getString("code"), json.getString("url"), json.getLong("expiresAt"))
    }

    suspend fun acceptInvite(code: String): RemoteAlarm =
        parseAlarm(call("POST", "/v1/invites/${code.trim().uppercase()}/accept")!!)

    suspend fun leave(alarmId: String) {
        call("POST", "/v1/alarms/$alarmId/leave")
    }

    suspend fun removeParticipant(alarmId: String, userId: String) {
        call("DELETE", "/v1/alarms/$alarmId/participants/$userId")
    }

    suspend fun postEvents(events: List<OutgoingEvent>): List<EventResult> {
        val arr = JSONArray()
        for (e in events) {
            val o = JSONObject().put("eventId", e.eventId).put("occurrenceId", e.occurrenceId).put("type", e.type).put("clientAt", e.clientAt)
            if (e.status != null) o.put("status", e.status.name)
            arr.put(o)
        }
        val results = call("POST", "/v1/events", JSONObject().put("events", arr))!!.getJSONArray("results")
        return (0 until results.length()).map {
            val r = results.getJSONObject(it)
            EventResult(r.getString("eventId"), r.getString("result"), r.optString("reason").ifEmpty { null })
        }
    }

    suspend fun occurrenceStatus(occurrenceId: String): OccurrenceStatus =
        parseStatus(call("GET", "/v1/occurrences/$occurrenceId")!!)

    /** Live status updates for an alarm; the caller closes the socket when the screen goes away. */
    fun openLive(alarmId: String, onStatus: (OccurrenceStatus) -> Unit, onChanged: () -> Unit): WebSocket? {
        val token = tokenProvider() ?: return null
        val request = Request.Builder()
            .url(baseUrl.replaceFirst("http", "ws") + "/v1/alarms/$alarmId/live")
            .header("Authorization", "Bearer $token")
            .build()
        return http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "participant_status") onStatus(parseStatus(json)) else onChanged()
                } catch (_: Exception) {
                    // Ignore malformed frames.
                }
            }
        })
    }

    private suspend fun call(method: String, path: String, body: JSONObject? = null, authenticated: Boolean = true): JSONObject? =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(baseUrl + path)
            if (authenticated) {
                val token = tokenProvider() ?: throw ApiException(401, "not_signed_in")
                builder.header("Authorization", "Bearer $token")
            }
            val requestBody = body?.toString()?.toRequestBody(JSON)
            builder.method(method, requestBody ?: if (method == "POST" || method == "PUT") "".toRequestBody(JSON) else null)
            http.newCall(builder.build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val code = try {
                        JSONObject(text).optString("error", "http_${res.code}")
                    } catch (_: Exception) {
                        "http_${res.code}"
                    }
                    throw ApiException(res.code, code)
                }
                if (text.isBlank()) null else JSONObject(text)
            }
        }

    companion object {
        private val JSON = "application/json".toMediaType()

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        private fun AlarmInput.toJson() = JSONObject()
            .put("label", label)
            .put("hour", hour)
            .put("minute", minute)
            .put("repeatDaysMask", repeatDaysMask)
            .put("oneTimeDate", oneTimeDate ?: JSONObject.NULL)
            .put("zoneId", zoneId)

        fun parseAlarm(o: JSONObject): RemoteAlarm {
            val ps = o.optJSONArray("participants") ?: JSONArray()
            return RemoteAlarm(
                id = o.getString("id"),
                ownerId = o.getString("ownerId"),
                label = o.optString("label"),
                hour = o.getInt("hour"),
                minute = o.getInt("minute"),
                repeatDaysMask = o.getInt("repeatDaysMask"),
                oneTimeDate = if (o.isNull("oneTimeDate")) null else o.optString("oneTimeDate"),
                zoneId = o.getString("zoneId"),
                status = o.getString("status"),
                version = o.getInt("version"),
                myRole = if (o.isNull("myRole")) null else o.optString("myRole"),
                restricted = o.optBoolean("restricted"),
                maxGroupSize = o.optInt("maxGroupSize", 3),
                participants = (0 until ps.length()).map {
                    val p = ps.getJSONObject(it)
                    RemoteParticipant(p.getString("userId"), p.getString("displayName"), p.getString("role"))
                },
            )
        }

        fun parseStatus(o: JSONObject): OccurrenceStatus {
            val ps = o.getJSONArray("participants")
            return OccurrenceStatus(
                occurrenceId = o.getString("occurrenceId"),
                awake = o.getInt("awake"),
                total = o.getInt("total"),
                participants = (0 until ps.length()).map {
                    val p = ps.getJSONObject(it)
                    ParticipantStatus(p.getString("userId"), p.getString("displayName"), PublicStatusMapper.parse(p.optString("status")))
                },
            )
        }
    }
}
