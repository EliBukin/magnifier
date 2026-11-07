package bukin.magnifier

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Backups hold the settings file only: never pictures, never the device-only state. */
class BackupRulesTest {

    private val expected = setOf("include sharedpref magnifier_settings.xml")

    private fun rules(file: String, section: String? = null): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$file"))
        val root: Element = if (section == null) {
            doc.documentElement
        } else {
            doc.getElementsByTagName(section).item(0) as Element
        }
        val result = mutableSetOf<String>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            result += "${node.tagName} ${node.getAttribute("domain")} ${node.getAttribute("path")}"
        }
        return result
    }

    @Test
    fun androidSixToElevenBacksUpSettingsOnly() {
        assertEquals(expected, rules("backup_rules.xml"))
    }

    @Test
    fun androidTwelveAndLaterBacksUpSettingsOnly() {
        assertEquals(expected, rules("data_extraction_rules.xml", "cloud-backup"))
        assertEquals(expected, rules("data_extraction_rules.xml", "device-transfer"))
    }

    @Test
    fun theBackedUpFileIsTheSettingsFile() {
        assertEquals(setOf("include sharedpref ${MagnifierSettings.FILE}.xml"), expected)
    }

    @Test
    fun noPictureFolderIsEverIncluded() {
        // Saved pictures live in MediaStore and shared ones in the cache: neither may be in a backup.
        for (rules in listOf(rules("backup_rules.xml"), rules("data_extraction_rules.xml", "cloud-backup"), rules("data_extraction_rules.xml", "device-transfer"))) {
            for (rule in rules) {
                assertEquals(false, rule.contains("external") || rule.contains("Pictures") || rule.contains("shared ") || rule.contains("root"))
            }
        }
    }

    @Test
    fun theDeviceOnlyStateIsAnotherFile() {
        assertEquals(false, expected.any { it.contains(MainActivity.STATE_PREFS) })
    }
}
