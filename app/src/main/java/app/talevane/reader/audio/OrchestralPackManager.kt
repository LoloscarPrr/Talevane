package app.talevane.reader.audio

import android.content.Context
import com.github.junrar.Junrar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class OrchestralPackSnapshot(
    val installed: Boolean,
    val sampleCount: Int,
    val sizeBytes: Long
)

data class OrchestralPackInstallResult(
    val sampleCount: Int,
    val sizeBytes: Long
)

/**
 * Optional offline orchestral pack. The base APK remains small and the user downloads the
 * official VSCO 2 CE 50-sample archive only when they want the richer score.
 */
object OrchestralPackManager {
    const val DOWNLOAD_SIZE_BYTES = 35_328_535L
    const val DOWNLOAD_SIZE_LABEL = "34 MB"
    const val SOURCE_LABEL = "VSCO 2 Community Edition · 50 muestras · CC0"

    private const val PACK_VERSION = 1
    private const val MIN_SAMPLE_COUNT = 40
    private const val ARCHIVE_URL =
        "https://s3.amazonaws.com/VersilianStudios/50OrchestralSamples.rar"
    private const val ARCHIVE_SHA256 =
        "d6a758250a277e60dbe4a58c8c219325bebfe7c20e1d5ed82b3e80f52ba775b3"
    private const val ROOT_DIRECTORY = "talevane-orchestra-v$PACK_VERSION"
    private const val MARKER_FILE = ".talevane-pack-v$PACK_VERSION"

    fun snapshot(context: Context): OrchestralPackSnapshot {
        val samples = sampleFiles(context)
        return OrchestralPackSnapshot(
            installed = marker(context).isFile && samples.size >= MIN_SAMPLE_COUNT,
            sampleCount = samples.size,
            sizeBytes = samples.sumOf { it.length() }
        )
    }

    fun isInstalled(context: Context): Boolean = snapshot(context).installed

    fun sampleFiles(context: Context): List<File> {
        val root = root(context)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            .toList()
    }

    suspend fun install(
        context: Context,
        onProgress: (Float) -> Unit = {}
    ): Result<OrchestralPackInstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            val parent = context.filesDir
            val archive = File(context.cacheDir, "vsco2-ce-50-v$PACK_VERSION.rar")
            val staging = File(parent, "$ROOT_DIRECTORY.staging")
            archive.delete()
            staging.deleteRecursively()
            check(staging.mkdirs()) { "No se pudo preparar la carpeta del pack." }

            try {
                downloadArchive(archive, onProgress)
                verifyArchive(archive)
                onProgress(0.94f)
                Junrar.extract(archive, staging)

                val extractedSamples = staging.walkTopDown()
                    .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
                    .toList()
                check(extractedSamples.size >= MIN_SAMPLE_COUNT) {
                    "El pack no contiene todas las muestras esperadas."
                }

                val destination = root(context)
                destination.deleteRecursively()
                if (!staging.renameTo(destination)) {
                    check(staging.copyRecursively(destination, overwrite = true)) {
                        "No se pudo guardar el pack orquestal."
                    }
                    staging.deleteRecursively()
                }
                marker(context).writeText("vsco2-ce-50\n$ARCHIVE_SHA256\n")
                onProgress(1f)

                val installed = sampleFiles(context)
                OrchestralPackInstallResult(
                    sampleCount = installed.size,
                    sizeBytes = installed.sumOf { it.length() }
                )
            } finally {
                archive.delete()
                staging.deleteRecursively()
            }
        }
    }

    suspend fun remove(context: Context) = withContext(Dispatchers.IO) {
        root(context).deleteRecursively()
    }

    private fun root(context: Context): File = File(context.filesDir, ROOT_DIRECTORY)
    private fun marker(context: Context): File = File(root(context), MARKER_FILE)

    private fun downloadArchive(file: File, onProgress: (Float) -> Unit) {
        val connection = (URL(ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Talevane/$PACK_VERSION")
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "El servidor del pack respondió ${connection.responseCode}."
            }
            val reportedLength = connection.contentLengthLong.takeIf { it > 0L }
                ?: DOWNLOAD_SIZE_BYTES
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            connection.inputStream.buffered().use { input ->
                FileOutputStream(file).buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        onProgress((received.toFloat() / reportedLength).coerceIn(0f, 1f) * 0.9f)
                    }
                }
            }
            check(received == DOWNLOAD_SIZE_BYTES) { "La descarga quedó incompleta." }
            val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualDigest == ARCHIVE_SHA256) { "La descarga no superó la verificación." }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyArchive(file: File) {
        check(file.isFile && file.length() == DOWNLOAD_SIZE_BYTES) {
            "No se pudo verificar el archivo descargado."
        }
    }
}
