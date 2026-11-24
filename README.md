# Thor Reader

A reading app designed for the AYN Thor to read books, comics, and manga in whatever
orientation you want.

Thor Reader is made for the **AYN Thor**, the dual-screen Android clamshell handheld. It uses both
screens the way a book would: hold the Thor sideways and the two screens become the two pages
of an open book, or keep it closed-clamshell style and read on one screen while the other stays
free for something else.

## Reading modes

Each book remembers its own mode. Switch with the **Select** button or from the reader menu.

| Mode | How you hold it | What you see |
|---|---|---|
| 1. Book | Sideways, top screen on the left | Left page on the top screen, right page on the bottom screen |
| 2. Book reversed | Sideways the other way | Left page on the bottom screen, right page on the top screen |
| 3. Top screen | Normally | A two-page spread on the top screen; the bottom screen is free |
| 4. Bottom screen | Normally | One page (or two) on the bottom screen; the top screen is free |

## Features

- Books are laid out to the exact size of each screen; no line is ever cut between pages.
- Running chapter header and page numbers, in the book's own font.
- Themes: Paper, Sepia, and Night (pure black with grey text, for OLED).
- Comics and manga: fit-to-screen, double-page spreads split across both screens in book
  modes, right-to-left reading (auto-detected from ComicInfo.xml, or set per series),
  double-tap and pinch zoom.
- Quick navigation: page scrubber, go to page, chapter list, and "back to where I was".
- Page turns by tap, swipe, d-pad and shoulder buttons (hold to flip quickly), always in the
  physically right direction for the current mode.
- Reading position, mode and text settings are remembered per book.
- Book modes can use a different text size on each screen ("Same text size on both screens" off
  in the reader menu); the text still flows from one screen to the next without losing a line.
- Library: "Library | Completed" switch, views by Authors, Series, Type (Books / Manga / Comics)
  and Genre, search, and sorting (recently read, recently added, title, author, progress).
  Each row shows pages, format and progress; finished books get a gold star and move to
  "Completed". Works with the d-pad and A/B as well as touch.
- A short first-run tutorial (can be shown again from the reader menu).

## Supported formats

- **Books:** EPUB, MOBI, AZW, AZW3 (KF8), FB2, TXT, HTML / HTM / XHTML, Markdown (.md), DOCX,
  ODT, RTF.
- **Fixed layout:** PDF, XPS.
- **Comics / manga:** CBZ, CBR (RAR4), CB7, CBT, single pictures (JPG, PNG, WEBP, GIF, AVIF), and
  a folder of pictures added with "Add folder" (shown as one comic).

Kindle books (MOBI / AZW / AZW3) must be DRM-free; DRM-protected books show "This book is
DRM-protected". Pictures next to an HTML or Markdown file are shown when the file was added
with "Add folder" (Android only lets apps see a file's neighbours then).

## Install

1. On the Thor, open this repository's **Releases** page and download the latest
   `ThorReader-x.y.z.apk` (or download it on a computer and copy it over).
2. Open the APK on the Thor. If Android asks, allow installing apps from that source
   (Settings › Apps › Special app access › Install unknown apps).
3. Open **Thor Reader**, tap **+ Add files** or **+ Add folder**, and pick your books.
   Files stay where they are; nothing is copied.

Updates from Releases install over the previous version and keep your library.

## Building from source

Requires JDK 17 and the Android SDK (platform 34).

```
.\gradlew assembleDebug
```

Release builds are signed with a keystore described by a local, untracked
`keystore.properties` file (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).

## Credits and licences

Thor Reader is free software, licensed under the **GNU Affero General Public License v3.0**
(see [LICENSE](LICENSE)), because it is built on MuPDF.

- **[MuPDF](https://mupdf.com)** by Artifex Software — document rendering and layout.
  Licensed under the GNU AGPL v3.0 (commercial licences available from Artifex).
- **[junrar](https://github.com/junrar/junrar)** — reading CBR (RAR) comics.
  Licensed under the UnRAR licence (free to use; may not be used to re-create the RAR
  compression algorithm).
- **[Apache Commons Compress](https://commons.apache.org/proper/commons-compress/)** — reading
  CBZ and CB7 comics. Apache License 2.0.
- **[XZ for Java](https://tukaani.org/xz/java.html)** — LZMA/7z decompression. Zero-Clause BSD
  (0BSD) licence.
- **[commonmark-java](https://github.com/commonmark/commonmark-java)** — Markdown to HTML (used
  when MuPDF can't open a .md file itself). BSD 2-Clause licence.
- Apache Commons Compress also reads CBT (tar) comics.
- The AZW3/KF8, MOBI, DOCX, ODT and RTF readers are Thor Reader's own code (AGPL v3), written
  from the public format descriptions; converted books are kept as EPUB files in the app's
  private storage.
- AndroidX, Jetpack Compose and Room — Apache License 2.0.
