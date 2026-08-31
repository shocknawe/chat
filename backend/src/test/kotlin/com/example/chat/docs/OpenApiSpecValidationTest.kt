package com.example.chat.docs

import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Fails the build if `docs/openapi.yaml` does not parse as a valid OpenAPI
 * document (`add-conversation-creation-presence-inspector` slice 0, task
 * 1.5).
 *
 * The contract is authored by hand and lives outside the module the Gradle
 * build otherwise governs (`../docs/openapi.yaml` relative to `backend/`),
 * so nothing else in this project's toolchain would catch a broken edit —
 * a hand-typed indentation slip or a dangling `$ref` would otherwise only
 * surface when a human (or a code generator) next opened the file.
 */
class OpenApiSpecValidationTest {

    /**
     * Resolves the repo-root `docs/openapi.yaml`. Walks upward from
     * `user.dir` rather than assuming a fixed relative depth, so the test
     * is robust to Gradle being invoked from the repo root, from `backend/`
     * (the normal case), or from an IDE's own working directory.
     */
    private fun findOpenApiSpec(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/openapi.yaml")
            if (candidate.isFile) {
                return candidate
            }
            dir = dir.parentFile
        }
        error(
            "Could not locate docs/openapi.yaml by walking up from " +
                "user.dir='${System.getProperty("user.dir")}'",
        )
    }

    @Test
    fun `docs openapi yaml parses as a valid OpenAPI document`() {
        val specFile = findOpenApiSpec()

        val parseOptions = ParseOptions().apply {
            isResolve = true
            isResolveFully = true
        }

        val result = OpenAPIV3Parser().readLocation(specFile.absolutePath, null, parseOptions)

        assertThat(result.messages)
            .describedAs(
                "docs/openapi.yaml ('%s') failed to parse/validate as OpenAPI",
                specFile.absolutePath,
            )
            .isEmpty()
        assertThat(result.openAPI)
            .describedAs("swagger-parser produced no OpenAPI model for '%s'", specFile.absolutePath)
            .isNotNull()
    }
}
