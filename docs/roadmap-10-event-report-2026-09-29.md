# Mishka Roadmap-10 Event Report

- Report date: 2026-09-29 (Asia/Shanghai)
- Repository: `/Volumes/SanDisk/Projects/Mishka`
- Working branch: `codex/mishka-roadmap-10`
- Baseline commit: `311e687` (`settings: Add Tailscale Tailnet outbound and device status`)
- Remote fork: `fork/main` still points to `311e687b19e64bc9f9f5308d1e642a02003bbae3`
- Report scope: preserve and review the uncommitted work produced during the first delegation attempt; no commit or push performed.

## 1. Delegation incident

The first attempt dispatched six concurrent workers. The requested `deepseek-flash-max` model is not exposed as an exact selectable model in this environment; the available model identifier is `deepseek-flash` with `max` reasoning. The UI showed `6-sol`, so model selection was not confirmed and the workers were stopped.

The workers wrote uncommitted changes into the current checkout before shutdown. No `git add`, `git commit`, or `git push` was executed. The current working tree must therefore be treated as an unreviewed aggregate patch, not as accepted implementation.

## 2. Progress by roadmap item

| # | Item | Current evidence | Status | Main risk / next action |
|---|---|---|---|---|
| 1 | Keystore + backup secret handling | `SecretStore.kt` was added; `PlatformStorage` exposes secret access; `BackupManager` excludes `TAILSCALE_AUTH_KEY`; `MishkaApplication` contains migration wiring. | Partial | Verify migration, fallback behavior, Android Keystore failure handling, and no plaintext persistence. |
| 2 | Tailscale empty/invalid configuration | `ProfileTransformWriter` now fails when enabled without an auth key and validates control URL; `TailscaleSettingsScreen` has additional UI/state changes. | Partial | Review UX, validation semantics, stale generated-file cleanup, and all locales. |
| 3 | Automated tests | New tests exist under `app/src/test/kotlin`, including transform, serialization, selection, and runtime override tests. | Partial | Build/test result is still pending; inspect test isolation and production coupling. |
| 4 | Real-device acceptance | `scripts/acceptance/collect-device-evidence.sh` exists and is read-only except optional ADB port forwarding. | Preparation only | Requires an actual authorized Android device and separate VPN/ROOT/Tailscale test execution. |
| 5 | Shortcuts / automation | `AppShortcut.kt` and `res/xml/shortcuts.xml` were added. | Partial | Confirm `MainActivity` consumes shortcut actions and that no external unsafe action surface was introduced. |
| 6 | ROOT dynamic traffic notification | `DynamicNotificationManager`, `MishkaTunService`, `MishkaRootService`, and settings were modified. | Partial | Review lifecycle, duplicate collectors, root attach behavior, and notification permission/state transitions. |
| 7 | Final config preview / diagnostics | `ConfigDiagnostics.kt` and `ConfigDiagnosticsBuilder.kt` were added. | Partial | Confirm the feature is reachable from navigation/UI and never exposes secrets or subscription content. |
| 8 | Network handover | No clear `WifiPolicyMonitorService`/network-handover implementation is present in the current diff. | Not started / unconfirmed | Needs a separate bounded audit before implementation; do not infer success from existing Wi-Fi policy behavior. |
| 9 | Widget + connection app icons | `MishkaWidgetProvider` and widget resources were added; `ConnectionScreen`/`AppIcon` changed. | Partial | Confirm manifest/layout/resources, widget refresh lifecycle, package lookup, and VPN permission UX. |
| 10 | README / changelog / release/CI | `README.md`, `CHANGELOG.md`, and `scripts/` changed or were added. | Partial | Review claims against code, CI scope, generated files, and avoid documenting unverified device behavior. |

## 3. Current workspace evidence

Tracked files changed include:

- Tailscale/secret path: `PlatformStorage.kt`, `SecretStore.kt`, `BackupManager.kt`, `ProfileTransformWriter.kt`, `MishkaApplication.kt`, `TailscaleSettingsScreen.kt`
- Runtime/notification path: `DynamicNotificationManager.kt`, `MishkaRootService.kt`, `MishkaTunService.kt`, `RuntimeOverrideBuilder.kt`, `SettingsScreen.kt`
- User-facing surfaces: `AppShortcut.kt`, widget sources/resources, `ConnectionScreen.kt`, `AppIcon.kt`
- Diagnostics: `ConfigDiagnostics.kt`, `ConfigDiagnosticsBuilder.kt`
- Tests: `app/src/test/kotlin/...`
- Documentation/acceptance: `README.md`, `CHANGELOG.md`, `scripts/acceptance/collect-device-evidence.sh`

