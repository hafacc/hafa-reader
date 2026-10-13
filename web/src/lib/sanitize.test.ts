import { describe, expect, test } from "bun:test";
import {
  absoluteSrcset,
  absoluteUrl,
  excerpt,
  FORBIDDEN_ATTRIBUTES,
  mentionsUrl,
  plainBody,
  sanitize,
  srcsetCandidates,
} from "./sanitize.ts";

const BASE = "https://blog.example/posts/2024/hello/";

describe("absoluteUrl", () => {
  test("resolves root and path relative addresses against the article", () => {
    expect(absoluteUrl("/images/a.png", BASE)).toBe(
      "https://blog.example/images/a.png",
    );
    expect(absoluteUrl("figure.png", BASE)).toBe(
      "https://blog.example/posts/2024/hello/figure.png",
    );
    expect(absoluteUrl("#note-1", BASE)).toBe(
      "https://blog.example/posts/2024/hello/#note-1",
    );
  });

  test("leaves absolute and non-web addresses alone", () => {
    expect(absoluteUrl("https://other.example/x", BASE)).toBe(
      "https://other.example/x",
    );
    expect(absoluteUrl("mailto:me@example.com", BASE)).toBe(
      "mailto:me@example.com",
    );
  });

  test("does nothing without a web base", () => {
    expect(absoluteUrl("/a.png", "")).toBe("/a.png");
    expect(absoluteUrl("#x", "javascript:alert(1)")).toBe("#x");
    expect(absoluteUrl("", BASE)).toBe("");
  });
});

test("absoluteUrl upgrades http", () => {
  expect(absoluteUrl("http://a.example/x", BASE)).toBe("https://a.example/x");
  expect(absoluteUrl("http://a.example/x", "")).toBe("https://a.example/x");
});

describe("absoluteSrcset", () => {
  test("resolves every candidate and keeps descriptors", () => {
    expect(absoluteSrcset("/a.png 1x, b.png 2x", BASE)).toBe(
      "https://blog.example/a.png 1x, https://blog.example/posts/2024/hello/b.png 2x",
    );
  });

  test("keeps data addresses, which hold commas", () => {
    const srcset = "data:image/png;base64,AAAA 1x";
    expect(absoluteSrcset(srcset, BASE)).toBe(srcset);
  });

  test("addresses with commas keep their descriptors", () => {
    const srcset =
      "https://substackcdn.com/image/fetch/w_424,c_limit,f_webp/https%3A%2F%2Fa.png 424w, https://substackcdn.com/image/fetch/w_848,c_limit/b.png 848w";
    expect(absoluteSrcset(srcset, BASE)).toBe(srcset);
  });

  test("plain http next to a data address is upgraded, other schemes dropped", () => {
    expect(
      absoluteSrcset(
        "data:image/png;base64,AAAA 1x, http://pics.example/b.png 2x, ftp://pics.example/c.png 3x",
        BASE,
      ),
    ).toBe("data:image/png;base64,AAAA 1x, https://pics.example/b.png 2x");
  });

  test("candidates are read as the spec reads them", () => {
    expect(srcsetCandidates(" a.png, b.png 2x,c.png 3x , d.png,")).toEqual([
      { address: "a.png", descriptors: "" },
      { address: "b.png", descriptors: "2x" },
      { address: "c.png", descriptors: "3x" },
      { address: "d.png", descriptors: "" },
    ]);
  });
});

describe("mentionsUrl", () => {
  test("finds an address with its ampersands escaped", () => {
    const address = "https://x.example/img?w=1&h=2";
    expect(
      mentionsUrl('<img src="https://x.example/img?w=1&amp;h=2">', address),
    ).toBe(true);
    expect(mentionsUrl(`<img src="${address}">`, address)).toBe(true);
    expect(mentionsUrl("<p>text</p>", address)).toBe(false);
    expect(mentionsUrl("<p>text</p>", "")).toBe(false);
  });
});

test("articles cannot borrow the app's classes or ids", () => {
  expect(FORBIDDEN_ATTRIBUTES).toEqual(
    expect.arrayContaining(["style", "class", "id"]),
  );
});

test("plain text becomes an escaped paragraph", () => {
  expect(plainBody(" a < b & c ")).toBe("<p>a &lt; b &amp; c</p>");
  expect(plainBody("  ")).toBe("");
});

describe("sanitize", () => {
  const article = "https://blog.example/posts/1";

  // happy-dom's node iterator skips the node after a removed one, which a
  // browser does not, so each removal is checked on its own
  test("strips scripts, handlers, styles, classes and ids", () => {
    expect(
      sanitize(
        '<p class="fixed inset-0" id="top" style="color:red" onclick="steal()">Hi</p>',
        article,
      ),
    ).toBe("<p>Hi</p>");
    expect(sanitize("<p>Hi</p><script>steal()</script>", article)).toBe(
      "<p>Hi</p>",
    );
    expect(sanitize('<a href="javascript:steal()">x</a>', article)).toBe(
      '<a target="_blank" rel="noopener noreferrer">x</a>',
    );
  });

  test("opens links in a new tab and resolves relative addresses", () => {
    const html = sanitize(
      '<a href="/about">About</a><img src="pic.png" srcset="/big.png 2x">',
      article,
    );
    expect(html).toContain('href="https://blog.example/about"');
    expect(html).toContain('target="_blank"');
    expect(html).toContain('rel="noopener noreferrer"');
    expect(html).toContain('src="https://blog.example/posts/pic.png"');
    expect(html).toContain('srcset="https://blog.example/big.png 2x"');
    expect(html).toContain('referrerpolicy="no-referrer"');
  });

  test("shows a title or long alt text under its image", () => {
    const html = sanitize(
      '<a href="/c"><img src="/c.png" title="The real joke"></a><img src="/d.png" alt="short">',
      article,
    );
    expect(html).toContain('</a><p class="alt-text">The real joke</p>');
    expect(html).not.toContain(">short<");
  });

  test("upgrades plain http and drops pictures that cannot load over https", () => {
    const html = sanitize(
      '<a href="http://other.example/x">x</a><img src="http://pics.example/a.png" srcset="http://pics.example/b.png 2x, ftp://pics.example/c.png 3x">',
      article,
    );
    expect(html).toContain('href="https://other.example/x"');
    expect(html).toContain('src="https://pics.example/a.png"');
    expect(html).toContain('srcset="https://pics.example/b.png 2x"');
    expect(sanitize('<img src="/a.png">')).toBe(
      '<img referrerpolicy="no-referrer" loading="lazy">',
    );
    expect(
      sanitize('<img src="ftp://pics.example/a.png">', article),
    ).not.toContain("src=");
  });

  test("drops form controls", () => {
    expect(sanitize("<form>Text</form>")).toBe("Text");
    expect(sanitize("<p>a</p><input>")).toBe("<p>a</p>");
    expect(sanitize("<p>a</p><button>Go</button>")).toBe("<p>a</p>Go");
  });
});

test("excerpt keeps a space between blocks and around line breaks", () => {
  expect(
    excerpt("<p>One</p><p>Two</p><div>Three<br>Four</div><li>Five</li>"),
  ).toBe("One Two Three Four Five");
});

test("excerpt leaves out styles and scripts", () => {
  expect(
    excerpt(
      "<style>.a { color: red }</style><p>Text</p><script>run()</script><noscript>Enable</noscript>",
    ),
  ).toBe("Text");
});

test("excerpt keeps the start of the text without markup", () => {
  expect(excerpt("<p>One   <b>two</b></p>\n<p>three</p>", 9)).toBe("One two t");
});
