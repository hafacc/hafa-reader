import { GlobalRegistrator } from "@happy-dom/global-registrator";
import { plugin } from "bun";
import { compileModule } from "svelte/compiler";
import "fake-indexeddb/auto";

GlobalRegistrator.register({ url: "http://localhost/" });

/**
 * happy-dom defines `nodeName` on each node class, so the getter on
 * `Node.prototype` returns "" for elements. DOMPurify reads that getter, so
 * it is made to answer as a browser's does.
 */
function patchNodeName(): void {
  const base = Node.prototype;
  const original = Object.getOwnPropertyDescriptor(base, "nodeName")?.get;
  Object.defineProperty(base, "nodeName", {
    configurable: true,
    get(this: Node): string {
      let prototype: object | null = Object.getPrototypeOf(this);
      while (prototype !== null && prototype !== base) {
        const own = Object.getOwnPropertyDescriptor(prototype, "nodeName")?.get;
        if (own !== undefined) {
          return String(own.call(this));
        }
        prototype = Object.getPrototypeOf(prototype);
      }
      return String(original?.call(this) ?? "");
    },
  });
}

patchNodeName();

const SVELTE_MODULE = /\.svelte\.ts$/u;

plugin({
  name: "svelte runes",
  setup(build): void {
    build.onLoad({ filter: SVELTE_MODULE }, async ({ path }) => {
      const source = await Bun.file(path).text();
      const script = new Bun.Transpiler({ loader: "ts" }).transformSync(source);
      const compiled = compileModule(script, {
        filename: path,
        generate: "client",
      });
      return { contents: compiled.js.code, loader: "js" };
    });
  },
});