The working tree also contains pre-existing unrelated items such as the dirty `scripta` submodule, `.DS_Store`, `.codegraph`, `.zcodeignore`, and `接手.md`; these were not changed by this review.

## 4. Verification boundary

- `git diff --check`: passed before this report.
- Combined `:app:compileDebugKotlin` and `:app:testDebugUnitTest` finished with failure. Main confirmed failures include unresolved service action constants in `ProxyServiceController`/`MishkaRootService`, resource and layout references in the new widget path, duplicate companion-object structure in `MishkaWidgetProvider`, and a Russian string formatting warning. The aggregate patch is not buildable yet.
- No real Android device result is available in this event.
- No commit, push, release, or destructive cleanup was performed.

## 5. Safe next phase

1. Finish and record the compile/test result.
2. Review the aggregate diff by task boundary and remove accidental cross-task changes.
3. Run a small number of bounded review workers only for the incomplete/high-risk items.
4. Re-run compile, tests, and `git diff --check`.
5. Keep the result uncommitted until the user explicitly authorizes commit/push.

## 6. Second delegation pass result

One bounded `deepseek-flash` agent with `max` reasoning was used after the first failed aggregate build; no new user-facing thread was created by the orchestration layer. It repaired compile-only regressions in the ROOT service and widget resource path without committing or pushing.

Confirmed repaired areas:

- ROOT service brace/lifecycle structure and `ServiceStartStateMachine` token completion;
- widget manifest, layout, drawable, colors, and strings;
- invalid `RemoteViews.setBoolean` call and duplicate companion object;
- `git diff --check`, `:app:compileDebugKotlin`, and `:app:processDebugResources` passed in the agent's targeted validation.

Remaining after this pass:

- widget refresh methods have no production caller, so the widget is buildable but not yet proven live/updating;
- Russian string formatting warning remains;
- no real-device UI or VPN/ROOT/Tailscale acceptance has been performed;
- aggregate changes still need parent-level review and must not be committed yet.

## 7. Parent-level verification after repair

- `./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a --no-daemon --console=plain`: **BUILD SUCCESSFUL**.
- `./gradlew :app:testDebugUnitTest -x buildMihomo_arm64_v8a --no-daemon --console=plain`: **BUILD SUCCESSFUL**, 47 tests completed.
- The initial test command without `-x buildMihomo_arm64_v8a` also exposed an environment/toolchain failure in `/Volumes/SanDisk/Developer/SDKs/go/src/internal/strconv` (duplicate/undefined standard-library symbols); this is unrelated to Kotlin/test assertions, so test verification used the repository-prescribed native-build skip.
- Three newly added test expectations were corrected to match the deliberate implementation: HTTPS-only control URLs and the generated JavaScript field syntax. No production behavior was changed for those assertion fixes.

## 8. Parent review findings and correction

- Fixed one concrete diagnostics bug: `ConfigDiagnosticsBuilder` was still checking the migrated Tailscale auth key through `getString`, which reported a configured Keystore secret as missing. It now uses `PlatformStorage.getSecret`.
- Re-ran `:app:compileDebugKotlin -x buildMihomo_arm64_v8a`: **BUILD SUCCESSFUL**.
- Shortcuts are currently not fully wired: `AppShortcut.kt` and `res/xml/shortcuts.xml` exist, but no `android.app.shortcuts` activity metadata or `MainActivity` action handling is present in the current tree.
- Widget resources/provider now compile, but `MishkaWidgetProvider.refresh/updateState/updateFromTraffic` have no production caller; the widget is registered but not live-synchronized.
- Diagnostics models/builder exist, but no settings route/screen currently exposes them.
- `SecretStore` still deliberately falls back to plaintext prefs when Android Keystore is unavailable; Keystore migration is therefore improved but not an absolute no-plaintext guarantee.

