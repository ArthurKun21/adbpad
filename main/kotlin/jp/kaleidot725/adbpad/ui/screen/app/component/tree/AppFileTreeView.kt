package jp.kaleidot725.adbpad.ui.screen.app.component.tree

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import jp.kaleidot725.adbpad.domain.model.app.AppFileEntry
import jp.kaleidot725.adbpad.domain.model.language.Language
import jp.kaleidot725.adbpad.ui.component.menu.ThemedContextMenuArea
import jp.kaleidot725.adbpad.ui.screen.app.state.AppFileTreeState

@Composable
fun AppFileTreeView(
    root: AppFileEntry.Directory,
    tree: AppFileTreeState,
    selectedFile: AppFileEntry?,
    onSelectNode: (AppFileEntry) -> Unit,
    onPreviewNode: (AppFileEntry) -> Unit,
    onUploadNode: (AppFileEntry) -> Unit,
    onDeleteNode: (AppFileEntry) -> Unit,
    onRefreshNode: (AppFileEntry) -> Unit,
    onRenameNode: (AppFileEntry) -> Unit,
    onCreateDirectoryNode: (AppFileEntry.Directory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            tree.isLoading && tree.entries.isEmpty() -> AppFileTreeLoadingRow()
            tree.errorMessage != null -> {
                AppFileTreeRootMessageRow(
                    root = root,
                    message = tree.errorMessage.ifBlank { Language.appFileTreeEmpty },
                    onCreateDirectoryNode = onCreateDirectoryNode,
                )
            }
            tree.entries.isEmpty() -> {
                AppFileTreeRootMessageRow(
                    root = root,
                    message = Language.appFileTreeEmpty,
                    onCreateDirectoryNode = onCreateDirectoryNode,
                )
            }
            else -> {
                tree.entries.forEach { entry ->
                    AppFileTreeNode(
                        entry = entry,
                        tree = tree,
                        depth = 0,
                        selectedFile = selectedFile,
                        onSelectNode = onSelectNode,
                        onPreviewNode = onPreviewNode,
                        onUploadNode = onUploadNode,
                        onDeleteNode = onDeleteNode,
                        onRefreshNode = onRefreshNode,
                        onRenameNode = onRenameNode,
                        onCreateDirectoryNode = onCreateDirectoryNode,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppFileTreeRootMessageRow(
    root: AppFileEntry.Directory,
    message: String,
    onCreateDirectoryNode: (AppFileEntry.Directory) -> Unit,
) {
    ThemedContextMenuArea(
        items = {
            listOf(
                ContextMenuItem(
                    label = Language.createDirectory,
                    onClick = { onCreateDirectoryNode(root) },
                ),
            )
        },
    ) {
        AppFileTreeMessageRow(message = message)
    }
}

@Preview
@Composable
private fun AppFileTreeViewPreview() {
    val directory = previewAppFileEntries.first() as AppFileEntry.Directory

    AppFileTreeView(
        root = directory,
        tree =
            AppFileTreeState(
                entries = previewAppFileEntries,
                expandedPaths = setOf(directory.path),
                childrenByPath = mapOf(directory.path to previewChildAppFileEntries),
            ),
        selectedFile = directory,
        onSelectNode = {},
        onPreviewNode = {},
        onUploadNode = {},
        onDeleteNode = {},
        onRefreshNode = {},
        onRenameNode = {},
        onCreateDirectoryNode = {},
        modifier = Modifier.width(280.dp).padding(16.dp),
    )
}
