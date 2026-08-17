package io.github.yuroyami.kitecodec.buildtools

import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.isExecutable
import kotlin.io.path.setPosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BuildFFmpegTaskTest {

    @Test
    fun androidArm64AndX64UseTheExactApi24MediaCodecJniPicArguments() {
        val task = ProjectBuilder.builder().build().tasks.create("ffmpeg", BuildFFmpegTask::class.java)
        val root = Files.createTempDirectory("kitecodec-android-args-test")
        try {
            val toolchainBin = root.resolve("toolchains/llvm/prebuilt/test-host/bin").createDirectories()
            toolchainBin.resolve("aarch64-linux-android24-clang").createFile()
            toolchainBin.resolve("x86_64-linux-android24-clang").createFile()

            val arm64 = task.configureArguments(
                target = TargetTriple.AndroidArm64,
                license = FFmpegLicense.LGPL,
                installPrefix = "/scratch/install-arm64",
                ndkToolchainBin = { toolchainBin.toFile() },
            )
            val x64 = task.configureArguments(
                target = TargetTriple.AndroidX64,
                license = FFmpegLicense.LGPL,
                installPrefix = "/scratch/install-x64",
                ndkToolchainBin = { toolchainBin.toFile() },
            )

            assertEquals(
                expectedSharedCoreArguments() + expectedAndroidArguments(
                    toolchainBin = toolchainBin.toString(),
                    arch = "aarch64",
                    compilerPrefix = "aarch64-linux-android",
                    suffix = listOf("--cpu=armv8-a"),
                    installPrefix = "/scratch/install-arm64",
                ),
                arm64,
            )
            assertEquals(
                expectedSharedCoreArguments() + expectedAndroidArguments(
                    toolchainBin = toolchainBin.toString(),
                    arch = "x86_64",
                    compilerPrefix = "x86_64-linux-android",
                    suffix = listOf("--disable-asm"),
                    installPrefix = "/scratch/install-x64",
                ),
                x64,
            )
            listOf(arm64, x64).forEach { arguments ->
                assertTrue("--enable-pic" in arguments)
                assertTrue("--enable-mediacodec" in arguments)
                assertTrue("--enable-jni" in arguments)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun iosProfilesUseTheExactStandardCoreZlibAndCrossArguments() {
        val task = ProjectBuilder.builder().build().tasks.create("ffmpeg", BuildFFmpegTask::class.java)
        task.hostPrefix.set("/host")

        val device = task.configureArguments(
            target = TargetTriple.IosArm64,
            license = FFmpegLicense.LGPL,
            installPrefix = "/scratch/install",
            sdkPath = { "/SDK/${it}" },
        )
        val simulator = task.configureArguments(
            target = TargetTriple.IosSimulatorArm64,
            license = FFmpegLicense.LGPL,
            installPrefix = "/scratch/install",
            sdkPath = { "/SDK/${it}" },
        )

        // VideoToolbox DECODE (KPKMP 17.4.8 S2.a) is on for every Apple target, simulator
        // included. The hwaccel line is a PIN: it keeps the two hwaccels D-2 needs even if the
        // wide class policy ever changes.
        assertEquals(
            expectedSharedCoreArguments() + listOf(
                "--disable-autodetect",
                "--enable-zlib",
                "--enable-videotoolbox",
                "--enable-hwaccel=h264_videotoolbox,hevc_videotoolbox",
                "--arch=arm64",
                "--target-os=darwin",
                "--cc=clang -arch arm64 -isysroot /SDK/iphoneos -mios-version-min=14.0",
                "--enable-cross-compile",
                "--disable-asm",
                "--prefix=/scratch/install",
            ),
            device,
        )
        assertEquals(
            expectedSharedCoreArguments() + listOf(
                "--disable-autodetect",
                "--enable-zlib",
                "--enable-videotoolbox",
                "--enable-hwaccel=h264_videotoolbox,hevc_videotoolbox",
                "--arch=arm64",
                "--target-os=darwin",
                "--cc=clang -arch arm64 -isysroot /SDK/iphonesimulator -mios-simulator-version-min=14.0",
                "--enable-cross-compile",
                "--disable-asm",
                "--prefix=/scratch/install",
            ),
            simulator,
        )

        val refusal = assertFailsWith<IllegalArgumentException> {
            task.configureArguments(
                target = TargetTriple.IosArm64,
                license = FFmpegLicense.GPL,
                installPrefix = "/must-not-resolve",
                sdkPath = { error("GPL refusal must happen before SDK resolution") },
            )
        }
        assertEquals(IOS_GPL_REFUSAL, refusal.message)
    }

    @Test
    fun linuxAndMingwUseTheExactKonanCrossCompileArguments() {
        val task = ProjectBuilder.builder().build().tasks.create("ffmpeg", BuildFFmpegTask::class.java)

        fun argumentsFor(target: TargetTriple) = task.configureArguments(
            target = target,
            license = FFmpegLicense.LGPL,
            installPrefix = "/scratch/install-${target.dirName}",
            konanBin = ::fakeKonanTools,
        )

        // Linux carries --enable-zlib (its konan sysroot has it) and a gcc runtime dir beside the
        // sysroot, so the --cc line grows the extra -B/-L pair.
        assertEquals(
            expectedSharedCoreArguments() + expectedKonanCrossArguments(
                leading = listOf("--enable-zlib"),
                arch = "x86_64",
                targetOs = "linux",
                cc = "/fake/llvm/bin/clang -target x86_64-unknown-linux-gnu " +
                    "--sysroot=/fake/linux-x64/sysroot -fuse-ld=lld -B/fake/llvm/bin " +
                    "-B/fake/linux-x64/gcc -L/fake/linux-x64/gcc",
                trailing = emptyList(),
                installPrefix = "/scratch/install-linux-x64",
            ),
            argumentsFor(TargetTriple.LinuxX64),
        )
        assertEquals(
            expectedSharedCoreArguments() + expectedKonanCrossArguments(
                leading = listOf("--enable-zlib"),
                arch = "aarch64",
                targetOs = "linux",
                cc = "/fake/llvm/bin/clang -target aarch64-unknown-linux-gnu " +
                    "--sysroot=/fake/linux-arm64/sysroot -fuse-ld=lld -B/fake/llvm/bin " +
                    "-B/fake/linux-arm64/gcc -L/fake/linux-arm64/gcc",
                trailing = emptyList(),
                installPrefix = "/scratch/install-linux-arm64",
            ),
            argumentsFor(TargetTriple.LinuxArm64),
        )
        // Windows differs four ways: no --enable-zlib (the msys2 sysroot has none), target-os is
        // mingw32, its headers need -std=gnu11, and it threads with w32threads. The pthreads
        // request from sharedCoreArgs is withdrawn here, since configure takes the last word.
        // The fake has no runtime dir, which also pins the branch with no -B/-L pair.
        assertEquals(
            expectedSharedCoreArguments() + expectedKonanCrossArguments(
                leading = emptyList(),
                arch = "x86_64",
                targetOs = "mingw32",
                cc = "/fake/llvm/bin/clang -target x86_64-w64-mingw32 " +
                    "--sysroot=/fake/mingw-x64/sysroot -fuse-ld=lld -B/fake/llvm/bin",
                trailing = listOf(
                    "--extra-cflags=-std=gnu11",
                    "--disable-pthreads", "--enable-w32threads",
                ),
                installPrefix = "/scratch/install-mingw-x64",
            ),
            argumentsFor(TargetTriple.MingwX64),
        )
    }

    @Test
    fun linuxAndMingwCarryNoneOfTheDesktopThirdPartyStack() {
        val task = ProjectBuilder.builder().build().tasks.create("ffmpeg", BuildFFmpegTask::class.java)
        // Decision W-D4 (KPKMP.md 17.13): these three triples get the REDUCED desktop profile,
        // because none of these libraries has ever been cross-built for them. If one grows back,
        // configure fails and the cross build dies, so pin its absence.
        val forbidden = listOf(
            "--enable-libsvtav1", "--enable-libvpx", "--enable-libaom",
            "--enable-libmp3lame", "--enable-libopus", "--enable-libwebp",
            "--enable-libfreetype", "--enable-libharfbuzz", "--enable-libfribidi",
            "--enable-libass", "--enable-filter=drawtext", "--enable-gpl",
        )
        listOf(TargetTriple.LinuxX64, TargetTriple.LinuxArm64, TargetTriple.MingwX64).forEach { target ->
            // Both licences, because W-D4 reduces the profile whatever was asked for: the GPL
            // desktop stack is the consumer plugin's job, not this cross build's.
            FFmpegLicense.entries.forEach { license ->
                val arguments = task.configureArguments(
                    target = target,
                    license = license,
                    installPrefix = "/scratch/install",
                    konanBin = ::fakeKonanTools,
                )
                forbidden.forEach { flag ->
                    assertFalse(flag in arguments, "$flag came back for $target ($license)")
                }
                // Compression is per sysroot, measured: the konan linux sysroots carry zlib and
                // nothing else, msys2's carries none of the three. configure REFUSES a library it
                // cannot find, so asking for one more here would fail the build.
                assertEquals(
                    target != TargetTriple.MingwX64,
                    "--enable-zlib" in arguments,
                    "zlib belongs to the linux sysroots only, not to msys2's ($target)",
                )
                assertFalse("--enable-bzlib" in arguments, "no sysroot here carries bzlib ($target)")
                assertFalse("--enable-lzma" in arguments, "no sysroot here carries lzma ($target)")
            }
        }
    }

    @Test
    fun theDav1dSwitchAddsExactlyItsThreeArgumentsAndOffIsByteIdentical() {
        val task = ProjectBuilder.builder().build().tasks.create("ffmpeg", BuildFFmpegTask::class.java)
        val root = Files.createTempDirectory("kitecodec-av1sw-args-test")
        try {
            val toolchainBin = root.resolve("toolchains/llvm/prebuilt/test-host/bin").createDirectories()
            toolchainBin.resolve("aarch64-linux-android24-clang").createFile()
            val deps = root.resolve("deps").toFile()

            val off = task.configureArguments(
                target = TargetTriple.AndroidArm64,
                license = FFmpegLicense.LGPL,
                installPrefix = "/scratch/install",
                ndkToolchainBin = { toolchainBin.toFile() },
            )
            val on = task.configureArguments(
                target = TargetTriple.AndroidArm64,
                license = FFmpegLicense.LGPL,
                installPrefix = "/scratch/install",
                ndkToolchainBin = { toolchainBin.toFile() },
                dav1dRoot = deps,
            )
            // Off carries not one dav1d trace: D-7's not-one-extra-byte promise starts at
            // configure. This test compares off against on, so it stays about the switch only.
            assertTrue(off.none { it.contains("dav1d") }, "dav1d leaked into off: " + off.filter { it.contains("dav1d") })
            assertTrue(off.none { it == "--pkg-config=pkg-config" }, "pkg-config flag leaked into off")
            assertEquals(
                off.dropLast(1) +
                    listOf("--enable-libdav1d", "--enable-decoder=libdav1d", "--pkg-config=pkg-config") +
                    listOf("--prefix=/scratch/install"),
                on,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun theDav1dArchiveAndLinkFlagRideEveryProfileOnlyWhenAsked() {
        // Android and iOS bundle nothing by default; the switch adds exactly libdav1d.a.
        assertEquals(emptyList(), StaticLinkFlags.thirdPartyArchives(TargetTriple.AndroidArm64, FFmpegLicense.LGPL))
        assertEquals(
            listOf("libdav1d.a"),
            StaticLinkFlags.thirdPartyArchives(TargetTriple.AndroidArm64, FFmpegLicense.LGPL, dav1d = true),
        )
        assertEquals(
            listOf("libdav1d.a"),
            StaticLinkFlags.thirdPartyArchives(TargetTriple.IosArm64, FFmpegLicense.LGPL, dav1d = true),
        )
        // The link flag leads the list where a static stack exists, and stands alone where not.
        assertEquals(
            emptyList(),
            StaticLinkFlags.forTarget(TargetTriple.AndroidArm64, FFmpegLicense.LGPL, isStaticVendored = true),
        )
        assertEquals(
            listOf("-ldav1d"),
            StaticLinkFlags.forTarget(TargetTriple.AndroidArm64, FFmpegLicense.LGPL, isStaticVendored = true, dav1d = true),
        )
        assertEquals(
            "-ldav1d",
            StaticLinkFlags.forTarget(TargetTriple.MacosArm64, FFmpegLicense.LGPL, isStaticVendored = true, dav1d = true).first(),
        )
    }

    @Test
    fun iosStaticLinkSetsAreExactlyZlib() {
        listOf(TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64).forEach { target ->
            assertEquals(emptyList(), StaticLinkFlags.thirdPartyArchives(target, FFmpegLicense.LGPL))
            assertEquals(emptyList(), StaticLinkFlags.hostFallbackSearchFlags(target, "/host", true))
            // zlib is still the ONLY library iOS links: no third-party static stack, no host
            // fallback. The four frameworks came with VideoToolbox decode (S2.a), because the
            // static archives now hold undefined references into the media frameworks.
            assertEquals(
                listOf(
                    "-lz",
                    "-framework", "CoreFoundation",
                    "-framework", "CoreMedia",
                    "-framework", "CoreVideo",
                    "-framework", "VideoToolbox",
                ),
                StaticLinkFlags.forTarget(target, FFmpegLicense.LGPL, true),
            )
        }
    }

    @Test
    fun hashPathSourceIsCopiedToAHashFreeScratchTreeWithoutBuildState() {
        val root = Files.createTempDirectory("kitecodec-source-test")
        try {
            val source = root.resolve("source#checkout").createDirectories()
            source.resolve("configure").createFile()
            source.resolve("configure").setPosixFilePermissions(
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            source.resolve("libavcodec/codec.c").apply {
                parent.createDirectories()
                createFile()
            }
            source.resolve("build/stale.o").apply {
                parent.createDirectories()
                createFile()
            }
            source.resolve("nested/build/stale.o").apply {
                parent.createDirectories()
                createFile()
            }
            source.resolve(".git/index").apply {
                parent.createDirectories()
                createFile()
            }

            val scratch = BuildFFmpegTask.createScratchWorkspace(root.resolve("tmp").createDirectories())
            BuildFFmpegTask.copySourceTree(source, scratch.resolve("source"))

            assertFalse('#' in scratch.toAbsolutePath().toString())
            assertTrue(scratch.resolve("source/configure").isExecutable())
            assertTrue(Files.isRegularFile(scratch.resolve("source/libavcodec/codec.c")))
            assertFalse(Files.exists(scratch.resolve("source/build")))
            assertFalse(Files.exists(scratch.resolve("source/nested/build")))
            assertFalse(Files.exists(scratch.resolve("source/.git")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidScratchInstallNeverReplacesAnExistingGoodOutput() {
        val root = Files.createTempDirectory("kitecodec-replacement-test")
        try {
            val goodOutput = createCompleteInstall(
                root.resolve("native-libs/lgpl/ios-arm64").createDirectories(),
                configureEvidence = "./configure --known-good\n",
            )
            goodOutput.resolve("known-good.marker").createFile()
            val invalidScratch = createCompleteInstall(
                root.resolve("scratch-install").createDirectories(),
                configureEvidence = null,
            )

            val failure = assertFailsWith<IllegalStateException> {
                BuildFFmpegTask.replaceOutputTree(invalidScratch, goodOutput)
            }

            assertTrue("configure provenance" in failure.message.orEmpty())
            assertTrue(Files.isRegularFile(goodOutput.resolve("known-good.marker")))
            assertEquals(
                "./configure --known-good\n",
                Files.readString(goodOutput.resolve(BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH)),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun configureEvidenceNormalizesOnlyTheLogMarkerAndUsesTheStableInstalledPath() {
        val root = Files.createTempDirectory("kitecodec-configure-evidence-test")
        try {
            val configLog = root.resolve("build/ffbuild/config.log").apply {
                parent.createDirectories()
                Files.writeString(
                    this,
                    "# ./configure --disable-autodetect --enable-zlib\nignored second line\n",
                )
            }
            val install = createCompleteInstall(root.resolve("install").createDirectories(), null)

            BuildFFmpegTask.writeConfigureEvidence(configLog, install)

            val evidence = install.resolve(BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH)
            assertEquals(
                "./configure --disable-autodetect --enable-zlib\n",
                Files.readString(evidence),
            )
            BuildFFmpegTask.verifyInstall(install)

            Files.writeString(configLog, "#not-a-marker\n")
            BuildFFmpegTask.writeConfigureEvidence(configLog, install)
            assertEquals("#not-a-marker\n", Files.readString(evidence))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingConfigureLogIsRefusedBeforeWritingAnInstallRecord() {
        val root = Files.createTempDirectory("kitecodec-missing-configure-log-test")
        try {
            val install = root.resolve("install").createDirectories()
            val missingLog = root.resolve("build/ffbuild/config.log")

            val failure = assertFailsWith<IllegalStateException> {
                BuildFFmpegTask.writeConfigureEvidence(missingLog, install)
            }

            assertTrue("configure provenance is missing" in failure.message.orEmpty())
            assertFalse(Files.exists(install.resolve(BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun createCompleteInstall(
        install: java.nio.file.Path,
        configureEvidence: String?,
    ): java.nio.file.Path {
        install.resolve("include/libavformat/avformat.h").apply {
            parent.createDirectories()
            Files.writeString(this, "fixture")
        }
        BuildFFmpegTask.REQUIRED_LIBS.forEach { library ->
            install.resolve("lib/$library.a").apply {
                parent.createDirectories()
                Files.writeString(this, "fixture")
            }
        }
        configureEvidence?.let { text ->
            install.resolve(BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH).apply {
                parent.createDirectories()
                Files.writeString(this, text)
            }
        }
        return install
    }

    /**
     * The exact `sharedCoreArgs()` line. Changing it must stay a reviewed act, not a silent one.
     *
     * Shaped by the wide read-side class policy (KPKMP.md 17.4.9): only the WRITE side and the
     * protocol list are curated, so there is no `--disable-everything` and no named demuxer,
     * decoder, parser or bsf list any more.
     */
    private fun expectedSharedCoreArguments(): List<String> = listOf(
        "--enable-static",
        "--disable-shared",
        "--disable-programs",
        "--disable-doc",
        "--disable-debug",
        "--disable-htmlpages",
        "--disable-manpages",
        "--disable-podpages",
        "--disable-txtpages",
        // 17.4.9 replaced the single `--disable-everything` with five class disables, so the read
        // side compiles whole and only these four classes stay curated.
        "--disable-encoders",
        "--disable-muxers",
        "--disable-filters",
        "--disable-devices",
        "--disable-protocols",
        "--enable-network",
        // `fd` joined the list so an Android content:// descriptor stays seekable.
        "--enable-protocol=file,fd,pipe,data,http,tcp",
        // The wide demuxer class SELECTS udp and rtp via rtsp/sdp, and a named disable beats a
        // select, so these two are hard-off instead of coming back through the class.
        "--disable-protocol=udp,rtp",
        "--enable-muxer=mp4,mov,ipod,webm,matroska,matroska_audio,mp3,wav,flac,ogg,opus,mpegts,image2",
        "--enable-encoder=mpeg4,flac,pcm_s16le,pcm_s24le,pcm_f32le,png,mjpeg",
        "--enable-filter=buffer,buffersink,abuffer,abuffersink,trim,setpts,scale,pad,overlay,hue,unsharp,vignette,colorbalance,colorlevels,curves,lut,format,colorchannelmixer,split,null,atrim,asetpts,asetrate,aresample,volume,atempo,adelay,afade,amix,anull,aformat,loop,tpad",
        "--enable-pthreads",
        "--enable-pic",
        "--enable-runtime-cpudetect",
    )

    /**
     * A fake Kotlin/Native toolchain, injected through `configureArguments(konanBin = ...)`.
     *
     * The paths are made up on purpose, so the golden pins the flag SHAPE and never this
     * machine's `~/.konan` layout. Only mingw gets a null runtime dir, to cover both branches.
     */
    private fun fakeKonanTools(target: TargetTriple): BuildFFmpegTask.KonanTools =
        BuildFFmpegTask.KonanTools(
            clang = "/fake/llvm/bin/clang",
            toolchainBin = "/fake/llvm/bin",
            runtimeDir = if (target == TargetTriple.MingwX64) null else "/fake/${target.dirName}/gcc",
            ar = "/fake/llvm/bin/llvm-ar",
            nm = "/usr/bin/nm",
            ranlib = "/fake/llvm/bin/llvm-ar s",
            triple = when (target) {
                TargetTriple.LinuxX64 -> "x86_64-unknown-linux-gnu"
                TargetTriple.LinuxArm64 -> "aarch64-unknown-linux-gnu"
                else -> "x86_64-w64-mingw32"
            },
            sysroot = "/fake/${target.dirName}/sysroot",
        )

    /** The exact cross block `desktopTargetArgs` writes for the three konan-built triples. */
    private fun expectedKonanCrossArguments(
        leading: List<String>,
        arch: String,
        targetOs: String,
        cc: String,
        trailing: List<String>,
        installPrefix: String,
    ): List<String> = leading + listOf(
        "--arch=$arch",
        "--target-os=$targetOs",
        "--enable-cross-compile",
        "--cc=$cc",
        "--ar=/fake/llvm/bin/llvm-ar",
        "--nm=/usr/bin/nm",
        // ranlib is llvm-ar's own `s` operation, and stripping is off because konan's LLVM
        // package ships no strip that reads ELF or PE.
        "--ranlib=/fake/llvm/bin/llvm-ar s",
        "--disable-stripping",
        "--host-cc=/usr/bin/clang",
    ) + trailing + "--prefix=$installPrefix"

    private fun expectedAndroidArguments(
        toolchainBin: String,
        arch: String,
        compilerPrefix: String,
        suffix: List<String>,
        installPrefix: String,
    ): List<String> {
        val compiler = "$toolchainBin/$compilerPrefix${BuildFFmpegTask.ANDROID_API}-clang"
        return listOf(
            "--target-os=android",
            "--arch=$arch",
            "--enable-cross-compile",
            "--cc=$compiler",
            "--cxx=$compiler++",
            "--ar=$toolchainBin/llvm-ar",
            "--ranlib=$toolchainBin/llvm-ranlib",
            "--nm=$toolchainBin/llvm-nm",
            "--strip=$toolchainBin/llvm-strip",
            "--enable-mediacodec",
            "--enable-jni",
            "--enable-encoder=aac,h264_mediacodec,hevc_mediacodec",
            // av1/vp9/vp8 joined here because FFmpeg has no native software AV1 decoder, so on
            // Android the MediaCodec wrappers are the only AV1 route this profile can offer.
            "--enable-decoder=h264_mediacodec,hevc_mediacodec,av1_mediacodec,vp9_mediacodec,vp8_mediacodec",
            "--enable-zlib",
        ) + suffix + "--prefix=$installPrefix"
    }
}
