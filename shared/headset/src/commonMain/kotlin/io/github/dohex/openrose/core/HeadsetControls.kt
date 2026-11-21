package io.github.dohex.openrose.core

/** Checks the current model options and prerequisite readings both at submission and before the write. */
fun DeviceState.validate(command: HeadsetCommand): ControlError? {
    if (command.setting !in capabilities.writableSettings) return unsupported("${command.setting.name} 尚不支持写入")
    return settingPrerequisite(command.setting)?.let { ControlError(ControlErrorCode.NOT_READY, it) } ?: command.checkOptions(this)
}

internal fun unsupported(message: String) = ControlError(ControlErrorCode.UNSUPPORTED, message)

internal fun checkBinding(touch: TouchControlCapabilities?, side: String, gesture: String, action: String): ControlError? = when {
    touch == null -> unsupported("该型号不支持触控绑定")
    TouchSlot(side, gesture) !in touch.slots -> ControlError(ControlErrorCode.INVALID_ARGUMENT, "触控作用耳或手势无效")
    action !in touch.actions -> unsupported("该型号不支持 $action 触控动作")
    else -> null
}
