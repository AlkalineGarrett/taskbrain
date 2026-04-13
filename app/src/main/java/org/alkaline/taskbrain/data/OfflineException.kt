package org.alkaline.taskbrain.data

/**
 * Thrown when an operation requires network connectivity but the device is offline.
 */
class OfflineException(message: String) : Exception(message)
