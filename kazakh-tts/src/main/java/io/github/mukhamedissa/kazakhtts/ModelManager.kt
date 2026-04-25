package io.github.mukhamedissa.kazakhtts

import android.content.Context
import android.content.res.AssetManager
import io.github.mukhamedissa.kazakhtts.config.ModelDefaults
import io.github.mukhamedissa.kazakhtts.logger.d
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Thrown when a downloaded archive's SHA-256 digest does not match the expected value.
 *
 * @param expected The hex digest declared in [ModelSource.Remote.sha256].
 * @param actual   The hex digest computed from the downloaded bytes.
 */
class ChecksumMismatchException(expected: String, actual: String) :
    Exception("SHA-256 mismatch: expected $expected, got $actual")

/**
 * Resolved absolute paths for a TTS model, ready to be passed to the sherpa-onnx engine.
 *
 * @param modelPath    Absolute path (or asset-relative path) to the `.onnx` model file.
 * @param tokensPath   Absolute path (or asset-relative path) to `tokens.txt`.
 * @param dataDirPath  Absolute path to the `espeak-ng-data` directory on disk.
 * @param assetManager Non-null only when paths are asset-relative ([ModelSource.Assets]).
 */
internal data class ResolvedPaths(
    val modelPath: String,
    val tokensPath: String,
    val dataDirPath: String,
    val assetManager: AssetManager?,
)

/**
 * Resolves a [ModelSource] into [ResolvedPaths] that the TTS engine can consume.
 *
 * - [ModelSource.Assets] — copies `espeak-ng-data` to disk on first use; model/tokens stay in assets.
 * - [ModelSource.Files]  — validates nothing; caller is responsible for file integrity.
 * - [ModelSource.Remote] — downloads and extracts the archive on first use; verified via SHA-256 if provided.
 *
 * All blocking I/O is dispatched on [Dispatchers.IO] internally.
 */
internal class ModelManager(private val context: Context) {

    private companion object {
        const val TAG = "ModelManager"
        const val FILES_ROOT_DIR = "kazakh-tts"
        const val READY_SENTINEL = ".ready"
        const val ARCHIVE_PART = "model.tar.bz2.part"
        const val BUFFER_SIZE = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }

    private val filesRoot = File(context.filesDir, FILES_ROOT_DIR)

    /**
     * Resolves [source] into [ResolvedPaths], performing any necessary I/O (copy / download / extract).
     *
     * @param source     Where the model files come from.
     * @param onProgress Optional download progress callback `(bytesDownloaded, totalBytes)`.
     *                   `totalBytes` may be `-1` if the server does not send `Content-Length`.
     */
    suspend fun resolve(
        source: ModelSource,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null,
    ): ResolvedPaths = withContext(Dispatchers.IO) {
        when (source) {
            is ModelSource.Assets -> resolveAssets(source)
            is ModelSource.Files -> resolveFiles(source.rootDir)
            is ModelSource.Remote -> resolveRemote(source, onProgress)
        }
    }

    private fun resolveAssets(source: ModelSource.Assets): ResolvedPaths {
        val espeakDataDir = File(filesRoot, "${source.rootPath}/${ModelDefaults.ESPEAK_DIR}")
        val readyFile = File(filesRoot, "${source.rootPath}/$READY_SENTINEL")

        if (!readyFile.exists()) {
            KazakhTts.logger.d(TAG, "First use: copying ${ModelDefaults.ESPEAK_DIR} from assets → $espeakDataDir")
            copyAssetFolder(
                assets = context.assets,
                assetPath = "${source.rootPath}/${ModelDefaults.ESPEAK_DIR}",
                destPath = espeakDataDir.absolutePath,
            )
            readyFile.parentFile?.mkdirs()
            readyFile.createNewFile()
            KazakhTts.logger.d(TAG, "Asset copy complete")
        }

        return ResolvedPaths(
            modelPath = "${source.rootPath}/${ModelDefaults.MODEL_FILE}",
            tokensPath = "${source.rootPath}/${ModelDefaults.TOKENS_FILE}",
            dataDirPath = espeakDataDir.absolutePath,
            assetManager = context.assets,
        )
    }

