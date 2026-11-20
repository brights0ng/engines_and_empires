#!/usr/bin/env bash
# Written by Claude (GreedyFluids M7b).
# Pins a copy of GreedyFluids for Meteodyne (packaging option C, Bright 2026-11-14; docs/PROJECT.md): builds the given
# greedy_fluids clone's main jar and sources, publishes them into meteodyne/vendor/maven as
# dev.brights0ng:greedyfluids:<version>-<commit>, replacing the previous pin, and points meteodyne/gradle.properties at
# it. Commit vendor/maven and gradle.properties together afterwards.
#
#   tools/greedyfluids-update.sh <path to a greedy_fluids clone>     (Git Bash on Windows, or any bash)
#
# The pin names a commit, so the clone's jar inputs (src/main and its build files) must be committed; anything else in
# the clone (docs, proto, tests) may differ. The clone's Gradle needs a JDK (JAVA_HOME or java on the PATH).
set -euo pipefail

gf="${1:?usage: tools/greedyfluids-update.sh <path to a greedy_fluids clone>}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gf="$(cd "$gf" && pwd)"

inputs=(src/main build.gradle settings.gradle gradle.properties)
if ! git -C "$gf" diff --quiet HEAD -- "${inputs[@]}"; then
    echo "greedy_fluids: uncommitted changes in src/main or its build files; commit them first." >&2
    exit 1
fi
if [ -n "$(git -C "$gf" ls-files --others --exclude-standard -- src/main)" ]; then
    echo "greedy_fluids: untracked files in src/main; commit or remove them first." >&2
    exit 1
fi

sha="$(git -C "$gf" rev-parse --short=7 HEAD)"
base="$(sed -n 's/^version=//p' "$gf/gradle.properties" | tr -d '\r')"
version="$base-$sha"
repo="$root/meteodyne/vendor/maven"
# Gradle on Windows wants a Windows path; cygpath exists only in Git Bash/Cygwin.
repo_arg="$repo"
if command -v cygpath >/dev/null 2>&1; then repo_arg="$(cygpath -m "$repo")"; fi

rm -rf "$repo/dev/brights0ng/greedyfluids"
(cd "$gf" && ./gradlew publish "-Pgf.publishTo=$repo_arg" "-Pversion=$version" --console=plain -q)
# Checksums and the per-artifact .module/.pom stay: Gradle reads the .module, and they are small.
test -f "$repo/dev/brights0ng/greedyfluids/$version/greedyfluids-$version.jar"

props="$root/meteodyne/gradle.properties"
if grep -q '^greedyfluids_version=' "$props"; then
    # Keeps the line's own ending (CRLF in a Windows checkout).
    sed -i -E "s/^(greedyfluids_version=)[^\r]*/\1$version/" "$props"
else
    printf 'greedyfluids_version=%s\n' "$version" >> "$props"
fi
echo "Pinned dev.brights0ng:greedyfluids:$version (greedy_fluids $(git -C "$gf" rev-parse --abbrev-ref HEAD) at $sha) in meteodyne/vendor/maven."
