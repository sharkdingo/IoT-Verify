# Style guards, and what they are each scoped to

These files assert properties of the stylesheets and component styles that no type checker sees. A guard here is
worth exactly what it can fail on, so this note records the scope of each — because a scope mismatch reads
identically to a bug, in both directions.

## Every guard in this directory was mutation-audited

Each was tested by injecting the violation it owns and confirming it goes red. Results:

| Guard | Caught its violation | Scope |
| :--- | :---: | :--- |
| `radiusScale` | yes | component `<style scoped>` and `board.css` |
| `typographyFloor` | yes | component `<style scoped>` and `board.css` |
| `elevationScale` | yes | `base.css` + `board.css` |
| `inkFillSeparation` | yes | `board.css` |
| `disabledFillLegibility` | yes | component `<style scoped>` only |
| `semanticColourOwnership` | yes | Tailwind hue names in **templates** |
| `scrollOwnership` | yes | undefined `scrollbar-*` classes |
| `buttonCursor` | yes, after repair | `board.css`, any selector depth |
| `navTargetSize` | yes, after repair | narrow-viewport nav rules, rem **and** px |
| `reducedMotion` | yes, after repair | the reduced-motion block's own braces |
| `emptyGroupDisclosure` | yes, after repair | `v-for`-driven `<details>` |
| `playbackOverlayCaps` | yes | both timeline height ceilings |
| `sliderTrackVisibility` | yes | range tracks and the field normaliser |
| `scopedWidthOverride` | yes | scoped `max-*` against a Tailwind cap |
| `roleClassVariants` | yes | `hover:`/`disabled:` variants of hand-written classes |
| `boardDockGeometry` | yes | dock rail widths and the injected gap |
| `toastPointerTransparency` | yes | every `.css` file and `.vue` `<style>` block under `src/`, `@media`-nested rules included |

## Two traps that produced false findings while auditing them

**A probe that does not land.** Injecting a `<style scoped>` rule into a component that has no `<style scoped>`
block is a silent no-op, and the guard then "fails to catch" a violation that was never there. That nearly got
`disabledFillLegibility` recorded as vacuous. Always confirm the injection is present before believing the result.

**A violation outside the guard's scope.** `focusIndicator` deliberately permits `outline: none` — it is normally
paired with a box-shadow ring — and `semanticColourOwnership` scans Tailwind hue *names*, not raw `rgb()`. Neither
firing on those inputs is correct behaviour, not a gap. A pattern-based sweep for "an empty assertion with no
non-empty floor" flags all sixteen of these files; every one resolved as either verified or correctly scoped, so
that heuristic alone is not evidence.

## The shapes that made five of these vacuous before repair

Recorded because they recur: an **empty scan** (a loop over a selector or unit that matches nothing), a **wrong
slice** (a source window that excludes the place the defect lives — one overran its block by 17,904 characters), an
**unfalsifiable claim** (asserting something the framework can never emit), an **unreached path** (a fixture that
never enters the branch it names), and an **`expect` at `describe` scope**, which throws during collection so the
whole file reports "no tests" while looking green.
