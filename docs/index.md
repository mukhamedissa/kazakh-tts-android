# How it works

What follows is the long version of what happens between `tts.generate("Сәлем")` and the speaker producing sound. It covers the pipeline, the model architecture in detail, audio fundamentals, and the runtime concerns specific to Android. It is not a code walkthrough.

## 1. The pipeline at a glance

```
"Сәлем"
   │
   ▼  text normalization
"sɑlem"
   │
   ▼  phonemization (espeak-ng)
[34, 12, 7, 19, 4]
   │
   ▼  VITS (ONNX, run by sherpa-onnx)
[0.012, -0.034, 0.008, …]   raw PCM float at 22050 Hz
   │
   ▼  AudioTrack
```

Every modern neural TTS system has roughly this shape. Older systems split the model side into two networks (acoustic model + vocoder). VITS collapses them into one. The interesting boxes are phonemization and the model. Everything before phonemization is rule-based string processing, and everything after the model is just shipping numbers to hardware.

## 2. Phonemes and why models need them

Models do not consume letters. They consume **phonemes**, abstract units of sound. The reason is that orthography (how a language is written) and phonology (how it is spoken) often disagree.

English is the worst offender. Consider `read`. As a present-tense verb it is `riːd`. As a past-tense verb it is `rɛd`. The letters are identical. A character-level model would have to learn this from context, which is hard with limited data. A phoneme-level model receives the disambiguated sequence directly and only has to learn how phonemes map to sound.

Kazakh is more transparent than English. Cyrillic Kazakh is mostly phonemic, meaning each letter usually maps to one sound. Even so, there are quirks:

- Vowel harmony. Kazakh has front and back vowels, and suffixes shift form to match the root. The phonemizer encodes this once at the phoneme level so the model does not have to learn it from spelling.
- The letter `и` represents `iy` or `ɯy` depending on context.
- `у` represents `uw` or `yw`. It is a vowel-glide pair, not a single vowel.
- Russian loanwords appear with their Russian spelling and need separate rules.

These transformations are exactly what `espeak-ng` does. It is a small open-source phonemizer (~500 KB native library, plus 5 MB of language data) that supports over 100 languages with hand-written rules. For Kazakh it produces an IPA-like sequence with stress markers and prosodic boundaries. The output is then mapped to integer IDs through `tokens.txt`, a per-model lookup table that defines which phoneme symbols the model understands.

A typical `tokens.txt` line looks like `s 12`, meaning the symbol `s` has ID 12. The vocabulary is usually 50 to 100 symbols. Phonemes the model was not trained on get mapped to a fallback token, which is why mixed-language text often sounds wrong.

espeak-ng accesses its data files via `fopen()` in native code, not through Android's asset manager. That is why this library copies `espeak-ng-data/` from APK assets into the app's private files directory on first launch. Without that copy, the phonemizer cannot read its rules and produces empty output.

## 3. VITS in detail

