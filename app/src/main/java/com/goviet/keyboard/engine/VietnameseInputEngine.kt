package com.goviet.keyboard.engine

import android.content.Context
import com.goviet.core.AppPreferences
import com.goviet.core.EngineConfig

/**
 * VietnameseInputEngine:
 * Public API called by Keyboard / IME.
 * Thin facade delegating to VietnameseComposer.
 */
class VietnameseInputEngine {

    var vietnameseModeEnabled: Boolean = true
    var options: EngineOptions = EngineOptions()

    var macroEnabled: Boolean
        get() = options.macroEnabled
        set(v) { options.macroEnabled = v }

    var alwaysMacro: Boolean
        get() = options.alwaysMacro
        set(v) { options.alwaysMacro = v }

    var directW: Boolean
        get() = options.directW
        set(v) { options.directW = v }

    var oldTonePlacement: Boolean
        get() = options.oldTonePlacement
        set(v) { options.oldTonePlacement = v }

    var autoCapitalize: Boolean = false

    var macroStore: MacroStore? = null
    private var macroPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var settingsPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val composer = VietnameseComposer(options)

    /**
     * Process an individual key press.
     */
    fun processKey(key: Char): EngineResult {
        if (!vietnameseModeEnabled) {
            return EngineResult(text = key.toString(), consumed = true, composing = false)
        }
        composer.options = options
        return composer.process(key)
    }

    /**
     * Process a full raw character sequence (backward compatibility for IME controller).
     */
    fun process(raw: String): String {
        if (!vietnameseModeEnabled) return raw
        composer.options = options
        return composer.processString(raw)
    }

    /**
     * Delete a single display character (backspace).
     */
    fun backspace(): String {
        return composer.backspace()
    }

    /**
     * Re-derive conservative variant for static strings.
     */
    fun reDerive(raw: String): String {
        if (!vietnameseModeEnabled) return raw
        composer.options = options
        return composer.reDerive(raw)
    }

    /**
     * Reset composer state.
     */
    fun reset() {
        composer.reset()
    }

    /**
     * Invalidate cache / state.
     */
    fun invalidateCache() {
        composer.reset()
    }

    fun loadPreferences(context: Context) {
        try {
            AppPreferences.init(context)
            val config = AppPreferences.getEngineConfig()
            
            macroEnabled = config.macroEnabled
            alwaysMacro = config.alwaysMacro
            autoCapitalize = config.autoCapitalize
            directW = config.directW
            oldTonePlacement = config.oldTonePlacement
            
            macroStore = MacroRepository(context).loadMacroStore()
            if (macroPrefsListener == null) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (AppPreferences.isMacroDataKey(key)) {
                        reloadMacroStore(context)
                    }
                }
                macroPrefsListener = listener
                AppPreferences.registerMacroPrefsListener(listener)
            }

            if (settingsPrefsListener == null) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                    loadPreferences(context)
                }
                settingsPrefsListener = listener
                AppPreferences.registerSettingsPrefsListener(listener)
            }
        } catch (e: Exception) {
            System.err.println("[VietnameseInputEngine] Failed to load preferences: ${e.message}")
        }
    }

    fun cleanup() {
        macroPrefsListener?.let {
            AppPreferences.unregisterMacroPrefsListener(it)
            macroPrefsListener = null
        }
        settingsPrefsListener?.let {
            AppPreferences.unregisterSettingsPrefsListener(it)
            settingsPrefsListener = null
        }
    }

    fun reloadMacroStore(context: Context) {
        try {
            AppPreferences.init(context)
            val config = AppPreferences.getEngineConfig()
            macroEnabled = config.macroEnabled
            alwaysMacro = config.alwaysMacro
            macroStore = MacroRepository(context).loadMacroStore()
            invalidateCache()
        } catch (e: Exception) {
            System.err.println("[VietnameseInputEngine] Failed to reload macro store: ${e.message}")
        }
    }

    fun savePreferences(
        context: Context,
        macro: Boolean = macroEnabled,
        alwaysMac: Boolean = alwaysMacro,
        autoCap: Boolean = autoCapitalize,
        dirW: Boolean = directW,
        oldTone: Boolean = oldTonePlacement
    ) {
        try {
            AppPreferences.init(context)
            val config = EngineConfig(
                macroEnabled = macro,
                alwaysMacro = alwaysMac,
                autoCapitalize = autoCap,
                directW = dirW,
                oldTonePlacement = oldTone
            )
            AppPreferences.setEngineConfig(config)
            
            macroEnabled = macro
            alwaysMacro = alwaysMac
            autoCapitalize = autoCap
            directW = dirW
            oldTonePlacement = oldTone
        } catch (e: Exception) {
            System.err.println("[VietnameseInputEngine] Failed to save preferences: ${e.message}")
        }
    }
}

// Alias for backwards compatibility
typealias GoVietInputEngine = VietnameseInputEngine
