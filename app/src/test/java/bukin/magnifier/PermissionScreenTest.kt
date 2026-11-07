package bukin.magnifier

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionScreenTest {

    @Test
    fun grantedShowsTheCameraWhateverElseIsTrue() {
        for (asked in listOf(false, true)) for (rationale in listOf(false, true)) {
            assertEquals(PermissionScreen.CAMERA, PermissionScreen.from(granted = true, askedBefore = asked, showRationale = rationale))
        }
    }

    @Test
    fun firstStartExplainsBeforeAsking() {
        assertEquals(PermissionScreen.EXPLAIN, PermissionScreen.from(granted = false, askedBefore = false, showRationale = false))
        // A rationale without a first ask (e.g. restored state) still explains first.
        assertEquals(PermissionScreen.EXPLAIN, PermissionScreen.from(granted = false, askedBefore = false, showRationale = true))
    }

    @Test
    fun deniedOnceOffersGrant() {
        val screen = PermissionScreen.from(granted = false, askedBefore = true, showRationale = true)
        assertEquals(PermissionScreen.DENIED, screen)
        assertEquals(R.string.permission_grant, screen.button)
    }

    @Test
    fun deniedForGoodOpensSettings() {
        val screen = PermissionScreen.from(granted = false, askedBefore = true, showRationale = false)
        assertEquals(PermissionScreen.BLOCKED, screen)
        assertEquals(R.string.permission_open_settings, screen.button)
        assertEquals(R.string.permission_blocked_body, screen.body)
    }

    @Test
    fun everyPermissionScreenHasText() {
        for (screen in PermissionScreen.values().filter { it != PermissionScreen.CAMERA }) {
            assert(screen.title != 0 && screen.body != 0 && screen.button != 0) { screen.name }
        }
    }
}
