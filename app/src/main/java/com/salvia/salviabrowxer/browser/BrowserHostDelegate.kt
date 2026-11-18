package com.salvia.salviabrowxer.browser

import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback

/**
 * Capabilities [BrowserHost] needs from the hosting Activity. Implemented by MainActivity using
 * ActivityResultLaunchers (which can only be registered before onStart), keeping BrowserHost
 * free of AndroidX activity-result plumbing.
 */
interface BrowserHostDelegate {

    /**
     * Opens the system file picker for `<input type=file>`. Returns false when a previous picker
     * is still pending (the page's callback is then answered with null, like Chrome does).
     */
    fun openFileChooser(
        callback: ValueCallback<Array<Uri>>,
        acceptTypes: Array<String>,
        allowMultiple: Boolean
    ): Boolean

    /** True when the Android runtime permission is already granted. */
    fun hasAndroidPermission(permission: String): Boolean

    /** Asks the user for an Android runtime permission; the callback runs on the main thread. */
    fun requestAndroidPermission(permission: String, onResult: (Boolean) -> Unit)

    /** Shows the "open in another app" confirmation and launches [intent] when accepted. */
    fun openExternalIntent(intent: Intent, url: String): Boolean
}
