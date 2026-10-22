# PostHog for JetBrains IDEs: Plan

A community port of [PostHog/posthog-vscode](https://github.com/PostHog/posthog-vscode) (MIT) to the IntelliJ Platform.

## Decisions

| Topic | Decision |
|---|---|
| First feature | **Inline flag status**: end-of-line hint after each flag check, hover details, warning on unknown flag keys |
| Languages | **JS/TS** (`.js .jsx .mjs .cjs .ts .tsx`) and **Java/Kotlin** (`.java .kt .kts`) |
| IDE support | **Every JetBrains IDE, free editions included**, plus Android Studio |
| Publishing | Independent community project on JetBrains Marketplace; not presented as an official PostHog product |

### What "every IDE" implies
Language code models (PSI) are only present where the language plugin is installed. Java/Kotlin PSI is missing in WebStorm, PyCharm, GoLand and others; JS PSI is missing in some IDEs (and was paid-only in IDEA before 2026.1). So v0.1 must not depend on any language plugin:

- **Depend only on `com.intellij.modules.platform`.** No `<depends>` on Java, Kotlin or JavaScript.
- **Detect calls with our own small scanner over the document text**, chosen by file extension. It works even when the IDE opens the file as plain text.
- **Render at the editor level** (`InlayModel.addAfterLineEndElement`, `MarkupModel` highlighters), which works for any file type, rather than with language-registered inlay hint providers.
- Later, optionally: PSI/UAST-based detection that switches on when the language plugin is present (for constant resolution, variant branches, cleanup refactors). Keep the scanner behind an interface so this can be added.

## v0.1 feature spec (ported from `flagDecorationProvider.ts`)

For every recognised flag call, append a hint at the end of the line:

| Condition | Text | Colour |
|---|---|---|
| Key not in project | `⚠ not in PostHog` + wavy yellow underline on the key | `#F9BD2B` |
| `active == false` | `○ inactive` | editor ghost-text grey |
| Linked experiment (no results yet in v0.1) | `⚗ experiment draft / running / complete` | `#1D4AFF` |
| Multivariate | `● N variants` | `#4CBB17` |
| Rollout % | `● 50%` | `#4CBB17` |
| Otherwise | `● enabled` | `#4CBB17` |
| Plus, if conditions > 0 | ` · N condition(s)` | |

- Hover over the hint or key: flag details plus "Open in PostHog" and "Copy key" links (port `buildHover`).
- Settings: show hints on/off; mode `always` / `currentLine`; extra client names; extra flag functions.
- Out of scope for v0.1: experiment results (`⚗ test leading 72%`), the create-flag quick-fix (needs write scope), autocomplete, event features, sidebar. See Roadmap.

### Call detection rules

**Receivers** (case-sensitive): `posthog`, `postHog`, `PostHog`, `client`, `ph`, plus user-configured names. Also nested receivers (`window.posthog?.x`, `this.posthog.x`) and optional calls (`?.`).

**Methods**

| Language | Methods | Key argument |
|---|---|---|
| JS/TS | `getFeatureFlag`, `isFeatureEnabled`, `getFeatureFlagPayload`, `getFeatureFlagResult`, `isFeatureFlagEnabled`, `getRemoteConfig` | 1st |
| JS/TS bare hooks | `useFeatureFlag`, `useFeatureFlagPayload`, `useFeatureFlagVariantKey`, plus configured functions | 1st |
| Java/Kotlin client (Android) | `isFeatureEnabled`, `getFeatureFlag`, `getFeatureFlagPayload`, `getFeatureFlagResult` | 1st (`key, defaultValue`) |
| Java/Kotlin server | same names | **2nd** (`distinctId, key, ...`) |
| Java/Kotlin stateless | `*Stateless` variants | 2nd |
| Java/Kotlin server, `evaluateFlags` | `isEnabled`, `getFlag`, `getFlagPayload`, `getFlagPayloadAs` on the result of `evaluateFlags(...)`, called directly (`posthog.evaluateFlags(id).isEnabled("k")`) or through a variable assigned from it (`val flags = posthog.evaluateFlags(id); flags.isEnabled("k")`) | 1st |

**Java/Kotlin key ambiguity.** The Android and server SDKs use the same method names with different argument order. Resolution, in order:
1. Kotlin named argument `key = "..."` wins.
2. `*Stateless` → 2nd argument.
3. Only one of args 1–2 is a string literal → that one.
4. Both are string literals (e.g. server `("user-1", "flag")` vs Android `("flag", "default")`) → prefer the one that exists in the flag cache; if neither or both exist, take the 2nd for `isFeatureEnabled` (Android's 2nd is a Boolean) and the 1st for the others.

Each rule gets a test case.

Extra rules found in the SDK sources (the server SDK now recommends `evaluateFlags`, which the plan didn't cover): `Companion` is skipped when finding the receiver (`PostHog.Companion.isFeatureEnabled`, as Java calls the Kotlin object); explicit type arguments are allowed (`getFlagPayloadAs<String>("k")`, `getFeatureFlag<string>('k')`); a Kotlin named argument `key = ...` settles the position even when its value isn't a literal.

Known limits of the text scanner: regular expression literals and JSX text can hide the rest of their line; `evaluateFlags` results are tracked by variable name within one file, not by type; a call whose first arguments contain generic commas (`f<A, B>()`) can mis-split arguments. The PSI-based scanner (roadmap) removes these.

**Scanner requirements:** skip comments; understand string literals (`'…'`, `"…"`, JS template literals without `${}`, Kotlin raw `"""…"""`); split arguments while tracking nested `()[]{}`; record the key's text range for underline and hover. Unresolved keys (variables, constants) are ignored in v0.1.

## Architecture

```
src/main/kotlin/.../
├── auth/        AuthService (OAuth PKCE + API-key fallback), credentials in PasswordSafe
├── api/         PostHogClient (java.net.http + kotlinx.serialization), models
├── cache/       FlagCache, ExperimentCache (project service, periodic refresh, change events)
├── detect/      FlagCallScanner interface; TextFlagCallScanner (JS/TS + Java/Kotlin)
├── editor/      FlagStatusEditorManager (EditorFactoryListener, DocumentListener,
│                debounced 200 ms, inlays + highlighters), FlagHover, label builder
├── settings/    App settings (host, auth) + project settings (projectId, names, display)
└── actions/     Sign in/out, select project, refresh
```

- Threading: network and scanning off the EDT, using the coroutine scope injected into the service; the scan runs in a read action; inlays and highlighters are updated on the EDT.
- Port pure logic (label building, flag classification) with attribution comments pointing to the VS Code source.

## Authentication

PostHog's OAuth server (`oauth.posthog.com`) supports PKCE, public clients, **dynamic client registration**, and **client ID metadata documents**. The VS Code client ID is tied to a `vscode://` redirect, so it can't be reused.

1. **Spike result:** a client ID metadata document (CIMD) works with a loopback redirect. The document is `docs/oauth/client.json`, served by GitHub Pages at `https://brenobarreto.github.io/posthog-intellij-plugin/oauth/client.json`, which is also the `client_id`. It lists `http://127.0.0.1/callback` without a port, so any port matches, per [PostHog's OAuth docs](https://posthog.com/docs/api/oauth.md). The token response includes `posthog_base_url`, which handles US/EU routing. Consequences: users see an "unverified application" notice, because verification requires HTTPS redirect URIs; the scopes in `OAuthConfig.SCOPES` must stay within those declared in the document. Still to confirm with a real sign-in: which scopes the token actually carries.
2. **Fallback, which also stays as an option:** a personal API key (`phx_…`) plus host (US / EU / custom), stored in `PasswordSafe`.

## Platform targets

- Build with IntelliJ Platform Gradle Plugin **2.19.x** and Gradle 9.x, starting from the official plugin template.
- `sinceBuild = 251` (2025.1), with no `untilBuild`. This covers recent IDEs and the current and previous Android Studio releases (Rabbit = 262). Compile for JVM 21; 2026.x IDEs run on JBR 25.
- Plugin Verifier matrix: IntelliJ IDEA (Community 2025.1 + unified latest), PyCharm, WebStorm, GoLand, PhpStorm, RubyMine, CLion, RustRover, Rider, Android Studio, on builds 251 and latest.
- Extra `runIde` tasks for WebStorm, PyCharm and Android Studio, to check by hand that plain-text-file support works.

## Testing

- **Scanner unit tests** (pure Kotlin, no IDE): table-driven, with fixtures ported from `posthog-vscode/playgrounds/{javascript,typescript,node-*,react-native-*}` plus new Java/Kotlin fixtures based on `posthog-android/posthog-samples`. Cover every argument-order rule above.
- **Label tests**: port the cases from `flagDecorationProvider.test.ts` and `flagClassification.test.ts`.
- **Platform tests** (`BasePlatformTestCase`): open a fixture, check the inlay text, the highlighter range and the `currentLine` mode.
- **API tests** against a fake HTTP server, covering pagination, 401/403 and an expired token.

## Milestones

| # | Goal | Done when |
|---|---|---|
| M0 | Scaffold | Template-based repo, MIT license with credit to posthog-vscode, `runIde` works, CI builds, tests and runs the verifier |
| M1 | Auth spike | We know whether OAuth with a loopback redirect works with PostHog; API-key path implemented either way |
| M2 | API + cache | Sign in, pick a project, flags/experiments fetched with pagination and refreshed periodically, change events fire |
| M3 | Scanner | All detection rules implemented and covered by unit tests for JS/TS + Java/Kotlin |
| M4 | Editor rendering | Hints, underline, hover, settings, `currentLine` mode; platform tests pass; checked by hand in IDEA free, WebStorm, PyCharm, Android Studio |
| M5 | Release v0.1 | Signed build, verifier passes on the matrix, first manual Marketplace upload, then `publishPlugin` to the `beta` channel |
| M6 | Showcase | Public release; PR to posthog.com as Rafael suggested |

## Publishing checklist

- Plugin ID: `io.github.<you>.posthog` (no "intellij"/"jetbrains" in the ID or name).
- Name: something like **"PostHog Toolkit (Community)"**. The description must state it's unofficial and community-maintained. Ask PostHog before using their logo; use our own icon until then.
- JetBrains account → Marketplace → upload the first ZIP by hand → review.
- Signing certificate and Marketplace token stored as CI secrets (`ORG_GRADLE_PROJECT_intellijPlatformPublishingToken`, plus certificate chain, private key and password).
- Releases: tag → CI → `signPlugin` → `publishPlugin` (channel `beta` first, then default).

## Roadmap after v0.1

1. Flag-key autocomplete (`CompletionContributor`; reuses cache and scanner)
2. Experiment results in hints (`⚗ test leading 72%`)
3. Create-flag quick-fix and toggle flag (write scopes)
4. Event volume hints + event-name autocomplete (HogQL queries)
5. Constant resolution through optional PSI/UAST integration
6. Tool window (flags/experiments list), then stale-flag scan and cleanup
7. More languages (Python, Go, Ruby), matching the VS Code extension

## Open items
- [ ] Ask Rafael (PostHog) about using the name and logo, and whether PostHog can register an OAuth client if dynamic registration doesn't allow loopback redirects
- [ ] Confirm that intention actions and inlays behave in plain-text files in WebStorm/PyCharm (if not, fall back to editor-level tooltips only)
