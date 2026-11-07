package bukin.magnifier

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

/**
 * The app's few ways out to the world, all started by the user from the About screen and all
 * handled by other apps: the privacy policy opens in the browser, and the contact address in the
 * email app. The app itself has no internet permission and sends nothing.
 *
 * The one place for the policy URL and the contact email. The same values are in
 * docs/privacy-policy.md, docs/play/store-listing.md and the site; AboutTest checks they agree.
 */
object AppLinks {
    const val WEBSITE_URL = "https://elibukin.github.io/magnifier/"
    const val PRIVACY_POLICY_URL = "https://elibukin.github.io/magnifier/privacy-policy.html"
    const val CONTACT_EMAIL = "dev.mechanics998877@gmail.com"

    /** The email's subject: the app's name, not translated. */
    const val EMAIL_SUBJECT = "Bukin Magnifier"

    /** Opens the privacy policy in the browser. */
    fun privacyPolicy(): Intent =
        Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)

    /**
     * A new email to the developer, in the user's email app (only email apps handle `mailto:`).
     * The address and subject are in the URI, which every email app reads; the subject is also an
     * extra, for apps that only read that. Nothing is sent until the user sends it.
     */
    fun email(): Intent =
        Intent(Intent.ACTION_SENDTO, ("mailto:$CONTACT_EMAIL?subject=" + Uri.encode(EMAIL_SUBJECT)).toUri())
            .putExtra(Intent.EXTRA_SUBJECT, EMAIL_SUBJECT)
}
