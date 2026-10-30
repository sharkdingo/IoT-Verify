// @vitest-environment node
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = (path: string) => readFileSync(join(__dirname, path), 'utf8')

describe('shared RuleDto contract', () => {
  it('uses one declaration for live responses and frozen playback rules', () => {
    const board = source('../../api/board.ts')
    const model = source('../model.ts')
    expect(board).toMatch(/import type \{[^}]*RuleDto[^}]*\} from '\.\.\/types\/rule'/)
    expect(model).toContain("import type { RuleDto } from './rule'")
    expect(model).toContain('rules: RuleDto[]')
    expect(board).not.toContain('interface BackendRuleDto')
    expect(model).not.toContain('interface ModelRule')
    const declaration = source('../rule.ts')
    expect(declaration).toMatch(/id: number \| null/)
    expect(declaration).toMatch(/ruleString\?: string \| null/)
    expect(declaration).toMatch(/contentDevice\?: string \| null/)
    expect(declaration).toMatch(/content\?: string \| null/)
  })
})
