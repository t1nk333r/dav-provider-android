package app.davkeep.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import app.davkeep.core.ErrorClass
import app.davkeep.core.ResponseEvidence
import app.davkeep.error.SyncErrorClassifierImpl

/**
 * What a server's refusal of a write means to the upload step.
 *
 * Step U carries on after a refusal it can do nothing about and ends the Collection after a failure
 * the next request would meet too, and the difference between those two is this class: class 10 is
 * neither terminal nor retryable, so one refused body does not stop the run learning what the server
 * has and does not raise a notification. A read keeps the class it had, where a rewritten response
 * is the honest answer.
 */
class RefusedWriteTest {

    private val classifier = SyncErrorClassifierImpl()

    private fun evidence(status: Int, method: String) = ResponseEvidence(
        httpStatus = status,
        locationHeader = null,
        wwwAuthenticate = null,
        contentType = null,
        body = null,
        requestMethod = method,
        certificateOffered = false,
    )

    @Test
    fun `a bare 403 on a PUT is the server refusing the write`() {
        val error = classifier.classify(evidence(status = 403, method = "PUT"))

        assertEquals(ErrorClass.METHOD_REFUSED, error.errorClass)
        assertFalse(error.errorClass.terminal)
        assertFalse(error.errorClass.retryable)
    }

    @Test
    fun `a status no rule names is the same refusal on a write`() {
        // 415 is the "I will not take this media type" answer a body-level refusal arrives as, and
        // it is not class 12: nothing between the app and the server rewrote a response.
        assertEquals(ErrorClass.METHOD_REFUSED, classifier.classify(evidence(status = 415, method = "DELETE")).errorClass)
    }

    @Test
    fun `a bare 403 on a read keeps the class it had`() {
        assertEquals(ErrorClass.PROXY_INTERFERENCE, classifier.classify(evidence(status = 403, method = "PROPFIND")).errorClass)
    }
}
