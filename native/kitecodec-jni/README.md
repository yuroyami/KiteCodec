# kitecodec-jni

The JNI adapter that lets JVM and Android consumers use KiteCodec through its opaque C boundary
(KPKMP.md register item S1C-01). It is deliberately narrow: no logic, no FFmpeg types, no policy.

What it is:

- One dynamically registered shared library exporting exactly `JNI_OnLoad`. There is no `Java_*`
  symbol; `methods.def` is the single manifest both `kj_registration.c` and the Kotlin bridge
  are written from.
- A generation-tagged handle table (`kj_handles.c`): every C object crosses as a `jlong` token,
  never a pointer, so stale, zero, double-closed and wrong-kind tokens throw a typed exception
  instead of corrupting memory.
- Category units (`kj_abi.c`, `kj_packet.c`, `kj_format.c`, `kj_codec.c`, `kj_frame.c`,
  `kj_filter.c`) that resolve tokens and call exactly one `kc_`/`ffkmp_` helper each.
  `kj_abi.c` is the canonical pattern.

What it may never do, enforced by `scripts/source-discipline.sh` and `scripts/symbol-audit.sh`:
include a libav header, spell a direct FFmpeg call, or export anything but `JNI_OnLoad`.

Built by `:kitecodec-core:linkKiteCodecJni{MacosArm64,AndroidArm64,AndroidX64}`. The macOS dylib
is test-only (jvmTest loads it through the `kitecodec.jni.path` system property); the two Android
arms are the AAR's `jniLibs` inputs, linked with 16 KiB page alignment.

Status: scaffolded 2026-08-12 (substrate complete and link-proven on macOS; identity, packet,
format, codec and frame core rows implemented). The remaining categories grow at S1.c.2 by the
canonical pattern, one manifest row per operation.
