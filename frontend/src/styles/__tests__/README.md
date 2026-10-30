# Focused UI contracts

These checks protect a small set of contrast, input, interaction and evidence invariants.
Source checks inspect declarations or markup; they do not establish rendered layout or
cover every selector and viewport. See the [CI coverage policy](../../../../docs/development/ci.md#coverage-policy).

| Spec | Responsibility |
| :--- | :--- |
| `themeContrast` | Resolved semantic and node-tone contrast pairs |
| `neutralSurfaceOwnership` | Neutral surfaces, fields, progress and switches across the palettes |
| `inkFillSeparation` | Distinct text inks and filled surfaces |
| `neutralTextContrast` | Text roles applied to their intended neutral grounds |
| `targetSizeFloor` | Shared control targets and navigation overrides |
| `focusIndicator` | Contrast of keyboard focus indicators |
| `reducedMotion` | Reduced decoration with usable progress and focus cues |
| `tokenReferenceIntegrity` | References to declared or runtime-injected custom properties |
| `toastPointerTransparency` | Notifications leave underlying pointer targets reachable |
| `recommendationPanelParity` | Localized explanations and disclosure of server-adjusted values |

The former typography, radius, elevation, vocabulary, shell and geometry inventories are
retired. Review those visual decisions on rendered components using the UI conventions;
add a focused regression only for a named behavior or interaction risk.
