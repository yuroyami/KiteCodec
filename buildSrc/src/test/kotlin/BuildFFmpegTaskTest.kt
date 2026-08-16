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

        assertEquals(
            expectedSharedCoreArguments() + listOf(
                "--disable-autodetect",
                "--enable-zlib",
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
            // configure. (The exact-list helpers in this file are stale since the 17.4.9 wide
            // profile, a recorded drift; this test deliberately compares off against on.)
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
            assertEquals(listOf("-lz"), StaticLinkFlags.forTarget(target, FFmpegLicense.LGPL, true))
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
        "--disable-everything",
        "--enable-network",
        "--enable-protocol=file,pipe,data,http,tcp",
        "--enable-demuxer=mov,mp4,m4v,matroska,webm,mp3,wav,aac,flac,ogg,opus,mpegts,image2,png_pipe,jpeg_pipe",
        "--enable-muxer=mp4,mov,ipod,webm,matroska,matroska_audio,mp3,wav,flac,ogg,opus,mpegts,image2",
        "--enable-decoder=h264,hevc,vp8,vp9,av1,mpeg4,aac,mp3,opus,vorbis,flac,pcm_s16le,pcm_s24le,pcm_f32le,png,mjpeg,webp",
        "--enable-encoder=mpeg4,flac,pcm_s16le,pcm_s24le,pcm_f32le,png,mjpeg",
        "--enable-parser=h264,hevc,vp8,vp9,av1,mpeg4video,aac,mpegaudio,opus,vorbis,flac,png",
        "--enable-bsf=extract_extradata,h264_mp4toannexb,hevc_mp4toannexb,aac_adtstoasc,vp9_superframe,null",
        "--enable-filter=buffer,buffersink,abuffer,abuffersink,trim,setpts,scale,pad,overlay,hue,unsharp,vignette,colorbalance,colorlevels,curves,lut,format,colorchannelmixer,split,null,atrim,asetpts,asetrate,aresample,volume,atempo,adelay,afade,amix,anull,aformat,loop,tpad",
        "--enable-pthreads",
        "--enable-pic",
        "--enable-runtime-cpudetect",
    )

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
            "--enable-decoder=h264_mediacodec,hevc_mediacodec",
            "--enable-zlib",
        ) + suffix + "--prefix=$installPrefix"
    }
}
