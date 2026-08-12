package io.github.yuroyami.kitecodec.buildtools

import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinkKiteCodecJniTaskTest {

    @Test
    fun bothAndroidAbiRecipesPinTheirDedicatedHelperProvidersAndExactLinkFlags() {
        val arm64 = LinkKiteCodecJniTask.AndroidAbiRecipe(
            linkTaskName = "linkKiteCodecJniAndroidArm64",
            helperTaskName = "compileKiteCodecCForJniAndroidArm64",
            ffmpegDirName = "android-arm64",
            konanTargetName = "android_arm64",
            ndkTarget = "aarch64-linux-android24",
            abiDirectory = "arm64-v8a",
            outputRelativePath = "kitecodec-jni/android-arm64/arm64-v8a/libkitecodec_jni.so",
        )
        val x64 = LinkKiteCodecJniTask.AndroidAbiRecipe(
            linkTaskName = "linkKiteCodecJniAndroidX64",
            helperTaskName = "compileKiteCodecCForJniAndroidX64",
            ffmpegDirName = "android-x64",
            konanTargetName = "android_x64",
            ndkTarget = "x86_64-linux-android24",
            abiDirectory = "x86_64",
            outputRelativePath = "kitecodec-jni/android-x64/x86_64/libkitecodec_jni.so",
        )

        assertEquals(listOf(arm64, x64), LinkKiteCodecJniTask.ANDROID_ABI_RECIPES)
        assertEquals(expectedAndroidLinkFlags("aarch64-linux-android24"), LinkKiteCodecJniTask.androidLinkFlags(arm64))
        assertEquals(expectedAndroidLinkFlags("x86_64-linux-android24"), LinkKiteCodecJniTask.androidLinkFlags(x64))
    }

    @Test
    fun elfAndMachOExportControlsAreContentTrackedAndConsumedByTheLinkRecipe() {
        val root = Files.createTempDirectory("kitecodec-jni-link-input-test").toFile()
        try {
            val elf = root.resolve("exports.map").apply { writeText("{ global: JNI_OnLoad; local: *; };\n") }
            val macho = root.resolve("exports.macos").apply { writeText("_JNI_OnLoad\n") }
            val project = ProjectBuilder.builder().build()
            val elfTask = newLinkTask(project, "linkElf", root, elf, LinkKiteCodecJniTask.ExportControlKind.ELF_VERSION_SCRIPT)
            val machoTask = newLinkTask(
                project,
                "linkMacho",
                root,
                macho,
                LinkKiteCodecJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS,
            )

            assertTrue(elf in elfTask.inputs.files.files, "exports.map is not a tracked link-task input")
            assertTrue(macho in machoTask.inputs.files.files, "exports.macos is not a tracked link-task input")
            assertEquals(
                listOf("-Wl,--version-script=${elf.absolutePath}"),
                LinkKiteCodecJniTask.exportControlArguments(elfTask.exportControlKind.get(), elf),
            )
            assertEquals(
                listOf("-Wl,-exported_symbols_list,${macho.absolutePath}"),
                LinkKiteCodecJniTask.exportControlArguments(machoTask.exportControlKind.get(), macho),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun coreBuildWiresBothDedicatedAndroidHelpersAndThePlatformExportControls() {
        val repoRoot = File(checkNotNull(System.getProperty("kitecodec.repo.root")))
        val source = repoRoot.resolve("kitecodec-core/build.gradle.kts").readText()
        val helperRegistrations = sourceSection(
            source,
            "val androidHelperTasks = LinkKiteCodecJniTask.ANDROID_ABI_RECIPES.associateWith { arm ->",
            "tasks.register<LinkKiteCodecJniTask>(\n        \"linkKiteCodecJniMacosArm64\",",
        )
        val macLinkRegistration = sourceSection(
            source,
            "tasks.register<LinkKiteCodecJniTask>(\n        \"linkKiteCodecJniMacosArm64\",",
            "// The two Android arms, exactly the S1.c.1 step 6 recipe.",
        )
        val androidLinkRegistrations = source.substring(
            sourceMarker(
                source,
                "LinkKiteCodecJniTask.ANDROID_ABI_RECIPES.forEach { arm ->",
            ),
        )

        assertSourceContains(
            helperRegistrations,
            "tasks.register<CompileKiteCodecCTask>(arm.helperTaskName)",
        )
        assertSourceContains(helperRegistrations, "konanTargetName.set(arm.konanTargetName)")
        assertSourceContains(
            helperRegistrations,
            "outputDir.set(layout.buildDirectory.dir(\"kitecodec-c-jni/${'$'}{arm.konanTargetName}\"))",
        )

        assertSourceContains(androidLinkRegistrations, "val helperCompile = androidHelperTasks.getValue(arm)")
        assertSourceContains(
            androidLinkRegistrations,
            "tasks.register<LinkKiteCodecJniTask>(arm.linkTaskName)",
        )
        assertSourceContains(androidLinkRegistrations, "dependsOn(helperCompile)")
        assertSourceContains(
            androidLinkRegistrations,
            "helperArchive.from(helperCompile.flatMap { it.outputDir.file(CompileKiteCodecCTask.ARCHIVE_NAME) })",
        )

        assertSourceContains(
            macLinkRegistration,
            "exportControlFile.set(jniDir.resolve(\"exports.macos\"))",
        )
        assertSourceContains(
            macLinkRegistration,
            "exportControlKind.set(LinkKiteCodecJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS)",
        )
        assertSourceContains(
            androidLinkRegistrations,
            "exportControlFile.set(jniDir.resolve(\"exports.map\"))",
        )
        assertSourceContains(
            androidLinkRegistrations,
            "exportControlKind.set(LinkKiteCodecJniTask.ExportControlKind.ELF_VERSION_SCRIPT)",
        )
    }

    @Test
    fun registrationManifestHasExactlyFourFieldsPerRecord() {
        val repoRoot = File(checkNotNull(System.getProperty("kitecodec.repo.root")))
        val manifest = repoRoot.resolve("native/kitecodec-jni/methods.def")
        val records = manifest.readLines().map(String::trim).filter { it.startsWith("KJ_METHOD(") }
        assertTrue(records.isNotEmpty(), "methods.def contains no KJ_METHOD records")

        records.forEach { record -> assertFourFieldRecord(record) }

        val corrupted = records.first().dropLast(1) + ", packet)"
        assertFailsWith<AssertionError> { assertFourFieldRecord(corrupted) }
    }

    private fun expectedAndroidLinkFlags(ndkTarget: String): List<String> = listOf(
        "--target=$ndkTarget",
        "-lavformat", "-lavcodec", "-lavfilter", "-lavutil", "-lswscale", "-lswresample",
        "-lmediandk", "-landroid", "-llog", "-lz", "-ldl", "-lm",
        "-Wl,-z,defs", "-Wl,-z,noexecstack", "-Wl,-z,relro", "-Wl,-z,now",
        "-Wl,--gc-sections", "-Wl,--exclude-libs,ALL",
        "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
    )

    private fun newLinkTask(
        project: org.gradle.api.Project,
        name: String,
        root: File,
        exportControl: File,
        kind: LinkKiteCodecJniTask.ExportControlKind,
    ): LinkKiteCodecJniTask {
        val jniDir = root.resolve("$name-jni").apply { mkdirs() }
        val includeDir = root.resolve("$name-include").apply { mkdirs() }
        val ffmpegLibDir = root.resolve("$name-ffmpeg-lib").apply { mkdirs() }
        val source = jniDir.resolve("kj_probe.c").apply { writeText("int kj_probe(void) { return 0; }\n") }
        val archive = root.resolve("$name-libkitecodec.a").apply { writeText("fixture") }
        return project.tasks.create(name, LinkKiteCodecJniTask::class.java).apply {
            jniSources.from(source)
            opaqueIncludeDir.set(includeDir)
            helperArchive.from(archive)
            this.ffmpegLibDir.set(ffmpegLibDir)
            compiler.set("clang")
            extraIncludeDirs.set(emptyList())
            linkFlags.set(emptyList())
            libSearchDirs.set(emptyList())
            exportControlFile.set(exportControl)
            exportControlKind.set(kind)
            outputLibrary.set(root.resolve("$name-output/libkitecodec_jni.so"))
        }
    }

    private fun assertFourFieldRecord(record: String) {
        assertTrue(record.endsWith(')'), "malformed manifest record: $record")
        val body = record.removePrefix("KJ_METHOD(").dropLast(1)
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var escaped = false
        body.forEach { character ->
            when {
                escaped -> {
                    field.append(character)
                    escaped = false
                }
                character == '\\' && quoted -> {
                    field.append(character)
                    escaped = true
                }
                character == '"' -> {
                    field.append(character)
                    quoted = !quoted
                }
                character == ',' && !quoted -> {
                    fields += field.toString().trim()
                    field.clear()
                }
                else -> field.append(character)
            }
        }
        fields += field.toString().trim()

        assertEquals(4, fields.size, "manifest records are class, name, descriptor and C function: $record")
        assertTrue(fields.take(3).all { it.startsWith('"') && it.endsWith('"') }, record)
        assertTrue(fields[3].matches(Regex("kj_[a-z0-9_]+")), record)
    }

    private fun sourceSection(source: String, start: String, end: String): String {
        val startIndex = sourceMarker(source, start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        assertTrue(endIndex >= 0, "build script is missing section terminator: $end")
        return source.substring(startIndex, endIndex)
    }

    private fun sourceMarker(source: String, marker: String): Int {
        val index = source.indexOf(marker)
        assertTrue(index >= 0, "build script is missing wiring marker: $marker")
        return index
    }

    private fun assertSourceContains(section: String, expected: String) {
        assertTrue(expected in section, "build-script wiring is missing: $expected")
    }
}
