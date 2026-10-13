/** Rewrite a build's manifest.json for the Chrome Web Store: no development key, no localhost. */
const [, , directory = "dist"] = Bun.argv;
const path = `${directory}/manifest.json`;
const { key: _key, ...manifest } = await Bun.file(path).json();
manifest.externally_connectable.matches =
  manifest.externally_connectable.matches.filter(
    (match: string) => !match.includes("localhost"),
  );
await Bun.write(path, `${JSON.stringify(manifest, null, 2)}\n`);