## 9. Third delegation pass — 2026-09-30

This pass used three bounded workers inside the current task workspace; no new user-facing Codex threads were created. The callable model identifier was `deepseek-flash` with `max` reasoning; this environment does not expose a separate `deepseek-flash-max` model ID, so the UI model label was not treated as independently verified.

### Completed and parent-reviewed

| Item | Result | Evidence / boundary |
|---|---|---|
| #1 Keystore + backup secret handling | **Improved to fail closed** | `SecretStore` now refuses plaintext fallback; `PlatformStorage.getSecret` treats unavailable Keystore as not configured, and `putSecret` reports a secure-storage error to the Tailscale UI. Legacy plaintext is migrated only after a usable Keystore is available; backup still excludes the key. |
| #2 Tailscale configuration | **Implemented, build-verified** | Empty auth key and invalid non-HTTPS control URL are rejected; generated transform cleanup and localized messages remain in the aggregate patch. Real Tailscale runtime is unverified. |
| #3 Automated tests | **51 JVM tests passing** | Existing four test classes plus `ServiceStartStateMachineTest` (three cases); no device/native assertions. |
| #4 Device acceptance | **Preparation only** | `scripts/acceptance/collect-device-evidence.sh` exists, but no authorized device run was performed. |
| #5 Shortcuts / automation | **Wired** | Manifest metadata, fixed shortcut actions, allowlist, `MainActivity` consumption, navigation, and service-controller start/stop paths are present. A parent review moved initial shortcut consumption until after `HomeViewModel` injection to avoid a startup crash. |
| #6 ROOT dynamic notification | **Lifecycle fixes implemented** | Notification collector swap is synchronized and checks coroutine activity before notifying, preventing duplicate collectors and post-stop notification resurrection. ROOT background refresh remains best-effort because device idle may batch updates. |
| #7 Diagnostics | **Reachable** | Settings → Diagnostics route/screen/VM/DI wiring is present; output is summary-only and excludes auth keys, secrets, external-controller and subscription body. Native transform validation path exists but still requires device/native acceptance. |
| #8 Network handover | **Observation implemented; seamless handover not claimed** | `NetworkHandoverMonitor` observes default transport changes with a 3-second debounce and does not restart mihomo or reapply netfilter rules. Low-interruption core switching remains intentionally unimplemented pending runtime evidence/design. |
| #9 Widget + connection icons | **Wired, runtime unverified** | Widget provider is registered; state and traffic refresh callers are connected; shortcut/widget resources and app icon changes compile. Real launcher/widget/VPN authorization behavior remains untested. |
| #10 Docs / CI / release | **Docs updated; release not verified** | README/CHANGELOG/scripts remain uncommitted aggregate changes. ROOT documentation now describes dynamic notification as foreground-reliable/background-best-effort; no release or CI run was claimed. |

### Parent verification after this pass

- `git diff --check`: **passed**.
- `./gradlew :app:compileDebugKotlin :app:processDebugResources -x buildMihomo_arm64_v8a --rerun-tasks --no-daemon --console=plain`: **BUILD SUCCESSFUL**. A pre-existing Russian resource format warning was removed by marking the percentage-only density summary `formatted="false"` in all locales.
- `./gradlew :app:testDebugUnitTest -x buildMihomo_arm64_v8a --rerun-tasks --no-daemon --console=plain`: **BUILD SUCCESSFUL**, 51 tests, 0 failures.
- Native-inclusive build remains **unverified**: the local Go SDK under `/Volumes/SanDisk/Developer/SDKs/go` previously failed in `internal/strconv` with duplicate/undefined standard-library symbols.
- No real-device acceptance, commit, push, release, or destructive cleanup was performed.

### Remaining risks

1. Verify VPN, ROOT TUN, ROOT TPROXY, Tailscale and widget/shortcut behavior on an authorized Android device; JVM/compile success is not runtime proof.
2. Decide separately whether network handover needs a tested seamless-restart design; the current monitor deliberately avoids speculative restarts and avoids racing Wi-Fi policy restarts.
3. Review the aggregate patch before any commit. The working tree still contains unrelated pre-existing `.DS_Store`, `.codegraph`, dirty `scripta`, `.zcodeignore`, and `接手.md` items; none were reset or deleted.
