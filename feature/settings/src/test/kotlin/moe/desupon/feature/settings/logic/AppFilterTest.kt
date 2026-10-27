package moe.desupon.feature.settings.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class AppFilterTest {
    private val browser = InstalledApp("org.example.browser", "Browser", system = false)
    private val bank = InstalledApp("ru.example.bank", "банк", system = false)
    private val alpha = InstalledApp("com.example.alpha", "alpha", system = false)
    private val service = InstalledApp("com.vendor.security", "Security", system = true)
    private val apps = listOf(browser, bank, service, alpha)

    private fun packages(query: String = "", showSystem: Boolean = false, pinned: Set<String> = emptySet()) =
        AppFilter.apply(apps, query, showSystem, pinned).map { it.packageName }

    @Test
    fun hidesSystemAppsAndSortsByLabelIgnoringCase() {
        assertEquals(listOf(alpha, browser, bank).map { it.packageName }, packages())
    }

    @Test
    fun showsSystemAppsWhenEnabled() {
        assertEquals(listOf(alpha, browser, service, bank).map { it.packageName }, packages(showSystem = true))
    }

    @Test
    fun pinnedSystemAppStaysVisibleAndPinnedSortFirst() {
        assertEquals(
            listOf(service, bank, alpha, browser).map { it.packageName },
            packages(pinned = setOf(service.packageName, bank.packageName)),
        )
    }

    @Test
    fun searchMatchesLabelOrPackageCaseInsensitive() {
        assertEquals(listOf(bank.packageName), packages(query = "БАНК"))
        assertEquals(listOf(browser.packageName), packages(query = " org.EXAMPLE "))
        assertEquals(listOf(alpha, browser, bank).map { it.packageName }, packages(query = "example"))
        assertEquals(emptyList(), packages(query = "security"))
        assertEquals(listOf(service.packageName), packages(query = "security", showSystem = true))
    }
}
