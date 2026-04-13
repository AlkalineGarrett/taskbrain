package org.alkaline.taskbrain.data

import org.junit.Assert.*
import org.junit.Test

class ConnectivityMonitorTest {

    @Test
    fun `isOnline defaults to true`() {
        assertTrue(ConnectivityMonitor.isOnline.value)
    }
}
