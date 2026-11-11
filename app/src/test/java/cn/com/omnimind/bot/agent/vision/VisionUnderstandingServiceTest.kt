package cn.com.omnimind.bot.agent.vision

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class VisionUnderstandingServiceTest {

    @Test
    fun analyzeImageThrowsIllegalArgumentExceptionOnBlankImageData() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                VisionUnderstandingService.analyzeImage(
                    imageDataUrl = "   ",
                    prompt = "测试"
                )
            }
        }
    }

    @Test
    fun analyzeImagePropagatesCancellationExceptionImmediatelyWhenTurnJobIsCancelled() {
        val cancelledJob = Job().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                VisionUnderstandingService.analyzeImage(
                    imageDataUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==",
                    prompt = "测试",
                    turnJob = cancelledJob
                )
            }
        }
    }
}
