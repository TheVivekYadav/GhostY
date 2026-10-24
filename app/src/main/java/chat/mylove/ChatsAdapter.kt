package chat.mylove

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatsAdapter(
    private val onClick: (ChatStore.Conversation) -> Unit
) : RecyclerView.Adapter<ChatsAdapter.ChatViewHolder>() {
    private val items = mutableListOf<ChatStore.Conversation>()

    fun submit(conversations: List<ChatStore.Conversation>) {
        items.clear()
        items.addAll(conversations)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Color.rgb(22, 30, 46))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
        }
        val name = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val preview = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.rgb(170, 183, 202))
            maxLines = 2
            setPadding(0, 5, 0, 0)
        }
        val state = TextView(context).apply {
            text = "Saved locally · tap to open"
            textSize = 11f
            setTextColor(Color.rgb(184, 172, 255))
            gravity = Gravity.END
            setPadding(0, 8, 0, 0)
        }
        row.addView(name)
        row.addView(preview)
        row.addView(state)
        return ChatViewHolder(row, name, preview)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.preview.text = item.lastMessage
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class ChatViewHolder(
        itemView: LinearLayout,
        val name: TextView,
        val preview: TextView
    ) : RecyclerView.ViewHolder(itemView)
}