package moe.desupon.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.desupon.brain.port.PoolSelection
import moe.desupon.brain.port.SelectionPort
import moe.desupon.model.selection.LocationChoice

/** [base] as the connection sees it: any country while [simpleMode] is on, the stored choice left as it is. */
class SimpleModeSelection(base: SelectionPort, simpleMode: Flow<Boolean>) : SelectionPort {
    override val selection: Flow<PoolSelection> =
        combine(base.selection, simpleMode) { s, simple -> if (simple) s.copy(choice = LocationChoice.Any) else s }.distinctUntilChanged()
}
