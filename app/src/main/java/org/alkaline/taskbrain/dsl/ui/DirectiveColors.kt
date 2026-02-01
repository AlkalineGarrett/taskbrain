package org.alkaline.taskbrain.dsl.ui

import androidx.compose.ui.graphics.Color

/**
 * Centralized color definitions for directive UI components.
 *
 * These colors are used across DirectiveChip, DirectiveLineRenderer, and DirectiveEditRow.
 */
object DirectiveColors {
    // Success/computed state - green palette
    val SuccessBackground = Color(0xFFE8F5E9)   // Light green (chip background)
    val SuccessContent = Color(0xFF2E7D32)      // Dark green (chip text)
    val SuccessBorder = Color(0xFF4CAF50)       // Medium green (box borders, confirm button)

    // Error state - red palette
    val ErrorBackground = Color(0xFFFFEBEE)     // Light red (chip background)
    val ErrorContent = Color(0xFFC62828)        // Dark red (chip text)
    val ErrorBorder = Color(0xFFF44336)         // Medium red (box borders)
    val ErrorText = Color(0xFFD32F2F)           // Dark red (inline error text)

    // Warning state - orange palette (Milestone 8)
    val WarningBackground = Color(0xFFFFF3E0)   // Light orange (chip background)
    val WarningContent = Color(0xFFE65100)      // Dark orange (chip text)
    val WarningBorder = Color(0xFFFF9800)       // Medium orange (box borders)
    val WarningText = Color(0xFFF57C00)         // Dark orange (inline warning text)

    // Edit row - neutral palette
    val EditRowBackground = Color(0xFFF5F5F5)   // Light gray
    val EditIndicator = Color(0xFF9E9E9E)       // Medium gray
    val CancelButton = Color(0xFF757575)        // Darker gray

    // Action buttons - blue palette
    val RefreshButton = Color(0xFF2196F3)       // Blue (refresh button)

    // Button directive - primary action blue palette
    val ButtonBackground = Color(0xFF1976D2)    // Primary blue (button background)
    val ButtonContent = Color(0xFFFFFFFF)       // White (button text)
    val ButtonBorder = Color(0xFF1565C0)        // Darker blue (button border)
    val ButtonLoadingBackground = Color(0xFF90CAF9) // Light blue (loading state)
    val ButtonSuccessBackground = Color(0xFF4CAF50) // Green (success flash)
    val ButtonErrorBackground = Color(0xFFF44336)   // Red (error state)

    // View directive - subtle indicator (Milestone 10)
    val ViewIndicator = Color(0xFFB0BEC5)       // Blue-gray (left border for views)
    val ViewDivider = Color(0xFFCFD8DC)         // Light blue-gray (divider between viewed notes)
}
