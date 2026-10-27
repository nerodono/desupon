package moe.desupon.feature.subscriptions.chain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.ui.SubsIcons
import moe.desupon.model.subscription.SubId
import moe.desupon.ui.theme.tabular
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val HEAD_KEY = "chain-head"
private const val REST_HEADER_KEY = "chain-rest-header"
private val BoxInset = 8.dp
private val BoxRadius = 20.dp

/** A subscription row of a priority box; [step] is 1-based in the chain and null in the rest. */
internal class ChainRowUi(
    val id: SubId,
    val step: Int?,
    val menu: ChainMenu,
    /** Apply to the ≡ handle: long-press starts the drag. */
    val handle: Modifier,
    val dragging: Boolean,
)

@Composable
internal fun rememberChainDragState(chain: List<SubId>, members: List<SubId>): ChainDragState {
    val state = remember { ChainDragState(chain, members) }
    SideEffect { state.sync(chain, members) }
    return state
}

@Composable
internal fun rememberChainReorderState(listState: LazyListState, drag: ChainDragState): ReorderableLazyListState {
    val haptics = LocalHapticFeedback.current
    return rememberReorderableLazyListState(listState) { from, to ->
        val crossed = drag.move(from.key, to.key)
        haptics.performHapticFeedback(if (crossed) HapticFeedbackType.Confirm else HapticFeedbackType.SegmentFrequentTick)
    }
}

/** The tinted box behind the head, chain rows and footer emitted by [chainBoxItems] into the same list. */
internal fun Modifier.chainBoxBackground(listState: LazyListState, drag: ChainDragState, fill: Color, border: Color): Modifier = drawBehind {
    val info = listState.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return@drawBehind
    val shift = -info.viewportStartOffset.toFloat()
    val radius = BoxRadius.toPx()
    val head = visible.firstOrNull { it.key == HEAD_KEY }
    val end = visible.firstOrNull { it.key == ChainRow.BOUNDARY_KEY }
    if (head == null && end == null) {
        val chainKeys = drag.chain.mapTo(HashSet()) { ChainRow.Sub(it).key }
        if (visible.none { it.key in chainKeys }) return@drawBehind
    }
    val top = head?.let { it.offset + shift } ?: -radius
    val bottom = end?.let { it.offset + it.size + shift } ?: (size.height + radius)
    val inset = BoxInset.toPx()
    val topLeft = Offset(inset, top)
    val boxSize = Size(size.width - 2 * inset, bottom - top)
    drawRoundRect(fill, topLeft, boxSize, CornerRadius(radius))
    drawRoundRect(border, topLeft, boxSize, CornerRadius(radius), style = Stroke(1.dp.toPx()))
}

/**
 * Head, chain rows, boundary footer, rest header and rest rows; position relative to the boundary decides chain membership.
 * [horizontalPadding] insets the rows when the list itself has none.
 */
internal fun LazyListScope.chainBoxItems(
    drag: ChainDragState,
    reorder: ReorderableLazyListState,
    haptics: HapticFeedback,
    restCurrent: Boolean,
    onDrop: (ChainOp) -> Unit,
    horizontalPadding: Dp = 0.dp,
    row: @Composable (ChainRowUi) -> Unit,
) {
    val rows = drag.rows
    val boundary = ChainList.boundaryIndex(rows)
    val chain = ChainList.chainOf(rows)
    val pad = Modifier.padding(horizontal = horizontalPadding)

    item(key = HEAD_KEY, contentType = HEAD_KEY) { ChainHead(pad) }
    itemsIndexed(rows.subList(0, boundary), key = { _, r -> r.key }, contentType = { _, _ -> "chain-sub" }) { i, r ->
        SubItem(reorder, haptics, drag, chain, r as ChainRow.Sub, i + 1, pad, onDrop, row)
    }
    item(key = ChainRow.BOUNDARY_KEY, contentType = ChainRow.BOUNDARY_KEY) {
        ReorderableItem(reorder, key = ChainRow.BOUNDARY_KEY, modifier = pad) { ChainFooter(empty = boundary == 0) }
    }
    item(key = REST_HEADER_KEY, contentType = REST_HEADER_KEY) {
        RestHeader(current = restCurrent, empty = boundary == rows.lastIndex, modifier = pad)
    }
    items(rows.subList(boundary + 1, rows.size), key = { it.key }, contentType = { "chain-sub" }) { r ->
        SubItem(reorder, haptics, drag, chain, r as ChainRow.Sub, null, pad, onDrop, row)
    }
}

@Composable
private fun ChainHead(modifier: Modifier) {
    Text(
        text = stringResource(R.string.subs_chain_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp),
    )
}

@Composable
private fun ChainFooter(empty: Boolean) {
    if (empty) {
        val dash = MaterialTheme.colorScheme.outline
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
                .drawBehind {
                    val r = 12.dp.toPx()
                    drawRoundRect(
                        color = dash,
                        cornerRadius = CornerRadius(r),
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
                    )
                }
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.subs_chain_drop),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Text(
            text = stringResource(R.string.subs_chain_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun RestHeader(current: Boolean, empty: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.subs_chain_rest),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (current) NowBadge()
        }
        if (empty) {
            Text(
                text = stringResource(R.string.subs_chain_rest_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun NowBadge() {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary) {
        Text(
            text = stringResource(R.string.subs_chain_now),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun StepNumber(step: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = step.toString(), style = MaterialTheme.typography.labelMedium.tabular)
        }
    }
}

@Composable
internal fun DragHandle(handle: Modifier) {
    Box(handle.size(width = 40.dp, height = 48.dp), contentAlignment = Alignment.Center) {
        Icon(
            SubsIcons.DragHandle,
            contentDescription = stringResource(R.string.subs_chain_drag_cd),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Menu equivalents of dragging; call inside a DropdownMenu. */
@Composable
internal fun ChainMenuItems(menu: ChainMenu, onOp: (ChainOp) -> Unit, close: () -> Unit) {
    @Composable
    fun Entry(label: Int, op: ChainOp?) {
        op ?: return
        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
            close()
            onOp(op)
        })
    }
    Entry(R.string.subs_chain_add, menu.add)
    Entry(R.string.subs_move_up, menu.up)
    Entry(R.string.subs_move_down, menu.down)
    Entry(R.string.subs_chain_remove, menu.remove)
}

@Composable
private fun LazyItemScope.SubItem(
    reorder: ReorderableLazyListState,
    haptics: HapticFeedback,
    drag: ChainDragState,
    chain: List<SubId>,
    r: ChainRow.Sub,
    step: Int?,
    modifier: Modifier,
    onDrop: (ChainOp) -> Unit,
    row: @Composable (ChainRowUi) -> Unit,
) {
    ReorderableItem(reorder, key = r.key, modifier = modifier) { dragging ->
        val handle = Modifier.longPressDraggableHandle(
            onDragStarted = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                drag.start(r.id)
            },
            onDragStopped = {
                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                drag.drop()?.let(onDrop)
            },
        )
        row(ChainRowUi(r.id, step, ChainMenu.of(chain, r.id), handle, dragging))
    }
}
