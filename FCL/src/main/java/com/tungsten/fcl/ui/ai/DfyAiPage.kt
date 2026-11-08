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
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tungsten.fcl.R
import com.tungsten.fcl.databinding.ItemDfyAiMsgBinding
import com.tungsten.fcl.databinding.PageDfyAiBinding
import com.tungsten.fcl.dfy.DfyAiClient
import com.tungsten.fcl.dfy.DfyAiStore
import com.tungsten.fcl.dfy.DfyMotion
import com.tungsten.fcl.dfy.DfyTools
import com.tungsten.fcl.dfy.DfyTrace
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.ui.FCLPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 大肥鱼启动器 AI 助手页。
 *
 * 能力：
 *  - 多轮对话 + 流式输出；
 *  - function calling：AI 可以真的改启动器设置、开关多人联机、启动实例；
 *  - 每一步都显示在步骤条里（AI 想做什么 / 是否通过审核 / 执行结果）；
 *  - 写操作一律先弹「权限审核」，用户点允许才执行。
 */
class DfyAiPage(context: Context?, id: Int) : FCLPage(context, id, R.layout.page_dfy_ai) {

    private lateinit var binding: PageDfyAiBinding
    private val items = ArrayList<ChatItem>()
    private val adapter = MsgAdapter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, t ->
            // 兜底：协程里真正逃逸出来的异常也只在日志里出现，不触发进程崩溃
            try {
                DfyTrace.step(getContext(), "uncaught", "${t.javaClass.name}: ${t.message}")
            } catch (ignored: Throwable) {
            }
        }
    )
    private var running: Job? = null
    private var lastRefreshAt = 0L

    /** 纯文本对话历史（不含工具消息），用于多轮上下文 */
    private val conversation = ArrayList<DfyAiClient.Msg>()

    private data class ChatItem(val role: String, var text: String, var animateOnBind: Boolean = true)

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
            // 新消息淡入上浮：同一条只播一次，滚动回看不重播
            if (item.animateOnBind) {
                item.animateOnBind = false
                DfyMotion.fadeInUp(holder.itemView, position == 0)
            } else {
                holder.itemView.alpha = 1f
                holder.itemView.translationY = 0f
            }
        }
    }

    init {
        create()
    }

    private fun create() {
        binding = PageDfyAiBinding.bind(contentView)
        binding.list.layoutManager = LinearLayoutManager(getContext()).apply { stackFromEnd = true }
        // 流式输出时高频刷新，ItemAnimator 会让 RecyclerView 在动画期间重排，
        // 极易触发内部 "Inconsistency detected" 崩溃（该异常在布局过程中抛出，外部 try/catch 拦不到）
        binding.list.itemAnimator = null
        binding.list.adapter = adapter
        binding.send.setOnClickListener { send() }
        binding.config.setOnClickListener { openConfig() }
        binding.clear.setOnClickListener {
            if (running?.isActive == true) {
                toast(getContext().getString(R.string.dfy_ai_busy))
                return@setOnClickListener
            }
            items.clear()
            conversation.clear()
            adapter.notifyDataSetChanged()
            addWelcome()
        }
        DfyMotion.press(binding.send)
        DfyMotion.press(binding.config)
        DfyMotion.press(binding.clear)
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
        try {
            adapter.notifyItemInserted(items.size - 1)
        } catch (ignored: Throwable) {
            // RecyclerView 正在计算布局时可能抛出 IllegalStateException，降级为全量刷新
            mainHandler.post {
                try {
                    adapter.notifyDataSetChanged()
                } catch (ignored2: Throwable) {
                }
            }
        }
        scrollToBottom()
    }

    /**
     * 流式更新：优先直接改绑定中的 ViewHolder 文本（零布局、零动画、零崩溃风险）；
     * 找不到绑定（例如被滚出屏幕）时退化为 notifyItemChanged。
     */
    private fun updateLiveText(item: ChatItem) {
        val index = items.indexOf(item)
        if (index < 0) return
        try {
            val holder = binding.list.findViewHolderForAdapterPosition(index) as? MsgAdapter.Holder
            if (holder != null) {
                val label = if (item.role == "user") holder.itemView.context.getString(R.string.dfy_ai_me_prefix) else ""
                holder.binding.text.text = label + item.text
                return
            }
        } catch (ignored: Throwable) {
        }
        refreshItem(item)
    }

    /** 单条刷新：页面元素已不存在或 RecyclerView 忙时就跳过，绝不向外抛 */
    private fun refreshItem(item: ChatItem) {
        val index = items.indexOf(item)
        if (index < 0) return
        try {
            adapter.notifyItemChanged(index)
        } catch (ignored: Throwable) {
            try {
                adapter.notifyDataSetChanged()
            } catch (ignored2: Throwable) {
            }
        }
    }

    private fun addTrace(text: String) {
        try {
            addItem(ChatItem("trace", text))
        } catch (ignored: Throwable) {
        }
    }

    private fun scrollToBottom() {
        try {
            if (items.isNotEmpty()) binding.list.scrollToPosition(items.size - 1)
        } catch (ignored: Throwable) {
        }
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
        conversation.add(DfyAiClient.Msg("user", text))
        addTrace(getContext().getString(R.string.dfy_ai_step_request, data.model))

        DfyTrace.step(getContext(), "send", "开始 baseUrl=${data.baseUrl.take(60)} model=${data.model.take(40)} msgs=${conversation.size}")
        val startedAt = SystemClock.uptimeMillis()
        running = scope.launch {
            var finalText = ""
            var failed = ""
            try {
                var round = 0
                while (round < MAX_TOOL_ROUNDS) {
                    val msgs = ArrayList(conversation)
                    val prefix = arrayListOf(DfyAiClient.Msg("system", SYSTEM_PROMPT))
                    prefix.addAll(msgs)

                    var textItem: ChatItem? = null
                    val reply = DfyAiClient.chatWithTools(
                        data.baseUrl, key, data.model, prefix, DfyTools.schemasJson(),
                        onTrace = { stage -> DfyTrace.step(getContext(), "round$round", stage) }
                    ) { piece ->
                        mainHandler.post {
                            // 流式回调可能在页面已离开后到达，任何 UI 操作都不能把整个应用带崩
                            try {
                                if (textItem == null) {
                                    val item = ChatItem("assistant", "")
                                    textItem = item
                                    addItem(item)
                                    DfyTrace.step(getContext(), "ui", "首个增量已渲染")
                                }
                                textItem?.text = (textItem?.text ?: "") + piece
                                val now = SystemClock.uptimeMillis()
                                if (now - lastRefreshAt > 120L) {
                                    lastRefreshAt = now
                                    // 关键：直接写已绑定的 ViewHolder，绝不 notifyItemChanged，
                                    // 避免 RecyclerView 在布局/动画期间状态不一致而崩
                                    textItem?.let { updateLiveText(it) }
                                    scrollToBottom()
                                }
                            } catch (ignored: Throwable) {
                                // 刷 UI 失败不影响请求本身
                            }
                        }
                    }.getOrElse {
                        failed = it.message ?: it.javaClass.simpleName
                        DfyTrace.step(getContext(), "round$round", "请求失败 $failed")
                        return@launch
                    }
                    DfyTrace.step(getContext(), "round$round", "拿到回复 文本=${reply.content.length} 工具=${reply.toolCalls.size}")

                    textItem?.let { refreshItem(it) }
                    finalText = reply.content

                    if (reply.toolCalls.isEmpty()) {
                        if (finalText.isNotBlank()) conversation.add(DfyAiClient.Msg("assistant", finalText))
                        break
                    }

                    // 本轮模型要求调用工具：先把 assistant 的 tool_calls 入历史
                    prefix.add(DfyAiClient.Msg("assistant", reply.content?.ifBlank { null }, reply.toolCalls))
                    val assistantMsg = DfyAiClient.Msg("assistant", reply.content?.ifBlank { null }, reply.toolCalls)
                    conversation.add(assistantMsg)

                    for (call in reply.toolCalls) {
                        DfyTrace.step(getContext(), "round$round", "工具 ${call.name}")
                        val args = parseArgs(call.args)
                        val desc = DfyTools.describe(call.name, args)
                        addTrace(getContext().getString(R.string.dfy_ai_step_tool, desc))

                        var result: String
                        if (DfyTools.isWriteTool(call.name)) {
                            DfyTrace.step(getContext(), "round$round", "等待权限审核")
                            val allowed = awaitPermission(desc)
                            DfyTrace.step(getContext(), "round$round", "权限结果 $allowed")
                            if (!allowed) {
                                result = "用户拒绝执行这项操作，请改用别的方式或直接说明需要用户手动操作"
                                addTrace(getContext().getString(R.string.dfy_ai_step_denied, desc))
                                conversation.add(DfyAiClient.Msg("tool", result, toolCallId = call.id))
                                continue
                            }
                            result = DfyTools.execute(getContext(), call.name, args)
                            addTrace(getContext().getString(R.string.dfy_ai_step_result, result))
                        } else {
                            result = DfyTools.execute(getContext(), call.name, args)
                            addTrace(getContext().getString(R.string.dfy_ai_step_result, result))
                        }
                        conversation.add(DfyAiClient.Msg("tool", result, toolCallId = call.id))
                    }
                    round++
                }
                if (round >= MAX_TOOL_ROUNDS) {
                    addTrace(getContext().getString(R.string.dfy_ai_step_round_limit))
                }
            } catch (t: Throwable) {
                // AI 链路任何异常都只在步骤条里显示，不允许把整个启动器带崩
                failed = t.message ?: t.javaClass.simpleName
            } finally {
                try {
                val cost = (SystemClock.uptimeMillis() - startedAt) / 1000.0
                if (failed.isNotEmpty()) {
                    addTrace(getContext().getString(R.string.dfy_ai_step_fail, failed))
                } else {
                    addTrace(
                        getContext().getString(
                            R.string.dfy_ai_step_done,
                            String.format(java.util.Locale.US, "%.1f", cost),
                            conversation.lastOrNull { it.role == "assistant" }?.content?.length ?: finalText.length
                        )
                    )
                }
                scrollToBottom()
                } catch (ignored: Throwable) {
                }
                DfyTrace.step(getContext(), "end", "结束 failed=${failed.take(80)}")
            }
        }
    }

    /** 写操作前弹权限审核；返回用户是否允许 */
    private suspend fun awaitPermission(desc: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        mainHandler.post {
            try {
                FCLAlertDialog.Builder(getContext()).apply {
                    setAlertLevel(FCLAlertDialog.AlertLevel.INFO)
                    setTitle(getContext().getString(R.string.dfy_ai_perm_title))
                    setMessage(getContext().getString(R.string.dfy_ai_perm_msg, desc))
                    setCancelable(false)
                    setPositiveButton(getContext().getString(R.string.dfy_ai_perm_allow)) {
                        if (!deferred.isCompleted) deferred.complete(true)
                    }
                    setNegativeButton(getContext().getString(R.string.dfy_ai_perm_deny)) {
                        if (!deferred.isCompleted) deferred.complete(false)
                    }
                }.create().show()
            } catch (t: Throwable) {
                // 弹不出审核框时按「拒绝」处理，绝不默认放行
                if (!deferred.isCompleted) deferred.complete(false)
            }
        }
        return deferred.await()
    }

    private fun parseArgs(raw: String): JsonObject = try {
        if (raw.isBlank()) JsonObject() else JsonParser.parseString(raw).asJsonObject
    } catch (e: Exception) {
        JsonObject()
    }

    private fun toast(message: String) {
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 4

        private const val SYSTEM_PROMPT =
            "你是「大肥鱼启动器」（DFY Launcher）内置的 AI 助手。启动器是基于 Fold Craft Launcher 二次开发的安卓我的世界启动器。" +
                "你可以通过工具真正修改启动器：读取设置、改下载源/版本列表源/下载线程、开关多人联机、启动指定实例。" +
                "任何写操作都会弹权限审核给用户确认，用户拒绝时必须如实说明该操作未生效，不要假装成功。" +
                "需要改设置时直接调用工具，不要只给手动步骤；不确定实例名时先调用 list_instances 核对。" +
                "自动生成模组/插件在安卓端暂不支持，被问到时明确说明暂不可用。" +
                "回答简洁、可执行，使用简体中文。"
    }
}
