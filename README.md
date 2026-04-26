# kazakh-tts-android

[![](https://jitpack.io/v/mukhamedissa/kazakh-tts-android.svg)](https://jitpack.io/#mukhamedissa/kazakh-tts-android)

An Android library for offline Kazakh text-to-speech synthesis, built on [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) and the [ISSAI KazakhTTS](https://github.com/IS2AI/Kazakh_TTS) VITS model.

- 6 speaker voices, 22050 Hz output
- Model loads from a remote URL, bundled assets, or a local directory
- Pluggable logger (Timber, no-op, or custom)
- Min SDK 26; arm64-v8a, armeabi-v7a, x86_64

## How it works

The library uses the `kk_KZ-issai-high` ONNX model via sherpa-onnx's native runtime. On first use, the model archive is downloaded from [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) and extracted to the app's private files directory. Subsequent runs skip the download.

Synthesis runs on a background thread. Generated PCM samples are written to an `AudioTrack` and played back immediately.

## Installation

In your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")

        // Needed to resolve the sherpa-onnx transitive dependency.
        exclusiveContent {
            forRepository {
                ivy {
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/")
                    patternLayout { artifact("v[revision]/[artifact]-[revision].aar") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.k2fsa.sherpa.onnx", "sherpa-onnx") }
        }
    }
}
```

In your module `build.gradle.kts`:

```kotlin
implementation("io.github.mukhamedissa:kazakh-tts-android:1.0.0") {
    exclude(group = "com.k2fsa.sherpa.onnx", module = "sherpa-onnx")
}
implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:1.12.40") {
    artifact { type = "aar" }
}
```

The exclude is necessary because the Ivy repository's synthetic metadata types the artifact as `jar`, which overrides the `aar` type declared in the library's POM and breaks the Android AAR transform. Excluding the transitive dependency and redeclaring it directly with `artifact { type = "aar" }` bypasses this.

If you use `ModelSource.Remote` (the default), add the internet permission to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Usage

```kotlin
import io.github.mukhamedissa.kazakhtts.KazakhTts
import io.github.mukhamedissa.kazakhtts.player.KazakhTtsPlayer

val tts = KazakhTts.create(context)

val player = KazakhTtsPlayer(tts.sampleRate)
player.init()

tts.generate("Сәлем! Бұл қазақ тілінің синтезаторы.") { samples ->
    player.write(samples)
}

player.drainAndRelease()
tts.release()
```

See the [sample app](sample/) for a complete ViewModel-based example.

### Speaker and speed

```kotlin
tts.generate(
    text = "Сәлеметсіз бе!",
    speakerId = 2,    // 0-5, default 0
    speed = 1.2f,     // 0.5-2.0, default 1.0
) { samples -> player.write(samples) }
```

## Configuration

```kotlin
val tts = KazakhTts.create(
    context = context,
    config = KazakhTtsConfig(
        source = ModelSource.Remote(),
        numThreads = 2,          // CPU threads for inference
        maxNumSentences = 100,   // input is split into sentences up to this limit
        silenceScale = 0.2f,     // inter-sentence pause, 0.0-1.0
    ),
    onProgress = { downloaded, total -> /* update UI */ },
)
```

## Model sources

| Source | Behaviour |
|--------|-----------|
| `ModelSource.Remote()` | Downloads on first use, cached in app's private files directory (default) |
| `ModelSource.Assets(rootPath)` | Reads from APK assets, no network needed, adds ~65 MB to APK size |
| `ModelSource.Files(rootDir)` | Reads from an existing directory on disk |

### Bundling in assets

Place the model under `src/main/assets/` with this structure:

```
assets/
└── vits-piper-kk_KZ-issai-high/
    ├── kk_KZ-issai-high.onnx
    ├── tokens.txt
    └── espeak-ng-data/
```

Then use `ModelSource.Assets()` in `KazakhTtsConfig`.

## Logging

Logs go to Logcat by default. To disable:

```kotlin
KazakhTts.logger = Logger.NONE
```

To route to [Timber](https://github.com/JakeWharton/timber):

```kotlin
KazakhTts.logger = Logger { priority, tag, message, throwable ->
    Timber.tag(tag).log(priority, throwable, message)
}
```

## Attribution

The TTS model is created by the **Institute of Smart Systems and Artificial Intelligence (ISSAI)** at Nazarbayev University, Kazakhstan, published as [Kazakh_TTS](https://github.com/IS2AI/Kazakh_TTS). The ONNX version is distributed via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

## License

Library code: Apache 2.0, see [LICENSE](LICENSE).

The `kk_KZ-issai-high.onnx` model is licensed under CC BY 4.0 by ISSAI, Nazarbayev University. Apps using this library must include attribution to ISSAI. The model must not be used to generate obscene, offensive, or discriminatory content.

| Component | License |
|-----------|---------|
| This library | Apache-2.0 |
| KazakhTTS model | CC BY 4.0 (ISSAI, Nazarbayev University) |
| sherpa-onnx | Apache-2.0 |
| Apache Commons Compress | Apache-2.0 |
