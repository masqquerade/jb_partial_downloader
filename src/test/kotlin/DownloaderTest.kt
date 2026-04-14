import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DownloaderTest {
    @Test
    @DisplayName("Parallel download writes chunks to the correct file positions")
    fun testDummyParallelDownload() = runTest {
        val mockEngine = MockEngine { req ->
            val rangeHeader = req.headers[HttpHeaders.Range]
            assertNotNull(rangeHeader, "The client did not send the range header.")
            assertTrue(rangeHeader!!.startsWith("bytes="), "The range header is formatted incorrectly.")

            respond(
                content = ByteReadChannel("Jet".toByteArray()),
                status = HttpStatusCode.PartialContent
            )
        }

        val client = HttpClient(mockEngine)
        val targetFile = File.createTempFile("test", ".txt")

        downloadFileParallel(
            client,
            targetUrl = "",
            destFile = targetFile,
            contentLength = 9L,
            chunkSize = 3L
        )

        val content = targetFile.readText()

        assertEquals("JetJetJet", content)

        client.close()
        targetFile.delete()
    }

    @Test
    @DisplayName("Parallel download aborts if server rejects to send one specific chunk")
    fun testParallelDownloadChunkRejected() = runTest {
        val mockEngine = MockEngine { req ->
            val range = req.headers[HttpHeaders.Range]

            if (range == "bytes=3-5") {
                throw IOException("Imitate connection lost")
            }

            respond(
                content = ByteReadChannel("Jet".toByteArray()),
                status = HttpStatusCode.PartialContent
            )
        }

        val client = HttpClient(mockEngine)
        val targetFile = File.createTempFile("test", ".txt")
        val tmpFile = File("${targetFile.absolutePath}.tmp")

        assertThrows<IOException>("IOException should be thrown.") {
            downloadFileParallel(
                client,
                targetUrl = "",
                destFile = targetFile,
                contentLength = 9L,
                chunkSize = 3L
            )
        }

        assertFalse(tmpFile.exists(), "Tmp file should be deleted.")

        client.close()
        targetFile.delete()
    }
}