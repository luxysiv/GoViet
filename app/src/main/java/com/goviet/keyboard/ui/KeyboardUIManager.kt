package com.goviet.keyboard.ui

import com.goviet.keyboard.VietnameseInputMethodService
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

@Suppress("DEPRECATION")
class KeyboardUIManager(private val service: VietnameseInputMethodService) {
    private var rootView: KeyboardRootView? = null

    fun notifyConfigurationChanged() {
        rootView?.render()
    }

    fun onCreateInputView(): View {
        service.window?.window?.let { win ->
            win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        
        val rootContainer = FrameLayout(service).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        service.window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(service)
            decor.setViewTreeViewModelStoreOwner(service)
            decor.setViewTreeSavedStateRegistryOwner(service)
        }
        
        rootContainer.setOnApplyWindowInsetsListener { v, insets ->
            val navInsets = insets.getInsets(android.view.WindowInsets.Type.navigationBars())
            val navBarHeight = navInsets.bottom
            val tappableBottom = insets.getInsets(android.view.WindowInsets.Type.tappableElement()).bottom

            val effectivePadding = maxOf(navBarHeight, tappableBottom)
            service._navigationBarHeight.value = effectivePadding

            insets
        }

        val krv = KeyboardRootView(
            context = service,
            service = service,
            onKeyPress = { key ->
                if (key == "LEFT_MOVE") {
                    service.handleEditAction("LEFT")
                } else if (key == "RIGHT_MOVE") {
                    service.handleEditAction("RIGHT")
                } else {
                    service.handleKeyPress(key)
                }
            }
        ).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        rootContainer.addView(krv)
        rootView = krv
        service.keyboardRootView = krv
        return rootContainer
    }

    fun updateNavigationBarColor(color: Int, isDark: Boolean) {
        val win = service.window?.window ?: return
        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        win.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        win.navigationBarColor = color
        
        win.isNavigationBarContrastEnforced = false
        win.navigationBarDividerColor = android.graphics.Color.TRANSPARENT

        val controller = win.insetsController
        if (controller != null) {
            if (!isDark) {
                controller.setSystemBarsAppearance(
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else {
                controller.setSystemBarsAppearance(
                    0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            }
        }
    }
}
