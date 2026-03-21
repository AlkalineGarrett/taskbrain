package org.alkaline.taskbrain.dsl.ui

import androidx.compose.foundation.clickable
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.directives.DirectiveSegment
import org.alkaline.taskbrain.dsl.directives.DirectiveSegmenter
import android.util.Log
import org.alkaline.taskbrain.dsl.runtime.values.ButtonVal
import org.alkaline.taskbrain.dsl.runtime.values.ViewVal
import org.alkaline.taskbrain.data.Note
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

private const val TAG = "DirectiveLineRenderer"

/**
 * Button execution state for tracking button click progress.
 */
enum class ButtonExecutionState {
    IDLE,
    LOADING,
    SUCCESS,
    ERROR
}

/**
 * Renders line content with computed directive results replacing source text.
 * Computed directives are displayed in dashed boxes and are tappable.
 *
 * This component renders display-only content - no text editing.
 * Editing happens via DirectiveEditRow when a directive is tapped.
 *
 * @param sourceContent The original line content with directive source text
 * @param lineIndex The line number (0-indexed) - used for position-based directive keys
 * @param directiveResults Map of directive key to execution result (keys are position-based)
 * @param textStyle The text style for rendering
 * @param onDirectiveTap Called when a directive is tapped, with position-based key and source text
 * @param onViewNoteTap Called when a note within a view directive is tapped for inline editing.
 *        Parameters: (directiveKey, noteId, noteContent) - note content is the rendered content
 * @param onViewEditDirective Called when the edit button on a view directive is tapped.
 *        Parameters: (directiveKey, sourceText) - opens the directive editor overlay
 * @param onButtonClick Called when a button directive is clicked.
 *        Parameters: (directiveKey, buttonVal) - executes the button's action lambda
 * @param buttonExecutionStates Map of directive key to current execution state (for loading/success/error display)
 * @param modifier Modifier for the root composable
 */
