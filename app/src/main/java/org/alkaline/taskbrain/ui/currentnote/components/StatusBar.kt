package org.alkaline.taskbrain.ui.currentnote.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.ui.Dimens
import org.alkaline.taskbrain.ui.components.ActionButtonBar
import org.alkaline.taskbrain.ui.currentnote.UnifiedSaveStatus

/**
 * A status bar showing save status, save button, undo/redo buttons, and overflow menu.
 */
@Composable
fun StatusBar(
    saveStatus: UnifiedSaveStatus,
    onSaveClick: () -> Unit,
    noteNeedsFix: Boolean = false,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndoClick: () -> Unit = {},
    onRedoClick: () -> Unit = {},
    isDeleted: Boolean = false,
    onDeleteClick: () -> Unit = {},
    onUndeleteClick: () -> Unit = {},
    showCompleted: Boolean = true,
    onShowCompletedToggle: () -> Unit = {},
    showOfflineIcon: Boolean
) {
    val isSaveEnabled = saveStatus is UnifiedSaveStatus.Dirty ||
        (noteNeedsFix && saveStatus !is UnifiedSaveStatus.Saving)
    val saveButtonText = when {
        saveStatus is UnifiedSaveStatus.Saving -> stringResource(R.string.status_saving)
        saveStatus is UnifiedSaveStatus.Saved -> stringResource(R.string.status_saved)
        noteNeedsFix && saveStatus !is UnifiedSaveStatus.Dirty -> stringResource(R.string.status_needs_fix)
        else -> stringResource(R.string.action_save)
    }
    val isSaved = !noteNeedsFix &&
        (saveStatus is UnifiedSaveStatus.Idle || saveStatus is UnifiedSaveStatus.Saved)
    val enabledButtonColor = if (noteNeedsFix) {
        colorResource(R.color.action_button_needs_fix_background)
    } else {
        colorResource(R.color.action_button_background)
    }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.action_delete_note_confirm_title)) },
            text = { Text(stringResource(R.string.action_delete_note_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteClick()
                }) {
                    Text(
                        stringResource(R.string.action_delete_note),
                        color = colorResource(R.color.menu_danger_text)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    ActionButtonBar {
        if (showOfflineIcon) {
            Icon(
                painter = painterResource(id = R.drawable.ic_cloud_off),
                contentDescription = null,
                tint = colorResource(R.color.status_unsaved_icon),
                modifier = Modifier.size(Dimens.StatusIconSize)
            )
            Spacer(modifier = Modifier.width(Dimens.StatusBarButtonIconTextSpacing))
        }

        Icon(
            painter = if (isSaved) painterResource(id = R.drawable.ic_check_circle) else painterResource(id = R.drawable.ic_warning),
            contentDescription = null,
            tint = if (isSaved) colorResource(R.color.status_saved_icon) else colorResource(R.color.status_unsaved_icon),
            modifier = Modifier.size(Dimens.StatusIconSize)
        )

        Spacer(modifier = Modifier.width(Dimens.StatusTextIconSpacing))

        Button(
            onClick = onSaveClick,
            enabled = isSaveEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = enabledButtonColor,
                contentColor = colorResource(R.color.action_button_text),
                disabledContainerColor = colorResource(R.color.action_button_disabled_background),
                disabledContentColor = colorResource(R.color.action_button_disabled_text)
            ),
            shape = RoundedCornerShape(Dimens.StatusBarButtonCornerRadius),
            contentPadding = PaddingValues(horizontal = Dimens.StatusBarButtonHorizontalPadding, vertical = 0.dp),
            modifier = Modifier.height(Dimens.StatusBarButtonHeight)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_save),
                contentDescription = stringResource(id = R.string.action_save),
                modifier = Modifier.size(Dimens.StatusBarButtonIconSize),
                tint = colorResource(if (isSaveEnabled) R.color.action_button_text else R.color.action_button_disabled_text)
            )
            Spacer(modifier = Modifier.width(Dimens.StatusBarButtonIconTextSpacing))
            Text(
                text = saveButtonText,
                fontSize = Dimens.StatusBarButtonTextSize
            )
        }

        // Flexible spacer pushes undo/redo to right
        Spacer(modifier = Modifier.weight(1f))

        // Undo button
        IconButton(
            onClick = onUndoClick,
            enabled = canUndo,
            modifier = Modifier.size(Dimens.StatusBarButtonHeight)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_undo),
                contentDescription = stringResource(id = R.string.action_undo),
                modifier = Modifier.size(Dimens.StatusBarButtonIconSize),
                tint = if (canUndo) colorResource(R.color.icon_default) else colorResource(R.color.icon_disabled)
            )
        }

        // Redo button
        IconButton(
            onClick = onRedoClick,
            enabled = canRedo,
            modifier = Modifier.size(Dimens.StatusBarButtonHeight)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_redo),
                contentDescription = stringResource(id = R.string.action_redo),
                modifier = Modifier.size(Dimens.StatusBarButtonIconSize),
                tint = if (canRedo) colorResource(R.color.icon_default) else colorResource(R.color.icon_disabled)
            )
        }

        // Overflow menu
        Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
            IconButton(
                onClick = { showMenu = !showMenu },
                modifier = Modifier.size(Dimens.StatusBarButtonHeight)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(id = R.string.action_more_options),
                    modifier = Modifier.size(Dimens.StatusBarButtonIconSize),
                    tint = colorResource(R.color.icon_default)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.action_show_completed)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(
                                id = if (showCompleted) R.drawable.ic_check_circle else R.drawable.ic_check_box_outline
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {
                        showMenu = false
                        onShowCompletedToggle()
                    }
                )
                if (isDeleted) {
                    DropdownMenuItem(
                        text = { Text(stringResource(id = R.string.action_restore_note)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_restore),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            showMenu = false
                            onUndeleteClick()
                        }
                    )
                } else {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(id = R.string.action_delete_note),
                                color = colorResource(R.color.menu_danger_text)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_delete),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = colorResource(R.color.menu_danger_text)
                            )
                        },
                        onClick = {
                            showMenu = false
                            showDeleteConfirm = true
                        }
                    )
                }
            }
        }
    }
}
