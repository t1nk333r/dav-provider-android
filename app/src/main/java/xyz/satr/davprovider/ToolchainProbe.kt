package xyz.satr.davprovider

import at.bitfire.dav4jvm.ktor.DavResource
import java.time.Instant

/**
 * Exists to prove the toolchain at build time.
 *
 * Referencing a dav4jvm type forces its Kotlin metadata (mv=[2,4,0]) to be read,
 * which fails under AGP 9's bundled KGP 2.2.10 and succeeds under the 2.4.20
 * override in the root build file. Touching java.time forces core library
 * desugaring to be exercised at minSdk 24.
 *
 * Note the package: 4.x moved the Ktor-based API to at.bitfire.dav4jvm.ktor.
 */
internal object ToolchainProbe {
    fun davResourceName(): String = DavResource::class.qualifiedName ?: "unknown"
    fun now(): Instant = Instant.now()
}
