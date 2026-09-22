// Reading and writing the mapsforge binary map file header, plus the tile maths the
// sub-file indexes are addressed by.
//
// Everything multi-byte is big-endian. Strings are a VBE-U length followed by UTF-8 -
// not the 2-byte length the rest of the header uses, which is the one thing in here that
// will silently desynchronise a parser if you get it wrong.

export const MAGIC = 'mapsforge binary OSM';

// Bit 1 of a 5-byte index entry marks a tile as all water; the remaining 39 bits are the
// tile's offset from the start of its sub-file.
export const INDEX_ENTRY_BYTES = 5;
const OFFSET_MASK = 0x7fffffffffn;

const FLAG_DEBUG = 0x80;
const FLAG_START_POSITION = 0x40;
const FLAG_START_ZOOM = 0x20;
const FLAG_LANGUAGE = 0x10;
const FLAG_COMMENT = 0x08;
const FLAG_CREATED_BY = 0x04;

class Reader {
  constructor(buffer) {
    this.buffer = buffer;
    this.at = 0;
  }
  u8() { return this.buffer.readUInt8(this.at++); }
  u16() { const v = this.buffer.readUInt16BE(this.at); this.at += 2; return v; }
  u32() { const v = this.buffer.readUInt32BE(this.at); this.at += 4; return v; }
  i32() { const v = this.buffer.readInt32BE(this.at); this.at += 4; return v; }
  i64() { const v = this.buffer.readBigInt64BE(this.at); this.at += 8; return Number(v); }
  bytes(n) { const v = this.buffer.subarray(this.at, this.at + n); this.at += n; return v; }

  vbeu() {
    let result = 0;
    let shift = 0;
    for (;;) {
      const byte = this.u8();
      if (byte & 0x80) {
        result |= (byte & 0x7f) << shift;
        shift += 7;
      } else {
        return result | (byte << shift);
      }
    }
  }

  string() { return this.bytes(this.vbeu()).toString('utf8'); }
}

class Writer {
  constructor() { this.parts = []; }
  raw(buf) { this.parts.push(buf); return this; }
  u8(v) { const b = Buffer.alloc(1); b.writeUInt8(v); return this.raw(b); }
  u16(v) { const b = Buffer.alloc(2); b.writeUInt16BE(v); return this.raw(b); }
  u32(v) { const b = Buffer.alloc(4); b.writeUInt32BE(v); return this.raw(b); }
  i32(v) { const b = Buffer.alloc(4); b.writeInt32BE(v); return this.raw(b); }
  i64(v) { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(v)); return this.raw(b); }

  vbeu(value) {
    let v = value;
    const out = [];
    while (v > 0x7f) {
      out.push((v & 0x7f) | 0x80);
      v >>>= 7;
    }
    out.push(v);
    return this.raw(Buffer.from(out));
  }

  string(s) {
    const b = Buffer.from(s, 'utf8');
    return this.vbeu(b.length).raw(b);
  }

  build() { return Buffer.concat(this.parts); }
}

/**
 * The fixed 24 bytes every file starts with: the magic string and the length of the
 * variable-length header that follows it. Read this first to know how much more to fetch.
 */
export const PREAMBLE_BYTES = MAGIC.length + 4;

export function parsePreamble(buffer) {
  if (buffer.subarray(0, MAGIC.length).toString('latin1') !== MAGIC) {
    throw new Error('not a mapsforge map file');
  }
  return { headerBytes: buffer.readUInt32BE(MAGIC.length) };
}

/** Parses the header body - the `headerBytes` that follow the preamble. */
export function parseHeader(body) {
  const r = new Reader(body);
  const header = {
    fileVersion: r.u32(),
    fileSize: r.i64(),
    date: r.i64(),
    minLat: r.i32() / 1e6,
    minLon: r.i32() / 1e6,
    maxLat: r.i32() / 1e6,
    maxLon: r.i32() / 1e6,
    tileSize: r.u16(),
  };
  header.projection = r.string();
  header.flags = r.u8();

  if (header.flags & FLAG_DEBUG) {
    // Debug files interleave 32-byte signatures with the data, which would need a
    // different copy path. Nothing published ships them.
    throw new Error('map file has debug signatures; unsupported');
  }
  if (header.flags & FLAG_START_POSITION) {
    header.startLat = r.i32();
    header.startLon = r.i32();
  }
  if (header.flags & FLAG_START_ZOOM) header.startZoom = r.u8();
  if (header.flags & FLAG_LANGUAGE) header.language = r.string();
  if (header.flags & FLAG_COMMENT) header.comment = r.string();
  if (header.flags & FLAG_CREATED_BY) header.createdBy = r.string();

  header.poiTags = Array.from({ length: r.u16() }, () => r.string());
  header.wayTags = Array.from({ length: r.u16() }, () => r.string());

  header.intervals = Array.from({ length: r.u8() }, () => ({
    baseZoom: r.u8(),
    minZoom: r.u8(),
    maxZoom: r.u8(),
    start: r.i64(),
    size: r.i64(),
  }));
  return header;
}

