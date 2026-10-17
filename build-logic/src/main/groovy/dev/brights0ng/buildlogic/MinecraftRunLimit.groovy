package dev.brights0ng.buildlogic

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * A build service with no state: every Minecraft run task uses it, and it allows one user at a time, so the bare runs of
 * each mod and the aggregate run queue up instead of starting one game per mod at once under org.gradle.parallel.
 */
abstract class MinecraftRunLimit implements BuildService<BuildServiceParameters.None> {
}
