// Written by ChatGPT.
package dev.brights0ng.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

/** Checks the generated test mod directory without capturing the Gradle project at execution time. */
@DisableCachingByDefault(because = 'Verification has no outputs')
abstract class VerifyGameTestMods extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    abstract ConfigurableFileCollection getExpectedMods()

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getModsDirectory()

    @TaskAction
    void verifyMods() {
        def expected = expectedMods.files.collect { it.name }.toSet()
        def actual = modsDirectory.get().asFile.listFiles()
                .findAll { it.isFile() && it.name.endsWith('.jar') }.collect { it.name }.toSet()
        if (actual != expected) {
            throw new GradleException("Aggregate game-test mods differ from declared colonyRuntime: expected ${expected}, found ${actual}")
        }
    }
}
