# Thor Reader — Project Plan

Android e-reader app for the **AYN Thor** (dual-screen Android clamshell handheld).
The owner has no coding experience: all code work is done by Claude; anything the owner
must do is explained in plain words.

## Status

| Milestone | Status |
|---|---|
| 1. Dual-screen setup | DONE (2026-11-23). Gyro auto-detection removed in milestone 2. |
| 2. Manual modes + real reading + comics/manga | Rounds 1–3 tested on Thor. Step 10 (quick page navigation, app icon, GitHub + signed release) = **v0.1.0**, BUILT 2026-11-24 |
| 2b. v0.2.0 (step 11): per-screen font size, more formats, tutorial, library views/search/sort, completed books | BUILT 2026-11-24, to test on Thor (see "v0.2.0" below) |
| 3. Bookshelf | not started |
| 4. Animations (book enchant-out, OpenGL page curl) | not started |
| 5. Polish | not started |

## Project setup

- Folder: `C:\Users\Dylan\thor-reader`
- Package: `dev.thorreader.app`
- Kotlin + Jetpack Compose, Gradle with wrapper
- minSdk 33, targetSdk 34, ABI arm64-v8a
- Log tag `ThorReader` for all logging.
- Second screen is driven with `android.app.Presentation` (written from scratch; the old
  `zelda-ds` project was not on this PC).

### Thor display facts (measured)

