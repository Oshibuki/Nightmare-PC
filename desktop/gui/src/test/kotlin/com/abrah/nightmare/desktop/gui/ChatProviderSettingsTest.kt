package com.abrah.nightmare.desktop.gui

import com.abrah.nightmare.agent.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Provider configuration is observed at the state-layer seam only: environment
 * in, resolved config / masked status out. No Compose UI test framework, no
 * files, no network.
 */
class ChatProviderSettingsTest {

    private val env = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        env.clear()
    }

    @Test
    fun `environment alone supplies provider base url key and model`() {
        env["NIGHTMARE_LLM_PROVIDER"] = "DEEPSEEK"
        env["NIGHTMARE_LLM_BASE_URL"] = "https://env.example/v1"
        env["NIGHTMARE_LLM_KEY"] = "env-secret-0123456789"
        env["NIGHTMARE_LLM_MODEL"] = "env-model"
        val settings = ChatProviderSettings(env = { env.toMap() })

        val config = settings.resolved()

        assertEquals(Provider.DEEPSEEK, config.provider)
        assertEquals("https://env.example/v1", config.baseUrl)
        assertEquals("env-secret-0123456789", config.key)
        assertEquals("env-model", config.model)
    }

    @Test
    fun `with no configuration at all the default is openrouter and the status is unconfigured`() {
        val settings = ChatProviderSettings(env = { env.toMap() })

        val config = settings.resolved()

        assertEquals(Provider.OPENROUTER, config.provider)
        assertEquals(Provider.OPENROUTER.baseUrl, config.baseUrl)
        assertEquals("", config.key)
        assertEquals("", config.model)
        assertEquals("未配置", settings.statusLabel())
        assertFalse(settings.hasKey())
    }

    @Test
    fun `a saved dialog entry overrides the environment in this process only`() {
        env["NIGHTMARE_LLM_PROVIDER"] = "DEEPSEEK"
        env["NIGHTMARE_LLM_BASE_URL"] = "https://env.example/v1"
        env["NIGHTMARE_LLM_KEY"] = "env-secret-0123456789"
        env["NIGHTMARE_LLM_MODEL"] = "env-model"
        val settings = ChatProviderSettings(env = { env.toMap() })

        settings.save(
            ProviderSettingsInput(
                provider = Provider.GROQ,
                baseUrl = "https://memory.example/v1",
                key = "memory-secret-9876543210",
                model = "memory-model",
            ),
        )
        val config = settings.resolved()

        assertEquals(Provider.GROQ, config.provider)
        assertEquals("https://memory.example/v1", config.baseUrl)
        assertEquals("memory-secret-9876543210", config.key)
        assertEquals("memory-model", config.model)
        assertEquals("env-model", env["NIGHTMARE_LLM_MODEL"])
        assertEquals("env-secret-0123456789", env["NIGHTMARE_LLM_KEY"])
    }

    @Test
    fun `a blank key field keeps the configured key instead of echoing it`() {
        env["NIGHTMARE_LLM_KEY"] = "env-secret-0123456789"
        val settings = ChatProviderSettings(env = { env.toMap() })

        settings.save(
            ProviderSettingsInput(
                provider = Provider.OPENROUTER,
                baseUrl = "",
                key = "",
                model = "",
            ),
        )

        assertEquals("env-secret-0123456789", settings.resolved().key)
        assertEquals("已配置", settings.statusLabel())
        assertTrue(settings.hasKey())
    }

    @Test
    fun `custom provider with no injected base url stays blank`() {
        env["NIGHTMARE_LLM_PROVIDER"] = "CUSTOM"
        val settings = ChatProviderSettings(env = { env.toMap() })

        assertEquals("", settings.resolved().baseUrl)
    }

    @Test
    fun `the visible status never echoes a key or its last four digits`() {
        env["NIGHTMARE_LLM_KEY"] = "topsecret-4321"
        val settings = ChatProviderSettings(env = { env.toMap() })

        val shown = settings.statusLabel()

        assertEquals("已配置", shown)
        assertFalse("no key echo", shown.contains("topsecret"))
        assertFalse("no last four digits", shown.contains("4321"))
    }
}
