import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.request.*
import io.ktor.http.HttpHeaders
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.pow

val dotenv = dotenv()

val SERVER_URL = dotenv["SERVER_URL"] ?: "http://localhost:8080/"
val filename = dotenv["FILENAME"] ?: "test.txt"

// Buffer size 8 KB
const val BUFFER_SIZE = 8 * 1024

// Chunk size 5 MB
const val CHUNK_SIZE = 5000000L

/**
 * Checks the server's capabilities for handling partial file downloads
 * by sending a HEAD request to the specified URL. Evaluates whether the
 * server supports range requests and determines the content length of the resource.
 *
 * @param targetUrl The URL of the target resource to check capabilities for.
 * @param httpClient The HttpClient instance used to perform the HTTP request.
 * @return A ServerInfo instance containing the content length and whether range requests are supported.
 * @throws IOException If the server responds with a non-successful status code.
 */
suspend fun checkCapabilities(targetUrl: String, httpClient: HttpClient): ServerInfo {
    val res: HttpResponse = httpClient.head(targetUrl)

    if (!res.status.isSuccess()) {
        throw IOException("Failed to fetch initial metadata. Server responded with ${res.status.value}")
    }

    val contentLengthHeader = res.headers[HttpHeaders.ContentLength]
        ?.toLongOrNull()
        ?: -1L

    val acceptRangesHeader = res.headers[HttpHeaders.AcceptRanges]

    val supportsRanges = contentLengthHeader > 0L
            && acceptRangesHeader?.contains("bytes") == true

    return ServerInfo(contentLengthHeader, supportsRanges)
}

/**
 * Downloads a file sequentially from the specified URL and saves it to the provided destination file.
 * The method reads the file in chunks and ensures safe file handling by using a temporary file during the process.
 *
 * @param client The HttpClient instance used to perform the HTTP request.
 * @param targetUrl The URL of the file to download.
 * @param destFile The destination file where the downloaded content will be saved.
 * @throws IOException If an error occurs during the download or if saving the file fails.
 */
suspend fun downloadFileSeq(
    client: HttpClient,
    targetUrl: String,
    destFile: File
) {
    // I first create a .tmp file to be able to clean up if some error occurs during the process.
    val tmpFile = File("${destFile.absolutePath}.tmp")

    withContext(Dispatchers.IO) {
        try {
            client.prepareGet(targetUrl).execute { res ->
                if (!res.status.isSuccess()) {
                    throw IOException("Server responded with ${res.status}")
                }

                val payloadChannel: ByteReadChannel = res.bodyAsChannel()
                val buffer = ByteBuffer.allocate(BUFFER_SIZE)

                FileOutputStream(tmpFile).channel.use { diskChannel ->
                    while (!payloadChannel.isClosedForRead) {
                        val bytesRead = payloadChannel.readAvailable(buffer)

                        if (bytesRead <= 0) break

                        buffer.flip()

                        while (buffer.hasRemaining()) {
                            diskChannel.write(buffer)
                        }

                        buffer.clear()
                    }
                }
            }

            if (!tmpFile.renameTo(destFile)) {
                throw IOException("Failed to rename .tmp file to target")
            }
        } catch (e: Exception) {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }

            throw e
        }
    }
}

/**
 * Calculates the exponential backoff time in milliseconds for a given number of retry attempts.
 *
 * The backoff time increases exponentially with the number of attempts to introduce
 * a delay between retries and avoid overwhelming the system.
 *
 * @param attempts The number of attempts made so far. Must be a non-negative integer.
 * @return The calculated backoff time in milliseconds as a long value.
 */
fun getExpBackoffTime(attempts: Int): Long {
    return (2.0.pow(attempts) * 1000).toLong()
}

/**
 * Downloads a file from the specified target URL to the destination file sequentially, with a retry mechanism.
 * The download will be retried up to the specified maximum number of attempts in case of failures.
 *
 * @param client The HttpClient instance used to perform the HTTP request.
 * @param targetUrl The URL of the file to download.
 * @param destFile The file where the downloaded content will be saved.
 * @param maxAttempts The maximum number of retry attempts in case of failures. Default is 3.
 * @throws IOException If the download fails after the specified number of retry attempts.
 */
suspend fun downloadFileSeqWithRetry(
    client: HttpClient,
    targetUrl: String,
    destFile: File,
    maxAttempts: Int = 3
) {
    // If downloading fails, I will retry maxAttempts times.
    var attempted = 0

    while (true) {
        try {
            downloadFileSeq(client, targetUrl, destFile)
            return
        } catch (e: Exception) {
            attempted++

            if (attempted >= maxAttempts) {
                throw IOException("Failed to download a file sequentially after $maxAttempts attempts.", e)
            }

            // Exponential backoff
            delay(getExpBackoffTime(attempted))
        }
    }
}

