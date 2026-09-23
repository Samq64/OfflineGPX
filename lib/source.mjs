// Byte-range access to a map file, over HTTP or from disk.
//
// The whole point of cutting rather than generating is that a 1.7 GB national file is a
// perfectly good source when you only ever read the few hundred KB you need out of it.

import { open } from 'node:fs/promises';

export class HttpSource {
  constructor(url, { concurrency = 6, timeoutMs = 60_000 } = {}) {
    this.url = url;
    this.concurrency = concurrency;
    this.timeoutMs = timeoutMs;
    this.requests = 0;
    this.bytes = 0;
  }

  /** [from, to] inclusive, matching the HTTP Range header rather than JS slice semantics. */
  async read(from, to) {
    const response = await fetch(this.url, {
      headers: { Range: `bytes=${from}-${to}` },
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    if (response.status !== 206) {
      throw new Error(`expected 206 from ${this.url}, got ${response.status}`);
    }
    const buffer = Buffer.from(await response.arrayBuffer());
    this.requests += 1;
    this.bytes += buffer.length;
    return buffer;
  }

  /** Runs [jobs] with a bounded number in flight, preserving result order. */
  async all(jobs) {
    const results = new Array(jobs.length);
    let next = 0;
    const workers = Array.from({ length: Math.min(this.concurrency, jobs.length) }, async () => {
      for (;;) {
        const index = next++;
        if (index >= jobs.length) return;
        results[index] = await jobs[index]();
      }
    });
    await Promise.all(workers);
    return results;
  }
}

export class FileSource {
  constructor(path) {
    this.path = path;
    this.requests = 0;
    this.bytes = 0;
  }

  async read(from, to) {
    const handle = this.handle ?? (this.handle = await open(this.path, 'r'));
    const length = to - from + 1;
    const buffer = Buffer.alloc(length);
    for (let done = 0; done < length;) {
      const { bytesRead } = await handle.read(buffer, done, length - done, from + done);
      if (bytesRead === 0) throw new Error(`${this.path} ends before byte ${from + done}`);
      done += bytesRead;
    }
    this.requests += 1;
    this.bytes += length;
    return buffer;
  }

  async all(jobs) {
    const out = [];
    for (const job of jobs) out.push(await job());
    return out;
  }

  async close() { await this.handle?.close(); }
}
