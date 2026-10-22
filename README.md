# PostHog Toolkit (Community)

![Build](https://github.com/brenobarreto/posthog-intellij-plugin/workflows/Build/badge.svg)

See your [PostHog](https://posthog.com) feature flags right next to the code that uses them, in any JetBrains IDE.

> **Community project.** This is not an official PostHog product. It is a port of PostHog's
> [VS Code extension](https://github.com/PostHog/posthog-vscode) to the IntelliJ Platform.

**Status:** early development. See [PLAN.md](PLAN.md) for scope and milestones.

## Planned for v0.1

- Inline flag status after flag checks (`● 50% · 2 conditions`, `○ inactive`, `⚠ not in PostHog`)
- Hover details for each flag
- JavaScript, TypeScript, Java and Kotlin
- Every JetBrains IDE (2025.1+), including free editions and Android Studio

## Development

Requires JDK 21 (Gradle downloads it automatically via the toolchain resolver if missing).

```bash
./gradlew runIde        # launch a sandbox IDE (IntelliJ IDEA 2025.1, the oldest supported) with the plugin
./gradlew runIdeaLatest # ...or the latest IntelliJ IDEA; also runWebStorm, runPyCharm, runAndroidStudio
./gradlew check         # run tests
./gradlew verifyPlugin  # run the Plugin Verifier against the IDEs in build.gradle.kts
./gradlew buildPlugin   # build the ZIP in build/distributions
```

## License

[MIT](LICENSE). Includes logic ported from PostHog for VS Code, also MIT; see [NOTICE](NOTICE).