/**
 * Calculates ranges dividing a file into chunks
 * @param contentLength Size of the file
 * @param chunkSize Preferred size of a single chunk
 * @return List of ranges
 */
fun calculateRanges(contentLength: Long, chunkSize: Long): List<LongRange> {
    val ranges = mutableListOf<LongRange>()
    var start = 0L

    while (start < contentLength) {
        val end = minOf(start + chunkSize - 1, contentLength - 1)
        ranges.add(start..end)
        start += chunkSize
    }

    return ranges
}

/**
 * Downloads a specific byte range (chunk) of a file from a given URL and writes it to the provided file channel.
 * Retries the download on failure up to a specified maximum number of attempts with exponential backoff.
 *
 * @param client The HTTP client used to perform the download request.
 * @param targetUrl The URL of the file to download the chunk from.
 * @param diskChannel The file channel to which the downloaded chunk will be written.
 * @param range The byte range of the file to download.
 * @param maxAttempts The maximum number of retry attempts in case of failure. Defaults to 3.
 * @throws IOException If the download fails after reaching the maximum number of attempts, or if the HTTP response is invalid.
 */
suspend fun downloadChunk(
    client: HttpClient,
    targetUrl: String,
    diskChannel: FileChannel,
    range: LongRange,
    maxAttempts: Int = 3
) {
    var attempted = 0
    val buffer = ByteBuffer.allocate(BUFFER_SIZE)

    while (true) {
        try {
            client.prepareGet(targetUrl) {
                header(HttpHeaders.Range, "bytes=${range.first}-${range.last}")
            }.execute { res ->
                if (res.status != HttpStatusCode.PartialContent) {
                    throw IOException("Expected Partial Content, but got: ${res.status}")
                }

                val payloadChannel: ByteReadChannel = res.bodyAsChannel()


                var ptr = range.first

                while (!payloadChannel.isClosedForRead) {
                    val bytesRead = payloadChannel.readAvailable(buffer)
                    if (bytesRead <= 0) break

                    buffer.flip()

                    while (buffer.hasRemaining()) {
                        val bytesWritten = diskChannel.write(buffer, ptr)
                        ptr += bytesWritten
                    }

                    buffer.clear()
                }
            }

            return
        } catch (e: Exception) {
            attempted++

            if (attempted >= maxAttempts) {
                throw IOException("Chunk [${range.first}-${range.last}] failed after $attempted attempts.", e)
            }

            delay(getExpBackoffTime(attempted))
        }
    }
}

/**
 * Downloads a file from the specified URL in parallel using a given HTTP client.
 * Divides the file into chunks and downloads each chunk concurrently, writing to a temporary file
 * which is renamed to the destination file upon successful completion.
 *
 * If an error occurs during the download process, the temporary file is deleted.
 *
 * @param client The HTTP client used to perform the download.
 * @param targetUrl The URL of the file to download.
 * @param destFile The destination file where the downloaded content will be saved.
 * @param contentLength The length of the content to be downloaded, obtained from the server.
 */
suspend fun downloadFileParallel(
    client: HttpClient,
    targetUrl: String,
    destFile: File,
    contentLength: Long,
    chunkSize: Long = CHUNK_SIZE
) {
    // I first create a .tmp file to be able to clean up if some error occurs during the process.
    val tmpFile = File("${destFile.absolutePath}.tmp")

    val ranges = calculateRanges(contentLength, chunkSize)

    RandomAccessFile(tmpFile, "rw").use {
        it.setLength(contentLength)
    }

    withContext(Dispatchers.IO) {
        val connectionsSema = Semaphore(4)

        try {
            RandomAccessFile(tmpFile, "rw").channel.use { diskChannel ->
                val deferredChunks = ranges.map { range ->
                    async {
                        connectionsSema.withPermit {
                            downloadChunk(client, targetUrl, diskChannel, range)
                        }
                    }
                }

                deferredChunks.awaitAll()
            }

            if (!tmpFile.renameTo(destFile)) {
                throw IOException("Failed to rename .tmp file to target")
            }
        } catch (e: Exception) {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }

            throw e
        }
    }
}

suspend fun main() {
    val client = HttpClient(CIO)
    val targetUrl = SERVER_URL + filename

    val destFile = File("downloaded_file.txt")

    val (contentLength, supportsRanges) = checkCapabilities(targetUrl, client)

    // First, I handle a case where supportsRanges is false.
    // That means that I should try to download the file sequentially.
    // If the server does support partial downloading, I will use this feature.

    if (!supportsRanges) {
        println("Server does not support ranges. Downloading sequentially.")
        downloadFileSeqWithRetry(client, targetUrl, destFile)
    } else {
        println("Server does support ranges. Downloading in parallel.")
        downloadFileParallel(client, targetUrl, destFile, contentLength)
    }

    client.close()
}

