import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { dirname, resolve, relative, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { MarkdownIt, GithubSlugger } from '../tools/quality/runtime.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const markdown = new MarkdownIt({ html: true });
const validLink = markdown.validateLink.bind(markdown);
// Parse machine-local links so the checker can reject them without opening them.
markdown.validateLink = (href) =>
  /^file:|^[a-z]:[\\/]/i.test(href) || validLink(href);

export function inspectLinks(base, names) {
  const delivery = new Set(names);
  const errors = [];
  const cache = new Map();
  function parse(name) {
    if (cache.has(name)) return cache.get(name);
    const tokens = markdown.parse(
      readFileSync(resolve(base, name), 'utf8'),
      {},
    );
    const anchors = new Set();
    const slugger = new GithubSlugger();
    for (let i = 0; i < tokens.length; i++) {
      if (tokens[i].type === 'heading_open') {
        const inline = tokens[i + 1];
        const label = (inline.children ?? [])
          .filter((t) =>
            ['text', 'code_inline', 'image', 'softbreak', 'hardbreak'].includes(
              t.type,
            ),
          )
          .map((t) =>
            ['softbreak', 'hardbreak'].includes(t.type) ? ' ' : t.content,
          )
          .join('');
        anchors.add(slugger.slug(label));
      }
      const html =
        tokens[i].type === 'html_block'
          ? tokens[i].content
          : (tokens[i].children ?? [])
              .filter((child) => child.type === 'html_inline')
              .map((child) => child.content)
              .join('');
      for (const match of html.matchAll(
        /\b(?:id|name)\s*=\s*["']([^"']+)["']/g,
      ))
        anchors.add(match[1]);
    }
    const result = { tokens, anchors };
    cache.set(name, result);
    return result;
  }
  function check(source, href) {
    if (/^file:|^[a-z]:[\\/]/i.test(href)) {
      errors.push(
        `${source}: ${href}: machine-local file URL is not distributable`,
      );
      return;
    }
    if (/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(href)) return;
    let url;
    try {
      url = new URL(href, pathToFileURL(resolve(base, source)));
    } catch {
      errors.push(`${source}: invalid URL ${href}`);
      return;
    }
    const target = relative(base, fileURLToPath(url)).split(sep).join('/');
    if (
      !delivery.has(target) &&
      [...delivery].some((name) => name.toLowerCase() === target.toLowerCase())
    ) {
      errors.push(`${source}: ${href}: incorrect capitalization (${target})`);
      return;
    }
    if (target.startsWith('../') || !delivery.has(target)) {
      errors.push(
        `${source}: ${href}: target outside the delivery set (${target})`,
      );
      return;
    }
    const path = resolve(base, target);
    if (!existsSync(path) || !statSync(path).isFile()) {
      errors.push(`${source}: ${href}: file missing`);
      return;
    }
    // Directory enumeration detects case mismatches even on a case-insensitive filesystem.
    let current = base;
    for (const part of target.split('/')) {
      if (!readdirSync(current).includes(part)) {
        errors.push(`${source}: ${href}: incorrect capitalization`);
        return;
      }
      current = resolve(current, part);
    }
    if (url.hash && /\.(md|html?|svg)$/.test(target)) {
      let fragment;
      try {
        fragment = decodeURIComponent(url.hash.slice(1));
      } catch {
        errors.push(`${source}: ${href}: invalid fragment encoding`);
        return;
      }
      const anchors = target.endsWith('.md')
        ? parse(target).anchors
        : new Set(
            [
              ...readFileSync(path, 'utf8').matchAll(
                /\b(?:id|name)\s*=\s*["']([^"']+)["']/g,
              ),
            ].map((match) => match[1]),
          );
      if (!anchors.has(fragment))
        errors.push(`${source}: ${href}: fragment missing`);
    }
  }
  for (const name of names.filter((n) => n.endsWith('.md'))) {
    if (!existsSync(resolve(base, name))) {
      errors.push(`${name}: file missing`);
      continue;
    }
    const { tokens } = parse(name);
    for (const token of tokens) {
      for (const child of token.children ?? []) {
        if (child.type === 'link_open') check(name, child.attrGet('href'));
        if (child.type === 'image') check(name, child.attrGet('src'));
        if (child.type === 'html_inline')
          for (const match of child.content.matchAll(
            /\b(?:href|src)\s*=\s*["']([^"']+)["']/g,
          ))
            check(name, match[1]);
      }
      if (token.type === 'html_block')
        for (const match of token.content.matchAll(
          /\b(?:href|src)\s*=\s*["']([^"']+)["']/g,
        ))
          check(name, match[1]);
    }
  }
  return errors;
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const base = process.argv[2] ? resolve(process.argv[2]) : root;
  const { files } = JSON.parse(
    readFileSync(resolve(base, 'config/delivery-files.json'), 'utf8'),
  );
  const errors = inspectLinks(base, files);
  for (const error of errors) console.error(error);
  console.log(
    JSON.stringify({
      status: errors.length ? 'failed' : 'passed',
      documents: files.filter((n) => n.endsWith('.md')).length,
      errors: errors.length,
    }),
  );
  process.exitCode = errors.length ? 1 : 0;
}
