package io.github.dohex.openrose.ui

import io.github.dohex.openrose.androidintegration.ModuleAvailability
import io.github.dohex.openrose.androidintegration.ModuleUnavailableCause

/**
 * Why the system module cannot act now, or null when it can. Every surface that explains the module
 * (diagnostics, readiness, control way) builds on this one clause so they never disagree.
 */
internal fun ModuleAvailability.unreadyClause(): String? = when (this) {
    ModuleAvailability.Available -> null
    ModuleAvailability.NotEnabled -> "系统模块未启用"
    is ModuleAvailability.Unavailable -> "系统模块暂不可用：$reason"
    ModuleAvailability.Unresponsive -> "系统模块已连接但未响应"
}

/**
 * What the user can do about [unreadyClause], or null when nothing is known to help. Only causes the
 * application can tell apart get advice; the restart action it names lives in the Status page's top bar.
 */
internal fun ModuleAvailability.nextStep(): String? = when (this) {
    ModuleAvailability.Available -> null
    ModuleAvailability.NotEnabled -> "请在 Xposed 框架中启用 OpenRose 模块。"
    ModuleAvailability.Unresponsive -> "宿主进程仍在运行但没有应答。可用右上角的重启作用域按钮让模块重新加载。"
    is ModuleAvailability.Unavailable -> when (cause) {
        ModuleUnavailableCause.FRAMEWORK_MISSING -> "请确认 Xposed 框架正在运行，并已在其中启用 OpenRose 模块。"
        ModuleUnavailableCause.FRAMEWORK_OUTDATED -> "请把 Xposed 框架更新到支持 API 102 的版本。"
        ModuleUnavailableCause.HOST_NOT_FOUND -> "请确认已为 OpenRose 模块勾选系统蓝牙（com.android.bluetooth）作用域，再用右上角的重启作用域按钮让模块重新加载。"
        ModuleUnavailableCause.AUTHORIZATION_REFUSED -> "系统蓝牙宿主拒绝了本应用，详情见“运行状态与诊断”。"
        ModuleUnavailableCause.LINK_LOST -> "与系统蓝牙宿主的连接已断开，正在自动重试。反复出现时可用右上角的重启作用域按钮。"
        null -> null
    }
}
