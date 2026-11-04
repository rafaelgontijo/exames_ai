package br.com.exames.inferenceexperimental

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.exames.inferencelocal.RuntimeSampler
import br.com.exames.domain.validation.*
import br.com.exames.domain.inference.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeMetadataTest {
    @Test fun numericSamplingIsMonotonicAndNeverFabricatesTokenChannelsOrAModelAttempt() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        RuntimeSampler(context).use { sampler ->
            val first=sampler.snapshot(null,null,null)
            Thread.sleep(120)
            val last=sampler.snapshot(null,null,null)
            assertTrue(last.elapsedMillis>=first.elapsedMillis)
            assertTrue(last.sampledMaxPssKiB>0 && last.totalRamBytes>0 && last.availableBytesBefore>=0)
            assertEquals(android.os.Process.myPid(),last.processId)
            assertNull(last.thoughtTokens);assertNull(last.finalTokens)
            try { PageInferenceAttempt(ProposalDisposition.NOT_REQUESTED,InferenceOutcome.NOT_ATTEMPTED,measurements=last);fail("Metrics without request accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
