package com.chardy.doom

internal enum class OverlayAttachment { ATTACHED, DETACHED, UNKNOWN }
internal enum class OverlayRemovalResult { DETACHED, RETRY_REQUIRED }

/** One saved window identity. Implementations must make recovery adds invisible and inert. */
internal interface OverlayWindowRemovalPort {
    fun attachment(): OverlayAttachment
    fun removeImmediate()
    fun reAddForRemoval()
}

internal object OverlayWindowRemover {
    fun attachment(port: OverlayWindowRemovalPort): OverlayAttachment = try {
        port.attachment()
    } catch (_: RuntimeException) { OverlayAttachment.UNKNOWN }

    fun attempt(port: OverlayWindowRemovalPort, mayReAdd: Boolean): OverlayRemovalResult {
        fun detached() = attachment(port) == OverlayAttachment.DETACHED
        if (detached()) return OverlayRemovalResult.DETACHED
        try { port.removeImmediate() } catch (_: RuntimeException) { }
        if (detached()) return OverlayRemovalResult.DETACHED
        if (mayReAdd) {
            try { port.reAddForRemoval() } catch (_: RuntimeException) { }
            if (detached()) return OverlayRemovalResult.DETACHED
            try { port.removeImmediate() } catch (_: RuntimeException) { }
        }
        return if (detached()) OverlayRemovalResult.DETACHED else OverlayRemovalResult.RETRY_REQUIRED
    }
}
