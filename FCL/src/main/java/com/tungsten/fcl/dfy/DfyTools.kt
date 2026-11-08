package com.tungsten.fcl.dfy

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tungsten.fcl.setting.ConfigHolder
import com.tungsten.fcl.setting.Profiles
import com.tungsten.fcl.ui.version.Versions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 权限工具层：把启动器的真实能力包成模型可调用的工具。
 *
 * 约定：
 *  - 读工具（write=false）自动执行，不打扰用户；
 *  - 写工具（write=true）必须先在 UI 上弹「审核」对话框，用户点允许才执行；
 *  - 所有执行都切到主线程（配置项是 fakefx property，必须在主线程改），避免 ANR。
 */
object DfyTools {

    data class Spec(val name: String, val desc: String, val write: Boolean, val schema: JsonObject)

    private fun enumProp(vararg values: String, description: String): JsonObject = JsonObject().apply {
        addProperty("type", "string")
        add("enum", JsonArray().apply { values.forEach { add(it) } })
        addProperty("description", description)
    }

    private fun noParams(): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
        add("required", JsonArray())
    }

    private fun oneStringProp(key: String, description: String): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add(key, JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", description)
            })
        })
        add("required", JsonArray().apply { add(key) })
    }

    private fun oneEnumProp(key: String, values: List<String>, description: String): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply { add(key, enumProp(*values.toTypedArray(), description = description)) })
        add("required", JsonArray().apply { add(key) })
    }

    private fun oneBoolProp(key: String, description: String): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add(key, JsonObject().apply {
                addProperty("type", "boolean")
                addProperty("description", description)
            })
        })
        add("required", JsonArray().apply { add(key) })
    }

    private fun oneIntProp(key: String, description: String, min: Int, max: Int): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add(key, JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", description)
                addProperty("minimum", min)
                addProperty("maximum", max)
            })
        })
        add("required", JsonArray().apply { add(key) })
    }

    val specs: List<Spec> = listOf(
        Spec(
            "get_settings",
            "读取大肥鱼启动器的当前设置：下载源、版本列表源、下载线程数、联机开关状态、当前游戏目录名称与实例数。",
            false,
            noParams()
        ),
        Spec(
            "list_instances",
            "列出当前游戏目录里的所有实例（版本）名称，并标出当前选中实例。",
            false,
            noParams()
        ),
        Spec(
            "set_download_source",
            "修改游戏文件下载源。DEFAULT=自动选择，OFFICIAL=优先官方源，MIRROR=优先国内镜像（国内网络推荐）。",
            true,
            oneEnumProp("source", listOf("DEFAULT", "OFFICIAL", "MIRROR"), "新的下载源")
        ),
        Spec(
            "set_version_list_source",
            "修改版本列表来源。DEFAULT=自动，OFFICIAL=官方，MIRROR=国内镜像。",
            true,
            oneEnumProp("source", listOf("DEFAULT", "OFFICIAL", "MIRROR"), "新的版本列表来源")
        ),
        Spec(
            "set_download_threads",
            "修改下载线程数（1-256）。网络差可调低，带宽足可调高。",
            true,
            oneIntProp("threads", "下载线程数", 1, 256)
        ),
        Spec(
            "set_multiplayer",
            "开关多人联机（Terracotta）。开启后启动器的联机页会生效，需要通知权限。",
            true,
            oneBoolProp("enabled", "true 开启联机，false 关闭")
        ),
        Spec(
            "launch_instance",
            "启动指定实例进入游戏。必须先确认实例名存在（可先调 list_instances）。",
            true,
            oneStringProp("version", "要启动的实例（版本）名称，需与 list_instances 返回的名字完全一致")
        )
    )

    fun schemasJson(): JsonArray = JsonArray().apply {
        specs.forEach { spec ->
            add(JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", spec.name)
                    addProperty("description", spec.desc)
                    add("parameters", spec.schema)
                })
            })
        }
    }

    fun isWriteTool(name: String): Boolean = specs.firstOrNull { it.name == name }?.write ?: false

    /** 把工具调用翻译成一句人话，用于权限审核弹窗与步骤条 */
    fun describe(name: String, args: JsonObject): String = when (name) {
        "get_settings" -> "读取启动器设置"
        "list_instances" -> "读取实例列表"
        "set_download_source" -> "把下载源改成 ${args.string("source")}"
        "set_version_list_source" -> "把版本列表源改成 ${args.string("source")}"
        "set_download_threads" -> "把下载线程数改成 ${args.int("threads", 0)}"
        "set_multiplayer" -> if (args.bool("enabled")) "开启多人联机" else "关闭多人联机"
        "launch_instance" -> "启动实例「${args.string("version")}」"
        else -> name
    }

    suspend fun execute(context: Context, name: String, args: JsonObject): String = withContext(Dispatchers.Main) {
        try {
            when (name) {
                "get_settings" -> readSettings(context)
                "list_instances" -> listInstances()
                "set_download_source" -> {
                    val source = args.string("source").uppercase()
                    if (source !in listOf("DEFAULT", "OFFICIAL", "MIRROR")) return@withContext "参数错误：source 只能是 DEFAULT/OFFICIAL/MIRROR"
                    ConfigHolder.config().fileDownloadSource = source
                    "已把下载源改为 $source"
                }
                "set_version_list_source" -> {
                    val source = args.string("source").uppercase()
                    if (source !in listOf("DEFAULT", "OFFICIAL", "MIRROR")) return@withContext "参数错误：source 只能是 DEFAULT/OFFICIAL/MIRROR"
                    ConfigHolder.config().versionListSource = source
                    "已把版本列表源改为 $source"
                }
                "set_download_threads" -> {
                    val threads = args.int("threads", 0)
                    if (threads !in 1..256) return@withContext "参数错误：threads 必须在 1-256 之间"
                    ConfigHolder.config().downloadThreads = threads
                    "已把下载线程数改为 $threads"
                }
                "set_multiplayer" -> {
                    val enabled = args.bool("enabled")
                    val prefs = context.getSharedPreferences("third_party", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("terracotta", enabled).apply()
                    if (enabled) "已开启多人联机（首次使用需通知权限，进联机页确认状态）" else "已关闭多人联机"
                }
                "launch_instance" -> {
                    val version = args.string("version")
                    val profile = Profiles.getSelectedProfile()
                    if (version.isEmpty()) return@withContext "参数错误：version 不能为空"
                    if (profile == null) return@withContext "启动失败：没有选中游戏目录"
                    if (!profile.repository.isLoaded) return@withContext "启动失败：游戏目录还没载入完成，请稍后再试"
                    if (!profile.repository.hasVersion(version)) return@withContext "启动失败：没有名为「$version」的实例，请先 list_instances 核对"
                    Versions.launch(context, profile, version)
                    "已发起启动：$version（若缺账户或缺少资源会弹提示）"
                }
                else -> "未知工具：$name"
            }
        } catch (e: Exception) {
            "执行失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun readSettings(context: Context): String {
        val config = ConfigHolder.config()
        val profile = Profiles.getSelectedProfile()
        val multiplayer = context.getSharedPreferences("third_party", Context.MODE_PRIVATE)
            .getBoolean("terracotta", false)
        val versions = if (profile != null && profile.repository.isLoaded) profile.repository.versions.size else 0
        return buildString {
            append("下载源=").append(config.fileDownloadSource)
            append("；版本列表源=").append(config.versionListSource)
            append("；下载线程=").append(config.downloadThreads)
            append("；联机开关=").append(if (multiplayer) "开" else "关")
            append("；游戏目录=").append(profile?.name ?: "未选择")
            append("；实例数=").append(versions)
        }
    }

    private fun listInstances(): String {
        val profile = Profiles.getSelectedProfile() ?: return "当前没有选中游戏目录"
        if (!profile.repository.isLoaded) return "游戏目录还没载入完成，请稍后再试"
        val ids = profile.repository.versions.map { it.id }
        if (ids.isEmpty()) return "当前游戏目录还没有实例"
        val selected = profile.selectedVersion
        return ids.joinToString("；") { id -> if (id == selected) "$id（当前选中）" else id }
    }

    private fun JsonObject.string(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    private fun JsonObject.int(key: String, fallback: Int): Int = try {
        if (has(key) && !get(key).isJsonNull) get(key).asInt else fallback
    } catch (e: Exception) {
        fallback
    }

    private fun JsonObject.bool(key: String): Boolean = try {
        has(key) && !get(key).isJsonNull && get(key).asBoolean
    } catch (e: Exception) {
        false
    }
}
