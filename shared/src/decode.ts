const CHARSET_PATTERN = /charset\s*=\s*["']?(?<label>[\w.:-]+)/iu;
const XML_ENCODING_PATTERN =
  /^\s*<\?xml\b[^>]*\bencoding\s*=\s*["'](?<label>[\w.:-]+)["']/u;
// Bytes enough to hold any XML declaration.
const DECLARATION_BYTES = 256;

function byteOrderMarkLabel(bytes: Uint8Array): string {
  const [first, second, third] = bytes;
  let label = "";
  if (first === 0xef && second === 0xbb && third === 0xbf) {
    label = "utf-8";
  } else if (first === 0xfe && second === 0xff) {
    label = "utf-16be";
  } else if (first === 0xff && second === 0xfe) {
    label = "utf-16le";
  }
  return label;
}

function decodeAs(label: string, bytes: Uint8Array): string | undefined {
  let decoded: string | undefined;
  try {
    decoded = new TextDecoder(label).decode(bytes);
  } catch {
    decoded = undefined;
  }
  return decoded;
}

/**
 * Text of a response body in the character set it names: a byte order mark
 * first, then the `Content-Type` charset, then an XML declaration's encoding,
 * else UTF-8. An unknown name falls through to the next.
 */
export function decodeBody(bytes: Uint8Array, contentType: string): string {
  const declaration = new TextDecoder("latin1").decode(
    bytes.subarray(0, DECLARATION_BYTES),
  );
  const labels = [
    byteOrderMarkLabel(bytes),
    CHARSET_PATTERN.exec(contentType)?.groups?.label ?? "",
    XML_ENCODING_PATTERN.exec(declaration)?.groups?.label ?? "",
  ].filter((label) => label !== "");
  let decoded: string | undefined;
  for (const label of labels) {
    decoded ??= decodeAs(label, bytes);
  }
  return decoded ?? new TextDecoder().decode(bytes);
}

/** Largest response body read, in bytes. */
const MAX_BODY_BYTES = 10 * 1024 * 1024;

/**
 * Read a response body of at most `limit` bytes; undefined when `Content-Length`
 * or the bytes read pass it, in which case the rest is left unread.
 */
export async function readCapped(
  body: ReadableStream<Uint8Array> | null,
  contentLength: string,
  limit = MAX_BODY_BYTES,
): Promise<Uint8Array | undefined> {
  let result: Uint8Array | undefined;
  if (Number(contentLength) > limit) {
    await body?.cancel();
    result = undefined;
  } else {
    const chunks: Uint8Array[] = [];
    let size = 0;
    const reader = body?.getReader();
    let next = await reader?.read();
    while (next !== undefined && !next.done && size <= limit) {
      chunks.push(next.value);
      size += next.value.byteLength;
      // biome-ignore lint/performance/noAwaitInLoops: a stream is read one chunk at a time
      next = size <= limit ? await reader?.read() : undefined;
    }
    if (size > limit) {
      await reader?.cancel();
      result = undefined;
    } else {
      result = new Uint8Array(size);
      let offset = 0;
      for (const chunk of chunks) {
        result.set(chunk, offset);
        offset += chunk.byteLength;
      }
    }
  }
  return result;
}
