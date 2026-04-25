package io.github.mukhamedissa.kazakhtts

import io.github.mukhamedissa.kazakhtts.config.ModelDefaults
import java.io.File

/**
 * Describes where the TTS model files are sourced from.
 *
 * Three strategies are supported:
 * - [Assets] — bundled inside the APK (no network, instant first use)
 * - [Files] — already extracted on disk (e.g. copied by the app on a previous run)
 * - [Remote] — downloaded on first use from a remote URL
 *
 * All variants expect the same file layout:
 * ```
 * <root>/
 * ├── kk_KZ-issai-high.onnx
 * ├── tokens.txt
 * └── espeak-ng-data/
 * ```
 * File names are defined in [ModelDefaults].
 */
sealed interface ModelSource {

    /**
     * Model files are bundled in app assets under [rootPath].
     *
     * [ModelDefaults.ESPEAK_DIR] will be copied to `filesDir` on first use,
     * since the espeak-ng runtime requires files to be on disk.
     *
     * @param rootPath Path inside the assets folder. Defaults to [ModelDefaults.ASSET_ROOT].
     */
    data class Assets(
        val rootPath: String = ModelDefaults.ASSET_ROOT,
    ) : ModelSource

    /**
     * Model files are already extracted on disk at [rootDir].
     *
     * Use this when the app has previously unpacked the model, or when
     * files were sideloaded by the user.
     *
     * @param rootDir Absolute directory containing the model files.
     */
    data class Files(
        val rootDir: File,
    ) : ModelSource

    /**
     * Model files are downloaded from [url] as a `tar.bz2` archive and
     * extracted to `filesDir` on first use.
     *
     * @param url     Direct download URL. Defaults to the official sherpa-onnx release.
     * @param sha256  Optional SHA-256 hex digest to verify archive integrity before extraction.
     *                Pass `null` to skip verification.
     */
    data class Remote(
        val url: String = ModelDefaults.REMOTE_URL,
        val sha256: String? = null,
    ) : ModelSource
}
