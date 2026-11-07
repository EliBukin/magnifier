package bukin.magnifier

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RawRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import bukin.magnifier.databinding.ActivityAboutBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * About and privacy, opened from the "i" button on the camera screen: the app's name and version,
 * "No internet: nothing leaves your phone", and three big buttons: the privacy policy (in the
 * browser), an email to the developer (in the email app) and the open-source licences. Back, or
 * the arrow at the top, returns to the camera.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()
        with(binding) {
            ViewCompat.setAccessibilityHeading(aboutTitle, true)
            ViewCompat.setAccessibilityHeading(aboutPrivacyTitle, true)
            aboutVersion.text = versionText(this@AboutActivity)
            aboutEmail.text = AppLinks.CONTACT_EMAIL
            aboutBack.setOnClickListener { finish() }
            aboutPrivacyPolicy.setOnClickListener {
                open(AppLinks.privacyPolicy(), getString(R.string.about_no_browser, AppLinks.PRIVACY_POLICY_URL))
            }
            aboutContact.setOnClickListener {
                open(AppLinks.email(), getString(R.string.about_no_email, AppLinks.CONTACT_EMAIL))
            }
            aboutLicences.setOnClickListener {
                if (supportFragmentManager.findFragmentByTag(LicencesDialog.TAG) == null) {
                    LicencesDialog().show(supportFragmentManager, LicencesDialog.TAG)
                }
            }
            // TalkBack says where each button goes: "double tap to open in the browser".
            clickLabel(aboutPrivacyPolicy, R.string.about_action_browser)
            clickLabel(aboutContact, R.string.about_action_email)
        }
    }

    /** Hands [intent] to another app; if none can take it, says so and how to get there by hand. */
    private fun open(intent: Intent, fallback: String) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, fallback, Toast.LENGTH_LONG).show()
        }
    }

    private fun clickLabel(view: View, label: Int) =
        ViewCompat.replaceAccessibilityAction(view, AccessibilityActionCompat.ACTION_CLICK, getString(label), null)

    /** Edge to edge: the black page runs under the bars; its content keeps clear of them. */
    private fun applyWindowInsets() = with(binding) {
        val base = intArrayOf(aboutContent.paddingLeft, aboutContent.paddingTop, aboutContent.paddingRight, aboutContent.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            aboutContent.updatePadding(
                left = base[0] + bars.left,
                top = base[1] + bars.top,
                right = base[2] + bars.right,
                bottom = base[3] + bars.bottom,
            )
            insets
        }
    }

    /** The open-source notices: every library the app ships, their licences and notices. */
    class LicencesDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.about_licences)
                .setMessage(licenceText(requireContext()))
                .setPositiveButton(R.string.about_close, null)
                .create()

        companion object {
            const val TAG = "licences"
        }
    }

    internal companion object {
        /**
         * "Version 1.0" ("1.0-debug" in a debug build). The version is isolated as left to right, so
         * Hebrew shows "גרסה 1.0-debug", not "debug-1.0".
         */
        fun versionText(context: Context): String = context.getString(
            R.string.about_version,
            BidiFormatter.getInstance(context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                .unicodeWrap(BuildConfig.VERSION_NAME, TextDirectionHeuristicsCompat.LTR),
        )

        /** The translated intro, then the notices and the Apache License 2.0, in English. */
        @VisibleForTesting
        fun licenceText(context: Context): String =
            context.getString(R.string.licences_intro) + "\n\n" +
                raw(context, R.raw.third_party_notices).trim() + "\n\n" + raw(context, R.raw.apache_license_2_0).trim()

        private fun raw(context: Context, @RawRes id: Int): String =
            context.resources.openRawResource(id).bufferedReader().use { it.readText() }
    }
}
