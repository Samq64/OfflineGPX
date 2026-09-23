// The prototype extract service.
//
// This exists as a server rather than a static page for one reason: download.mapsforge.org
// serves range requests but sends no Access-Control-Allow-Origin, so a browser cannot read
// it directly. The cut therefore happens here and the browser just gets the result.

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join, normalize } from 'node:path';
import { cut, parseBbox, readHeader } from './lib/cut.mjs';
import { HttpSource } from './lib/source.mjs';

const ROOT = 'https://download.mapsforge.org/maps/v5/';
const here = dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.PORT ?? 8787);

// Directory listings change about weekly; the tree is re-read rarely and cheaply.
const listings = new Map();
const CACHE_MS = 60 * 60 * 1000;

// A cut is held in memory whole, so both its size and how many run at once are bounded.
const MAX_EXTRACT_BYTES = 256 * 1024 * 1024;
const MAX_EXTRACTS = 2;
let extracts = 0;

/** One level of the upstream tree: the sub-directories and the .map files in it. */
async function list(path) {
  const cached = listings.get(path);
  if (cached && Date.now() - cached.at < CACHE_MS) return cached.value;

  const response = await fetch(ROOT + path, { signal: AbortSignal.timeout(30_000) });
  if (!response.ok) throw new Error(`upstream ${response.status} for ${path}`);
  const html = await response.text();

  const hrefs = [...html.matchAll(/href="([^"]+)"/g)].map((m) => m[1]);
  const value = {
    directories: hrefs.filter((h) => h.endsWith('/') && !h.startsWith('/') && !h.startsWith('?'))
      .map((h) => h.slice(0, -1)),
    maps: hrefs.filter((h) => h.endsWith('.map') && !h.includes('/')),
  };
  listings.set(path, { at: Date.now(), value });
  return value;
}

const json = (response, body, status = 200) => {
  response.writeHead(status, { 'content-type': 'application/json' });
  response.end(JSON.stringify(body));
};

/** Upstream paths are pasted into a URL, so they may only look like `a/b/c.map`. */
const safePath = (path) => {
  if (!path || !/^[a-z0-9/_.-]+$/i.test(path) || normalize(path) !== path || path.includes('..')) {
    throw new Error('bad path');
  }
  return path;
};

const routes = {
  async '/api/list'(url, response) {
    const path = url.searchParams.get('path') ?? '';
    json(response, await list(path === '' ? '' : `${safePath(path)}/`));
  },

  async '/api/source'(url, response) {
    const path = safePath(url.searchParams.get('path'));
    const source = new HttpSource(ROOT + path);
    const header = await readHeader(source);
    json(response, {
      path,
      bbox: {
        minLon: header.minLon, minLat: header.minLat,
        maxLon: header.maxLon, maxLat: header.maxLat,
      },
      intervals: header.intervals.map((i) => ({ baseZoom: i.baseZoom, min: i.minZoom, max: i.maxZoom })),
      comment: header.comment ?? null,
      createdBy: header.createdBy ?? null,
      requests: source.requests,
    });
  },

  async '/api/extract'(url, response) {
    const path = safePath(url.searchParams.get('path'));
    const bbox = parseBbox(url.searchParams.get('bbox'));
    const { minLon, minLat, maxLon, maxLat } = bbox;
    if (extracts >= MAX_EXTRACTS) {
      return json(response, { error: 'busy with other extracts; try again shortly' }, 503);
    }

    const source = new HttpSource(ROOT + path);
    const started = Date.now();
    extracts += 1;
    let output;
    try {
      output = await cut(source, bbox, { maxBytes: MAX_EXTRACT_BYTES });
    } finally {
      extracts -= 1;
    }
    const name = `${path.split('/').pop().replace(/\.map$/, '')}-extract.map`;

    console.log(
      `cut ${path} ${minLon},${minLat},${maxLon},${maxLat}`,
      `-> ${output.length} B in ${Date.now() - started} ms`,
      `(${source.requests} requests, ${source.bytes} B upstream)`,
    );
    response.writeHead(200, {
      'content-type': 'application/octet-stream',
      'content-disposition': `attachment; filename="${name}"`,
      'content-length': output.length,
      'x-upstream-requests': source.requests,
      'x-upstream-bytes': source.bytes,
    });
    response.end(output);
  },
};

createServer(async (request, response) => {
  const url = new URL(request.url, `http://localhost:${port}`);
  try {
    const route = routes[url.pathname];
    if (route) return await route(url, response);

    const file = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
    if (!/^[a-z0-9._-]+$/i.test(file)) throw new Error('not found');
    const body = await readFile(join(here, 'public', file));
    const type = file.endsWith('.html') ? 'text/html' : 'text/plain';
    response.writeHead(200, { 'content-type': `${type}; charset=utf-8` });
    response.end(body);
  } catch (error) {
    json(response, { error: String(error.message ?? error) }, error.status ?? 400);
  }
}).listen(port, () => console.log(`mapcut prototype on http://localhost:${port}`));
