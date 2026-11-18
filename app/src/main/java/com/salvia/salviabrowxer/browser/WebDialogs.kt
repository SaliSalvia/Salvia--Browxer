package com.salvia.salviabrowxer.browser

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.util.Locale

/**
 * Native dialogs for the browser layer: JavaScript alert/confirm/prompt, HTTP authentication,
 * SSL certificate warnings, web permission prompts (camera / microphone / location) and the
 * "open in another app" confirmation. Shown above both the page WebView and the React UI.
 *
 * Text is English or Persian depending on the device locale, matching the app's two languages.
 * All [show] functions must be called on the main thread.
 */
object WebDialogs {

    private val fa: Boolean get() = Locale.getDefault().language.equals("fa", ignoreCase = true)

    private fun s(en: String, farsi: String): String = if (fa) farsi else en

    private fun build(activity: Activity, title: String): AlertDialog.Builder {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(title)
        return builder
    }

    // ------------------------------------------------------------------ JavaScript dialogs

    fun showJsAlert(activity: Activity, url: String, message: String, callback: (Boolean) -> Unit) {
        val host = try {
            java.net.URI(url).host ?: url
        } catch (ignored: Exception) {
            url
        }
        build(activity, host)
            .setMessage(message.ifBlank { s("Alert", "هشدار") })
            .setPositiveButton(android.R.string.ok) { _, _ -> callback(true) }
            .setOnCancelListener { callback(false) }
            .show()
    }

    fun showJsConfirm(activity: Activity, url: String, message: String, callback: (Boolean) -> Unit) {
        val host = try {
            java.net.URI(url).host ?: url
        } catch (ignored: Exception) {
            url
        }
        build(activity, host)
            .setMessage(message.ifBlank { s("Confirm", "تأیید") })
            .setPositiveButton(android.R.string.ok) { _, _ -> callback(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> callback(false) }
            .setOnCancelListener { callback(false) }
            .show()
    }

    fun showJsPrompt(
        activity: Activity,
        url: String,
        message: String,
        defaultValue: String,
        callback: (String?) -> Unit
    ) {
        val host = try {
            java.net.URI(url).host ?: url
        } catch (ignored: Exception) {
            url
        }
        val input = EditText(activity).apply {
            setText(defaultValue)
            setSingleLine()
            setTextAppearance(android.R.style.TextAppearance_Medium)
        }
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        if (message.isNotBlank()) {
            wrap.addView(TextView(activity).apply { text = message })
        }
        wrap.addView(input)
        build(activity, host)
            .setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ -> callback(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> callback(null) }
            .setOnCancelListener { callback(null) }
            .show()
    }

    fun showJsBeforeUnload(activity: Activity, callback: (Boolean) -> Unit) {
        build(activity, s("Leave page?", "ترک صفحه؟"))
            .setMessage(
                s(
                    "This page is asking to confirm that you want to leave — data you have entered may not be saved.",
                    "این صفحه می‌خواهد مطمئن شود که واقعاً می‌خواهید خارج شوید — اطلاعاتی که وارد کرده‌اید ممکن است ذخیره نشود."
                )
            )
            .setPositiveButton(s("Leave", "خروج")) { _, _ -> callback(true) }
            .setNegativeButton(s("Stay", "ماندن")) { _, _ -> callback(false) }
            .setOnCancelListener { callback(false) }
            .show()
    }

    // ------------------------------------------------------------------ HTTP authentication

    fun showHttpAuth(
        activity: Activity,
        host: String,
        realm: String,
        callback: (user: String?, password: String?) -> Unit
    ) {
        val user = EditText(activity).apply {
            hint = s("Username", "نام کاربری")
            setSingleLine()
        }
        val pass = EditText(activity).apply {
            hint = s("Password", "گذرواژه")
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(user)
            addView(pass)
        }
        val title = if (realm.isBlank()) host else "$host — \"$realm\""
        build(activity, s("Sign in", "ورود"))
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton(s("Sign in", "ورود")) { _, _ ->
                callback(user.text.toString(), pass.text.toString())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> callback(null, null) }
            .setOnCancelListener { callback(null, null) }
            .show()
    }

    // ------------------------------------------------------------------ SSL warning

    /** Classic "Your connection is not private" interstitial with a hidden-by-default proceed option. */
    fun showSslWarning(
        activity: Activity,
        host: String,
        errorDescription: String,
        onProceed: () -> Unit,
        onCancel: () -> Unit
    ) {
        val message = (
            s(
                "The security certificate of %s is not trusted.\n\n%s\n\n" +
                    "Attackers might be trying to steal your information (for example passwords or credit card numbers). " +
                    "You should only continue if you trust this site and know why this warning appears.",
                "گواهی امنیتی %s معتبر نیست.\n\n%s\n\n" +
                    "ممکن است مهاجمان در تلاش برای سرقت اطلاعات شما (مانند گذرواژه یا شماره کارت) باشند. " +
                    "فقط در صورتی ادامه دهید که به این سایت اعتماد دارید و دلیل این هشدار را می‌دانید."
                )
            ).format(host, errorDescription)
        build(activity, s("Connection is not private", "اتصال امن نیست"))
            .setMessage(message)
            .setPositiveButton(s("Go back (safe)", "بازگشت (امن)")) { _, _ -> onCancel() }
            .setNegativeButton(s("Advanced: continue anyway", "پیشرفته: به هر حال ادامه بده")) { _, _ -> onProceed() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    // ------------------------------------------------------------------ web permissions

    /**
     * Asks the user whether the page may use camera / microphone / location, with a
     * "remember for this site" checkbox (checked by default, like a real browser).
     */
    fun showPermissionPrompt(
        activity: Activity,
        host: String,
        permissionLabel: String,
        onResult: (granted: Boolean, remember: Boolean) -> Unit
    ) {
        val remember = CheckBox(activity).apply {
            text = s("Remember for this site", "برای این سایت به خاطر بسپار")
            isChecked = true
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(
                TextView(activity).apply {
                    text = s(
                        "%s wants to use your %s.",
                        "%s می‌خواهد از %s شما استفاده کند."
                    ).format(host, permissionLabel)
                }
            )
            addView(remember)
        }
        build(activity, s("Permission request", "درخواست مجوز"))
            .setView(ScrollView(activity).apply { addView(wrap) })
            .setPositiveButton(s("Allow", "اجازه دادن")) { _, _ -> onResult(true, remember.isChecked) }
            .setNegativeButton(s("Block", "مسدود کردن")) { _, _ -> onResult(false, remember.isChecked) }
            .setOnCancelListener { onResult(false, false) }
            .show()
    }

    // ------------------------------------------------------------------ external apps

    fun showOpenExternalConfirm(
        activity: Activity,
        appLabel: String,
        url: String,
        onOpen: () -> Unit
    ) {
        val message = if (appLabel.isBlank()) {
            s("Open this link outside the browser?\n\n%s", "این لینک بیرون از مرورگر باز شود؟\n\n%s").format(url)
        } else {
            s("Open with %s?\n\n%s", "با %s باز شود؟\n\n%s").format(appLabel, url)
        }
        build(activity, s("Open in another app", "باز کردن در برنامهٔ دیگر"))
            .setMessage(message)
            .setPositiveButton(s("Open", "باز کن")) { _, _ -> onOpen() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    /** Unused-view suppression so lint does not complain about the imported View symbol. */
    @Suppress("unused")
    private fun touch(v: View?) {}
}