@Composable
fun DirectiveLineContent(
    sourceContent: String,
    lineIndex: Int,
    directiveResults: Map<String, DirectiveResult>,
    textStyle: TextStyle,
    onDirectiveTap: (directiveKey: String, sourceText: String) -> Unit,
    onViewNoteTap: ((directiveKey: String, noteId: String, noteContent: String) -> Unit)? = null,
    onViewEditDirective: ((directiveKey: String, sourceText: String) -> Unit)? = null,
    onButtonClick: ((directiveKey: String, buttonVal: ButtonVal, sourceText: String) -> Unit)? = null,
    buttonExecutionStates: Map<String, ButtonExecutionState> = emptyMap(),
    modifier: Modifier = Modifier
) {
    // Build display text with directive results replacing source
    val displayResult = remember(sourceContent, lineIndex, directiveResults) {
        DirectiveSegmenter.buildDisplayText(sourceContent, lineIndex, directiveResults)
    }

    if (displayResult.directiveDisplayRanges.isEmpty()) {
        // No directives - render as plain text
        BasicText(
            text = displayResult.displayText,
            style = textStyle,
            modifier = modifier
        )
        return
    }

    // Check if this line contains a view directive with multi-line content
    val hasMultiLineView = displayResult.directiveDisplayRanges.any { range ->
        range.isView && range.displayText.contains('\n')
    }

    Log.d(TAG, "DirectiveLineContent: hasMultiLineView=$hasMultiLineView, rangesCount=${displayResult.directiveDisplayRanges.size}")
    displayResult.directiveDisplayRanges.forEach { range ->
        Log.d(TAG, "  Range: key=${range.key}, isView=${range.isView}, isButton=${range.isButton}, displayTextLength=${range.displayText.length}, hasNewline=${range.displayText.contains('\n')}")
    }

    if (hasMultiLineView) {
        // Use Column layout for multi-line view content
        Log.d(TAG, "DirectiveLineContent: Using Column layout for multi-line view")
        Column(modifier = modifier.fillMaxWidth()) {
            for (segment in displayResult.segments) {
                when (segment) {
                    is DirectiveSegment.Text -> {
                        if (segment.content.isNotEmpty()) {
                            BasicText(
                                text = segment.content,
                                style = textStyle
                            )
                        }
                    }
                    is DirectiveSegment.Directive -> {
                        val directiveRange = displayResult.directiveDisplayRanges.find { it.key == segment.key }
                        val isView = directiveRange?.isView ?: false
                        val isButton = directiveRange?.isButton ?: false
                        val isAlarm = directiveRange?.isAlarm ?: false

                        Log.d(TAG, "Directive render: key=${segment.key}, isButton=$isButton, isView=$isView, isAlarm=$isAlarm, isComputed=${segment.isComputed}, hasError=${segment.result?.error != null}")

                        when {
                            isAlarm && segment.isComputed && segment.result?.error == null -> {
                                // Alarm directives render as plain text (⏰)
                                Text(
                                    text = segment.displayText,
                                    style = textStyle
                                )
                            }
                            isView && segment.isComputed && segment.result?.error == null -> {
                                // Extract ViewVal from result to get notes
                                val viewVal = segment.result?.toValue() as? ViewVal
                                ViewDirectiveContent(
                                    viewVal = viewVal,
                                    displayText = segment.displayText,
                                    textStyle = textStyle,
                                    onNoteTap = { noteId, noteContent ->
                                        onViewNoteTap?.invoke(segment.key, noteId, noteContent)
                                    },
                                    onEditDirective = {
                                        onViewEditDirective?.invoke(segment.key, segment.sourceText)
                                            ?: onDirectiveTap(segment.key, segment.sourceText)
                                    }
                                )
                            }
                            isButton && segment.isComputed && segment.result?.error == null -> {
                                // Extract ButtonVal from result
                                val buttonVal = segment.result?.toValue() as? ButtonVal
                                Log.d(TAG, "Button branch taken: key=${segment.key}, buttonVal=${buttonVal?.javaClass?.simpleName}")
                                if (buttonVal != null) {
                                    ButtonDirectiveContent(
                                        buttonVal = buttonVal,
                                        executionState = buttonExecutionStates[segment.key] ?: ButtonExecutionState.IDLE,
                                        onButtonClick = { onButtonClick?.invoke(segment.key, buttonVal, segment.sourceText) },
                                        onEditDirective = { onDirectiveTap(segment.key, segment.sourceText) }
                                    )
                                } else {
                                    DirectiveResultBox(
                                        displayText = segment.displayText,
                                        isComputed = segment.isComputed,
                                        hasError = segment.result?.error != null,
                                        textStyle = textStyle,
                                        onTap = { onDirectiveTap(segment.key, segment.sourceText) }
                                    )
                                }
                            }
                            else -> {
                                DirectiveResultBox(
                                    displayText = segment.displayText,
                                    isComputed = segment.isComputed,
                                    hasError = segment.result?.error != null,
                                    textStyle = textStyle,
                                    onTap = { onDirectiveTap(segment.key, segment.sourceText) }
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Use Row layout for single-line content (original behavior)
        Row(
            modifier = modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (segment in displayResult.segments) {
                when (segment) {
                    is DirectiveSegment.Text -> {
                        BasicText(
                            text = segment.content,
                            style = textStyle
                        )
                    }
                    is DirectiveSegment.Directive -> {
                        val directiveRange = displayResult.directiveDisplayRanges.find { it.key == segment.key }
                        val isView = directiveRange?.isView ?: false
                        val isButton = directiveRange?.isButton ?: false
                        val isAlarm = directiveRange?.isAlarm ?: false

                        Log.d(TAG, "Directive render: key=${segment.key}, isButton=$isButton, isView=$isView, isAlarm=$isAlarm, isComputed=${segment.isComputed}, hasError=${segment.result?.error != null}")

                        when {
                            isAlarm && segment.isComputed && segment.result?.error == null -> {
                                // Alarm directives render as plain text (⏰)
                                Text(
                                    text = segment.displayText,
                                    style = textStyle
                                )
                            }
                            isView && segment.isComputed && segment.result?.error == null -> {
                                // Extract ViewVal from result to get notes
                                val viewVal = segment.result?.toValue() as? ViewVal
                                ViewDirectiveContent(
                                    viewVal = viewVal,
                                    displayText = segment.displayText,
                                    textStyle = textStyle,
                                    onNoteTap = { noteId, noteContent ->
                                        onViewNoteTap?.invoke(segment.key, noteId, noteContent)
                                    },
                                    onEditDirective = {
                                        onViewEditDirective?.invoke(segment.key, segment.sourceText)
                                            ?: onDirectiveTap(segment.key, segment.sourceText)
                                    }
                                )
                            }
                            isButton && segment.isComputed && segment.result?.error == null -> {
                                // Extract ButtonVal from result
                                val buttonVal = segment.result?.toValue() as? ButtonVal
                                Log.d(TAG, "Button branch taken: key=${segment.key}, buttonVal=${buttonVal?.javaClass?.simpleName}")
                                if (buttonVal != null) {
                                    ButtonDirectiveContent(
                                        buttonVal = buttonVal,
                                        executionState = buttonExecutionStates[segment.key] ?: ButtonExecutionState.IDLE,
                                        onButtonClick = { onButtonClick?.invoke(segment.key, buttonVal, segment.sourceText) },
                                        onEditDirective = { onDirectiveTap(segment.key, segment.sourceText) }
                                    )
                                } else {
                                    DirectiveResultBox(
                                        displayText = segment.displayText,
                                        isComputed = segment.isComputed,
                                        hasError = segment.result?.error != null,
                                        textStyle = textStyle,
                                        onTap = { onDirectiveTap(segment.key, segment.sourceText) }
                                    )
                                }
                            }
                            else -> {
                                DirectiveResultBox(
                                    displayText = segment.displayText,
                                    isComputed = segment.isComputed,
                                    hasError = segment.result?.error != null,
                                    textStyle = textStyle,
                                    onTap = { onDirectiveTap(segment.key, segment.sourceText) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Layout constants for view directive
private val ViewEditButtonSize = 24.dp
private val ViewEditIconSize = 16.dp

/**
 * Content from a view directive, rendered inline without a box.
 * Shows the viewed notes' content with a subtle left border indicator.
 * Supports multi-line content from viewed notes.
 *
 * Features:
 * - Edit button at top-right to open directive editor overlay
 * - Each note section is independently tappable for inline editing
 * - Notes are separated by "---" dividers (non-editable)
 *
 * Milestone 10: Initial view functionality
 * Phase 1: Added edit button and note tap callbacks
 */
@Composable
private fun ViewDirectiveContent(
    viewVal: ViewVal?,
    displayText: String,
    textStyle: TextStyle,
    onNoteTap: (noteId: String, noteContent: String) -> Unit,
    onEditDirective: () -> Unit
) {
    val notes = viewVal?.notes ?: emptyList()
    val renderedContents = viewVal?.renderedContents

    Log.d(TAG, "ViewDirectiveContent: notes.size=${notes.size}, renderedContents.size=${renderedContents?.size}")
    notes.forEachIndexed { index, note ->
        val content = renderedContents?.getOrNull(index) ?: note.content
        Log.d(TAG, "  Note[$index]: id=${note.id}, contentPreview='${content.take(50)}...'")
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .viewIndicator(DirectiveColors.ViewIndicator)
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
    ) {
        // Edit directive button at top-right
        IconButton(
            onClick = onEditDirective,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(ViewEditButtonSize)
                .padding(end = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Edit view directive",
                tint = DirectiveColors.ViewIndicator,
                modifier = Modifier.size(ViewEditIconSize)
            )
        }

        // Note content - either split by sections or as a single block
        if (notes.isEmpty()) {
            // Empty view - show placeholder
            Text(
                text = displayText,
                style = textStyle.copy(color = DirectiveColors.ViewIndicator),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = ViewEditButtonSize)
            )
        } else if (notes.size == 1) {
            // Single note - simple case
            val note = notes.first()
            val content = renderedContents?.firstOrNull() ?: note.content
            ViewNoteSection(
                note = note,
                content = content,
                textStyle = textStyle,
                onTap = { onNoteTap(note.id, content) },
                modifier = Modifier.padding(end = ViewEditButtonSize)
            )
        } else {
            // Multiple notes with separators
            Log.d(TAG, "ViewDirectiveContent: Rendering ${notes.size} notes in Column")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = ViewEditButtonSize)
            ) {
                notes.forEachIndexed { index, note ->
                    // Separator before each note except first
                    if (index > 0) {
                        Log.d(TAG, "  Rendering separator before note[$index]")
                        NoteSeparator()
                    }

                    // Note section
                    val content = renderedContents?.getOrNull(index) ?: note.content
                    Log.d(TAG, "  Rendering ViewNoteSection[$index]: contentLength=${content.length}")
                    ViewNoteSection(
                        note = note,
                        content = content,
                        textStyle = textStyle,
                        onTap = { onNoteTap(note.id, content) }
                    )
                }
            }
        }
    }
}

/**
 * A single note section within a view directive.
 * Tappable for inline editing.
 */
@Composable
private fun ViewNoteSection(
    note: Note,
    content: String,
    textStyle: TextStyle,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
    ) {
        SelectionContainer {
            Text(
                text = content,
                style = textStyle,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Visual separator between notes in a multi-note view.
 * Renders "---" with subtle styling.
 */
@Composable
private fun NoteSeparator() {
    Text(
        text = "---",
        style = TextStyle(color = DirectiveColors.ViewDivider),
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

// Layout constants for button directive
private val ButtonMinWidth = 80.dp
private val ButtonHeight = 32.dp
private val ButtonCornerRadius = 4.dp
private val ButtonIconSize = 16.dp

/**
 * A button directive rendered as an interactive button.
 * Shows the button label and executes the action when clicked.
 *
 * Features:
 * - Clickable button with label from ButtonVal
 * - Loading indicator while action executes
 * - Success/error state display after execution
 * - Settings icon to edit the directive source
 *
 * Button UI milestone.
 */
@Composable
private fun ButtonDirectiveContent(
    buttonVal: ButtonVal,
    executionState: ButtonExecutionState,
    onButtonClick: () -> Unit,
    onEditDirective: () -> Unit
) {
    val backgroundColor = when (executionState) {
        ButtonExecutionState.IDLE -> DirectiveColors.ButtonBackground
        ButtonExecutionState.LOADING -> DirectiveColors.ButtonLoadingBackground
        ButtonExecutionState.SUCCESS -> DirectiveColors.ButtonSuccessBackground
        ButtonExecutionState.ERROR -> DirectiveColors.ButtonErrorBackground
    }

    val isEnabled = executionState != ButtonExecutionState.LOADING

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The clickable button
        Box(
            modifier = Modifier
                .height(ButtonHeight)
                .background(
                    color = backgroundColor,
                    shape = RoundedCornerShape(ButtonCornerRadius)
                )
                .clickable(enabled = isEnabled) { onButtonClick() }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            when (executionState) {
                ButtonExecutionState.LOADING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonIconSize),
                        color = DirectiveColors.ButtonContent,
                        strokeWidth = 2.dp
                    )
                }
                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = DirectiveColors.ButtonContent,
                            modifier = Modifier.size(ButtonIconSize)
                        )
                        Text(
                            text = buttonVal.label,
                            color = DirectiveColors.ButtonContent,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }

        // Settings icon to edit the directive
        IconButton(
            onClick = onEditDirective,
            modifier = Modifier
                .size(ViewEditButtonSize)
                .padding(start = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Edit button directive",
                tint = DirectiveColors.ViewIndicator,
                modifier = Modifier.size(ViewEditIconSize)
            )
        }
    }
}

/**
 * A directive result displayed in a dashed box.
 * Shows the computed result (or source if not computed).
 * Empty results show a vertical dashed line placeholder.
 */
@Composable
private fun DirectiveResultBox(
    displayText: String,
    isComputed: Boolean,
    hasError: Boolean,
    textStyle: TextStyle,
    onTap: () -> Unit
) {
    val boxColor = if (hasError) DirectiveColors.ErrorBorder else DirectiveColors.SuccessBorder

    val textColor = when {
        hasError -> DirectiveColors.ErrorText
        else -> textStyle.color
    }

    val isEmpty = displayText.isEmpty()

    Box(
        modifier = Modifier
            .clickable(onClick = onTap)
            .then(
                if (isEmpty) {
                    Modifier
                        .size(width = EmptyPlaceholderWidth, height = EmptyPlaceholderHeight)
                        .emptyResultPlaceholder(boxColor)
                } else {
                    Modifier
                        .dashedBorder(boxColor)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                }
            )
    ) {
        if (!isEmpty) {
            SelectionContainer {
                Text(
                    text = displayText,
                    style = textStyle.copy(color = textColor)
                )
            }
        }
    }
}

// Empty result placeholder dimensions
private val EmptyPlaceholderWidth = 12.dp
private val EmptyPlaceholderHeight = 16.dp

/**
 * Modifier that draws a dashed border around the content.
 */
private fun Modifier.dashedBorder(color: Color): Modifier = this.drawBehind {
    val strokeWidth = 1.dp.toPx()
    val dashLength = 4.dp.toPx()
    val gapLength = 2.dp.toPx()
    val cornerRadius = 3.dp.toPx()

    drawRoundRect(
        color = color,
        topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength, gapLength))
        )
    )
}

/**
 * Modifier for view directive content - draws a left border indicator.
 * This provides a subtle visual distinction for viewed content.
 *
 * Milestone 10.
 */
private fun Modifier.viewIndicator(color: Color): Modifier = this.drawBehind {
    val strokeWidth = 2.dp.toPx()

    // Draw solid left border
    drawLine(
        color = color,
        start = Offset(0f, 0f),
        end = Offset(0f, size.height),
        strokeWidth = strokeWidth
    )
}

/**
 * Modifier for empty result placeholder - draws a vertical dashed line.
 * This provides a tappable target when a directive evaluates to an empty string.
 */
private fun Modifier.emptyResultPlaceholder(color: Color): Modifier = this.drawBehind {
    val strokeWidth = 1.5.dp.toPx()
    val dashLength = 3.dp.toPx()
    val gapLength = 2.dp.toPx()
    val lineHeight = size.height

    // Draw vertical dashed line in the center
    val centerX = size.width / 2

    drawLine(
        color = color,
        start = Offset(centerX, 0f),
        end = Offset(centerX, lineHeight),
        strokeWidth = strokeWidth,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength, gapLength))
    )
}

