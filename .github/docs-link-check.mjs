// Validates Markdown cross-references: that every relative link resolves to a file that exists,
// and every `#fragment` resolves to a heading in the target document.
//
// This exists because a documentation-only push is routed as inert and skips every test tier, so
// nothing else in CI reads these files. A link that rots is then indistinguishable from one that
// works until a reader follows it and lands nowhere -- and the docs deliberately link between pages
// instead of repeating facts, so broken navigation is not cosmetic: it hides the authoritative
// source a reader was sent to find.
//
// Pure functions here; the runner in docs-link-check-cli.mjs walks the tree.

/** Directories whose Markdown is not part of the project's documentation. */
const SKIP_DIRECTORIES = new Set(['node_modules', '.git', 'target', 'dist', 'playwright-report',
  'test-results', '.vite', 'artifacts']);

export const shouldSkipDirectory = (name) => SKIP_DIRECTORIES.has(name);

/**
 * GitHub's heading-to-anchor rule, reduced to what this repo needs: lowercase, drop backticks and
 * other punctuation, then turn each remaining space into a hyphen. Spaces are not collapsed, so
 * dropped punctuation between two spaces (`A — B`) leaves a double hyphen (`a--b`).
 */
export const headingSlug = (heading) => heading
  .replace(/^#+\s*/, '')
  .toLowerCase()
  .replace(/`/g, '')
  .replace(/[^\w\s-]/g, '')
  .trim()
  .replace(/\s/g, '-');

/** Every anchor a document defines, from its ATX headings. */
export const definedAnchors = (markdown) => new Set(
  markdown.split('\n')
    .filter((line) => /^#{1,6}\s/.test(line))
    .map(headingSlug)
);

/**
 * Inline links in a document, as `{ target, fragment }`.
 *
 * Fenced code blocks are excluded: a link inside an example is illustrative, and failing CI because
 * a sample response body contains a path would train people to work around this check.
 */
export const linksIn = (markdown) => {
  const links = [];
  let inFence = false;
  for (const line of markdown.split('\n')) {
    if (/^\s*```/.test(line)) {
      inFence = !inFence;
      continue;
    }
    if (inFence) continue;
    for (const match of line.matchAll(/\[[^\]]*\]\(([^)\s]+)\)/g)) {
      const raw = match[1];
      const hashIndex = raw.indexOf('#');
      links.push(hashIndex === -1
        ? { target: raw, fragment: null }
        : { target: raw.slice(0, hashIndex), fragment: raw.slice(hashIndex + 1) });
    }
  }
  return links;
};

/**
 * True when a link points outside the repository and cannot be resolved locally.
 *
 * External URLs are deliberately not fetched: a network check would make CI fail for reasons
 * unrelated to the commit, which is the fastest way to make a required check untrusted.
 */
export const isExternal = (target) => /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(target);
