package app.syncwake.social

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class Account(val userId: String, val displayName: String, val token: String)

/** What the app knows about a shared alarm beyond the local alarm row. */
data class SharedInfo(
    val version: Int,
    val isOwner: Boolean,
    val ownerName: String,
    val participantCount: Int,
)

/**
 * Account session and shared-alarm metadata. Lives in normal (credential-protected) storage:
 * it is never needed to ring an alarm, only to sync, so it doesn't have to be readable before the
 * first unlock.
 */
class SocialStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("social", Context.MODE_PRIVATE)

    private val _account = MutableStateFlow(readAccount())
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _shared = MutableStateFlow(readShared())
    /** alarmId -> metadata, for every alarm shared with other people. */
    val shared: StateFlow<Map<String, SharedInfo>> = _shared.asStateFlow()

    /** Stable id for this install, registered with the server at sign-in. */
    val deviceId: String
        get() = prefs.getString(K_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(K_DEVICE_ID, it).apply()
        }

    var pushToken: String?
        get() = prefs.getString(K_PUSH_TOKEN, null)
        set(value) = prefs.edit().putString(K_PUSH_TOKEN, value).apply()

    fun saveAccount(account: Account) {
        prefs.edit()
            .putString(K_USER_ID, account.userId)
            .putString(K_NAME, account.displayName)
            .putString(K_TOKEN, account.token)
            .apply()
        _account.value = account
    }

    fun clearAccount() {
        prefs.edit().remove(K_USER_ID).remove(K_NAME).remove(K_TOKEN).remove(K_SHARED).apply()
        _account.value = null
        _shared.value = emptyMap()
    }

    fun setShared(alarmId: String, info: SharedInfo) = writeShared(_shared.value + (alarmId to info))

    fun removeShared(alarmId: String) = writeShared(_shared.value - alarmId)

    fun replaceShared(all: Map<String, SharedInfo>) = writeShared(all)

    private fun writeShared(map: Map<String, SharedInfo>) {
        val json = JSONObject()
        for ((id, info) in map) {
            json.put(
                id,
                JSONObject()
                    .put("version", info.version)
                    .put("isOwner", info.isOwner)
                    .put("ownerName", info.ownerName)
                    .put("participants", info.participantCount),
            )
        }
        prefs.edit().putString(K_SHARED, json.toString()).apply()
        _shared.value = map
    }

    private fun readAccount(): Account? {
        val id = prefs.getString(K_USER_ID, null) ?: return null
        val token = prefs.getString(K_TOKEN, null) ?: return null
        return Account(id, prefs.getString(K_NAME, null).orEmpty(), token)
    }

    private fun readShared(): Map<String, SharedInfo> {
        val raw = prefs.getString(K_SHARED, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { id ->
                val o = json.getJSONObject(id)
                SharedInfo(o.getInt("version"), o.getBoolean("isOwner"), o.optString("ownerName"), o.optInt("participants", 1))
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private companion object {
        const val K_DEVICE_ID = "device_id"
        const val K_USER_ID = "user_id"
        const val K_NAME = "display_name"
        const val K_TOKEN = "session_token"
        const val K_SHARED = "shared_alarms"
        const val K_PUSH_TOKEN = "push_token"
    }
}
