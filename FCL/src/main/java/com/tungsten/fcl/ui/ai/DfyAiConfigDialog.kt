package com.tungsten.fcl.ui.ai

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import com.tungsten.fcl.R
import com.tungsten.fcl.databinding.DialogDfyAiConfigBinding
import com.tungsten.fcl.dfy.DfyAiClient
import com.tungsten.fcl.dfy.DfyAiStore
import com.tungsten.fcllibrary.component.dialog.FCLDialog
import com.tungsten.fcllibrary.util.ConvertUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * AI 助手配置：接口地址 + API Key + 模型名。
 * API Key 的保存方式由「加密保存」开关决定：开 = Android Keystore 加密落盘；关 = 只留内存。
 */
class DfyAiConfigDialog(
    context: Context,
    private val onSave: () -> Unit
) : FCLDialog(context) {

    private lateinit var binding: DialogDfyAiConfigBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    init {
        binding = DialogDfyAiConfigBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        window?.setLayout(ConvertUtils.dip2px(context, 420f), ViewGroup.LayoutParams.WRAP_CONTENT)

        val data = DfyAiStore.data()
        binding.baseUrl.setText(data.baseUrl)
        binding.model.setText(data.model)
        binding.saveKey.isChecked = data.saveKey
        if (data.saveKey) {
            val saved = DfyAiStore.key()
            if (saved.isNotEmpty()) binding.apiKey.setText(saved)
        }
        binding.keyState.text = if (DfyAiStore.hasKey()) {
            context.getString(R.string.dfy_ai_key_saved)
        } else {
            context.getString(R.string.dfy_ai_key_empty)
        }

        binding.fetchModels.setOnClickListener { fetchModels() }
        binding.negative.setOnClickListener { dismiss() }
        binding.positive.setOnClickListener {
            val base = binding.baseUrl.text.toString().trim()
            val model = binding.model.text.toString().trim()
            val key = binding.apiKey.text.toString().trim()
            if (base.isNotEmpty()) DfyAiStore.setBaseUrlAndModel(base, model)
            if (key.isNotEmpty()) DfyAiStore.setKey(key, binding.saveKey.isChecked)
            dismiss()
            onSave()
        }
    }

    private fun fetchModels() {
        val base = binding.baseUrl.text.toString().trim()
        val typed = binding.apiKey.text.toString().trim()
        val key = typed.ifEmpty { DfyAiStore.key() }
        if (base.isEmpty()) {
            Toast.makeText(context, R.string.dfy_ai_need_config, Toast.LENGTH_SHORT).show()
            return
        }
        binding.fetchModels.isEnabled = false
        scope.launch {
            val models = DfyAiClient.listModels(base, key)
            binding.fetchModels.isEnabled = true
            if (models.isEmpty()) {
                Toast.makeText(context, R.string.dfy_ai_models_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (binding.model.text.isNullOrBlank()) binding.model.setText(models.first())
            Toast.makeText(
                context,
                context.getString(R.string.dfy_ai_models_found, models.size, models.first()),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
