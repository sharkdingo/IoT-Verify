# Device Identity Authority

This project is in active development, so device identity is intentionally strict.
There is no label/template fallback compatibility layer.

Verified against code on 2026-10-22. Source: board storage, node creation,
scene recommendation/import, model request conversion, traces, and fix apply.

For the full field-level contract across devices, rules, specs, traces, tasks, and
fix, see [Data authority model](data-authority-model.md).

## Canonical Contract

| Surface | Authoritative value | Display-only value |
| --- | --- | --- |
| Board node | System/scene-provided `DeviceNode.id` | User-provided `DeviceNode.label` |
| Rule source / command | Raw node id in board storage; normalized `varName` at model boundary | Rule text / labels |
| Specification condition | Raw node id in board storage; normalized `varName` at model boundary | `SpecConditionDto.deviceLabel` |
| Specification selected device | Raw node id in board storage; normalized `varName` at model boundary | `DeviceRefDto.deviceLabel` |
| NuSMV device variable | `DeviceVerificationDto.varName`, derived from node id | none |
| AI tools on an existing board | `deviceId` / node id | `deviceName` / `deviceLabel` |
| Complete AI scene draft | Backend-assigned portable id after alias rewriting | Recommended device label |
| Fix candidates | Resolved canonical id / SMV varName | none |

## Rules

- `DeviceNode.id` is the only persistent device identity.
- Ordinary UI creation and `add_device` generate this id independently of the display
  label. A full-replacement AI scene draft treats LLM ids as temporary aliases and rewrites every
  dependency to backend-assigned portable ids before returning the scene.
- An explicitly supplied display label is exact across manual and AI creation. A
  case-insensitive conflict performs no write and returns an available suggestion; only
  an omitted system-generated label may receive an automatic suffix.
- Standard scene JSON preserves explicit ids exactly so export/import remains lossless;
  it never silently renames a conflicting id.
- Device ids with leading or trailing Unicode `White_Space` or `U+FEFF` are rejected by
  scene import, targeted requests, and direct Board writes. Internal whitespace is preserved.
  Identity is never repaired by trimming; targeted device commands use the persisted id
  exactly.
- Targeted HTTP commands carry the id as query data rather than a URL path segment.
  A slash, dot segment, percent sign, or other delimiter in a valid id remains part of
  that identity. Clients pass the raw id to their query serializer; they do not pre-encode
  or normalize it. The endpoint contract lives in [Board API](../api/board.md#canvas-storage).
- AI device edits, deletion previews, rule/specification references, and device attack
  points validate their raw identity fields before lookup or model-name conversion.
  A malformed id is rejected instead of being trimmed into a different device reference.
- Device ids must also be unique after NuSMV normalization; two raw ids that map to the
  same `varName` are rejected at board save.
- Board save also rejects a stable id that would collide with the current scene's
  generated environment, rule-playback, attack-analysis, or existing-condition fix
  identifiers. The validation error is attached to `nodes[i].id`; the display label may
  remain unchanged.
- `DeviceNode.templateName` is the device's semantic template pointer and must match
  an existing template for the same user; deleting an in-use template is rejected.
- `label`, `deviceLabel`, `deviceName` labels, and template names are never used as
  identity fallback.
- Renaming a device changes display labels only. Rules, specs, traces, AI references,
  and fix candidates continue to target the same node id.
- Deleting a device removes dependent rules/specs by node id only.
- Rule conditions must provide `targetType` (`api`, `variable`, `mode`, or `state`);
  backend modeling does not infer semantics from the attribute name.
- Rule `targetType=api` conditions may reference only template APIs with
  `Signal=true` and must not include relation/value; rule commands may invoke any
  template API.
- Spec conditions must provide `deviceId` and `targetType`. `deviceLabel` is a UI
  snapshot and is ignored by validation/resolution.
- AI tools operating on an existing board must use canonical node ids. Scene-generation
  tools may use response-local aliases, which are never persisted directly. Returned
  labels are for readable UI text only.
- NuSMV and fix code resolve device references from canonical ids and fail fast on
  unknown ids.
- NuSMV variable names are derived from node ids with the shared safe-normalization
  algorithm: non-`[a-zA-Z0-9_]` characters become `_` and letters are lowercased. An
  all-underscore result becomes `device_0`; a leading digit and NuSMV reserved words
  are prefixed with `_`. The raw
  board node id remains the persisted identity; the normalized name is the model/trace
  boundary representation.
  Model and history readers apply the model identifier bound after normalization; the raw Board
  id bound cannot be reused because the required prefix can increase the identifier length.
  Non-ASCII Unicode code points each become one underscore on both sides of the API, including
  supplementary characters, so browser attack selections and replay references match the model.

If old local data was saved with labels as identity, clear the development database or
recreate the affected board data instead of adding compatibility branches.
