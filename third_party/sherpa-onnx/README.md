# sherpa-onnx

Enginehost reads games aloud on the device with sherpa-onnx (docs/text-services.md, "Voices on
the device").

- **Engine in the APK.** sherpa-onnx v1.13.8, the AAR from https://github.com/k2-fsa/sherpa-onnx/releases,
  SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`, Apache-2.0
  (LICENSE here). The build fetches it and checks the hash (`app/build.gradle.kts`,
  `sherpaOnnxFetch`). Only `libsherpa-onnx-jni.so` and `libonnxruntime.so` ship, for arm64-v8a and
  x86_64. ONNX Runtime is MIT-licensed (https://github.com/microsoft/onnxruntime).
- **Voices, never in the APK.** Downloaded on request from sherpa-onnx's `tts-models` release,
  pinned in `VoiceModels.OFFERED`:
  - `kokoro-int8-en-v0_19.tar.bz2`, SHA-256 `c9f0dd39…08bd`.
  - `kokoro-int8-multi-lang-v1_0.tar.bz2`, SHA-256 `72c34723…0653`.

  Both are Kokoro-82M (https://huggingface.co/hexgrad/Kokoro-82M), Apache-2.0. Each archive
  carries its own LICENSE, which stays beside the model on the device. A person's own voice files
  keep whatever licence they came with.
