# Packaged reading fonts

The fonts in `app/src/main/assets/fonts` come from Nico Verbruggen's
[ebook-fonts](https://github.com/nicoverbruggen/ebook-fonts) collection. Their licenses are in
`app/src/main/assets/font-licenses.txt`, which the app shows under Settings, Font licenses.

NV Adelph is left out on purpose. Its license, BBB CUTE, isn't the OFL and adds conditions.

To update them, clone that repository, then:

1. `python3 inspect_fonts.py <repo>/fonts <work>/fonts.json` reads each font's family, styles, and embedded license.
2. Put the license sources the generator expects in `<work>/src`: `atkinson-hyperlegible-next/OFL.txt` (the OFL text) and `cartisse/LICENSE`.
3. `python3 generate_font_list.py <work> <repo> <this project>` copies the fonts, rewrites `BookFontList.kt`, and rewrites the license file.

Check any font whose embedded license isn't the OFL before adding it.
