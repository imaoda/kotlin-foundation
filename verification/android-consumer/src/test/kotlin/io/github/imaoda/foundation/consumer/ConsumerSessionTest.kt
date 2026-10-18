package io.github.imaoda.foundation.consumer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConsumerSessionTest {
    @Test fun publicApiCanBeConsumedFromAndroidModule() {
        val session = ConsumerSession()
        session.increment()
        assertEquals(1, session.total)
        session.close()
        session.close()
        assertThrows(IllegalStateException::class.java) { session.increment() }
        assertEquals(1, session.total)
    }
}
