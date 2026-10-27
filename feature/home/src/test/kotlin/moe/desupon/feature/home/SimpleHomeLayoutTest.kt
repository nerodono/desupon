package moe.desupon.feature.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import moe.desupon.brain.FailReason
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.ConnState
import moe.desupon.brain.port.VpnControl
import moe.desupon.ui.theme.ConnectionButtonState
import moe.desupon.ui.theme.DesuponTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SMALL_PHONE = "w360dp-h640dp"

/** Native graphics, so text is measured with real fonts. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SMALL_PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SimpleHomeLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private var connects = 0
    private var disconnects = 0

    private val shown = mutableStateOf(SimpleUiState(ConnectionButtonState.Disconnected, SimpleStatus.TAP_TO_CONNECT, ButtonAction.CONNECT, false))

    /** Screen size and font scale come from each test's @Config. */
    private fun show(state: SimpleUiState, fontScale: Float = 1f) {
        shown.value = state
        var appliedScale = 0f
        rule.setContent {
            appliedScale = LocalDensity.current.fontScale
            DesuponTheme {
                Surface(Modifier.fillMaxSize()) {
                    SimpleContent(shown.value, onConnect = { connects++ }, onDisconnect = { disconnects++ }, onAdvanced = {}, modifier = Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
        assertEquals(fontScale, appliedScale)
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).bounds()

    /** Everything stacks top to bottom inside the window, the button keeps its round shape and no text is cut off. */
    private fun assertFits(expired: Boolean) {
        val root = rule.onRoot().bounds()
        assertEquals(with(rule.density) { 360f * density }, root.width, 1f, "not a 360 dp wide screen")
        val arrow = shown.value.status == SimpleStatus.TAP_TO_CONNECT
        assertEquals(arrow, rule.onAllNodesWithTag(SimpleTags.ARROW, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "arrow only while off")
        val tags = listOfNotNull(
            SimpleTags.LOGO, SimpleTags.BUTTON, SimpleTags.ARROW.takeIf { arrow }, SimpleTags.STATUS, SimpleTags.EXPIRED.takeIf { expired }, SimpleTags.ADVANCED,
        )
        val rects = tags.map(::bounds)
        for ((tag, r) in tags.zip(rects)) {
            assertTrue(r.left >= root.left && r.top >= root.top && r.right <= root.right && r.bottom <= root.bottom, "$tag $r outside $root")
            assertTrue(r.width > 0 && r.height > 0, "$tag is empty")
        }
        rects.zipWithNext().forEachIndexed { i, (a, b) -> assertTrue(a.bottom <= b.top, "${tags[i]} overlaps ${tags[i + 1]}") }

        val button = bounds(SimpleTags.BUTTON)
        assertEquals(button.width, button.height, 0.5f)
        val minButtonPx = with(rule.density) { 120f * density }
        assertTrue(button.width >= minButtonPx, "button shrank to ${button.width}px")

        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(texts.isNotEmpty())
        for (node in texts) {
            val layout = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            val text = layout.single().layoutInput.text
            val l = layout.single()
            // A wrap-content text is laid out at the max width, so didOverflowWidth flags it although every line fits.
            val widest = (0 until l.lineCount).maxOf { l.getLineRight(it) - l.getLineLeft(it) }
            val ellipsized = (0 until l.lineCount).any { l.isLineEllipsized(it) }
            assertFalse(l.didOverflowHeight || ellipsized || widest > l.size.width + 1f, "\"$text\" is clipped: widest=$widest size=${l.size} lines=${l.lineCount}")
            val r = node.boundsInRoot
            assertTrue(r.bottom <= root.bottom && r.right <= root.right, "\"$text\" leaves the window")
        }
    }

    private val worstCases = SimpleStatus.entries.map { status ->
        val button = when (status) {
            SimpleStatus.TAP_TO_CONNECT, SimpleStatus.NO_SERVERS -> ConnectionButtonState.Disconnected
            SimpleStatus.CHECKING -> ConnectionButtonState.Checking(listOf(true, false))
            SimpleStatus.CONNECTING -> ConnectionButtonState.Connecting
            SimpleStatus.SWITCHING -> ConnectionButtonState.Switching
            SimpleStatus.CONNECTED -> ConnectionButtonState.Connected
            SimpleStatus.RETRYING -> ConnectionButtonState.Error
        }
        val action = if (status == SimpleStatus.TAP_TO_CONNECT || status == SimpleStatus.NO_SERVERS) ButtonAction.CONNECT else ButtonAction.DISCONNECT
        SimpleUiState(button, status, action, expired = true)
    }

    private fun assertAllFit(fontScale: Float) {
        for (state in worstCases) {
            if (state == worstCases.first()) show(state, fontScale) else shown.value = state
            rule.waitForIdle()
            assertFits(expired = true)
        }
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun `fits at default font on a small phone`() = assertAllFit(1f)

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = 1.3f)
    fun `fits at font scale 1_3 on a small phone`() = assertAllFit(1.3f)

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = 2f)
    fun `fits at font scale 2 on a small phone`() = assertAllFit(2f)

    @Test
    @Config(qualifiers = "w360dp-h568dp", fontScale = 2f)
    fun `fits at font scale 2 below the system bars of a small phone`() = assertAllFit(2f)

    @Test
    fun `expired line is shown only when expired`() {
        show(SimpleUiState(ConnectionButtonState.Connected, SimpleStatus.CONNECTED, ButtonAction.DISCONNECT, expired = false))
        rule.onNodeWithText("Подключено").assertExists()
        assertTrue(rule.onAllNodesWithText("Истекла").fetchSemanticsNodes().isEmpty())
        assertFits(expired = false)
    }

    @Test
    fun `simple wording replaces the normal captions`() {
        show(SimpleUiState(ConnectionButtonState.Disconnected, SimpleStatus.TAP_TO_CONNECT, ButtonAction.CONNECT, expired = false))
        rule.onNodeWithText("Нажмите, чтобы включить").assertExists()
        assertTrue(rule.onAllNodesWithText("Нажмите для подключения").fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(qualifiers = "w360dp-h568dp", fontScale = 2f)
    fun `the advanced mode button stays reachable and well below the connect button at font scale 2`() {
        show(SimpleUiState(ConnectionButtonState.Disconnected, SimpleStatus.TAP_TO_CONNECT, ButtonAction.CONNECT, expired = true), 2f)
        assertFits(expired = true)
        val gap = bounds(SimpleTags.ADVANCED).top - bounds(SimpleTags.BUTTON).bottom
        assertTrue(gap >= with(rule.density) { 48f * density }, "only ${gap}px between the connect and advanced buttons")
        rule.onNodeWithText("Продвинутый режим").assertExists()
    }

    @Test
    fun `while off, tapping the hint or the arrow connects like the button`() {
        show(SimpleUiState(ConnectionButtonState.Disconnected, SimpleStatus.TAP_TO_CONNECT, ButtonAction.CONNECT, expired = false))
        rule.onNodeWithText("Нажмите, чтобы включить").performClick()
        assertEquals(1, connects)
        rule.onNodeWithTag(SimpleTags.ARROW).performClick()
        assertEquals(2, connects)
        rule.onNodeWithContentDescription("Подключение").performClick()
        assertEquals(3, connects)
        assertEquals(0, disconnects)
    }

    @Test
    fun `in any other state the hint is just text and the arrow is gone`() {
        for (state in worstCases.filter { it.status != SimpleStatus.TAP_TO_CONNECT }) {
            if (state == worstCases[1]) show(state) else shown.value = state
            rule.waitForIdle()
            assertTrue(rule.onAllNodesWithTag(SimpleTags.ARROW, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "${state.status}")
            rule.onNodeWithTag(SimpleTags.STATUS).performClick()
        }
        assertEquals(0, connects)
        assertEquals(0, disconnects)
    }

    @Test
    fun `no usable servers disables the button`() {
        show(SimpleUiState(ConnectionButtonState.Disconnected, SimpleStatus.NO_SERVERS, ButtonAction.CONNECT, expired = false))
        rule.onNodeWithText("Нет серверов").assertExists()
        rule.onNodeWithContentDescription("Подключение").assertIsNotEnabled()
    }
}

@RunWith(RobolectricTestRunner::class)
class SimpleHomeExitTest {
    private val DIALOG_TITLE = "Перейти в продвинутый режим?"

    @get:Rule
    val rule = createComposeRule()

    private class IdleVpn : VpnControl {
        override val state = MutableStateFlow<ConnState>(ConnState.Failed(FailReason.CORE))
        override val retrying = MutableStateFlow(true)

        override suspend fun connect() = Unit

        override suspend fun disconnect() = Unit
    }

    private var exits = 0

    private fun showScreen() {
        startKoin {
            modules(
                module {
                    viewModel {
                        SimpleHomeViewModel(IdleVpn(), flowOf(listOf(sub(1))), flowOf(emptyList()), object : Clock {
                            override fun nowMs() = NOW_MS
                            override fun elapsedMs() = 0L
                        })
                    }
                },
            )
        }
        rule.setContent { DesuponTheme { SimpleHomeScreen(onExit = { exits++ }, modifier = Modifier.fillMaxSize()) } }
        rule.onNodeWithText("Не получилось. Пробую ещё раз…").assertExists()
        assertTrue(rule.onAllNodesWithText(DIALOG_TITLE).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Продвинутый режим").performClick()
        rule.onNodeWithText(DIALOG_TITLE).assertExists()
    }

    @After
    fun tearDown() = stopKoin()

    @Test
    fun `confirming the dialog leaves simple mode`() {
        showScreen()
        rule.onNodeWithText("Перейти").performClick()
        assertEquals(1, exits)
        assertTrue(rule.onAllNodesWithText(DIALOG_TITLE).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `cancelling the dialog stays`() {
        showScreen()
        rule.onNodeWithText("Остаться").performClick()
        assertEquals(0, exits)
        assertTrue(rule.onAllNodesWithText(DIALOG_TITLE).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `dismissing the dialog with back stays`() {
        showScreen()
        Espresso.pressBack()
        assertEquals(0, exits)
        assertTrue(rule.onAllNodesWithText(DIALOG_TITLE).fetchSemanticsNodes().isEmpty())
    }
}
