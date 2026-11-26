package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.Provider
import java.util.concurrent.atomic.AtomicReference

/** The four provider settings, resolved from memory / environment / built-ins (ADR-0012). */
data class ChatProviderConfig(
    val provider: Provider,
    val baseUrl: String,
    val key: String,
    val model: String,
)

/** What the settings dialog writes: a blank key means "keep the key already configured". */
data class ProviderSettingsInput(
    val provider: Provider,
    val baseUrl: String,
    val key: String,
    val model: String,
)

/**
 * Provider configuration state. The environment is the only persistent source;
 * a dialog save lives in this process's memory and disappears on restart. No
 * config file and no persistence seam are introduced (ADR-0012).
 */
class ChatProviderSettings(
    private val env: () -> Map<String, String> = { System.getenv() },
    private val probe: (ChatProviderConfig) -> List<String> = { ProviderProbe().models(it) },
    private val runProbe: (Runnable) -> Unit = { runnable ->
        Thread(runnable, "gui-provider-probe").apply { isDaemon = true }.start()
    },
    private val postToUi: (Runnable) -> Unit = { it.run() },
) {
    private val memory = AtomicReference<ChatProviderConfig?>(null)
    private val probeStatus = AtomicReference("未检查")
    private val listeners = mutableListOf<() -> Unit>()

    /** Memory > environment > built-in default. Read fresh so a save is visible at once. */
    fun resolved(): ChatProviderConfig {
        val values = env()
        val saved = memory.get()
        val provider = saved?.provider
            ?: values[ENV_PROVIDER]?.let { Provider.of(it) }
            ?: Provider.OPENROUTER
        val baseUrl = saved?.baseUrl?.takeIf { it.isNotBlank() }
            ?: values[ENV_BASE_URL]?.takeIf { !it.isNullOrBlank() }
            ?: if (provider == Provider.CUSTOM) "" else provider.baseUrl
        val key = saved?.key ?: values[ENV_KEY].orEmpty()
        val model = saved?.model?.takeIf { it.isNotBlank() }
            ?: values[ENV_MODEL]?.takeIf { it.isNotBlank() }
            ?: ""
        return ChatProviderConfig(provider, baseUrl, key, model)
    }

    /** The only write path from the GUI. Nothing is written to disk, and the check never blocks. */
    fun save(input: ProviderSettingsInput): ChatProviderConfig {
        val current = resolved()
        val baseUrl = input.baseUrl.ifBlank {
            if (input.provider == Provider.CUSTOM) "" else input.provider.baseUrl
        }
        val saved = ChatProviderConfig(
            provider = input.provider,
            baseUrl = baseUrl,
            // The key field is never pre-filled; blank means "leave it alone".
            key = input.key.ifBlank { current.key },
            model = input.model,
        )
        memory.set(saved)
        probeStatus.set("检查中…")
        notifyChanged()
        runProbe {
            val models = runCatching { probe(saved) }
            val message = models.fold(
                onSuccess = { ids ->
                    if (ids.isEmpty()) "未找到可用模型（配置已保存）" else "已连接（配置已保存）"
                },
                onFailure = { "未连接（配置已保存）" },
            )
            // Only an untouched blank model field may be filled by fitsChat.
            val ids = models.getOrNull()
            postToUi {
                probeStatus.set(message)
                if (!ids.isNullOrEmpty() && saved.model.isBlank()) {
                    memory.compareAndSet(saved, saved.copy(model = ids.first()))
                }
                notifyChanged()
            }
        }
        return saved
    }

    /** The probe status is a sentence with no key in it, safe for the dialog. */
    fun lastProbeStatus(): String = probeStatus.get()

    /** On the first turn a model may still be blank; pick one on the turn thread. */
    fun autoModel(): String {
        val config = resolved()
        if (config.model.isNotBlank()) return config.model
        val picked = probe(config).firstOrNull()
            ?: throw ProviderProbe.Unreachable("尚未配置可用模型，请打开设置选择模型")
        memory.compareAndSet(config, config.copy(model = picked))
        return picked
    }

    /** The dialog listens for the background check the same way other states do. */
    fun addChangeListener(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners += listener }
        return { synchronized(listeners) { listeners -= listener } }
    }

    private fun notifyChanged() {
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    fun hasKey(): Boolean = resolved().key.isNotBlank()

    /** The only configuration fact the shell is allowed to show — never a key. */
    fun statusLabel(): String = if (hasKey()) "已配置" else "未配置"

    companion object {
        const val ENV_PROVIDER = "NIGHTMARE_LLM_PROVIDER"
        const val ENV_BASE_URL = "NIGHTMARE_LLM_BASE_URL"
        const val ENV_KEY = "NIGHTMARE_LLM_KEY"
        const val ENV_MODEL = "NIGHTMARE_LLM_MODEL"
    }
}

/** The one process-wide settings instance shared by the chat shell and its model. */
object ChatProviderStore {
    val settings: ChatProviderSettings = ChatProviderSettings(
        postToUi = { runnable -> javax.swing.SwingUtilities.invokeLater(runnable) },
    )
}
