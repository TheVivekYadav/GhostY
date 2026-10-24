package chat.mylove

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-only conversation and message history for the GhostLink MVP.
 * Data is stored in app-private SharedPreferences and is not uploaded to the server.
 */
class ChatStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "ghostlink_chats_v1", Context.MODE_PRIVATE
    )

    data class Conversation(
        val id: String,
        val name: String,
        val lastMessage: String,
        val updatedAt: Long
    )

    data class SavedMessage(
        val id: String,
        val text: String,
        val outgoing: Boolean,
        val timestamp: Long
    )

    @Synchronized
    fun ensureConversation(id: String, name: String) {
        val all = readConversations()
        val existing = all.optJSONObject(id)
        val conversation = existing ?: JSONObject()
        conversation.put("id", id)
        val currentName = conversation.optString("name", "")
        if (currentName.isBlank() || name != "Paired device") {
            conversation.put("name", name.ifBlank { "Paired device" })
        }
        if (!conversation.has("lastMessage")) conversation.put("lastMessage", "No messages yet")
        if (!conversation.has("updatedAt")) conversation.put("updatedAt", System.currentTimeMillis())
        all.put(id, conversation)
        prefs.edit().putString(KEY_CONVERSATIONS, all.toString()).apply()
    }

    /** Save the peer identity and shared reconnect credential only in app-private local storage. */
    @Synchronized
    fun saveTrustedDevice(peerDeviceId: String, name: String, pairToken: String) {
        if (peerDeviceId.isBlank() || pairToken.length < 32) return
        ensureConversation(peerDeviceId, name)
        val all = readConversations()
        val item = all.optJSONObject(peerDeviceId) ?: JSONObject()
        item.put("id", peerDeviceId)
        item.put("name", name.ifBlank { "Paired device" })
        item.put("peerDeviceId", peerDeviceId)
        item.put("pairToken", pairToken)
        item.put("updatedAt", System.currentTimeMillis())
        all.put(peerDeviceId, item)
        prefs.edit().putString(KEY_CONVERSATIONS, all.toString()).apply()
    }

    @Synchronized
    fun getPairToken(peerDeviceId: String): String? =
        readConversations().optJSONObject(peerDeviceId)?.optString("pairToken")
            ?.takeIf { it.length >= 32 }

    @Synchronized
    fun isTrustedPeer(peerDeviceId: String, candidateToken: String): Boolean {
        val saved = getPairToken(peerDeviceId) ?: return false
        return try {
            java.security.MessageDigest.isEqual(
                saved.toByteArray(Charsets.UTF_8),
                candidateToken.toByteArray(Charsets.UTF_8)
            )
        } catch (_: Exception) { false }
    }

    @Synchronized
    fun addMessage(conversationId: String, text: String, outgoing: Boolean) {
        ensureConversation(conversationId, "Paired device")
        val allMessages = readAllMessages()
        val messages = allMessages.optJSONArray(conversationId) ?: JSONArray()
        messages.put(JSONObject()
            .put("id", java.util.UUID.randomUUID().toString())
            .put("text", text)
            .put("outgoing", outgoing)
            .put("timestamp", System.currentTimeMillis()))
        allMessages.put(conversationId, messages)
        prefs.edit().putString(KEY_MESSAGES, allMessages.toString()).apply()

        val conversations = readConversations()
        val item = conversations.optJSONObject(conversationId) ?: JSONObject()
        item.put("id", conversationId)
        if (!item.has("name")) item.put("name", "Paired device")
        item.put("lastMessage", text)
        item.put("updatedAt", System.currentTimeMillis())
        conversations.put(conversationId, item)
        prefs.edit().putString(KEY_CONVERSATIONS, conversations.toString()).apply()
    }

    @Synchronized
    fun getConversations(): List<Conversation> {
        val all = readConversations()
        val result = mutableListOf<Conversation>()
        val keys = all.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = all.optJSONObject(key) ?: continue
            result.add(Conversation(
                id = item.optString("id", key),
                name = item.optString("name", "Paired device"),
                lastMessage = item.optString("lastMessage", "No messages yet"),
                updatedAt = item.optLong("updatedAt", 0L)
            ))
        }
        return result.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun getMessages(conversationId: String): List<SavedMessage> {
        val messages = readAllMessages().optJSONArray(conversationId) ?: JSONArray()
        return (0 until messages.length()).mapNotNull { index ->
            val item = messages.optJSONObject(index) ?: return@mapNotNull null
            SavedMessage(
                id = item.optString("id", index.toString()),
                text = item.optString("text", ""),
                outgoing = item.optBoolean("outgoing", false),
                timestamp = item.optLong("timestamp", 0L)
            )
        }
    }

    private fun readConversations(): JSONObject = try {
        JSONObject(prefs.getString(KEY_CONVERSATIONS, "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun readAllMessages(): JSONObject = try {
        JSONObject(prefs.getString(KEY_MESSAGES, "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    companion object {
        const val DEFAULT_CONVERSATION_ID = "default-paired-device"
        private const val KEY_CONVERSATIONS = "conversations"
        private const val KEY_MESSAGES = "messages"
    }
}