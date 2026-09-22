// Cutting a rectangle out of a mapsforge .map file without decoding anything inside it.
//
// Three format properties make this a byte copy rather than a re-encode:
//   - tile coordinates are stored relative to their own tile's origin, so a block stays
//     valid as long as it lands at the same tile position in the output;
//   - tags are indices into dictionaries in the header, so copying those verbatim keeps
//     every index in every block resolving;
//   - the per-interval index is a dense row-major array, so the tiles of one output row
//     are contiguous in the source and come back in a single range request.

import {
  INDEX_ENTRY_BYTES, PREAMBLE_BYTES, layout, parseHeader, parsePreamble,
  rangeCount, rangeWidth, readIndexEntry, serialiseHeader, tileRange, writeIndexEntry,
} from './mapfile.mjs';

/** Reads just enough of [source] to know what it holds. */
export async function readHeader(source) {
  const preamble = await source.read(0, PREAMBLE_BYTES - 1);
  const { headerBytes } = parsePreamble(preamble);
  const body = await source.read(PREAMBLE_BYTES, PREAMBLE_BYTES + headerBytes - 1);
  return parseHeader(body);
}

/**
 * The requested box clipped to what the source actually holds, rounded to the
 * microdegrees the header stores. Rounding first matters: the reader will derive its tile
 * ranges from the rounded values, and this has to derive the same ones.
 */
export function resolveBbox(requested, header) {
  const micro = (v) => Math.round(v * 1e6) / 1e6;
  const bbox = {
    minLat: micro(Math.max(requested.minLat, header.minLat)),
    minLon: micro(Math.max(requested.minLon, header.minLon)),
    maxLat: micro(Math.min(requested.maxLat, header.maxLat)),
    maxLon: micro(Math.min(requested.maxLon, header.maxLon)),
  };
  if (bbox.minLat >= bbox.maxLat || bbox.minLon >= bbox.maxLon) {
    throw new Error('the requested area does not overlap this map');
  }
  return bbox;
}

/**
 * Builds one output sub-file: a fresh dense index followed by the tile blocks it points
 * at, both in output row-major order.
 */
async function cutInterval(source, header, interval, bbox) {
  const src = tileRange(header, interval.baseZoom);
  const out = tileRange(bbox, interval.baseZoom);
  const srcWidth = rangeWidth(src);
  const total = rangeCount(src);

  const flatIndex = (x, y) => (y - src.minY) * srcWidth + (x - src.minX);
  const indexBytes = rangeCount(out) * INDEX_ENTRY_BYTES;

  // One index slice per output row: the columns wanted, plus the following entry, whose
  // offset is where the last wanted block ends.
  const rows = [];
  for (let y = out.minY; y <= out.maxY; y++) rows.push(y);

  const slices = await source.all(rows.map((y) => async () => {
    const first = flatIndex(out.minX, y);
    const last = flatIndex(out.maxX, y);
    const end = Math.min(last + 1, total - 1);
    const from = interval.start + first * INDEX_ENTRY_BYTES;
    const to = interval.start + (end + 1) * INDEX_ENTRY_BYTES - 1;
    return { y, first, last, end, buffer: await source.read(from, to) };
  }));

  // Where each row's blocks start and stop, before any of them are fetched.
  const plans = slices.map(({ y, first, last, end, buffer }) => {
    const entries = [];
    for (let x = out.minX; x <= out.maxX; x++) {
      entries.push(readIndexEntry(buffer, (flatIndex(x, y) - first) * INDEX_ENTRY_BYTES));
    }
    const startOffset = entries[0].offset;
    // The entry after the last wanted one, unless that was the final tile of the sub-file,
    // where the sub-file's own end is the boundary.
    const endOffset = end > last
      ? readIndexEntry(buffer, (end - first) * INDEX_ENTRY_BYTES).offset
      : interval.size;
    return { entries, startOffset, endOffset };
  });

  const blobs = await source.all(plans.map((plan) => async () => (
    plan.endOffset > plan.startOffset
      ? source.read(interval.start + plan.startOffset, interval.start + plan.endOffset - 1)
      : Buffer.alloc(0)
  )));

  const index = Buffer.alloc(indexBytes);
  const data = [];
  let cursor = indexBytes;
  let at = 0;

  plans.forEach((plan, row) => {
    const blob = blobs[row];
    plan.entries.forEach((entry, column) => {
      const next = column + 1 < plan.entries.length
        ? plan.entries[column + 1].offset
        : plan.endOffset;
      const length = next - entry.offset;
      writeIndexEntry(index, at, cursor, entry.water);
      at += INDEX_ENTRY_BYTES;
      if (length > 0) {
        data.push(blob.subarray(entry.offset - plan.startOffset, next - plan.startOffset));
        cursor += length;
      }
    });
  });

  return Buffer.concat([index, ...data]);
}

/**
 * Cuts [requested] out of [source] and returns a complete .map file.
 *
 * The output's start position and start zoom are dropped rather than carried: the
 * source's are almost certainly outside the new box. The comment and created-by strings
 * are kept verbatim, since that is where a file's attribution lives.
 */
export async function cut(source, requested, { onProgress } = {}) {
  const header = await readHeader(source);
  const bbox = resolveBbox(requested, header);

  const subFiles = [];
  for (const [index, interval] of header.intervals.entries()) {
    onProgress?.({ stage: 'interval', index, of: header.intervals.length, baseZoom: interval.baseZoom });
    subFiles.push(await cutInterval(source, header, interval, bbox));
  }

  const out = layout({
    ...header,
    ...bbox,
    // Clearing both start-position flags; the values they gated are not carried over.
    flags: header.flags & ~0x40 & ~0x20,
    intervals: header.intervals.map((i) => ({ ...i })),
  }, subFiles.map((s) => s.length));

  return Buffer.concat([serialiseHeader(out), ...subFiles]);
}
