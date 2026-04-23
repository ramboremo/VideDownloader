package com.cognitivechaos.xdownload

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rebranding Property Test -- Task 9.1
 *
 * Property 1: No old package identifier in any Kotlin source file.
 *
 * Enumerates all .kt files under app/src/ and asserts that none of them
 * contain `com.videdownloader.app` in any `package` or `import` statement.
 *
 * **Validates: Requirements 5.3, 5.4, 5.6**
 */
class RebrandingPropertyTest {

    private val oldPackageId = "com.videdownloader.app"

    /**
     * Property 1: For every .kt file under app/src/, the file content must NOT
     * contain the old package identifier `com.videdownloader.app` in any
     * `package` or `import` statement.
     *
     * **Validates: Requirements 5.3, 5.4, 5.6**
     */
    @Test
    fun `no old package identifier in any Kotlin source file`() {
        // Resolve app/src/ relative to the working directory used by Gradle unit tests.
        // Gradle runs unit tests with the module root (app/) as the working directory.
        val appSrcDir = File("src")
        assertTrue(
            "Test setup error: expected 'src' directory to exist at ${appSrcDir.absolutePath}. " +
                "Ensure the test is run from the app/ module directory.",
            appSrcDir.exists() && appSrcDir.isDirectory
        )

        val ktFiles = appSrcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        assertTrue(
            "Test setup error: no .kt files found under ${appSrcDir.absolutePath}.",
            ktFiles.isNotEmpty()
        )

        val violatingFiles = ktFiles.filter { file ->
            file.readLines().any { line ->
                val trimmed = line.trim()
                (trimmed.startsWith("package") || trimmed.startsWith("import")) &&
                    trimmed.contains(oldPackageId)
            }
        }

        assertTrue(
            "REBRANDING INCOMPLETE: The following ${violatingFiles.size} Kotlin file(s) still " +
                "contain the old package identifier '$oldPackageId' in a package or import statement:\n" +
                violatingFiles.joinToString("\n") { "  - ${it.path}" },
            violatingFiles.isEmpty()
        )
    }
}
