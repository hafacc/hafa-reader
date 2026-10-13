import adapter from "@sveltejs/adapter-static";
import { sveltekit } from "@sveltejs/kit/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";

/** Hosts Firebase Auth and Firestore call. */
const FIREBASE_CONNECT = [
  "https://firestore.googleapis.com",
  "https://identitytoolkit.googleapis.com",
  "https://securetoken.googleapis.com",
  "https://www.googleapis.com",
] as const;

export default defineConfig(({ command }) => {
  return {
    plugins: [
      tailwindcss(),
      sveltekit({
        adapter: adapter(),
        compilerOptions: { runes: true },
        csp: {
          mode: "auto",
          directives: {
            "default-src": ["self"],
            // the Google sign-in popup loads its helper script from here
            "script-src": ["self", "https://apis.google.com"],
            "style-src": ["self", "unsafe-inline"],
            // https only; self covers the dev server's own pictures
            "img-src": ["self", "https:", "data:"],
            "media-src": ["self", "https:", "data:"],
            "font-src": ["self"],
            "connect-src": [
              "self",
              ...(command === "serve" ? (["ws:"] as const) : []),
              ...FIREBASE_CONNECT,
            ],
            // the sign-in popup talks to the page through a frame on the project's
            // auth domain, <project>.firebaseapp.com
            "frame-src": ["https://*.firebaseapp.com"],
            "object-src": ["none"],
            "base-uri": ["none"],
            "form-action": ["none"],
          },
        },
      }),
    ],
    resolve: {
      alias: {
        // biome-ignore lint/style/useNamingConvention: alias names start with a dollar sign like SvelteKit's own
        $shared: new URL("../shared/src", import.meta.url).pathname,
      },
    },
    build: { target: "es2024" },
    server: { fs: { allow: [".."] } },
  };
});
