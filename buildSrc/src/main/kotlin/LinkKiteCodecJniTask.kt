package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.CommandLineArgumentProvider
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * Supplies the path-valued JVM-test properties without stringifying Gradle [Provider] objects.
 * Gradle asks for these arguments when it starts the test JVM, after the three dylibs exist. The
 * dylibs are real content-tracked inputs; the transcript is the test task's declared output.
 */
abstract class KiteCodecJvmTestArgumentProvider : CommandLineArgumentProvider {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val normalJniLibrary: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mismatchJniLibrary: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val corruptJniLibrary: RegularFileProperty

    /** Declared as the owning Test task's output; retained here only to materialize its path. */
    @get:Internal
    abstract val contractTranscript: RegularFileProperty

    /** The real JVM test runtime, not Gradle's often-minimal worker-process java.class.path. */
    @get:Classpath
    abstract val probeClasspath: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = listOf(
        "-Dkitecodec.jni.path=${normalJniLibrary.get().asFile.absolutePath}",
        "-Dkitecodec.jni.mismatch.path=${mismatchJniLibrary.get().asFile.absolutePath}",
        "-Dkitecodec.jni.corrupt.path=${corruptJniLibrary.get().asFile.absolutePath}",
        "-Dkitecodec.contract.transcript=${contractTranscript.get().asFile.absolutePath}",
        "-Dkitecodec.jni.probe.classpath=${probeClasspath.asPath}",
    )
}

/**
 * Makes an isolated generated copy of a JNI/opaque-header tree and performs one exact mutation.
 * The exact-once gate is load bearing: a renamed or duplicated manifest/header record must fail the
 * harness producer instead of silently turning a falsifiability arm into a normal-library test.
 */
abstract class PrepareKiteCodecJniHarnessTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    /** File whose bytes are transformed into [relativeFile]; it may be an overlay outside the tree. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mutationSourceFile: RegularFileProperty

    @get:Input
    abstract val relativeFile: Property<String>

    @get:Input
    abstract val expectedText: Property<String>

    @get:Input
    abstract val replacementText: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val sourceRoot = sourceDirectory.get().asFile
        val outputRoot = outputDirectory.get().asFile.canonicalFile
        fileSystemOperations.sync {
            from(sourceRoot)
            into(outputRoot)
        }

        val target = outputRoot.resolve(relativeFile.get()).canonicalFile
        if (!target.toPath().startsWith(outputRoot.toPath()) || !target.isFile) {
            throw GradleException(
                "Harness mutation target '${relativeFile.get()}' is not a file inside " +
                    outputRoot.absolutePath,
            )
        }
        val original = mutationSourceFile.get().asFile.readText(StandardCharsets.UTF_8)
        target.writeText(
            replaceExactlyOnce(original, expectedText.get(), replacementText.get(), relativeFile.get()),
            StandardCharsets.UTF_8,
        )
    }

    companion object {
        internal fun replaceExactlyOnce(
            source: String,
            expected: String,
            replacement: String,
            label: String,
        ): String {
            if (expected.isEmpty()) {
                throw GradleException("Harness mutation for '$label' has an empty expected value.")
            }
            val first = source.indexOf(expected)
            val second = if (first >= 0) source.indexOf(expected, first + expected.length) else -1
            if (first < 0 || second >= 0) {
                val count = if (first < 0) 0 else 2
                throw GradleException(
                    "Harness mutation for '$label' expected exactly one source occurrence; found $count${if (second >= 0) "+" else ""}.",
                )
            }
            return source.replaceRange(first, first + expected.length, replacement)
        }
    }
}

/**
 * Compiles the `native/kitecodec-jni` adapter and links ONE shared JNI library against the opaque
 * helper archive and a static FFmpeg tree (S1.c.1 step 6).
 *
 * Three registrations exist (kitecodec-core/build.gradle.kts): the test-only macOS dylib that
 * jvmTest loads through the `kitecodec.jni.path` system property, and the two Android arms whose
 * outputs are the exact `jniLibs` inputs of the AAR. The Android arms use the NDK's clang with the
 * 16 KiB page flags and the version script; the macOS arm uses the system clang with an
 * `-exported_symbols_list`, because a Mach-O link does not read an ELF version script. Both
 * recipes were proved by hand at the S1.c scaffold (2026-08-12) before being encoded here.
 *
 * The output must export exactly `JNI_OnLoad`: `scripts/symbol-audit.sh` asserts it per arm, and
 * the S1.c.1 gate runs an ELF PT_LOAD 0x4000 check beside it for the Android arms.
 */
