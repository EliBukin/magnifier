package bukin.magnifier

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.DialogFragment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * The About button on the camera screen and the About screen it opens: labels, sizes, place,
 * the version, the policy link and the email, the licences, Hebrew, and that the URL and the
 * address agree with the policy and the store listing.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h780dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        MainActivity.cameraStartEnabled = false
    }

    @After
    fun tearDown() {
        MainActivity.cameraStartEnabled = true
    }

    private fun camera() = Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun about() = Robolectric.buildActivity(AboutActivity::class.java).setup().get()

    private fun View.dp(px: Int) = px / resources.displayMetrics.density

    private fun View.rectOnScreen() = IntArray(2).let { getLocationOnScreen(it); Rect(it[0], it[1], it[0] + width, it[1] + height) }

    private fun clickLabel(view: View): String? {
        val info = AccessibilityNodeInfoCompat.wrap(view.createAccessibilityNodeInfo())
        return info.actionList.firstOrNull { it.id == AccessibilityNodeInfoCompat.ACTION_CLICK }?.label?.toString()
    }

    // ---- The "i" button on the camera screen ----------------------------------------------------

    @Test
    fun theAboutButtonIsLabelledBigAndInTheTopEndCornerAwayFromThePanel() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = camera()
        val button = activity.findViewById<View>(R.id.aboutButton)
        val root = activity.findViewById<View>(R.id.root)
        assertTrue(button.isShown)
        assertEquals("About and privacy", button.contentDescription)
        assertTrue("width ${button.dp(button.width)}", button.dp(button.width) >= 56f)
        assertTrue("height ${button.dp(button.height)}", button.dp(button.height) >= 56f)
        // Top end corner (right, in English), within 16dp of both edges.
        val r = button.rectOnScreen()
        assertTrue("right edge ${r.right} of ${root.width}", button.dp(root.width - r.right) <= 16f)
        assertTrue("top ${button.dp(r.top)}", button.dp(r.top) <= 16f)
        // Nowhere near the panel.
        val panel = activity.findViewById<View>(R.id.controls).rectOnScreen()
        assertFalse(Rect.intersects(r, panel))
        // A long message shows below it, and the photo spinner is in the other corner.
        val message = activity.findViewById<TextView>(R.id.messageView)
        message.text = "The whole picture is saved in Pictures, Bukin Magnifier."
        message.visibility = View.VISIBLE
        val spinner = activity.findViewById<View>(R.id.photoProgress)
        spinner.visibility = View.VISIBLE
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(root.left, root.top, root.right, root.bottom)
        assertTrue("message top ${message.rectOnScreen().top}, button bottom ${r.bottom}", r.bottom <= message.rectOnScreen().top)
        assertFalse(Rect.intersects(r, spinner.rectOnScreen()))
        assertTrue(spinner.rectOnScreen().right <= message.rectOnScreen().left || spinner.rectOnScreen().bottom <= message.rectOnScreen().top)
    }

    @Test
    fun theAboutButtonIsThereBeforeTheCameraIsAllowed() {
        val activity = camera()
        assertEquals(PermissionScreen.EXPLAIN, activity.permissionScreen)
        assertTrue(activity.findViewById<View>(R.id.aboutButton).isShown)
    }

    @Test
    fun theAboutButtonClearsTheStatusBar() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = camera()
        val button = activity.findViewById<View>(R.id.aboutButton)
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, 48, 0, 0))
            .build()
        ViewCompat.dispatchApplyWindowInsets(activity.findViewById(R.id.root), insets)
        val margin = (button.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin
        assertEquals(48 + (8 * button.resources.displayMetrics.density).toInt(), margin)
    }

    @Test
    fun tappingTheAboutButtonOpensTheAboutScreen() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = camera()
        activity.findViewById<View>(R.id.aboutButton).performClick()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(AboutActivity::class.java.name, started.component?.className)
    }

    // ---- The About screen ------------------------------------------------------------------------

    @Test
    fun theAboutScreenShowsTheNameVersionAndNoInternet() {
        val activity = about()
        fun text(id: Int) = activity.findViewById<TextView>(id).text.toString()
        // The launcher name: "Bukin Magnifier", and "Bukin Magnifier (debug)" in a debug build.
        assertEquals(activity.getString(R.string.app_name), text(R.id.aboutTitle))
        assertTrue(text(R.id.aboutTitle).startsWith("Bukin Magnifier"))
        assertEquals("Version ${BuildConfig.VERSION_NAME}", text(R.id.aboutVersion).withoutBidiMarks())
        assertTrue(BuildConfig.VERSION_NAME.startsWith("1.0"))
        assertEquals("No internet: nothing leaves your phone", text(R.id.aboutPrivacyTitle))
        assertTrue(text(R.id.aboutPrivacyBody).contains("no internet permission"))
        assertEquals("dev.mechanics998877@gmail.com", text(R.id.aboutEmail))
        assertTrue(ViewCompat.isAccessibilityHeading(activity.findViewById(R.id.aboutTitle)))
        assertTrue(ViewCompat.isAccessibilityHeading(activity.findViewById(R.id.aboutPrivacyTitle)))
        screenshot(activity.window.decorView, "about-en.png")
    }

    @Test
    fun everyButtonIsLabelledAndBigEnough() {
        val activity = about()
        val back = activity.findViewById<View>(R.id.aboutBack)
        assertEquals("Back to the magnifier", back.contentDescription)
        val buttons = mapOf(
            R.id.aboutPrivacyPolicy to "Privacy policy",
            R.id.aboutContact to "Email the developer",
            R.id.aboutLicences to "Open-source licences",
        )
        for ((id, label) in buttons) assertEquals(label, activity.findViewById<TextView>(id).text.toString())
        assertEquals("open in the browser", clickLabel(activity.findViewById(R.id.aboutPrivacyPolicy)))
        assertEquals("write in your email app", clickLabel(activity.findViewById(R.id.aboutContact)))
        for (id in buttons.keys + R.id.aboutBack) {
            val v = activity.findViewById<View>(id)
            val name = activity.resources.getResourceEntryName(id)
            assertTrue("$name height ${v.dp(v.height)}", v.dp(v.height) >= 48f)
            assertTrue("$name width ${v.dp(v.width)}", v.dp(v.width) >= 48f)
        }
    }

    @Test
    fun theIntentsGoToTheBrowserAndTheEmailApp() {
        val policy = AppLinks.privacyPolicy()
        assertEquals(Intent.ACTION_VIEW, policy.action)
        assertEquals("https://elibukin.github.io/magnifier/privacy-policy.html", policy.dataString)
        assertTrue(policy.hasCategory(Intent.CATEGORY_BROWSABLE))

        val email = AppLinks.email()
        assertEquals(Intent.ACTION_SENDTO, email.action)
        assertEquals("mailto", email.data?.scheme)
        assertEquals("dev.mechanics998877@gmail.com?subject=Bukin Magnifier", email.data?.schemeSpecificPart)
        assertEquals("mailto:dev.mechanics998877@gmail.com?subject=Bukin%20Magnifier", email.dataString)
        assertEquals("Bukin Magnifier", email.getStringExtra(Intent.EXTRA_SUBJECT))
        // No attachment, no text: nothing but a new email the user writes and sends.
        assertFalse(email.hasExtra(Intent.EXTRA_STREAM))
        assertFalse(email.hasExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun theButtonsStartThoseIntents() {
        val activity = about()
        activity.findViewById<View>(R.id.aboutPrivacyPolicy).performClick()
        val view = shadowOf(activity).nextStartedActivity
        assertTrue(view.filterEquals(AppLinks.privacyPolicy()))
        activity.findViewById<View>(R.id.aboutContact).performClick()
        val mail = shadowOf(activity).nextStartedActivity
        assertTrue(mail.filterEquals(AppLinks.email()))
        assertEquals("Bukin Magnifier", mail.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun withoutABrowserOrAnEmailAppItSaysWhereToGo() {
        shadowOf(app).checkActivities(true) // Nothing can handle the intents: startActivity throws.
        val activity = about()
        try {
            activity.startActivity(AppLinks.privacyPolicy())
            throw AssertionError("expected no activity")
        } catch (expected: ActivityNotFoundException) {
        }
        activity.findViewById<View>(R.id.aboutPrivacyPolicy).performClick()
        assertEquals("No browser found. The privacy policy is at https://elibukin.github.io/magnifier/privacy-policy.html",
            ShadowToast.getTextOfLatestToast())
        activity.findViewById<View>(R.id.aboutContact).performClick()
        assertEquals("No email app found. Write to dev.mechanics998877@gmail.com", ShadowToast.getTextOfLatestToast())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun backAndTheArrowReturnToTheCamera() {
        val first = about()
        first.findViewById<View>(R.id.aboutBack).performClick()
        assertTrue(first.isFinishing)
        val second = about()
        second.onBackPressedDispatcher.onBackPressed()
        assertTrue(second.isFinishing)
    }

    @Test
    fun theLicencesListEveryLicenceAndTheNotices() {
        val activity = about()
        activity.findViewById<View>(R.id.aboutLicences).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = (activity.supportFragmentManager.findFragmentByTag(AboutActivity.LicencesDialog.TAG) as DialogFragment).dialog as AlertDialog
        assertTrue(dialog.isShowing)
        val text = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        for (part in listOf(
            "The app is built with these open-source libraries.",
            "AndroidX (Android Jetpack) libraries, including CameraX",
            "Material Components for Android",
            "Jakarta Dependency Injection is a trademark of the Eclipse Foundation.",
            "libyuv", "Copyright 2011 The LibYuv Project Authors",
            "Checker Framework qualifiers", "Permission is hereby granted, free of charge",
            "Apache License", "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", "END OF TERMS AND CONDITIONS",
        )) assertTrue("licences miss: $part", text.contains(part))
        // A second tap while it shows doesn't stack a second dialog.
        activity.findViewById<View>(R.id.aboutLicences).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, activity.supportFragmentManager.fragments.count { it is AboutActivity.LicencesDialog })
    }

    @Test
    @Config(qualifiers = "iw-w360dp-h780dp-port-xhdpi")
    fun hebrewAboutIsTranslatedAndRightToLeft() {
        val activity = about()
        fun text(id: Int) = activity.findViewById<TextView>(id).text.toString()
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.window.decorView.layoutDirection)
        assertEquals("בלי אינטרנט: שום דבר לא יוצא מהטלפון", text(R.id.aboutPrivacyTitle))
        assertEquals("גרסה ${BuildConfig.VERSION_NAME}", text(R.id.aboutVersion).withoutBidiMarks())
        // The version is isolated left to right ("1.0-debug", never "debug-1.0").
        assertTrue(text(R.id.aboutVersion).contains("\u202A${BuildConfig.VERSION_NAME}\u202C") ||
            text(R.id.aboutVersion).contains("\u2066${BuildConfig.VERSION_NAME}\u2069"))
        assertEquals("מדיניות הפרטיות", text(R.id.aboutPrivacyPolicy))
        assertEquals("שליחת אימייל למפתח", text(R.id.aboutContact))
        assertEquals("רישיונות קוד פתוח", text(R.id.aboutLicences))
        assertEquals("חזרה לזכוכית המגדלת", activity.findViewById<View>(R.id.aboutBack).contentDescription)
        // The back arrow sits at the right, the start in Hebrew.
        val back = activity.findViewById<View>(R.id.aboutBack).rectOnScreen()
        assertTrue(back.centerX() > activity.window.decorView.width / 2)
        screenshot(activity.window.decorView, "about-he.png")
    }

    @Test
    @Config(qualifiers = "iw-w360dp-h780dp-port-xhdpi")
    fun inHebrewTheAboutButtonIsAtTheTopLeft() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val activity = camera()
        val button = activity.findViewById<View>(R.id.aboutButton)
        assertEquals("אודות ופרטיות", button.contentDescription)
        val r = button.rectOnScreen()
        assertTrue("left ${button.dp(r.left)}", button.dp(r.left) <= 16f)
        screenshot(activity.window.decorView, "about-button-he.png")
    }

    @Test
    fun everyAboutStringHasAHebrewTranslation() {
        val names = Regex("<string name=\"((?:about|licences)_\\w+)\"")
        val en = names.findAll(File("src/main/res/values/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        val he = names.findAll(File("src/main/res/values-iw/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        assertTrue(en.size >= 14)
        assertEquals(en, he)
    }

    // ---- One URL, one address, everywhere ------------------------------------------------------

    @Test
    fun theUrlAndAddressAgreeWithThePolicyTheListingAndTheSite() {
        val policyEn = File("../docs/privacy-policy.md").readText()
        val policyHe = File("../docs/privacy-policy.he.md").readText()
        val listing = File("../docs/play/store-listing.md").readText()
        val dataSafety = File("../docs/play/data-safety.md").readText()
        val site = File("../scripts/render-site.py").readText()
        for (doc in listOf(policyEn, policyHe, listing)) assertTrue(doc.contains(AppLinks.CONTACT_EMAIL))
        assertTrue(listing.contains(AppLinks.PRIVACY_POLICY_URL))
        assertTrue(listing.contains(AppLinks.WEBSITE_URL))
        assertTrue(dataSafety.contains(AppLinks.PRIVACY_POLICY_URL))
        assertTrue(site.contains("SITE_URL = \"${AppLinks.WEBSITE_URL}\""))
        assertEquals(AppLinks.WEBSITE_URL + "privacy-policy.html", AppLinks.PRIVACY_POLICY_URL)
    }

    @Test
    fun theNoticesInTheAppMatchThirdPartyNotices() {
        val inApp = File("src/main/res/raw/third_party_notices.txt").readText()
        val doc = File("../THIRD_PARTY_NOTICES.md").readText()
        val components = Regex("^• (.+)$", RegexOption.MULTILINE).findAll(inApp).map { it.groupValues[1] }.toList()
        assertTrue(components.size >= 7)
        for (c in components) assertTrue("THIRD_PARTY_NOTICES.md misses: $c", doc.contains(c))
        assertNotNull(app.resources.openRawResource(R.raw.apache_license_2_0))
    }

    private fun String.withoutBidiMarks() = replace(Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]"), "")

    private fun screenshot(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/outputs/about-shots").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
