package dev.thorreader.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Space after the last row, so it is fully reachable and not glued to the screen edge. */
val ListBottomPadding = 24.dp

/**
 * THE list used by every library list (flat sorts, Author/Series/Genre/Type groups, the books
 * inside a group, Completed, the Add author/series picker): it fills ALL the space its parent
 * gives it (pass `Modifier.weight(1f)` inside a Column) and scrolls smoothly. A row that gets
 * d-pad focus is scrolled into view by Compose itself.
 *
 * Never give a list `weight(1f, fill = false)` next to another weighted sibling, or a fixed
 * `heightIn(max = …)`: that is what cut the grouped lists to half the screen (see LayoutRulesTest).
 */
@Composable
fun FillList(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(bottom = ListBottomPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}
