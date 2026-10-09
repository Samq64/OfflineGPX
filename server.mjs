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
// mapsforge builds from Geofabrik's extracts and mirrors its tree, so a file's real
// outline is the matching Geofabrik .poly; the header only has a bounding box.
const OUTLINES = 'https://download.geofabrik.de/';
const here = dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.PORT ?? 8787);

// Directory listings change about weekly; the tree is re-read rarely and cheaply.
const listings = new Map();
const CACHE_MS = 60 * 60 * 1000;

const outlines = new Map();

// A cut is held in memory whole, so both its size and how many run at once are bounded.
const MAX_EXTRACT_BYTES = 256 * 1024 * 1024;
const MAX_EXTRACTS = 2;
let extracts = 0;

// Running cuts by the page's job id, so it can poll how far upstream reading has got.
const jobs = new Map();

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

/**
 * A region's outline as rings of [lat, lon], or null where Geofabrik has no matching
 * polygon. Never fails the request: the bounding box is a fine fallback.
 */
async function outline(path) {
  const cached = outlines.get(path);
  if (cached && Date.now() - cached.at < CACHE_MS) return cached.value;

  let value = null;
  try {
    const response = await fetch(OUTLINES + path.replace(/\.map$/, '.poly'), { signal: AbortSignal.timeout(30_000) });
    if (response.ok) value = parsePoly(await response.text());
  } catch {
    // Unreachable or malformed: the box will do.
  }
  outlines.set(path, { at: Date.now(), value });
  return value;
}

/**
 * Osmosis polygon format: a name line, then rings of `lon lat` lines, each opened by a
 * name (`!` for a hole) and closed by END, then a final END. Holes are kept as rings,
 * since only the outline is drawn.
 */
function parsePoly(text) {
  const rings = [];
  let ring = null;
  for (const line of text.split('\n').slice(1).map((l) => l.trim()).filter(Boolean)) {
    if (line === 'END') {
      if (ring) rings.push(ring);
      ring = null;
    } else if (ring) {
      const [lon, lat] = line.split(/\s+/).map(Number);
      if (!Number.isFinite(lon) || !Number.isFinite(lat)) return null;
      ring.push([lat, lon]);
    } else {
      ring = [];
    }
  }
  return rings.length ? rings : null;
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
    const [header, rings] = await Promise.all([readHeader(source), outline(path)]);
    json(response, {
      path,
      bbox: {
        minLon: header.minLon, minLat: header.minLat,
        maxLon: header.maxLon, maxLat: header.maxLat,
      },
      outline: rings,
      intervals: header.intervals.map((i) => ({ baseZoom: i.baseZoom, min: i.minZoom, max: i.maxZoom })),
      comment: header.comment ?? null,
      createdBy: header.createdBy ?? null,
      requests: source.requests,
    });
  },

  /** Null counts once the job has finished, or for one that never started. */
  async '/api/progress'(url, response) {
    const source = jobs.get(url.searchParams.get('job'));
    json(response, { bytes: source?.bytes ?? null, requests: source?.requests ?? null });
  },

  async '/api/extract'(url, response) {
    const path = safePath(url.searchParams.get('path'));
    const bbox = parseBbox(url.searchParams.get('bbox'));
    const { minLon, minLat, maxLon, maxLat } = bbox;
    if (extracts >= MAX_EXTRACTS) {
      return json(response, { error: 'busy with other extracts; try again shortly' }, 503);
    }

    const source = new HttpSource(ROOT + path);
    const job = url.searchParams.get('job');
    const tracked = job && /^[a-z0-9]{1,32}$/i.test(job);
    const started = Date.now();
    extracts += 1;
    if (tracked) jobs.set(job, source);
    let output;
    try {
      output = await cut(source, bbox, { maxBytes: MAX_EXTRACT_BYTES });
    } finally {
      extracts -= 1;
      if (tracked) jobs.delete(job);
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
