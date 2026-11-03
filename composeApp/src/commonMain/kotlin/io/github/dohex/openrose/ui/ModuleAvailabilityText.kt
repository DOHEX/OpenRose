package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.ModuleAvailability

/**
 * Why the system module cannot act now, or null when it can. Every surface that explains the module
 * (diagnostics, readiness, control way) builds on this one clause so they never disagree.
 */
internal fun ModuleAvailability.unreadyClause(): String? = when (this) {
    ModuleAvailability.Available -> null
    ModuleAvailability.NotEnabled -> "系统模块未启用"
    is ModuleAvailability.Unavailable -> "系统模块暂不可用：$reason"
}
