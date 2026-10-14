#!/bin/sh
# Cut the bundled fonts in app/src/main/res/font from Google Fonts' variable files.
# Needs python3 and network access; run from anywhere.
set -eu

fonttools_version=4.67.0
literata_commit=4e5f06dbb274a27ebe71ed54ea706b3ee40eabd9
public_sans_commit=8b0a1d0f5983c89bc2b93f1b5fb55f9e252744b5

out=$(cd "$(dirname "$0")/../app/src/main/res/font" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

python3 -m venv "$work/venv"
"$work/venv/bin/pip" install --quiet "fonttools==$fonttools_version"

fetch() {
    curl -sSfL -o "$work/$3" "https://raw.githubusercontent.com/google/fonts/$1/ofl/$2"
}
fetch "$literata_commit" "literata/Literata%5Bopsz,wght%5D.ttf" literata.ttf
fetch "$literata_commit" "literata/Literata-Italic%5Bopsz,wght%5D.ttf" literata_italic.ttf
fetch "$public_sans_commit" "publicsans/PublicSans%5Bwght%5D.ttf" public_sans.ttf
fetch "$public_sans_commit" "publicsans/PublicSans-Italic%5Bwght%5D.ttf" public_sans_italic.ttf

# Latin, Latin-1, Latin Extended-A and B, IPA modifiers, combining marks, Vietnamese, punctuation, currency, arrows.
latin="U+0000-024F,U+02B0-02FF,U+0300-036F,U+1EA0-1EF9,U+2000-206F,U+20A0-20C0,U+2116,U+2122,U+2190-2193,U+2212,U+2215"
# Modern Greek and the Cyrillic of Russian, Ukrainian, Belarusian, Bulgarian, Serbian and Macedonian, as fontsource's
# greek and cyrillic files; polytonic Greek and extended Cyrillic would add about 70 KB more and fall back instead.
greek="U+0370-0377,U+037A-037F,U+0384-038A,U+038C,U+038E-03A1,U+03A3-03FF"
cyrillic="U+0400-045F,U+0490-0491,U+04B0-04B1"

for name in literata literata_italic; do
    "$work/venv/bin/fonttools" varLib.instancer "$work/$name.ttf" opsz=12 wght=400:800 -q -o "$work/$name-instanced.ttf"
    "$work/venv/bin/pyftsubset" "$work/$name-instanced.ttf" \
        --unicodes="$latin,$greek,$cyrillic" \
        --layout-features='*' --name-IDs='*' --name-languages='*' --notdef-outline \
        --output-file="$out/$name.ttf"
done

# Public Sans covers Latin only, so it keeps every glyph and only drops the weights the app never uses.
for name in public_sans public_sans_italic; do
    "$work/venv/bin/fonttools" varLib.instancer "$work/$name.ttf" wght=400:800 -q -o "$out/$name.ttf"
done

ls -l "$out"