[VITS](https://arxiv.org/abs/2106.06103) (2021) stands for *Variational Inference with adversarial learning for end-to-end Text-to-Speech*. It is a single neural network that takes phoneme IDs and emits a waveform at inference time. There is no separate mel‑spectrogram stage or external vocoder in the production pipeline.

The model is a conditional variational autoencoder with three augmentations: a flow-based prior, an adversarial decoder, and a stochastic duration predictor.

```
training time:
                   text                      audio waveform
                    │                              │
           ┌────────▼────────┐         ┌───────────▼──────────┐
           │  text encoder   │         │  posterior encoder   │
           │  (Transformer)  │         │  (linear spec → z)   │
           └────────┬────────┘         └───────────┬──────────┘
                    │                              │
              prior │ μ_p, σ_p          posterior  │ z_q
                    │                              │
                    │  ◄── flow f_θ(z_q) ◄─────────┤
                    │                              │
                    ▼                              ▼
              align with MAS                  decode to waveform
                                              (HiFi-GAN)

inference time:
                   text
                    │
           ┌────────▼────────┐
           │  text encoder   │
           └────────┬────────┘
                    │
                    ▼
           sample z from prior
                    │
                    ▼
           inverse flow f_θ⁻¹
                    │
                    ▼
              HiFi-GAN decoder
                    │
                    ▼
                waveform
```

### 3.1 Text encoder

A small Transformer (typically 6 layers, 192-hidden, 2 attention heads). Its input is the phoneme ID sequence with positional encoding. Its output is a sequence of contextual embeddings, one per phoneme. The embeddings are projected to produce per-phoneme prior parameters `(μ_p, σ_p)`, the parameters of a Gaussian distribution over the latent variable `z` for each phoneme.

### 3.2 Posterior encoder (training only)

Takes the linear spectrogram of the ground-truth waveform (computed by STFT during training) and produces posterior parameters `(μ_q, σ_q)`. The posterior `z` is sampled as `z_q = μ_q + σ_q · ε` with `ε ~ N(0, I)`.

This branch only exists during training. At inference, you do not have ground-truth audio, so you sample from the prior instead.

### 3.3 Monotonic Alignment Search (MAS)

The prior (one Gaussian per phoneme) and the posterior (one Gaussian per audio frame) have different lengths. Speech is much longer than its phoneme sequence. To compute the loss, the model needs to know which audio frames correspond to which phoneme. VITS does this with **MAS**, a dynamic-programming algorithm that finds the best monotonic alignment between phonemes and audio frames given the current model parameters.

"Monotonic" means the alignment never goes backward. Phoneme 1 gets some frames, then phoneme 2 gets some frames, and so on. The number of frames per phoneme is what the duration predictor learns to estimate at inference time.

MAS is the killer feature. Tacotron 2 and similar attention-based models can fail at long sentences because soft attention sometimes skips or repeats phonemes. MAS guarantees a valid alignment by construction.

### 3.4 Normalizing flow

A plain Gaussian prior is too restrictive. To make the prior expressive enough, VITS applies an invertible **normalizing flow** `f_θ` to the posterior `z_q` before computing the KL divergence. The flow is a stack of coupling layers (around 4 in the standard config). Each coupling layer splits its input in half, transforms one half conditioned on the other, and concatenates them back.

The point of the flow is that `f_θ(z_q)` should match the simpler prior distribution while preserving information. At inference the model samples `z` from the prior and runs the flow in reverse `f_θ⁻¹(z)` to obtain the latent that the decoder consumes.

### 3.5 Stochastic Duration Predictor

For each phoneme, the model needs to predict how long it should be held in audio frames. A deterministic regressor (predict a single number) tends to produce monotonous prosody because every speaker reads the same phrase the same way. The Stochastic Duration Predictor is a flow-based model that learns a *distribution* over durations and samples from it. The same input phoneme sequence produces slightly different timing on each invocation, which sounds less robotic.

The downside is that the same input can produce slightly different audio across runs. For most use cases this is desirable. If you need deterministic output, you would have to fix the random seed before each call.

### 3.6 HiFi-GAN decoder

The decoder upsamples the latent `z` (one vector per ~256 audio samples) into a raw waveform (one sample per sample). It is a convolutional network with transposed convolutions for upsampling.

The architecture is from [HiFi-GAN](https://arxiv.org/abs/2010.05646) (2020). Three details matter:

- **Multi-Receptive Field Fusion**: at each upsampling level, the output is a sum of three parallel residual blocks with different dilation rates (1, 3, 5 typically). This gives the network access to information at multiple time scales simultaneously.
- **Multi-Period Discriminator (MPD)**: during training, multiple discriminators look at the waveform reshaped into 2D arrays at different periods (2, 3, 5, 7, 11). This catches periodic artifacts at different frequencies, which is critical for speech because vocal cord vibration is periodic.
- **Multi-Scale Discriminator (MSD)**: additional discriminators operate on the raw waveform and on its average-pooled versions at three scales. This catches global artifacts that the MPD might miss.

The combination of MPD and MSD plus a feature-matching loss (matching intermediate discriminator activations between real and generated waveforms) is what gives HiFi-GAN its quality. WaveNet, the previous state of the art, was autoregressive (each sample predicted from the previous ones) and ran 100 to 1000 times slower at inference. HiFi-GAN runs in a single forward pass.

### 3.7 Inference

At inference VITS is much simpler than at training. The flow runs in reverse, the posterior encoder is gone, and there are no losses to compute. The path is:

1. Phoneme IDs go through the text encoder, producing prior parameters per phoneme.
2. The Stochastic Duration Predictor samples a duration for each phoneme.
3. Each phoneme's prior parameters are repeated by its duration to form a frame-rate latent sequence.
4. A latent `z` is sampled from this frame-rate prior.
5. The inverse flow `f_θ⁻¹` is applied.
6. The HiFi-GAN decoder generates the waveform.

For a multi-speaker model (the Piper config for `kk_KZ-issai-high` exposes 6 speaker IDs), a learned speaker embedding is concatenated to the latent at multiple stages so the decoder produces the right voice timbre.

The `speed` parameter at inference scales the predicted durations. A `speed` of 0.5 doubles every duration. Below 0.5 or above 2.0 the model produces visible artifacts because it never saw such extreme rates during training.

## 4. Why VITS instead of older approaches

Three predecessors are worth knowing:

**Tacotron 2** (2017) was an encoder-decoder with attention. It produced mel-spectrograms autoregressively. Two problems: attention sometimes failed (the decoder would skip or repeat phonemes on hard sentences), and the autoregressive decoder was slow.

**FastSpeech / FastSpeech 2** (2019, 2020) replaced the attention with an external duration predictor distilled from Tacotron 2. Non-autoregressive, much faster, and robust on long sentences. Quality was slightly behind Tacotron 2 because the duration predictor was deterministic. Required a separate vocoder (HiFi-GAN or similar).

**WaveNet** (2016) was the original neural vocoder. Autoregressive over samples, so producing one second of 22050 Hz audio required 22050 sequential network passes. Beautiful quality, terrible speed. Subsequent vocoders (Parallel WaveNet, WaveRNN, WaveGlow, HiFi-GAN) traded some quality for orders-of-magnitude speedup.

VITS effectively folds Tacotron-style duration alignment, FastSpeech-style non-autoregressive generation, and HiFi-GAN-style adversarial waveform synthesis into one model trained jointly. The end-to-end training avoids error accumulation between stages, and the stochastic duration predictor restores the natural variability that deterministic FastSpeech lost.

The price you pay: VITS models are larger than FastSpeech 2 + HiFi-GAN combined, and training requires careful loss balancing across multiple objectives (KL divergence, reconstruction, adversarial, feature matching, duration). Inference cost is similar.

## 5. PCM fundamentals

The waveform produced by HiFi-GAN is a sequence of numbers. To play it through a speaker, those numbers need to drive the digital-to-analog converter (DAC) in the audio hardware. The format the hardware expects is **PCM** (pulse code modulation), which is exactly what the model produces.

### 5.1 Sample, frame, sample rate

- A **sample** is one numeric value, e.g. `0.0123`. It represents the instantaneous amplitude of the sound wave at one moment.
- A **frame** is one sample per channel. Mono audio has one channel, so frame = sample. Stereo has two, so a frame is a pair (left, right). This model is mono.
- The **sample rate** is how many frames per second. This model produces 22050 Hz, meaning 22050 frames per second.

A 1-second utterance at 22050 Hz mono float is `22050 × 4 bytes = 86 KB`. A typical sentence of three seconds is ~260 KB. Trivial for memory and disk.

### 5.2 Bit depth and encoding

The same waveform can be encoded several ways. The two that matter on Android:

- **16-bit signed integer** (`ENCODING_PCM_16BIT`). Each sample is an `int16` in `[-32768, 32767]`. The theoretical dynamic range of ideal `16‑bit` PCM is about 96 dB (`6.02 dB per bit × 16 bits`), which is adequate for most consumer audio applications. The `20 × log10(2^15) ≈ 90 dB` figure is another way to approximate the ratio between the smallest and largest representable levels, but in audio practice the 6.02·N formula is the conventional one.
- **32-bit float** (`ENCODING_PCM_FLOAT`). Each sample is an IEEE-754 single-precision float, conventionally in `[-1.0, 1.0]`. In 32‑bit float, the available dynamic range is far greater than any fixed‑point PCM format used in playback. In practice on the order of 140 dB or more within the conventional. Available since Android 5.0.

VITS produces float samples natively. Using `ENCODING_PCM_FLOAT` avoids the conversion step that would otherwise have to clamp and quantize to int16, which can introduce minor artifacts. The library uses float throughout.

### 5.3 Why mono, not stereo

In this TTS setup the model generates mono speech without any spatial cues. A single channel is enough for intelligibility, and any stereo or binaural effects are better added as post‑processing on the mono output. Producing stereo would double the data with no benefit. The KazakhTTS model was trained on mono recordings and produces a mono signal.

If you wanted spatial effects (binaural, panning), you would do them as a post-processing step on the mono output, not inside the TTS model.

### 5.4 Quantization and dithering (briefly)

When converting from float to int16 you lose precision. Naive truncation introduces correlated noise that sounds harsher than uncorrelated noise of the same energy. **Dithering** adds a small random noise before quantization to decorrelate the error. It makes the artifact sound like white noise rather than buzzing.

The library does not dither because it stays in float, but if you ever need to ship int16 PCM (for older `MediaCodec` paths or smaller files), you should dither during the conversion.

## 6. Nyquist, aliasing, and why 22050 Hz is enough

The Nyquist-Shannon theorem says a sample rate of `f_s` can faithfully represent frequencies up to `f_s / 2`. For 22050 Hz, the upper limit is 11025 Hz.

Anything above the Nyquist frequency in the original signal must be filtered out before sampling, otherwise it folds back into the audible range as **aliasing** (a high tone reappears as a low tone, sounding wrong). Real audio chains always include an analog low-pass filter before the analog-to-digital converter.

For speech, 11 kHz is plenty. The most important formants of the human voice sit between 200 Hz and 5 kHz. Sibilants (`s`, `sh`) extend to about 8 kHz. Above that is mostly air noise that contributes some sense of "presence" or "brightness" but no intelligibility. CD-quality audio (44.1 kHz, 22 kHz Nyquist) was chosen for music, where harmonics matter. Speech is fine at 16 to 24 kHz sample rate, and 22050 Hz is a common compromise.

The DAC in the phone reconstructs a continuous waveform from the discrete samples using a low-pass reconstruction filter. The samples themselves are technically dirac impulses; the filter smooths them into a continuous signal. This step is invisible to you because it happens in hardware.

## 7. ONNX and inference

The trained VITS model is exported to **ONNX** (Open Neural Network Exchange), a portable graph format. ONNX represents the model as a directed acyclic graph of operators (Conv, MatMul, Tanh, etc.) plus a set of tensor weights, with no dependency on the framework that trained it (PyTorch, TensorFlow, JAX).

Each ONNX file declares which version of the operator set it uses. Newer opsets add operators and refine semantics. Inference engines must support the opset the model was exported with, or the model will fail to load.

### 7.1 sherpa-onnx as a runtime

[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) is a lightweight inference runtime that loads ONNX models and runs them on CPU. It uses [ONNX Runtime](https://github.com/microsoft/onnxruntime) under the hood for the actual operator implementations.

ONNX Runtime does several things during model load:

- **Graph optimization.** Constant folding (precomputing branches that depend only on weights), operator fusion (merging adjacent operators like Conv + BatchNorm + ReLU into one kernel), and dead code elimination.
- **Layout selection.** Choosing memory layouts (NCHW vs NHWC) per operator to minimize data movement.
- **Kernel selection.** Picking optimized kernels for the target CPU. On ARM that means NEON SIMD intrinsics. On x86_64 that means SSE/AVX.

For VITS the dominant cost is convolutions in HiFi-GAN. These have efficient SIMD implementations on modern phones, which is why on-device VITS is feasible at all.

### 7.2 Real-time factor

A useful number: **real-time factor** (RTF) is `wall_time / audio_duration`. RTF below 1.0 means the model produces audio faster than real time, which is what you need for streaming. RTF above 1.0 means you have to fully synthesize before playback can finish.

On our test mid‑range phones (Snapdragon 7‑series or comparable), this model typically runs with a real‑time factor around 0.2-0.4, so a 3‑second utterance synthesizes in roughly 0.6-1.2 seconds. Actual numbers depend heavily on the device and background load.

### 7.3 Quantization (not used here)

ONNX models can be quantized from float32 to int8, reducing size by 4x and often speeding up inference because int8 SIMD throughput is higher than float32 on modern CPUs. The KazakhTTS model used here is float32. There is a quality cost to int8 quantization for generative models, especially in the HiFi-GAN decoder where small numerical errors are audible. Some VITS deployments do quantize the text encoder while keeping the decoder in float, but Piper does not by default.

## 8. AudioTrack and Android's audio path

`AudioTrack` is the public Java/Kotlin API for streaming PCM to the audio hardware. Underneath it is a chain that goes:

```
AudioTrack
   │  (writes samples into a shared ring buffer)
   ▼
AudioFlinger (system service, mixes all streams)
   │
   ▼
AudioHAL (hardware abstraction layer)
   │
   ▼
DSP / DAC / amplifier / speaker
```

### 8.1 Streaming vs static mode

`AudioTrack` has two modes:

- **STATIC** mode loads the entire audio buffer up front and plays it from there. Useful for short notification sounds.
- **STREAM** mode uses a ring buffer that the app keeps refilling with `write()` calls. The hardware reads from one end of the buffer at the sample rate while the app writes to the other end. This is the right mode for TTS because audio is generated incrementally.

The library uses STREAM mode with `WRITE_BLOCKING` writes. `WRITE_BLOCKING` means `write()` does not return until all the samples passed in have been accepted into the ring buffer. If the buffer is full, `write()` blocks until space frees up. This naturally throttles the producer to playback speed without manual flow control.

### 8.2 The start threshold issue

Starting in Android 12 (API 31), `AudioTrack` does not actually begin playback until the buffer level crosses a "start threshold," which by default equals the buffer's capacity. The intent is to avoid underruns at the start of playback. The unintended consequence: short utterances that fit entirely within the buffer never trigger playback because the buffer is never full.

For example, a 600 ms utterance at 22050 Hz is `600 × 22050 / 1000 = 13230` frames. With a buffer of `22050` frames (1 second), 13230 < 22050, so the threshold is never crossed and the audio sits silently in the buffer until the track is stopped.

The fix is `AudioTrack.setStartThresholdInFrames(minBufferFrames)`, which lowers the threshold to roughly `getMinBufferSize() / 4` frames (a few hundred ms). Once any meaningful chunk arrives, playback starts. The library applies this on API 31+ unconditionally.

### 8.3 Latency

The chain from `write()` to actual sound has end-to-end latency. On Android this is variable and can be hundreds of milliseconds on devices with poor audio hardware. The components:

- **Buffer fill latency**: how much audio is buffered ahead of playback. This is what `bufferSizeInBytes` controls.
- **AudioFlinger mixing latency**: typically 20 to 40 ms.
- **HAL + DSP latency**: hardware-specific, often 20 to 200 ms.

For TTS this latency is rarely a problem because users do not expect instant response from a "Speak" button. For real-time interaction (voice chat, music apps) you would use the lower-latency `AAudio` API instead.

### 8.4 Underruns

If the producer cannot keep up with the consumer, the ring buffer empties and the hardware has nothing to play. This is an **underrun**, audible as a click or gap. Mitigations:

- Use a generous buffer (this library uses 4 × min buffer, ~1 second at 22050 Hz).
- Run synthesis on a high-priority thread.
- Avoid garbage collection pauses in the writing thread.

For VITS at RTF 0.3 there is significant headroom. Underruns happen mostly when other apps consume CPU.

## 9. The KazakhTTS model: dataset and provenance

The model was trained by [ISSAI](https://issai.nu.edu.kz/) (Institute of Smart Systems and Artificial Intelligence) at Nazarbayev University on a corpus of read Kazakh speech. ISSAI’s published KazakhTTS2 corpus contains multiple speakers (5 in the official description), and this Piper variant exposes 6 speaker IDs in its config, each with its own learned embedding.

The version of the model used here, `kk_KZ-issai-high`, was prepared by the [Piper](https://github.com/rhasspy/piper) project. Piper is a community effort that trains compact VITS models for use in Home Assistant and similar self-hosted voice systems. Piper publishes models in three quality tiers: `low` (fast, lower fidelity), `medium`, and `high`. This library uses `high`. The Piper-trained model was then exported to ONNX format compatible with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

The Piper retraining typically uses a smaller architecture than the original VITS paper: 192 hidden dimensions, 4 transformer layers in the text encoder, 4 coupling layers in the flow, smaller HiFi-GAN. The result is a ~65 MB ONNX file rather than the ~300 MB of a full-size VITS model, with quality that is acceptable for most embedded use cases.

## 10. What the model cannot do

Limitations that come from the architecture and training data, not from this library:

- **No emotion or expressivity beyond speaker and speed.** Standard VITS has no global style control. It produces neutral read-aloud speech. Models like StyleTTS or VITS variants with emotion conditioning exist but are not what is shipped here.
- **Speaker identity is fixed per ID.** You cannot interpolate between speakers or clone a new voice without retraining.
- **Out-of-vocabulary words.** The phonemizer falls back to letter-by-letter rules for unknown words, which sounds wrong. This is most visible with English loanwords or proper nouns.
- **Numbers and abbreviations.** Espeak-ng's number expansion is rule-based and works for ordinary cases. Phone numbers, years, and prices may need preprocessing in your app code if you want a specific reading.
- **Punctuation drives prosody.** Periods and commas shape intonation and pauses. Text without punctuation reads as a monotone list. This is not a bug, it is how the model was trained.
- **No streaming generation within an utterance.** VITS as published produces the whole waveform in one shot. Streaming TTS exists (e.g. [F5-TTS](https://arxiv.org/abs/2410.06885), various flow-matching models) but is a different architecture.

## Further reading

- [VITS paper](https://arxiv.org/abs/2106.06103). The original architecture.
- [HiFi-GAN paper](https://arxiv.org/abs/2010.05646). The decoder.
- [Piper](https://github.com/rhasspy/piper). The training pipeline that produced this model.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). The inference runtime.
- [espeak-ng](https://github.com/espeak-ng/espeak-ng). The phonemizer.
- [ISSAI Kazakh_TTS](https://github.com/IS2AI/Kazakh_TTS). The dataset.
- [Tacotron 2 paper](https://arxiv.org/abs/1712.05884). Useful for context on what VITS replaces.
- [WaveNet paper](https://arxiv.org/abs/1609.03499). The original neural vocoder.
- [Glow paper](https://arxiv.org/abs/1807.03039). Background on the normalizing flow used inside VITS.
