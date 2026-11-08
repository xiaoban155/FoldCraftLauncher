package com.tungsten.fcl.ui.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tungsten.fcl.R
import com.tungsten.fcl.databinding.ItemDfyAiMsgBinding
import com.tungsten.fcl.databinding.PageDfyAiBinding
import com.tungsten.fcl.dfy.DfyAiClient
import com.tungsten.fcl.dfy.DfyAiStore
import com.tungsten.fcllibrary.component.ui.FCLPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 大肥鱼启动器 AI 助手页。
 * 第一版能力：多轮对话 + 流式输出 + 每一步操作可视化（请求/耗时/结果）。
 * 工具层（改设置、联机开关、启动实例）在权限层落地后接入同一套步骤条。
 */
class DfyAiPage(context: Context?, id: Int) : FCLPage(context, id, R.layout.page_dfy_ai) {

    private lateinit var binding: PageDfyAiBinding
    private val items = ArrayList<ChatItem>()
    private val adapter = MsgAdapter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var running: Job? = null
    private var lastRefreshAt = 0L

    private data class ChatItem(val role: String, var text: String)

    private inner class MsgAdapter : RecyclerView.Adapter<MsgAdapter.Holder>() {

        inner class Holder(val binding: ItemDfyAiMsgBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemDfyAiMsgBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val label = when (item.role) {
                "user" -> holder.itemView.context.getString(R.string.dfy_ai_me_prefix)
                else -> ""
            }
            holder.binding.text.text = label + item.text
            holder.binding.text.alpha = if (item.role == "trace") 0.7f else 1f
            holder.binding.text.textSize = if (item.role == "trace") 11f else 14f
        }
    }

    init {
        create()
    }

    private fun create() {
        binding = PageDfyAiBinding.bind(contentView)
        binding.list.layoutManager = LinearLayoutManager(getContext()).apply { stackFromEnd = true }
        binding.list.adapter = adapter
        binding.send.setOnClickListener { send() }
        binding.config.setOnClickListener { openConfig() }
        binding.clear.setOnClickListener {
            if (running?.isActive == true) {
                toast(getContext().getString(R.string.dfy_ai_busy))
                return@setOnClickListener
            }
            items.clear()
            adapter.notifyDataSetChanged()
            addWelcome()
        }
        refreshStatus()
        addWelcome()
    }

    private fun addWelcome() {
        addItem(ChatItem("trace", getContext().getString(R.string.dfy_ai_welcome)))
    }

    private fun refreshStatus() {
        val data = DfyAiStore.data()
        val model = data.model.ifBlank { getContext().getString(R.string.dfy_ai_model_none) }
        val keyState = if (DfyAiStore.hasKey()) {
            if (data.saveKey) getContext().getString(R.string.dfy_ai_key_tag_saved)
            else getContext().getString(R.string.dfy_ai_key_tag_session)
        } else {
            getContext().getString(R.string.dfy_ai_key_tag_none)
        }
        binding.status.text = getContext().getString(R.string.dfy_ai_status_fmt, model, keyState)
    }

    private fun openConfig() {
        DfyAiConfigDialog(getContext()) { refreshStatus() }.show()
    }

    private fun addItem(item: ChatItem) {
        items.add(item)
        adapter.notifyItemInserted(items.size - 1)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        if (items.isNotEmpty()) binding.list.scrollToPosition(items.size - 1)
    }

    private fun send() {
        if (running?.isActive == true) {
            toast(getContext().getString(R.string.dfy_ai_busy))
            return
        }
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val data = DfyAiStore.data()
        val key = DfyAiStore.key()
        if (data.baseUrl.isBlank() || data.model.isBlank()) {
            toast(getContext().getString(R.string.dfy_ai_need_config))
            openConfig()
            return
        }
        if (key.isEmpty()) {
            toast(getContext().getString(R.string.dfy_ai_need_key))
            openConfig()
            return
        }

        binding.input.setText("")
        addItem(ChatItem("user", text))
        addItem(ChatItem("trace", getContext().getString(R.string.dfy_ai_step_request, data.model)))

        val reply = ChatItem("assistant", "")
        addItem(reply)

        val history = items
            .filter { it !== reply && (it.role == "user" || (it.role == "assistant" && it.text.isNotBlank())) }
            .map {
                DfyAiClient.Msg(if (it.role == "user") "user" else "assistant", it.text)
            }
        val messages = listOf(DfyAiClient.Msg("system", SYSTEM_PROMPT)) + history

        val startedAt = SystemClock.uptimeMillis()
        running = scope.launch {
            val result = DfyAiClient.chat(data.baseUrl, key, data.model, messages) { piece ->
                mainHandler.post {
                    reply.text += piece
                    val now = SystemClock.uptimeMillis()
                    if (now - lastRefreshAt > 120L) {
                        lastRefreshAt = now
                        val index = items.indexOf(reply)
                        if (index >= 0) adapter.notifyItemChanged(index)
                        scrollToBottom()
                    }
                }
            }
            val cost = (SystemClock.uptimeMillis() - startedAt) / 1000.0
            val index = items.indexOf(reply)
            if (index >= 0) adapter.notifyItemChanged(index)
            result.exceptionOrNull()?.let { error ->
                addItem(ChatItem("trace", getContext().getString(R.string.dfy_ai_step_fail, error.message ?: "")))
            } ?: run {
                addItem(
                    ChatItem(
                        "trace",
                        getContext().getString(
                            R.string.dfy_ai_step_done,
                            String.format(java.util.Locale.US, "%.1f", cost),
                            result.getOrNull()?.length ?: 0
                        )
                    )
                )
            }
            scrollToBottom()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "你是「大肥鱼启动器」（DFY Launcher）内置的 AI 助手。启动器是基于 Fold Craft Launcher 二次开发的安卓我的世界启动器。" +
                "回答要简洁、可执行，涉及启动器操作时给出具体菜单路径。暂时不能自动修改设置或启动游戏，需要时请明确提示用户手动操作。"
    }
}
