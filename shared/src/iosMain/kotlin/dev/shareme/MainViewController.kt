package dev.shareme

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.flow.MutableStateFlow

/** iOS entry point. Transport/file-provider integration is a separate platform implementation. */
fun MainViewController() = ComposeUIViewController {
    val controller = IosPreviewController
    ShareMeApp(controller, {}, {}, {}, {})
}

private object IosPreviewController : ShareController {
    override val state = MutableStateFlow(ShareState(
        deviceName = "iPhone / iPad",
        destination = "iOS file integration pending",
        message = "UI preview only. Native iOS networking and document access are not implemented yet.",
    ))
    override fun connect(code: String) { state.value = state.value.copy(message = "iOS transfers are not available in this build. Use Android or desktop for the native transfer preview.") }
    override fun disconnect() = Unit
    override fun cancel() = Unit
}
