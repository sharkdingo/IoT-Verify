import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

export function checkCIResults(needs) {
  assert.equal(needs.route.result, 'success', 'routing must succeed before trusting its decision');
  const outputs = needs.route.outputs;
  for (const key of ['full', 'smoke', 'frontend', 'backend']) {
    assert.ok(['true', 'false'].includes(outputs[key]), `${key} must be an explicit routing decision`);
  }
  const full = outputs.full === 'true';
  const smoke = outputs.smoke === 'true';
  const required = {
    frontend: !full && (outputs.frontend === 'true' || smoke),
    backend: !full && (outputs.backend === 'true' || smoke),
    smoke: !full && smoke,
    escalate: full,
  };
  for (const [job, selected] of Object.entries(required)) {
    assert.equal(needs[job]?.result, selected ? 'success' : 'skipped',
      `${job} must ${selected ? 'pass' : 'be skipped'} for the selected tier`);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  checkCIResults(JSON.parse(process.env.CI_NEEDS_JSON));
  console.log('CI satisfied: every selected tier passed.');
}