    private fun resolveFiles(rootDir: File): ResolvedPaths = ResolvedPaths(
        modelPath = rootDir.resolve(ModelDefaults.MODEL_FILE).absolutePath,
        tokensPath = rootDir.resolve(ModelDefaults.TOKENS_FILE).absolutePath,
        dataDirPath = rootDir.resolve(ModelDefaults.ESPEAK_DIR).absolutePath,
        assetManager = null,
    )

    private fun resolveRemote(
        source: ModelSource.Remote,
        onProgress: ((Long, Long) -> Unit)?,
    ): ResolvedPaths {
        val voiceDir = File(filesRoot, ModelDefaults.ASSET_ROOT)
        val readyFile = File(filesRoot, READY_SENTINEL)

        if (!readyFile.exists()) {
            filesRoot.mkdirs()
            val archiveFile = File(filesRoot, ARCHIVE_PART)

            KazakhTts.logger.d(TAG, "Downloading model from ${source.url}")
            downloadFile(source.url, archiveFile, source.sha256, onProgress)

            KazakhTts.logger.d(TAG, "Extracting archive → $filesRoot")
            extractTarBz2(archiveFile, destDir = filesRoot)

            archiveFile.delete()
            readyFile.createNewFile()
            KazakhTts.logger.d(TAG, "Remote model ready at $voiceDir")
        }

        return resolveFiles(voiceDir)
    }

    private fun downloadFile(
        url: String,
        destFile: File,
        expectedSha256: String?,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)?,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            val totalBytes = connection.contentLengthLong
            val digest = if (expectedSha256 != null)
                MessageDigest.getInstance("SHA-256")
            else
                null

            destFile.outputStream().use { fileOutput ->
                connection.inputStream.use { remoteInput ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesDownloaded = 0L
                    while (true) {
                        val bytesRead = remoteInput.read(buffer)
                        if (bytesRead < 0) break
                        fileOutput.write(buffer, 0, bytesRead)
                        digest?.update(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead
                        onProgress?.invoke(bytesDownloaded, totalBytes)
                    }
                }
            }

            if (expectedSha256 != null && digest != null) {
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    destFile.delete()
                    throw ChecksumMismatchException(expected = expectedSha256, actual = actualSha256)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTarBz2(archiveFile: File, destDir: File) {
        val canonicalDestPath = destDir.canonicalPath
        destDir.mkdirs()

        archiveFile.inputStream().buffered().use { fileInput ->
            BZip2CompressorInputStream(fileInput).use { bzip2Input ->
                TarArchiveInputStream(bzip2Input).use { tarInput ->
                    while (true) {
                        val entry = tarInput.nextEntry ?: break
                        if (!tarInput.canReadEntryData(entry)) continue

                        val entryFile = File(destDir, entry.name)
                        require(entryFile.canonicalPath.startsWith(canonicalDestPath)) {
                            "Zip-slip rejected: ${entry.name}"
                        }

                        if (entry.isDirectory) {
                            entryFile.mkdirs()
                        } else {
                            entryFile.parentFile?.mkdirs()
                            entryFile.outputStream().use { entryOutput -> tarInput.copyTo(entryOutput) }
                        }
                    }
                }
            }
        }
    }

    private fun copyAssetFolder(assets: AssetManager, assetPath: String, destPath: String) {
        val children = assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            File(destPath).parentFile?.mkdirs()
            assets.open(assetPath).use { assetInput ->
                File(destPath).outputStream().use { fileOutput -> assetInput.copyTo(fileOutput) }
            }
            return
        }
        File(destPath).mkdirs()
        for (child in children) copyAssetFolder(assets, "$assetPath/$child", "$destPath/$child")
    }
}