# Local Voice UI

The 1.0 baseline is preserved in `../evidence/ui-baseline/MainActivity-1.0.java`; screenshots `release-install-data-manager.png` and `fixture-global-cancel-before.png` show the old repeated instructions and large license controls.

## References read as text

- [UI UX Pro Max skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill/blob/56ea9cf174ed86786187daa9de0e6247cb0747e4/.claude/skills/ui-ux-pro-max/SKILL.md), [native UI checklist](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill/blob/56ea9cf174ed86786187daa9de0e6247cb0747e4/.claude/skills/ui-ux-pro-max/references/pro-rules.md), and [Compose guidance CSV](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill/blob/56ea9cf174ed86786187daa9de0e6247cb0747e4/src/ui-ux-pro-max/data/stacks/jetpack-compose.csv).
- Official Android skills: [adaptive](https://github.com/android/skills/blob/5bda39f154fb859a08d45e2392f3568e049f83fb/jetpack-compose/adaptive/SKILL.md), [edge to edge](https://github.com/android/skills/blob/5bda39f154fb859a08d45e2392f3568e049f83fb/system/edge-to-edge/SKILL.md), and [testing setup](https://github.com/android/skills/blob/5bda39f154fb859a08d45e2392f3568e049f83fb/testing/testing-setup/SKILL.md).
- Official [UI events](https://developer.android.com/topic/architecture/ui-layer/events), [Views insets](https://developer.android.com/develop/ui/views/layout/insets), and [accessibility](https://developer.android.com/guide/topics/ui/accessibility/apps) guidance takes precedence.

No third-party skill scripts were installed or executed. Compose-specific framework migrations, mandatory DI, and generic event-channel advice were not applied to this small native Java app.

## Decisions

- Three destinations: **Voices**, **Downloads**, **Settings**. About, licenses, paths, and runtime explanations are inside Settings or model Details. Ordinary screens use short labels and actionable state.
- Voice rows select and persist immediately. A separate right-hand Listen/Stop control previews without saving selection. Unique preview IDs suppress stale callbacks; `QUEUE_FLUSH` replaces previews. Caller preview rate is `1.0`, so the global 0.5–2.0 speed multiplier applies once.
- Full catalog rows are recycled by `ListView`; search is debounced and filtered on a worker. Language and search survive recreation. Catalog loading and explicit refresh run off the UI thread.
- Shared light/dark color tokens, native type scaling, 4/8 dp spacing, rounded surfaces, ripple feedback, 24 dp vector selection marks, and at least 48 dp controls. Selection has both a check mark and accessible checked state.
- One root handles system bars, cutouts, and IME insets. List headers scroll with rows so large fonts and short landscape windows keep controls reachable. Content width is bounded on wide windows.
- Download cards retain progress, cancel, retry, and complete error detail. Model terms and full notices are accessible before confirmation. Online privacy disclosure appears at the explicit enable action. Unsupported models have no fake download button.
- Shtorm's verified file pack uses **Install file** with Android's Storage Access Framework. The picker is offered only for a trusted catalog record marked `installMode=import`. The selected URI and pinned model ID go to ModelStore; archive configuration never becomes UI configuration. Store owns temporary/persistable read-grant cleanup. The pending model ID survives picker rotation; retries choose the file again. No hosted download is implied.

## Verification

The engine owner compiled the redesigned Java UI successfully. `tests/run-ui-state.ps1` passes 20 JVM cases using the actual filter and preview-session helpers: catalog replacement/removal, empty data, English discoverability, Unicode/locale matching, and late preview callbacks after replacement, stop, or destruction. Results: `build/verification/ui-state-checks.txt`. Eight light/dark text pairs pass 4.5:1 contrast (`../evidence/ui-static-contrast.json`).

Static lifecycle review verified paired start/stop listeners, destroyed-activity guards, generation checks for worker results, preference-backed selection/rate unaffected by destination changes, and refresh cancellation tokens created before a worker is queued. Actual Android interaction and device rendering remain separate evidence.

Device screenshot, small/landscape/large-font/dark/IME and TalkBack checks are pending: the user paused emulator/browser launches while another task investigates a `crashpad_handler.exe` exception. This UI worker launched neither emulator nor browser. After clearance, the sole emulator owner will check the three destinations; selected versus previewed voices; rapid Listen/Stop/replacement; slider persistence; missing/installed/downloading/failed models; explicit online consent; catalog refresh/cancel; full notices; keyboard focus; and rotation.
