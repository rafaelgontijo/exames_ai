package br.com.exames.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/** Drives only the test provider inside the standard Android document picker. */
internal class ExamPickerAutomation(private val automation: UiAutomation) {
    fun choose(title: String) {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        if (!authorizedRootOpen()) {
            if (pickerRoot()?.view("roots_list") == null) openRoots()
            check(clickText(AuthorizedExamDocumentsProvider.TITLE, "roots_list", 10_000)) {
                "PRIVATE_PICKER_ROOT_NOT_VISIBLE"
            }
        }
        check(clickText(title, "dir_list", 10_000, requireAuthorizedRoot = true)) { "PRIVATE_PICKER_DOCUMENT_NOT_VISIBLE" }
        if (!returnedToApp(2_000)) {
            // The Android 7 picker can display the only document while requiring keyboard activation.
            val root = pickerRoot()
            check(root != null && authorizedRootOpen() &&
                root.findAccessibilityNodeInfosByText(title).isNotEmpty()) { "PRIVATE_PICKER_SELECTION_FAILED" }
            sendInput("input keyevent 20 66")
            check(returnedToApp(10_000)) { "PRIVATE_PICKER_SELECTION_FAILED" }
        }
    }

    private fun returnedToApp(timeout: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (automation.rootInActiveWindow?.packageName == "br.com.exames.offline.validation.extraction") return true
            SystemClock.sleep(100)
        }
        return false
    }

    fun waitForPicker() {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (pickerRoot() != null) return
            SystemClock.sleep(100)
        }
        error("PRIVATE_PICKER_NOT_OPEN")
    }

    fun back() = sendInput("input keyevent 4")

    private fun sendInput(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            check(it.read() == -1) { "PRIVATE_PICKER_INPUT_FAILED" }
        }
    }

    private fun pickerRoot(): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow
        val packageId = root?.packageName?.toString()
        return root.takeIf { packageId == "com.android.documentsui" || packageId == "com.google.android.documentsui" }
    }

    private fun AccessibilityNodeInfo.view(id: String): AccessibilityNodeInfo? =
        findAccessibilityNodeInfosByViewId("$packageName:id/$id").firstOrNull { it.isVisibleToUser }

    private fun authorizedRootOpen(): Boolean {
        val root = pickerRoot() ?: return false
        return listOfNotNull(root.view("toolbar"), root.view("horizontal_breadcrumb")).any { header ->
            header.findAccessibilityNodeInfosByText(AuthorizedExamDocumentsProvider.TITLE).any {
                it.text?.toString() == AuthorizedExamDocumentsProvider.TITLE
            }
        }
    }

    private fun openRoots() {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = pickerRoot()
            if (root != null) {
                val toolbar = root.view("toolbar")
                val navigation = toolbar?.let {
                    (0 until it.childCount).mapNotNull(it::getChild).firstOrNull { button ->
                        button.isClickable && button.className == "android.widget.ImageButton"
                    }
                }
                if (navigation?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            SystemClock.sleep(100)
        }
        error("PRIVATE_PICKER_NAVIGATION_NOT_VISIBLE")
    }

    private fun clickText(label: String, containerId: String, timeoutMillis: Long, requireAuthorizedRoot: Boolean = false): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (requireAuthorizedRoot && !authorizedRootOpen()) {
                SystemClock.sleep(100)
                continue
            }
            val container = pickerRoot()?.view(containerId)
            val matches = container?.findAccessibilityNodeInfosByText(label).orEmpty()
            for (match in matches) {
                if (!match.isVisibleToUser || (match.text?.toString() != label && match.contentDescription?.toString() != label)) continue
                var row: AccessibilityNodeInfo? = match
                while (row != null && row != container) {
                    if (row.isClickable && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    row = row.parent
                }
                // On older Android releases the list receives the tap even when its row is not clickable in accessibility.
                val bounds = Rect().also(match::getBoundsInScreen)
                if (bounds.isEmpty) continue
                sendInput("input tap ${bounds.centerX()} ${bounds.centerY()}")
                return true
            }
            SystemClock.sleep(100)
        }
        return false
    }
}
