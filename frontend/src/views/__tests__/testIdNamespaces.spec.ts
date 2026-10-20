import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * A `data-testid` prefix is an interface, and overlapping prefixes make it ambiguous.
 *
 * `trace-timeline-state-{i}` names the per-step buttons on a counterexample rail. A `<details>` panel beside
 * it was called `trace-timeline-state-details`, so `[data-testid^="trace-timeline-state-"]` matched both.
 * Measuring a 27-state trace through that selector reported **28 steps, one 598px-wide "step", an
 * overlapping pair, and a step missing its accessible name** — four false findings from one name, each
 * plausible enough to chase. `SimulationTimeline` had the identical collision.
 *
 * The cost lands on whoever writes the next selector, which is why this belongs in a test rather than a
 * convention document.
 */

const DIRS = ['components', 'views']

const sources = () => {
  const files: Array<{ name: string, text: string }> = []
  for (const dir of DIRS) {
    const full = join(__dirname, '../..', dir)
    for (const entry of readdirSync(full, { withFileTypes: true })) {
      if (entry.isFile() && entry.name.endsWith('.vue')) {
        files.push({ name: `${dir}/${entry.name}`, text: readFileSync(join(full, entry.name), 'utf8') })
      }
    }
  }
  return files
}

describe('test-id namespaces', () => {
  it('never puts a static id inside an indexed family\'s prefix', () => {
    const staticIds: Array<{ id: string, where: string }> = []
    const indexedPrefixes = new Set<string>()

    for (const { name, text } of sources()) {
      for (const match of text.matchAll(/data-testid="([a-z0-9-]+)"/g)) {
        staticIds.push({ id: match[1], where: name })
      }
      // `:data-testid="`family-${expr}`"` declares the family `family`.
      for (const match of text.matchAll(/data-testid="`([a-z0-9-]+)-\$\{/g)) {
        indexedPrefixes.add(match[1])
      }
    }

    const collisions = staticIds
      .filter(({ id }) => [...indexedPrefixes].some((prefix) => {
        if (!id.startsWith(`${prefix}-`)) return false
        // A static id that the family itself can generate is the same element, not a collision:
        // `control-tab-${tab.id}` legitimately produces `control-tab-devices`. Only a *suffix that the
        // family could never emit* is ambiguous — in practice a descriptive word rather than a key.
        const suffix = id.slice(prefix.length + 1)
        return /^(details|panel|wrapper|container|list|header|footer|summary|body)$/.test(suffix)
      }))
      .map(({ id, where }) => `${where}: ${id}`)

    expect(collisions).toEqual([])
  })

  it('keeps shared state evidence outside the numbered marker families', () => {
    const board = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
    const timeline = readFileSync(join(__dirname, '../../components/SimulationTimeline.vue'), 'utf8')

    const evidence = readFileSync(join(__dirname, '../../components/PlaybackStateDetails.vue'), 'utf8')
    expect(evidence).toContain('data-testid="playback-state-details"')
    expect(board).toContain('<PlaybackStateDetails')
    expect(board).not.toContain('data-testid="trace-timeline-state-details"')
    expect(timeline).toContain('<PlaybackStateDetails')
    expect(timeline).not.toContain('data-testid="simulation-timeline-state-details"')
  })

  it('gives each playback timeline one x-axis control rather than two', () => {
    // The counterexample viewer and the simulation timeline both had a `<input type="range">` slider
    // labelled "jump to state" stacked directly above a clickable state rail, both full-width, both
    // horizontal, both mapping x-position to the same state index, both drawn in the same accent hue.
    // Two controls over one axis read as two timelines for a single sequence. The rail is the one that
    // cannot be replaced, because only it can show where the violation sits relative to where you are,
    // so the slider was deleted and the rail took over its drag capability via pointer capture.
    //
    // This guard locks the decision in place: if a range input reappears in either overlay, something
    // drifted back to the old shape.
    const board = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
    const timeline = readFileSync(join(__dirname, '../../components/SimulationTimeline.vue'), 'utf8')

    const strip = (source: string) => source.replace(/<!--[\s\S]*?-->/g, '')
    const terminal = readFileSync(join(__dirname, '../../components/PlaybackTerminal.vue'), 'utf8')
    const template = strip(terminal.slice(terminal.indexOf('<template>')))
    const railAt = template.indexOf(':data-testid="`${prefix}-track`"')
    expect(railAt, 'the shared rail must exist').toBeGreaterThan(-1)
    expect(template).not.toContain('type="range"')
    const railTag = template.slice(template.lastIndexOf('<div', railAt), template.indexOf('>', railAt))
    expect(railTag).toContain('role="group"')
    expect(railTag).toContain('@pointerdown="scrub"')
    expect(template).toContain('to="#board-playback-timeline"')
    expect(template).not.toContain('<slot name="timeline"')
    expect(template.indexOf('<slot name="details"')).toBeGreaterThan(railAt)

    for (const owner of [board, timeline]) {
      const start = owner.indexOf('<PlaybackTerminal')
      expect(start, 'both replay owners must use the shared terminal').toBeGreaterThan(-1)
      const end = owner.indexOf('</PlaybackTerminal>', start)
      expect(end).toBeGreaterThan(start)
      const markup = strip(owner.slice(start, end))
      expect(markup).not.toContain('type="range"')
      expect(markup).not.toContain('class="board-playback-rail"')
    }
  })
})
