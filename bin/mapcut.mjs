#!/usr/bin/env node
// Cuts a rectangle out of a .map file, local or remote.
//
//   mapcut <source> <out.map> <minLon,minLat,maxLon,maxLat>
//
// The source may be a path or an https URL; a URL is read with range requests and only
// the bytes inside the box are transferred.

import { writeFile } from 'node:fs/promises';
import { cut, readHeader } from '../lib/cut.mjs';
import { FileSource, HttpSource } from '../lib/source.mjs';

export const openSource = (location) =>
  /^https?:\/\//.test(location) ? new HttpSource(location) : new FileSource(location);

const [location, destination, box] = process.argv.slice(2);
if (!location || !destination || !box) {
  console.error('usage: mapcut <source> <out.map> <minLon,minLat,maxLon,maxLat>');
  process.exit(2);
}

const [minLon, minLat, maxLon, maxLat] = box.split(',').map(Number);
const source = openSource(location);

const header = await readHeader(source);
console.error(
  `source: ${header.minLon},${header.minLat} .. ${header.maxLon},${header.maxLat}`,
  `| ${header.intervals.length} zoom intervals`,
  `| ${header.poiTags.length} POI + ${header.wayTags.length} way tags`,
);

const started = Date.now();
const output = await cut(source, { minLon, minLat, maxLon, maxLat });
await writeFile(destination, output);
await source.close?.();

console.error(
  `wrote ${destination}: ${output.length.toLocaleString()} B`,
  `in ${((Date.now() - started) / 1000).toFixed(1)}s`,
  `| ${source.requests} range requests, ${source.bytes.toLocaleString()} B transferred`,
);
