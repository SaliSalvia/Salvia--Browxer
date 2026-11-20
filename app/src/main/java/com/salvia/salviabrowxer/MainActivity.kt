package com.salvia.salviabrowxer

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import com.salvia.salviabrowxer.browser.BrowserHost
import com.salvia.salviabrowxer.browser.BrowserHostDelegate
import com.salvia.salviabrowxer.core.NativeProxyClient
import com.salvia.salviabrowxer.data.UiRoute
import com.salvia.salviabrowxer.service.NativeDownloadService
import com.salvia.salviabrowxer.storage.MediaPublisher
import com.salvia.salviabrowxer.ui.SalviaApp
import com.salvia.salviabrowxer.ui.SalviaViewModel

class MainActivity : AppCompatActivity(), BrowserHostDelegate {

    private val viewModel: SalviaViewModel by viewModels()

    private lateinit var browserContainer: FrameLayout
    private lateinit var fullscreenHolder: FrameLayout
    private var browserHost: BrowserHost? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private var permissionResultCallback: ((Boolean) -> Unit)? = null
    private val androidPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cb = permissionResultCallback
            permissionResultCallback = null
            cb?.invoke(granted)
        }

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            val uris: Array<Uri>? = result.data?.let { data ->
                val clip = data.clipData
                when {
                    clip != null -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }.toTypedArray()
                    data.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            }
            if (uris == null && result.resultCode != RESULT_OK) {
                callback?.onReceiveValue(null)
            } else {
                callback?.onReceiveValue(uris ?: arrayOf())
            }
        }

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                com.salvia.salviabrowxer.core.SalviaLog.w("MainActivity", _)
                }
                getSharedPreferences(DOWNLOAD_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_DOWNLOAD_TREE, uri.toString())
                    .apply()
                MediaPublisher.downloadTreeUri = uri
                viewModel.refreshDownloadFolderLabel()
            }
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        NativeProxyClient.initFromWebView(this)
        window.decorView.setBackgroundColor(Color.BLACK)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        try {
            WebViewCompat.startSafeBrowsing(this, null)
        } catch (_: Exception) {
        com.salvia.salviabrowxer.core.SalviaLog.w("MainActivity", _)
        }

        browserContainer = FrameLayout(this).apply {
            visibility = View.GONE
        }
        fullscreenHolder = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        val host = BrowserHost(
            activity = this,
            container = browserContainer,
            fullscreenHolder = fullscreenHolder,
            routing = null,
            delegate = this
        ) { payload ->
            viewModel.handleNativeBrowserEvent(payload)
        }
        browserHost = host
        viewModel.attachBrowserHost(host)

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SalviaApp(
                    viewModel = viewModel,
                    browserContainer = browserContainer,
                    onExitWebFullscreen = { browserHost?.exitFullscreen() == true },
                    onPickDownloadFolder = { pickDownloadFolder() },
                    onResetDownloadFolder = { resetDownloadFolder() },
                    onEnterPip = { enterPipIfSupported() },
                    onCopyText = { text -> copyToClipboard(text) },
                    onShareLink = { title, url -> shareUrl(title, url) }
                )
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                composeView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                fullscreenHolder,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        setContentView(root)

        requestNotificationPermissionIfNeeded()
        NativeDownloadService.resumeRunningJobs(applicationContext)

        extractUrlFromIntent(intent)?.let { incoming ->
            viewModel.handleIncomingExternalUrl(incoming)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.inspectClipboardIfChanged()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUrlFromIntent(intent)?.let { incoming ->
            viewModel.handleIncomingExternalUrl(incoming)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (browserHost?.isFullscreenActive() == true || viewModel.uiState.value.route == UiRoute.MediaPlayer) {
            enterPipIfSupported()
        }
    }

    private fun enterPipIfSupported() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            ) {
                val width = window.decorView.width.coerceAtLeast(16)
                val height = window.decorView.height.coerceAtLeast(9)
                val rational = if (height > width) Rational(9, 16) else Rational(16, 9)
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(rational)
                        .build()
                )
            }
        } catch (_: Exception) {
        com.salvia.salviabrowxer.core.SalviaLog.w("MainActivity", _)
        }
    }

    override fun onDestroy() {
        viewModel.detachBrowserHost()
        browserHost?.destroyAll()
        browserHost = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ BrowserHostDelegate

    override fun openFileChooser(
        callback: ValueCallback<Array<Uri>>,
        acceptTypes: Array<String>,
        allowMultiple: Boolean
    ): Boolean {
        return try {
            val previous = fileChooserCallback
            fileChooserCallback = callback
            if (previous != null) {
                try {
                    previous.onReceiveValue(null)
                } catch (_: Exception) {
                com.salvia.salviabrowxer.core.SalviaLog.w("MainActivity", _)
                }
            }
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = normalizeAcceptType(acceptTypes)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            }
            fileChooserLauncher.launch(intent)
            true
        } catch (_: Exception) {
            fileChooserCallback = null
            try {
                callback.onReceiveValue(null)
            } catch (_: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("MainActivity", _)
            }
            false
        }
    }

    private fun normalizeAcceptType(acceptTypes: Array<String>): String {
        val cleaned = acceptTypes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val interesting = cleaned.filter { it.contains("/") || it == "audio" || it == "video" || it == "image" }
        return when {
            interesting.any { it.startsWith("video/") || it == "video" } &&
                interesting.none { it.startsWith("image/") || it == "image" } -> "video/*"
            interesting.any { it.startsWith("audio/") || it == "audio" } &&
                interesting.none { it.startsWith("image/") || it == "image" } -> "audio/*"
            interesting.any { it.startsWith("image/") || it == "image" } &&
                interesting.none { it.startsWith("video/") || it == "video" || it.startsWith("audio/") || it == "audio" } -> "image/*"
            else -> "*/*"
        }
    }

    override fun hasAndroidPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun requestAndroidPermission(permission: String, onResult: (Boolean) -> Unit) {
        if (hasAndroidPermission(permission)) {
            onResult(true)
            return
        }
        if (permission == Manifest.permission.ACCESS_FINE_LOCATION &&
            hasAndroidPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            onResult(true)
            return
        }
        permissionResultCallback = onResult
        androidPermissionLauncher.launch(permission)
    }

    override fun openExternalIntent(intent: Intent, url: String): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val fallback = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/search?q=${Uri.encode(url)}&c=apps")
                )
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallback)
                true
            } catch (_: Exception) {
                Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    // ------------------------------------------------------------------ UI actions & helpers

    private fun pickDownloadFolder() {
        try {
            folderPickerLauncher.launch(null)
        } catch (_: Exception) {
        com.salvia.salviabrowxer.core.SalviaLog.w("MainActivity", _)
        }
    }

    private fun resetDownloadFolder() {
        getSharedPreferences(DOWNLOAD_PREFS, MODE_PRIVATE).edit().remove(PREF_DOWNLOAD_TREE).apply()
        MediaPublisher.downloadTreeUri = null
        viewModel.refreshDownloadFolderLabel()
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("url", text))
        } catch (_: Exception) {
        com.salvia.salviabrowxer.core.SalviaLog.w("MainActivity", _)
        }
    }

    private fun shareUrl(title: String, url: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(intent, title.ifBlank { url }))
        } catch (_: Exception) {
        com.salvia.salviabrowxer.core.SalviaLog.w("MainActivity", _)
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        val data = intent.dataString?.trim()
        if (!data.isNullOrBlank() && isHttpUrl(data)) return data.take(2048)
        val text = (
            intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
            )?.take(8192) ?: return null
        val match = Regex("(?i)https?://[^\\s\"'<>]+").find(text)?.value
            ?.trimEnd('.', ',', ';', ')', ']', '}', '»', '«') ?: return null
        return if (isHttpUrl(match)) match.take(2048) else null
    }

    private fun isHttpUrl(raw: String): Boolean = try {
        val scheme = Uri.parse(raw).scheme?.lowercase()
        scheme == "http" || scheme == "https"
    } catch (_: Exception) {
        false
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private companion object {
        const val PREF_DOWNLOAD_TREE = "download_tree_uri"
        const val DOWNLOAD_PREFS = "salvia_prefs"
    }
}