- Display id **0** = top screen, 1920x1080, density 2.30625.
- Display id **4** = bottom screen, 1240x1080, density 2.30625. (The bottom screen's id is 4, not 1.)
- Find the bottom display by id 4; fall back to "the first non-default display" if id 4 isn't present.

## Licensing

- **MuPDF** (`com.artifex.mupdf:fitz`, from Artifex's Maven repo `https://maven.ghostscript.com`)
  is licensed under the **GNU AGPL v3** (or a commercial licence from Artifex). Using it means
  this app, if distributed, must also be released under the AGPL with full source code
  available to its users. Fine for personal use; revisit before any public/commercial release.
- **junrar** (CBR/RAR): UnRAR licence (free use; may not be used to re-create the RAR
  compression algorithm). Only RAR4 archives are supported, not RAR5.
- **Apache Commons Compress** + **XZ for Java** (CBZ/CB7): Apache 2.0 / public domain.
- AndroidX / Room / Compose: Apache 2.0.

## How the app works (as of milestone 2)

### Four manual reading modes (orientation sensor NOT used)

On-device testing showed gravity auto-detection was unreliable, so it was removed from the UI
and startup. The sensor code lives unused in `OrientationSensor.kt` and may return later.

1. **BOOK**: device held sideways. Top screen = LEFT page, bottom screen = RIGHT page, both
   rotated 90 degrees so text is upright when held that way.
2. **BOOK REVERSED**: device held sideways the other way. Top screen = RIGHT page, bottom
   screen = LEFT page, rotated the opposite direction.
3. **TOP SCREEN**: device held normally. Two-page spread on the top screen only. The app
   RELEASES the bottom screen (no Presentation shown, nothing drawn) so another app can use it.
4. **BOTTOM SCREEN**: device held normally. Reading on the bottom screen only, single page by
   default, with a setting for a two-page spread. The top screen is released as fully as
   Android allows: the reader activity moves to display 4 (`ActivityOptions.setLaunchDisplayId`).
   If the device refuses that, the app falls back to a black top screen + Presentation on the
   bottom screen (logged).

**Page sizes in book modes**: each screen uses its FULL area. Top page is 1080 wide x 1920
tall (rotated); bottom page is 1080 x 1240. Reflowable books lay out each page to the exact size
of the screen it lands on. Book title, chapter, progress and clock live in the tap-to-open
reader menu (no permanent header/footer).

**Switching modes**: (a) reader menu (opened by press-and-hold, see below), with four
labelled mode buttons with icons; (b) the Thor's Select button cycles 1→2→3→4 with a brief
on-screen label. Last mode is remembered. Touch works on whichever screen(s) show pages; d-pad
and shoulder buttons turn pages in every mode, and the direction follows the mode so "next
page" always moves the right way physically.

### Real reading (MuPDF)

- MuPDF Android library from Artifex (latest stable at time of writing: 1.28.5).
- Formats: EPUB, PDF, MOBI, FB2, XPS, TXT, CBZ, CBR (junrar), CB7 (commons-compress).
- "Add books": Storage Access Framework, multiple files OR a whole folder (scanned for supported
  files). URI permissions are persisted; files are NOT copied.
- Temporary list screen (title, author, format, cover thumbnail). Book data is in a Room
  database that the future shelf will use.
- Metadata extracted and cached on import: title, author, series + number, genre/subjects,
  page count, cover image. Sources: EPUB OPF (incl. calibre:series and belongs-to-collection),
  ComicInfo.xml in CBZ/CBR/CB7 (Series, Number, Writer, Genre, Manga), PDF metadata, FB2
  title-info, filename parsing as fallback.
- Current position remembered per book; reopens there.
- Pages pre-rendered ahead and behind for instant turns (no animation yet).
- Reader menu: font size, margins, page theme (Paper / Sepia / Night = pure black, grey text).
  Text settings only apply to reflowable formats.
- **Menu & touch (changed after testing)**: the menu opens by PRESS-AND-HOLD anywhere on a page
  (default 2 s, setting 0.5–3 s in the menu); a ring fills up while holding, letting go early
  does nothing. Start button also opens it. Single tap: left half = previous page, right half
  = next page (flipped in right-to-left). "Return to reading" and "Close book" are big buttons
  at the top of the menu.
- **No line is ever split across pages.** Content that can't fit one page (huge image, table,
  very tall PDF/comic page) gets a page that scrolls on its own (vertical drag), with a fade and
  a "more" hint at the bottom; horizontal swipes still turn pages. Every overflow is logged
  (`OVERFLOW` in logcat) with book, chapter/page and how much it overflowed.
- **Fast opening**: reflowable books open at the saved position after laying out only the
  current chapter; the rest is laid out in the background (small progress pill, never a
  blocking screen) and cached on disk per text layout. Font/margin changes are debounced 400 ms.
  Comics read image sizes from file headers only, cached on disk.
- **Mode changes never close the book.**
- **Manga direction per series**: setting the reading direction on one volume applies it to
  every book in the same series, including volumes imported later. Library has a per-book
  "Manga (right-to-left)" toggle (also series-wide).

### Comics and manga

- Fixed-layout pages (comics, PDF, XPS) are scaled to fit each screen, aspect kept, black around.
- Right-to-left reading: auto-enabled when ComicInfo.xml says `Manga = YesAndRightToLeft`, plus a
  per-book "Reading direction" toggle in the reader menu. In RTL, page order across the two
  screens, tap zones, swipes and button directions all flip.
- Double-page spreads (image much wider than tall): in book modes split down the middle, one
  half per screen, ordered for the reading direction. In modes 3/4 shown whole.
- Double-tap to zoom, pinch to zoom/pan on comic/PDF pages; double-tap again to reset.

### Per-book modes, library and reader details (step 6)

- **Each book remembers its own mode (1–4).** A newly imported book gets the last mode used
  while reading. Opening a book switches to its saved mode; changing mode while reading saves it
  to that book.
- **Closing a book always returns to Mode 3** (Top screen) with the library on the top screen
  and the bottom screen released — from any mode, including Mode 4 on display 4 (clean move back
  to display 0; the Back guard from step 5 covers the handover). The book's saved mode is not
  changed. **The app always starts in Mode 3 on the library.** The Select button does nothing in
  the library (modes belong to books now).
- **Library row**: no mode-button row, no per-row Manga/Remove buttons. One square icon button at
  the right end of each row shows that book's mode icon. Tapping it opens a small popup beside it
  (to the right; to the left if there's no room) with the four modes (current highlighted), a
  "Manga (right-to-left)" toggle (whole series), and "Remove from library" in a warning colour
  with a confirm step (never deletes the file). Tapping outside / Back closes it. D-pad + A work
  inside it.
- **Running header and page number** (like a printed book), toggle "Header & page number" in the
  reader menu (updated in step 8):
  - **Books (EPUB, MOBI, FB2, TXT)**: PLAIN TEXT, no pill — drawn into the page in the book's own
    body font, 80% of the text size, in the theme's muted text colour. Space is reserved in
    pagination (band grows with the font size) so text never overlaps.
    - Chapter header only on the LEFT page of each view (left screen in modes 1/2, left page of a
      spread in modes 3/4), top-left; every page in single-page Mode 4. Right-to-left books:
      top-right of the RIGHT page.
    - Page number bottom-right on every page (bottom-left in right-to-left books).
    - Header text: the DEEPEST TOC entry containing the page; if that is only a part/book name
      (has sub-entries, starts with Part/Book/Volume…, or repeats the book title) or there is no
      TOC: the h1/h2/h3 heading at the start of the current section (EPUB HTML); otherwise
      "Chapter N", numbering the real text sections (≥ 12 lines) within that part, from the
      spine. The book's own title is never shown. Source logged per section
      ("Header for '…' section N: … from TOC/HEADING/NUMBERED").
    - Long titles truncated with "…". Page numbers appear once the whole book is laid out.
  - **Comics / manga / PDF**: page number only, bottom-right, as a small house-style pill over
    the art (art keeps full size).
- **Edge feedback**: trying to turn past the first/last page (tap, swipe, d-pad, shoulder) shows
  a soft dark curved shadow growing from that page's outer edge — follows the finger on a swipe
  and eases back on release; a quick in-out pulse on tap/button — plus one light haptic tick.
  Respects reading direction and book modes (shown on the screen holding the edge page). (The
  "End" label was removed in step 8.)
- **Reader menu** shows the book cover (cached cover, or a generated placeholder from title and
  author) with title, author and progress, right under "Return to reading" / "Close book".

### Quick page navigation (step 10, part of v0.1.0)

- **Scrubber**: tapping the page number (bottom corner; bottom-left in RTL) opens a thin
  house-style bar along the bottom of that page. Dragging moves through the book with a floating
  "Page N of M · chapter" label; pages follow live (throttled to ~10 updates/s). Letting go jumps
  there; tapping outside or B cancels and returns. D-pad left/right = 1 page, shoulders = 10
  pages (physical direction, so RTL runs right-to-left), A = confirm, B = cancel.
- **Holding** a page-turn button or the d-pad flips continuously: one turn at once, then after
  450 ms every 320 ms, speeding up ×0.85 per turn down to 60 ms; stops on release, at the edge of
  the book, on focus loss/pause, mode change or closing the book. (System key-repeat is ignored.)
- **Reader menu**: full-width progress slider (same live label), -10 / -1 / +1 / +10 (mirrored in
  RTL: +10 +1 … -1 -10), "Go to page" number pad (PDF/comics: real page numbers; books: this
  layout's page numbers, available once fully laid out), "Chapters" list (TOC / running-header
  names) with the current chapter highlighted.
- **Back to page N**: after any jump (scrubber, slider, ±, go-to-page, chapter) a "↩ Back to page N"
  house pill shows for 6 s; the menu offers it for 2 minutes. A burst of jumps within 10 s keeps
  the original place. Positions are stored as Anchors (location in the book), never page numbers.
- Code: `ReaderSession` (beginScrub/scrubTo/scrubPhysical/endScrub, jumpTo/jumpBy/jumpToPage/
  jumpBack, backTarget), `Pagination` (pageCount, viewOfPage, pageNumberOfView, chapters),
  `ui/Navigation.kt` (ScrubTrack, ScrubberBar, BackPill, NumberPad, ChapterList),
  `ThorApp.startRepeating/handleScrubKey`.

### v0.2.0 (step 11)

0. **Debug signing**: when `keystore.properties` exists, the debug build is signed with the
   release key, so debug and release APKs install over each other (library kept). Same
   applicationId; debug label "Thor Reader (dev)" (`res` value overridden per build type).
1. **Separate font size per screen** (book modes 1/2): menu toggle "Same text size on both
   screens" (default on). Off: "Top screen" / "Bottom screen" size controls. Modes 3/4 show one
   control (mode 3 edits the top size, mode 4 the bottom size; identical while the toggle is on).
   - How the text stays continuous: the book is laid out ONCE, at the larger of the two sizes,
     in a column as wide as the larger-text screen allows. The screen with the smaller size
     draws the same lines scaled down (vector, so still sharp) in a slightly narrower centred
     column, and fits proportionally more lines per page. Every page is still whole lines of
     one single flow, so nothing is lost, repeated or cut. Trade-off: the smaller-text screen
     has wider side margins (by the size ratio, e.g. 9 vs 10 pt = 10% narrower column).
   - `LayoutConfig.slotFontPt` (per slot, reading order), `fontPt` = the larger (layout size);
     `SliceUnit.scale`; layout id / disk cache keyed by both sizes (LAYOUT_VERSION 4).
   - Text settings (sizes, same-size toggle, margins) are now saved PER BOOK (Room columns);
     a book without its own settings takes the current ones the first time it opens.
2. **More formats** (decided at run time where MuPDF might do it natively: the app logs
   `MuPDF native support: ...` at start, from `Document.recognize`):
   - HTML/HTM/XHTML: MuPDF natively; images next to the file are supplied through a MuPDF
     `TreeArchive` when the file was added with "Add folder" (SAF only lets us see siblings
     then). Single picked files show text without pictures.
   - Markdown, DOCX: MuPDF natively when it recognises them, otherwise our own converters.
   - AZW3/KF8, AZW, ODT, RTF: our own small converters (no new libraries) -> a cached EPUB in
     `filesDir/converted/`, which MuPDF then reads like any EPUB (chapters, headings, TOC).
     MOBI/AZW/AZW3: DRM detected from the file header -> "This book is DRM-protected".
   - CBT: tar via the existing Apache Commons Compress (random access, like CBZ).
   - Single image files (JPG/PNG/WEBP/GIF/AVIF, decoded by Android) = a one-page comic; with
     "Add folder", a folder holding images and no books = one comic (named after the folder).
3. **Tutorial badges**: house-style pill with a small arrow, 4 steps in order (library empty ->
   add buttons; first book; reading -> hold/Start; menu -> mode buttons). Step stored in prefs
   (`tutorial_step`); a tap anywhere (not consumed) or doing the step advances. Reader menu:
   "Show tutorial again". Start (and the Menu key) open the reader menu; key codes are logged.
4. **Library**: "Library | Completed" switch, view chips All · Authors · Series · Type · Genre
   (category lists with counts -> books, back arrow / B), search (title, author, series), sort
   (Recently read, Recently added, Title, Author, Progress; Completed also "Date completed").
   View and sort remembered. Everything focusable for d-pad + A / B.
5. **Row second line**: "412 pages · EPUB · 37%" (pages = `Book.layoutPages` saved when a full
   layout finishes, else the import page count; "Not started" instead of 0%).
6. **Completed**: reaching the last page (by turning/jumping) sets `Book.completedAt`. Gold star
   after the title. Popup: "Mark as completed" / "Mark as unread" (unread keeps the position).
   Completed books live in the Completed view (default sort: date completed).
7. versionName 0.2.0, versionCode 2. Room DB v4 (migration 3->4 adds the new columns).
8. **Bookmark (step 12, still v0.2.0)**: ONE per book, stored as an Anchor (book location:
   `Book.bookmarkChapter` / `bookmarkFraction`, fixed pages use fraction 0.5 for the right half of
   a split spread), so it survives font/margin/mode changes. Reader menu: "Bookmark" toggle under
   Return / Close (two-page views ask "Left page / Right page"), "Go to bookmark" when it is on
   another page, round colour swatch -> picker (8 presets, blue default; "Custom" hue slider; live
   ribbon preview; one global colour, `Settings.bookmarkColor`). Ribbon: slim notched ribbon from
   the top edge, top-left of a left page / top-right of a right page (single pages: top-left, or
   top-right in RTL, like the running header). Swipe UP on it = off (slides up; no page turn, no
   hold ring).
   - **Auto**: leaving while a book is open (last activity stopped - app backgrounded, swiped
     away, lid closed - or ACTION_SCREEN_OFF) sets the bookmark on the later page of the view
     (right-hand page; left-hand in RTL). Coming straight back to the same open book (activity
     started again / ACTION_SCREEN_ON while visible) removes it after 1.2 s so the ribbon is seen
     sliding away. Mode-change handovers are ignored (2 s guard). If the book was closed or the
     app killed, the bookmark simply stays. Every on/off is logged: `Bookmark ON/OFF ... (reason)`.
   - While the menu is open the d-pad moves through the menu (no page turns) and B closes the
     colour picker, then the menu.
   - Completed books stay in the Library view too (star); the Completed view lists only them.
     Their row line is "22 / 22 · CBR" with the page in green (`CompletedGreen`).
   - Library row line: "277 / 1794 · EPUB" (current page in the bookmark colour, from
     `Book.lastPage` of the last-used layout; estimated from progress if unknown), "Not started ·
     EPUB" if never opened. Room DB v5 (`MIGRATION_4_5`).

### App icon (step 10)
- `icon.svg` (repo root) is the master design on the 108×108 adaptive grid.
- Adaptive icon: `res/drawable/ic_launcher_background.xml` (#17181C),
  `ic_launcher_foreground.xml` (all artwork; the SVG's scale 0.8 around (54,54) = vector `group`
  pivot 54/54, scale 0.8; rounded rects and circles as arc path data with identical coordinates;
  the R's bowl kept as the same arc command), `ic_launcher_monochrome.xml` (same paths, white,
  for themed icons). `mipmap-anydpi-v26/ic_launcher(.round).xml`. Label "Thor Reader".

### GitHub and releases (step 10)
- Repo: https://github.com/dylosama/ThorReader (branch `main`). Commits are authored as
  `dylosama <dylosama@users.noreply.github.com>` (keeps the personal email private).
- LICENSE = AGPL-3.0 (required by MuPDF). README has modes, formats, install, credits.
- `.gitignore` excludes build output, local.properties, .idea, *.apk/*.aab, keystores,
  keystore.properties, all e-book/comic files, and step*.md task prompts.
- **Release signing**: keystore `C:\Users\Dylan\keys\thor-reader-release.jks` (outside the repo,
  PKCS12, alias `thor-reader`, RSA 4096, valid 100 years). Passwords only in
  `keystore.properties` (git-ignored) which `app/build.gradle.kts` reads. **The .jks file and
  its password must be backed up**: without them no update can ever install over v0.1.0.
- Version: versionName 0.2.0, versionCode 2 (increase versionCode for every release).
- Build: `.\gradlew assembleRelease` -> `app\build\outputs\apk\release\app-release.apk`.
  The owner uploads it to GitHub Releases manually.
- Since v0.2.0 debug builds are signed with the release key too (when keystore.properties
  exists), so debug and release install over each other. A debug APK built BEFORE v0.2.0 (old
  debug key) still needs one uninstall first.

## Visual style

One house style everywhere, taken from the overflow badge ("▾ more – drag up") that tested well:
small, quiet, translucent. Nothing bold or boxy unless it's a primary button.
Tokens live in `ui/Style.kt` (`HouseStyle`) and the reusable component is `HouseBadge`:

| Token | Value |
|---|---|
| Text size | 11 sp |
| Font / weight | system default, Normal |
| Text colour | white (#FFFFFF) |
| Background | black at 60% opacity (#99000000) |
| Shape | full pill (50% corner radius) |
| Padding | 10 dp horizontal, 3 dp vertical |
| Gap from edges | 6 dp |
| Larger surfaces (popups, menu panel) | same translucent black family (#E0141414), 14 dp corners, 1 dp hairline #33FFFFFF |
| Interactive items in popups | 13 sp, same colours, ≥ 40 dp tall for touch |
| Warning text | #FF8A80 |
| Primary buttons (only) | solid accent #E0A050, bold |

Used for: overflow hint, page numbers on comics/manga/PDF (over the art), background-layout
progress pill, mode change label, status messages/toasts (importer, loading, errors), library
mode popup. **Not** used for books' running header and page numbers: those are plain text in the
book's own font (see above) so the page looks printed.

## Milestones

1. **DONE**: dual-screen setup (gyro removed in milestone 2).
2. **Manual modes + real reading + comics/manga** (everything in "How the app works" above).
3. **Bookshelf**: a visual shelf showing each book as a spine. Spines are generated from the
   cover (dominant colours, title and author set vertically, series number), and thickness
   scales with page count. Users drag spines to arrange them freely (order saved), or switch to
   grouping by series, author, genre, format, or recently read, each group on its own shelf
   with a label. Search. Edit metadata. Shown on the top screen with tap and d-pad/A-button
   selection; the bottom screen shows the selected book's cover and details plus
   sort/group/add controls.
4. **Animations**: tapping a spine makes the book slide out of the shelf, float toward the
   viewer (gentle bob, soft glow, a few dust particles, slight tilt — magical, like a book
   enchanted off a library shelf), turn to show its cover, then open and unfold into the reader
   layout for the current mode. Plus the realistic OpenGL page-curl animation for turning
   pages, crossing between screens in book modes.
5. **Polish**: table of contents, bookmarks, search in book, brightness slider, auto-dim after
   idle, subtle pixel shifting on static content for OLED burn-in protection.

## Build / install / launch

```
.\gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n dev.thorreader.app/.MainActivity
```

## Implementation notes

### Build environment (set up 2026-11-23)
- Installed per-user (no admin): Temurin JDK 17 at `%LOCALAPPDATA%\Android\jdk17`, Android SDK
  at `%LOCALAPPDATA%\Android\Sdk` (platform-tools, platforms;android-34, build-tools;34.0.0).
  User env vars: `JAVA_HOME`, `ANDROID_HOME`, PATH += jdk17\bin and platform-tools (adb).
  `local.properties` also points at the SDK.
- Versions: Gradle 8.7 (wrapper), AGP 8.5.2, Kotlin 2.0.20 (+ compose compiler plugin),
  Compose BOM 2024.09.03, activity-compose 1.9.2.

### Milestone 1 findings
- On-device: gravity auto-detection unreliable -> replaced by manual modes in milestone 2.
- Book-mode rotation: BOOK (top = left) rotates content +90 deg (clockwise); BOOK REVERSED -90.

### Milestone 2 code map (`app/src/main/java/dev/thorreader/app/`)
- `ThorApp.kt` — Application singleton: settings, Room DB, importer, the open `ReaderSession`,
  menu/toast state, screen metrics, mode switching, **all hardware-button handling**
  (`handleKey`: Select cycles modes, Start opens menu, d-pad mapped to physical directions per
  mode, L/R shoulders = page to the left/right, Page Up/Down).
- `Settings.kt` — `Screen`, `ReadingMode` (4 modes + content rotation), `PageTheme` (colour
  matrix: white->paper, black->ink), persisted `Settings`.
- `HostActivity.kt` — shared activity base: SAF pickers (persisted permissions), back handling,
  key dispatch, `launchOnDisplay` (checks `isActivityStartAllowedOnDisplay`).
- `MainActivity.kt` — `MainActivity` (display 0; modes 1–3; Presentation only in 1/2; mode 4 ->
  starts `BottomActivity` on display 4 and finishes, else fallback) and `BottomActivity`
  (display 4, own taskAffinity; any other mode -> starts MainActivity on display 0, finishes).
- `BottomScreenPresentation.kt` — bottom screen in modes 1/2 and the mode-4 fallback.
- `OrientationSensor.kt` — UNUSED old gyro code, kept for later.
- `data/Database.kt` — Room `Book` entity (metadata, cover path, rtl, position) + DAO.
- `io/Files.kt` — `SafFile` (reads user files in place via fd), MuPDF/junrar stream adapters,
  `NaturalOrder`. `io/ImageArchives.kt` — CBZ random access; CBR/CB7 scan (import) and unpack
  to `cacheDir/comics/book<id>` on open (keeps 3).
- `importer/` — `BookFormat`, `TextToHtml` (TXT -> HTML for MuPDF), metadata parsers (EPUB OPF
  incl. calibre:series/belongs-to-collection, FB2, ComicInfo.xml, filename fallback), `Importer`
  (files or folder tree scan, covers saved to `filesDir/covers`).
- `engine/Layout.kt` — `LayoutConfig` (slot sizes per mode/direction), units/views,
  `paginateFixed` (spread split/whole), anchors (position independent of layout).
- `engine/Engines.kt` — `MuPdfFixedEngine` (PDF/XPS), `ImageArchiveEngine` (comics),
  `ReflowEngine` (EPUB/MOBI/FB2/TXT): MuPDF lays each chapter out on a 40000pt-tall page; we cut
  it into screen pages between lines/images (structured text), so each page can have a different
  height (top 1920 vs bottom 1240 in book modes). User CSS removes page margins and caps image
  height at one page.
- `engine/ReaderSession.kt` — engine on its own thread, re-paginates when mode/direction/font/
  margins change (keeps position), 200 MB bitmap LRU, prefetch current, +1, -1, +2, -2, +3.
- `ui/` — `PhysicalScreen` (what each screen shows per mode, rotation), `Reader` (page slots:
  one gesture handler for tap halves, press-and-hold menu ring, swipe, overflow scroll,
  double-tap/pinch zoom for fixed pages), `ReaderMenu`, `Library` (incl. manga toggle),
  `Common` (RotatedBox, mode icons, mode toast).
- `engine/LinePaginator.kt` — pure page breaking from line/image boxes (unit-tested).
- `io/ImageHeader.kt` — image sizes from file headers.
- Unit tests: `app/src/test/.../LayoutTest.kt` (screen placement per mode/direction, spread
  splitting, position across modes, natural sort, filename parsing). Run `.\gradlew testDebugUnitTest`.

### Milestone 2 bug-fix round 1 (after first Thor test, 2026-11-23)
Causes confirmed in code before fixing:
1. **Lines cut mid-line**: line boxes (full font ascent+descent) overlap slightly; the old
   slicer merged any boxes overlapping >1pt, so whole paragraphs became one block, and blocks
   longer than the page got a forced cut at an exact height. Fix: `engine/LinePaginator.kt`
   keeps every line its own row (only boxes overlapping >50% - side-by-side image/text, table
   cells - are grouped), pages are built from whole rows, cuts fall between lines, and a row
   taller than a page OVERFLOWS (scrolls) instead of being cut. MuPDF's own pagination wasn't
   usable because book modes need alternating page heights (1920/1240) in one flow.
2. **Overflow safety net**: `Rendered.overflow`; reflow overflow pages render at full height,
   fixed pages much taller than their area (> 1.6x the area's aspect) fill the width. UI scrolls
   them independently (`ui/Reader.kt` PageSlot, `OverflowHints`). Logged as `OVERFLOW`.
3. **ChildCancelledException error screen**: `ReaderSession` caught `CancellationException` as a
   failure. Now rethrown everywhere; errors only shown if no page exists yet; font/margin
   changes debounced 400 ms; layout work is per-chapter tasks that stop when cancelled.
4. **Slow opening**: per-chapter progressive layout (anchor chapter first, then outward; views
   re-published every ~0.6 s, rendered pages reused via stable keys = layoutId + unit), disk
   cache `filesDir/layouts/b<id>-<size>-<hash>.bin` keyed by text layout (width, font, image cap,
   LAYOUT_VERSION). Mode switches with the same page width (1 <-> 2) only re-slice (ms).
   **Each chapter now starts on a new view** (so chapters are independent; in book modes a
   chapter may end with a blank page). Comics: `io/ImageHeader.kt` reads JPEG/PNG/GIF/BMP/WebP
   sizes from headers; page sizes cached in `filesDir/pagesizes/`.
5. **Mode 4 -> 3 closed the book**: no mode-switch code path closes a book; the only callers are
   Back, "Close book", opening another book, removing it, and the error screen. So a Back event
   reached the app during the screen handover (exact Thor trigger unknown). Fix: `closeBook(reason)`
   is now the only way to close and logs its reason; Back is ignored for 2 s after a mode change
   and while an activity is finishing (logged as "Back ... ignored").
6. **Wrong screen sizes in mode 4**: `getRealMetrics()` on Android 12+ reports the calling
   context's window area. Now uses `Display.getMode().physicalWidth/Height` looked up by display
   id, density via `createDisplayContext`.
7. **Manga not detected without ComicInfo**: series-wide direction (`series_direction` table,
   Room DB v2 with migration 1->2), applied on import; library toggle.
8/9. Press-and-hold menu with filling ring + hold-time setting; big top buttons in menu.

### Milestone 2 round 2 (step 6, 2026-11-24)
- **Bug 4, "Unpacking comic" never cleared** — cause confirmed in code: engine-thread progress was
  posted with a plain `Handler`, while the "clear" ran in a coroutine resumed by
  `Dispatchers.Main`, which uses *asynchronous* messages that Android may run ahead of queued
  normal posts; late "Unpacking… N%" posts then landed after the clear. Fix: every status change
  in `ReaderSession` bumps a token and progress posts only apply while their token is current;
  every phase ends in `finally { showStatus(null) }` (finished, failed, cancelled, book closed).
  Same audit fixed: a cancelled layout leaving "Laying out N%" behind, and the importer's
  5-second auto-clear wiping a newer import's status (`Importer.finalStatus`, `guarded`).
- Per-book mode: `Book.mode` column (Room DB v3, migration 2->3), `Settings.lastBookMode`,
  `ThorApp.setMode` saves to the open book, `openBook` switches to the book's mode, `closeBook`
  always returns to Mode 3 (`returnToLibrary`), `Settings.mode` is not persisted (starts at 3).
- Running header/page number: `LayoutConfig.headerPx` (30 dp band top and bottom, part of the
  layout id), `Pagination.pageNumber` (whole-book numbering once complete), `ChapterTitle.format`,
  `PageOverlays` in `ui/Reader.kt`. Setting `Settings.showHeader`.
- Edge feedback: `ReaderSession.step/atEdge/sideBlocked`, `edgePulse` / `edgePull`, `EdgeShadow`
  composable (quadratic curve + gradient), `ThorApp.tick()` (VIBRATE permission).
- House style: `ui/Style.kt` (`HouseStyle`, `HouseBadge`, `HouseProgressBadge`, `BookCover` with
  generated placeholder). Library popup: `ui/Library.kt` (`BookModeButton`, `BesideAnchor`,
  focusable `PopupItem` rows for d-pad + A).
- Tests: `HeaderTest` (chapter header formats, header band, page numbers).

### Milestone 2 round 3 (step 8, 2026-11-24)
- Books' header/page number moved from Compose pills into the rendered page:
  `ReflowEngine.render` -> `drawPlain` (MuPDF `Text` + `fillText`, grey 0.45 before the theme
  colour matrix). Body font = most-used font on the first page with ≥ 100 characters
  (`ensureBodyFont`); falls back to built-in Times-Roman if the (often subsetted) font lacks a
  glyph. Header band: `headerBandPx` = 1.8 × header text size, min 20 dp.
- `SliceUnit.header` (first unit of each view only) vs `SliceUnit.label` (chapter name, menu).
  Page number passed to `BookEngine.render(pageNumber)` and part of the render cache key.
- `engine/RunningHeader.kt` (pure rules, unit-tested), `engine/EpubSections.kt` (spine paths,
  `HtmlHeading.find` = first h1-h3 with ≤ 400 visible characters before it; "Chapter 3" + a
  following sub-heading are joined). TOC entry positions (#fragments) resolved after each
  chapter's layout and stored in the layout cache (LAYOUT_VERSION 3).
- Reflow code moved to `engine/ReflowEngine.kt`. "End" label removed.
- Tests: HeaderTest now covers the Dune case (part-only TOC -> numbered), deepest TOC entry,
  section headings, no-TOC numbering, HTML heading extraction.

### Known limitations / to verify on device
- Mode 4 relies on Android allowing our activity on display 4. If refused, logcat says
  "using fallback" and the top screen stays black instead of being released.
- Night theme is a colour transform, so pictures inside EPUBs appear inverted in Night.
- CBR: RAR4 only (junrar has no RAR5). Encrypted/DRM books show an error.
- First open of a reflowable book still lays out the whole book once in the background (page
  count shows "still laying out" until done); afterwards it comes from the disk cache. Whether
  MuPDF really lays out EPUB chapters lazily is to be confirmed from the "Layout ... ms" and
  "First page ready ... ms" log lines.
- Tests: `.\gradlew testDebugUnitTest` (LayoutTest + PaginationTest: no line ever cut with
  equal/unequal heights, overflow, side-by-side rows, image headers).
- Hardware key codes assumed: Select = BUTTON_SELECT, Start = BUTTON_START, shoulders L1/R1/L2/R2.
  Every key press is logged (`adb logcat -s ThorReader`) to check the Thor's real codes.

### v0.2.0 code map (step 11)
- `convert/` (new): `Epub.kt` (`ConvBook`, `EpubWriter` -> EPUB 2 with NCX TOC, `HeadingSplitter`
  splits generated HTML into chapters at h1/h2), `Mobi.kt` (`MobiFile`: PDB records, PalmDOC and
  HUFF/CDIC decompression, trailing entries, EXTH, DRM flag, INDX/TAGX/CNCX index reading, KF8
  skeleton+fragment reassembly, FDST flows -> CSS, kindle:embed pictures, NCX chapter names;
  MOBI 6 fallback with recindex pictures), `OfficeDocs.kt` (`DocxConverter`, `OdtConverter`),
  `Rtf.kt` (`RtfConverter`), `BookSource.kt` (native-vs-converter decision, converted EPUB cache
  `filesDir/converted/<sha1(uri)>-<size>.epub`, `siblingFiles` -> MuPDF `TreeArchive`,
  `MarkdownToHtml` via commonmark-java).
- `io/ImageArchives.kt`: `TarImageArchive` (CBT), `SingleImageArchive`, `SafFolderImageArchive`.
- `importer/Importer.kt`: folder scan groups pictures per folder (`BookFormat.IMAGES`), DRM books
  are not added (status "This book is DRM-protected, so it can't be added").
- `engine/Layout.kt`: `LayoutConfig.slotFontPt`, `slotScale`, `textWidthPt`, `textHeightPt`;
  `SliceUnit.scale`. `ReflowEngine` renders each slice at `pxPerPt * scale`, centred column.
- `engine/ReaderSession.kt`: `checkCompleted` (turns/jumps only, incl. trying to go past the end),
  `rememberPageCount` -> `Book.layoutPages`. DRM error shown as is.
- `Settings.kt`: `fontPtBottom`, `sameFont`, `fontFor(screen)`, `applyBookText`; `Shelf`,
  `LibraryView`, `SortOrder` (+ prefs); `TutorialStep` + `tutorialDone` bitmask.
- `ThorApp.kt`: per-book text (`applyBookText`/`saveBookText`), `setCompleted`, tutorial
  (`tutorialShows`, `tutorialDone`, `tutorialTapped`, `restartTutorial`), `libraryBack` (Back/B
  in the library), Start + Menu keys open the reader menu (logged "Start/Menu key ...").
- `ui/Library.kt` (rewritten top), `ui/LibraryLogic.kt` (sorting, categories, author splitting,
  types, row line; unit-tested), `ui/Style.kt` (`focusClick` = d-pad/A clickable,
  `TutorialBadge`, `tutorialTapCatcher`), `ui/ReaderMenu.kt` (text size rows, Show tutorial again).
- Room DB v4 (`MIGRATION_3_4`). Tests: `V020Test` (per-screen sizes incl. no line lost/repeated/
  cut for 9/10, 14/8, 6/24 pt; PalmDOC, trailing entries, a hand-made MOBI, DRM flag, RTF,
  EPUB writer, library logic).
- Not verifiable on the PC (no device attached while building): which formats MuPDF opens
  natively (see the "MuPDF native support" log line), KF8 files from the wild, AVIF decoding.

### Bookmark code map (step 12)
- `engine/Layout.kt`: `bookmarkAnchorOf`, `unitHolds`, `leftUnitIndex` (unit-tested in `BookmarkTest`).
- `engine/ReaderSession.kt`: `bookmark`, `bookmarkHere`, `bookmarkPage`, `setBookmark(unitIndex)`,
  `setBookmarkOnLaterPage`, `clearBookmark`, `goToBookmark` (all logged with a reason);
  `saveCurrentPage` -> `Book.lastPage`.
- `ThorApp.kt`: `onHostStart/onHostStop` (called from `HostActivity`, so MainActivity and
  BottomActivity), screen on/off receiver, `onLeave`/`onReturn` (logged "Leaving: ..." / "Back: ...").
  `menuBack` + d-pad pass-through while the menu is open.
- `ui/Bookmark.kt`: `BookmarkRibbon` (AnimatedVisibility slide), `inRibbonZone`, `ColorSwatch`,
  `BookmarkColorPicker`, `HueSlider`. `ui/Reader.kt`: ribbon swipe handled first in the page
  gesture. `ui/ReaderMenu.kt`: `BookmarkControls`. `LibraryLogic.detailLine` -> `PageLine`.

### Metadata fixes and online lookup (step 13, still v0.2.0)

Causes of the reported problems (checked in code):
- Spider-Man missing from Type/Series: it was completed, and the Library list (which the views
  use) excluded completed books. Fixed by the step-12 follow-up (Library shows every book); the
  views therefore include completed books with their star. Series with one book were already
  listed; now confirmed by a test.
- Blue Period under Comics: no ComicInfo.xml, so nothing said "manga". Now: online lookup.
- "Unknown" authors: comics without ComicInfo.xml have no author in the file. Now: online lookup.
- Harry Potter's genre = a description: the PDF "Subject" field was used as a genre.

Rules:
- **Type** (exactly one): Manga if right-to-left, ComicInfo Manga=Yes/YesAndRightToLeft, or the
  online lookup says manga (AniList format MANGA/ONE_SHOT or country JP/KR/CN); Comics = other
  comic archives (CBZ/CBR/CB7/CBT/IMAGE/IMAGES); Books = everything else. A hand-set type wins
  (`Book.typeOverride`).
- Online manga from Japan switches on right-to-left (automatic flag `mangaRtl`, for the book and
  its series) unless a direction was set (`rtlOverride` stays untouched). Korean/Chinese: LTR.
- **Genres**: short tags only (max 3 words, no sentence punctuation), normalised to a canonical
  list by keyword (`metadata/Genres.kt`: "Fiction / Fantasy / General" -> Fantasy). Long text
  goes to `Book.description` (shown in Edit info and the reader menu). Several genres allowed.
- **Series view**: every series, one-book series too, volumes/issues sorted by number.
- **Filename parsing**: "Series v01 (2020) (Digital) (group)", "Series 001", "Series v1 001",
  "Series Vol. 3", "Series #12"; bracketed tags stripped; year kept; volume + issue.

Online lookup (`metadata/`), metadata only, never book content, never touches the files:
- Background queue after import, "Refresh info" in the library popup, setting "Look up book info
  online" (default on). One worker; per-host minimum interval (AniList 2.5 s, ComicVine 1.5 s,
  Open Library 1 s), 429/5xx back-off (Retry-After or doubling, max 10 min), responses cached on
  disk for 30 days (`cacheDir/lookup`), offline -> state "retry", retried when the network
  returns and at the next start.
- Sources: comics/manga with a ComicInfo series + writer use that (no lookup needed for those
  fields); otherwise AniList (manga; also tried for unflagged comic archives, but those need a
  stricter score); ComicVine (comics, only with the user's key: volume search -> issue -> issue
  details for writer, artist, story arc; publisher; description); Open Library (books: ISBN from
  the EPUB, else title + author; authors, subjects -> genres, first publish year, description).
- Matching: score = title similarity (+ volume/issue, year, publisher, author). Applied at
  >= 0.75 (0.9 for AniList on comics not flagged as manga); otherwise "Needs review".
- Hand-edited fields (`Book.editedFields`) are never overwritten. Logged per lookup: source,
  query, best match, score, applied or not. API keys never logged.
- ComicVine key: Settings screen (gear in the library), stored encrypted with an Android Keystore
  AES key; "Get a free key" (opens comicvine.gamespot.com/api), "Test key". One-time house-style
  hint when a comic is imported without a key.
- Edit info (library popup): title, authors, series, number, type, genres, description;
  "Search online" shows the top 5 candidates (cover, title, year, author) - picking one fills
  the form, Save stores it as hand-edited. "Needs review" chip in the library views.
- First launch after this update (`meta_version` < 2): every book's file metadata is re-read
  with the new rules, then all books are looked up online.
- Room DB v6 (`MIGRATION_5_6`): description, isManga, country, publisher, year, isbn,
  storyArc, typeOverride, editedFields, lookupState, needsReview, lookupAt.