abstract class LinkKiteCodecJniTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** `native/kitecodec-jni`: the adapter sources, headers and manifest. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jniSources: ConfigurableFileCollection

    /** `native/kitecodec-c/include`: the opaque boundary headers. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val opaqueIncludeDir: DirectoryProperty

    /** The compiled opaque helper archive for this target. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val helperArchive: ConfigurableFileCollection

    /** The static FFmpeg install tree's `lib` directory for this target. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ffmpegLibDir: DirectoryProperty

    /** Absolute path of the C compiler driver (NDK clang for Android, /usr/bin/clang for macOS). */
    @get:Input
    abstract val compiler: Property<String>

    /** Directories added as `-I` beyond the adapter's own and the opaque include dir (JNI headers). */
    @get:Input
    abstract val extraIncludeDirs: ListProperty<String>

    /** Exact link flags AFTER the objects and archives (libraries, frameworks, export control). */
    @get:Input
    abstract val linkFlags: ListProperty<String>

    /** The one platform-specific file that limits the dynamic export surface to `JNI_OnLoad`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val exportControlFile: RegularFileProperty

    /** Selects the linker spelling for [exportControlFile]. */
    @get:Input
    abstract val exportControlKind: Property<ExportControlKind>

    /** Extra `-L` directories, absolute. */
    @get:Input
    abstract val libSearchDirs: ListProperty<String>

    /**
     * The generated-source root consumed by Android's `jniLibs` source API. It is deliberately
     * one level above the ABI directory, so AGP packages `arm64-v8a/...` and `x86_64/...` rather
     * than flattening either ABI out of the AAR.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** The shared library inside [outputDirectory]. The directory is the Gradle output. */
    @get:Internal
    abstract val outputLibrary: RegularFileProperty

    @TaskAction
    fun link() {
        val outputRoot = outputDirectory.get().asFile.canonicalFile
        val out = outputLibrary.get().asFile.canonicalFile
        assertOutputLibraryInsideDirectory(outputRoot, out)
        outputRoot.mkdirs()
        out.parentFile.mkdirs()
        val sources = jniSources.files.filter { it.name.endsWith(".c") }.sortedBy { it.name }
        if (sources.isEmpty()) throw GradleException("no kj_*.c sources found for $name")
        val jniDir = sources.first().parentFile

        val args = buildList {
            add(compiler.get())
            add("-shared")
            add("-fPIC")
            add("-fvisibility=hidden")
            add("-O2")
            add("-Wall"); add("-Wextra"); add("-Werror")
            add("-I"); add(jniDir.absolutePath)
            add("-I"); add(opaqueIncludeDir.get().asFile.absolutePath)
            extraIncludeDirs.get().forEach { add("-I"); add(it) }
            sources.forEach { add(it.absolutePath) }
            helperArchive.files.forEach { add(it.absolutePath) }
            add("-L"); add(ffmpegLibDir.get().asFile.absolutePath)
            libSearchDirs.get().forEach { add("-L"); add(it) }
            addAll(linkFlags.get())
            addAll(exportControlArguments(exportControlKind.get(), exportControlFile.get().asFile))
            add("-o"); add(out.absolutePath)
        }
        logger.lifecycle("linking ${out.name} with ${sources.size} adapter units")
        val result = execOperations.exec { commandLine(args) }
        result.assertNormalExitValue()
        if (!out.isFile) throw GradleException("link reported success but ${out.absolutePath} does not exist")
    }

    enum class ExportControlKind {
        ELF_VERSION_SCRIPT,
        MACHO_EXPORTED_SYMBOLS,
    }

    data class AndroidAbiRecipe(
        val linkTaskName: String,
        val helperTaskName: String,
        val ffmpegDirName: String,
        val konanTargetName: String,
        val ndkTarget: String,
        val abiDirectory: String,
        val outputRelativePath: String,
    )

    companion object {
        /**
         * The two S1.c Android arms. The ordinary Android KMP target does not register an
         * `androidNative*` target, so each recipe names its own dedicated opaque-helper producer.
         */
        val ANDROID_ABI_RECIPES: List<AndroidAbiRecipe> = listOf(
            AndroidAbiRecipe(
                linkTaskName = "linkKiteCodecJniAndroidArm64",
                helperTaskName = "compileKiteCodecCForJniAndroidArm64",
                ffmpegDirName = "android-arm64",
                konanTargetName = "android_arm64",
                ndkTarget = "aarch64-linux-android24",
                abiDirectory = "arm64-v8a",
                outputRelativePath = "kitecodec-jni/android-arm64/arm64-v8a/libkitecodec_jni.so",
            ),
            AndroidAbiRecipe(
                linkTaskName = "linkKiteCodecJniAndroidX64",
                helperTaskName = "compileKiteCodecCForJniAndroidX64",
                ffmpegDirName = "android-x64",
                konanTargetName = "android_x64",
                ndkTarget = "x86_64-linux-android24",
                abiDirectory = "x86_64",
                outputRelativePath = "kitecodec-jni/android-x64/x86_64/libkitecodec_jni.so",
            ),
        )

        /** The exact S1.c.1 Android link recipe after the objects and opaque helper archive. */
        fun androidLinkFlags(recipe: AndroidAbiRecipe): List<String> = listOf(
            "--target=${recipe.ndkTarget}",
            "-lavformat", "-lavcodec", "-lavfilter", "-lavutil", "-lswscale", "-lswresample",
            "-lmediandk", "-landroid", "-llog", "-lz", "-ldl", "-lm",
            "-Wl,-z,defs", "-Wl,-z,noexecstack", "-Wl,-z,relro", "-Wl,-z,now",
            "-Wl,--gc-sections", "-Wl,--exclude-libs,ALL",
            "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
        )

        fun exportControlArguments(kind: ExportControlKind, file: File): List<String> = when (kind) {
            ExportControlKind.ELF_VERSION_SCRIPT ->
                listOf("-Wl,--version-script=${file.absolutePath}")
            ExportControlKind.MACHO_EXPORTED_SYMBOLS ->
                listOf("-Wl,-exported_symbols_list,${file.absolutePath}")
        }

        internal fun assertOutputLibraryInsideDirectory(outputDirectory: File, outputLibrary: File) {
            val directoryPath = outputDirectory.canonicalFile.toPath()
            val libraryPath = outputLibrary.canonicalFile.toPath()
            if (libraryPath == directoryPath || !libraryPath.startsWith(directoryPath)) {
                throw GradleException(
                    "output library ${outputLibrary.absolutePath} must be inside output directory " +
                        outputDirectory.absolutePath,
                )
            }
        }
    }
}