/**
 * Serialises a header. The caller supplies the sub-file starts and sizes, which is
 * circular - they depend on the header's own length. `layout` below resolves that.
 */
export function serialiseHeader(header) {
  const body = new Writer()
    .u32(header.fileVersion)
    .i64(header.fileSize)
    .i64(header.date)
    .i32(Math.round(header.minLat * 1e6))
    .i32(Math.round(header.minLon * 1e6))
    .i32(Math.round(header.maxLat * 1e6))
    .i32(Math.round(header.maxLon * 1e6))
    .u16(header.tileSize)
    .string(header.projection)
    .u8(header.flags);

  if (header.flags & FLAG_START_POSITION) body.i32(header.startLat).i32(header.startLon);
  if (header.flags & FLAG_START_ZOOM) body.u8(header.startZoom);
  if (header.flags & FLAG_LANGUAGE) body.string(header.language);
  if (header.flags & FLAG_COMMENT) body.string(header.comment);
  if (header.flags & FLAG_CREATED_BY) body.string(header.createdBy);

  body.u16(header.poiTags.length);
  header.poiTags.forEach((t) => body.string(t));
  body.u16(header.wayTags.length);
  header.wayTags.forEach((t) => body.string(t));

  body.u8(header.intervals.length);
  header.intervals.forEach((i) => {
    body.u8(i.baseZoom).u8(i.minZoom).u8(i.maxZoom).i64(i.start).i64(i.size);
  });

  const bodyBuffer = body.build();
  return Buffer.concat([
    Buffer.from(MAGIC, 'latin1'),
    (() => { const b = Buffer.alloc(4); b.writeUInt32BE(bodyBuffer.length); return b; })(),
    bodyBuffer,
  ]);
}

/**
 * The header's own length depends on the sub-file offsets it contains, which depend on
 * the header's length. Both are fixed-width fields once the tag lists are known, so one
 * round of substitution settles it: serialise with placeholders, measure, then re-serialise.
 */
export function layout(header, subFileSizes) {
  let headerLength = serialiseHeader({ ...header, fileSize: 0 }).length;
  for (let pass = 0; pass < 4; pass++) {
    let cursor = headerLength;
    const intervals = header.intervals.map((interval, index) => {
      const placed = { ...interval, start: cursor, size: subFileSizes[index] };
      cursor += subFileSizes[index];
      return placed;
    });
    const candidate = { ...header, intervals, fileSize: cursor };
    const length = serialiseHeader(candidate).length;
    if (length === headerLength) return candidate;
    headerLength = length;
  }
  throw new Error('header layout did not converge');
}

// --- Tile maths ------------------------------------------------------------------

export const lonToTileX = (lon, zoom) =>
  clamp(Math.floor(((lon + 180) / 360) * 2 ** zoom), zoom);

export const latToTileY = (lat, zoom) => {
  const rad = (lat * Math.PI) / 180;
  const y = (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2;
  return clamp(Math.floor(y * 2 ** zoom), zoom);
};

const clamp = (value, zoom) => Math.min(2 ** zoom - 1, Math.max(0, value));

/** The inclusive tile rectangle a bounding box covers at [zoom]. */
export function tileRange(bbox, zoom) {
  return {
    minX: lonToTileX(bbox.minLon, zoom),
    maxX: lonToTileX(bbox.maxLon, zoom),
    // y grows southward, so the north edge gives the smaller index.
    minY: latToTileY(bbox.maxLat, zoom),
    maxY: latToTileY(bbox.minLat, zoom),
  };
}

export const rangeWidth = (r) => r.maxX - r.minX + 1;
export const rangeHeight = (r) => r.maxY - r.minY + 1;
export const rangeCount = (r) => rangeWidth(r) * rangeHeight(r);

/** Reads the 5-byte big-endian index entry at [at], splitting off the water flag. */
export function readIndexEntry(buffer, at) {
  let value = 0n;
  for (let i = 0; i < INDEX_ENTRY_BYTES; i++) {
    value = (value << 8n) | BigInt(buffer.readUInt8(at + i));
  }
  return { water: (value >> 39n) & 1n, offset: Number(value & OFFSET_MASK) };
}

export function writeIndexEntry(buffer, at, offset, water) {
  const value = (BigInt(water) << 39n) | (BigInt(offset) & OFFSET_MASK);
  for (let i = 0; i < INDEX_ENTRY_BYTES; i++) {
    buffer.writeUInt8(Number((value >> BigInt(8 * (INDEX_ENTRY_BYTES - 1 - i))) & 0xffn), at + i);
  }
}
