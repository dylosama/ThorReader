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
and startup. (The unused sensor code was deleted in step 20.)

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
- Version: versionName 0.2.0, versionCode 3 (increase versionCode for every release; code 3 =
  0.2.0 re-release with the step-21 fixes).
- Build: `.\gradlew assembleRelease` -> `app\build\outputs\apk\release\ThorReader-<versionName>.apk`
  (named by Gradle itself, `applicationVariants` in app/build.gradle.kts; debug:
  `app\build\outputs\apk\debug\ThorReader-<versionName>-debug.apk`).
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
adb install -r app\build\outputs\apk\debug\ThorReader-0.2.0-debug.apk
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
- (`OrientationSensor.kt` deleted in step 20: no sensors are used.)
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

### Library layout polish (step 14, still v0.2.0)
- **Row**: line 1 title (+ gold star), line 2 author (grey #B8B8B8, SemiBold, 15 sp - between
  the 17 sp title and the 13 sp third line), line 3 "277 / 1794 Pages · Published: 1965 · EPUB"
  (current page in the bookmark colour; completed "1794 / 1794 Pages" in green; "Not started /
  1794 Pages · ..."). No "Published" part when the year is unknown. The library background is
  always dark (page themes only affect reading), so contrast is checked against #1A1A1A.
- **Published year** (`Book.year`): the first published / release year. The online first-published
  year (Open Library first_publish_year, AniList start year, ComicVine cover year) replaces
  the year from the file (ComicInfo Year, EPUB dc:date, PDF creation date), unless edited in
  Edit info ("Published" field, edited-field "year"). `meta_version` 3 re-scans once more so
  existing books get their year (lookups come from the 30-day cache).
- **Sort row** below the view chips: Recently read · Recently added · Title · Author · Progress ·
  Published (+ "Completed" date in the Completed shelf). Active one shows ↑/↓; tapping it again
  flips the direction; another option starts with its default (newest first for dates, A→Z for
  text, highest first for progress). Remembered per shelf (`sort_*`, `sort_*_asc`). Applies to
  the All list, category lists (Title/Author sort by name; others order categories by their
  best book) and inside categories (inside a series, "Title" means volume/issue order).
  Books never opened / without a year always go last.
- **Empty library**: only "Add your books to get started" and two big buttons (Add files / Add
  folder) in the centre; the "Add your books here" badge points at them.
- **Top bar with books**: centre = "Library | Completed" switch with counts + round search
  button that expands into a search field in place (× clears and closes); right = round "+"
  (popup: Add files / Add folder) and round gear (Settings: online lookup, ComicVine key, Show
  tutorial again); left = small status badges (importing, looking up). Old Add/⚙/sort chips removed.

### Select button (after step 14, still v0.2.0)
- The Select button no longer changes the mode by default. Reader menu, under the mode
  buttons: "Select button changes mode" On/Off (`Settings.selectCyclesMode`, pref
  `select_cycles_mode`, default off). When off, ThorApp.handleKey leaves Select alone.
  README no longer mentions Select. (Older notes above that say "Select cycles modes" describe
  the earlier default.)

### Library declutter (step 16, still v0.2.0)
- ONE row under "Library | Completed" + search, in the exact style of the step-14 sort row
  (same chip size, font, weight, colours, spacing, radius, highlight, ↑/↓):
  Recently read · Recently added · Progress · Title · Author · Series · Genre · Type
  [· Needs review (N) at the end, only when N > 0]. The separate view-chip row is gone, and so
  is the row's old "Sort" label (the row now also groups). Scrolls sideways (LazyRow) and keeps
  the active chip in view; d-pad navigable.
- Flat chips sort the whole list; tapping the active one flips ↑/↓ (defaults: newest first,
  highest progress first, A→Z). Grouped chips show names with counts ("Frank Herbert · 1");
  tapping the active one flips the names A→Z / Z→A (Type: Books, Manga, Comics / reversed).
  Inside a group, books follow the last flat sort chosen; inside a series always volume order.
- "Published" and "Completed date" are no longer sort options (the year still shows on rows).
- One row state for both shelves, remembered: `lib_chip`, `flat_sort`, `flat_asc`, `group_asc`
  (default Recently read, newest first). Replaces `lib_view` and the per-shelf `sort_*` prefs.

### Swap pages between screens (step 17, still v0.2.0)
- Fixed-layout books (comics, manga, PDF/XPS, picture folders) in book modes 1/2 only: show the
  small (bottom) screen's page on the big (top) screen and vice versa. View-only: position,
  page numbers and bookmarks are untouched (`ReaderSession.swappedView` = the view index that is
  swapped; any page turn, re-layout or mode change ends it).
- Triggers: swipe UP (in reading orientation) on the small screen's page -> swap; swipe up on
  the big screen while swapped -> back. Only when not zoomed and the page isn't a scrolling
  (overflow) page; the ribbon swipe is checked first; dragging cancels the hold ring; mostly
  vertical (|dy| > 1.2 |dx|, > 60 dp). Thor Y button (KEYCODE_BUTTON_Y, logged) toggles.
  Menu toggle "Swipe up to swap screens (comics)" (`Settings.swipeSwap`, default on).
- Not allowed for split double-page spreads ("Spread can't be swapped" pill) or when one side
  is blank. Right-to-left books work the same (it swaps screens, not reading order).
- Animation: each screen's page slides out toward the hinge while the other slides in from it
  (300 ms, FastOutSlowIn), so they appear to pass each other across the hinge; each page is
  rendered for its new screen's size. House pill "Page 7 ⇄" on the big screen, fades after 1.5 s.

### Shoulder buttons for manga (step 18, still v0.2.0)
- Before: L1/L2 = page on the physical left, R1/R2 = page on the physical right, so in
  right-to-left books R1 went BACK. LTR books: R1 = next page and L1 = previous in all four modes
  (unchanged; every press is now logged so this can be checked on the Thor).
- Reader menu toggle "Swap L1/R1 for manga" (only for right-to-left books), default ON, saved
  per series (pref string set `shoulder_swap_off` of series keys / "book:<id>" for books without
  a series - only the OFF choices are stored). When ON (RTL books): Mode 1 R1 = next, L1 =
  previous; Mode 2 L1 = next, R1 = previous; Modes 3/4 R1 = next, L1 = previous. When OFF: the
  physical direction as before. L2/R2 behave like L1/R1. Only the shoulders change (d-pad, taps,
  swipes still follow the screen); holding a shoulder repeats the same action.
- Pure mapping in `ShoulderButtons.kt` (unit-tested). Log line per press:
  "Shoulder R1 (code 103): mode 1, right-to-left, swap ON -> next page".
- Addition: in Mode 1 (Book), fixed-layout books only, d-pad RIGHT toggles the swap instead of
  turning the page (Y still toggles too). Mode 2 keeps d-pad right as a page turn (Y swaps).
  Other modes and reflowable books unchanged. Logged: "D-pad RIGHT (code 22): mode 1,
  fixed-layout -> swap screens" / "... -> page turn (...)". Menu label follows the mode:
  "Swap screens: swipe up or d-pad right" (Mode 1), "Swap screens: swipe up or Y" (Mode 2);
  the On/Off switch is for the swipe only, the buttons always work.

### Library list management (step 19, still v0.2.0)
- **Hide entries** (Author, Series, Genre lists; never Type): stored per list in prefs string sets
  `hidden_authors` / `hidden_series` / `hidden_genres` (display names, matched ignoring case and
  spaces). Only that grouped list drops the name; books stay everywhere else. Ways to ask:
  swipe left on the right half of a row (row slides to reveal an eye-with-a-line icon + "Hide?"
  pill; swipe back / tap elsewhere cancels), hold a row ~0.5 s (long press), or X on a focused
  row (A confirms, B cancels; key code logged). Confirm -> the row collapses, then a "Hidden ·
  Undo" pill for 4 s.
- **Settings**: collapsible "Hidden items (N)" (collapsed by default) grouped Authors / Series /
  Genres, each with "Unhide"; "Unhide all" asks to confirm.
- **Add author / Add series** (those two lists only): faint "+ Add author/series" line at the end
  of the list, or tap the empty space below it. Overlay flow: 1) pick books (search, multi-select),
  2) per picked book that already has one: series "Move '<book>' from series 'X' to the new
  series?" Move / Cancel; author "'<book>' is by 'X'. Move it to the new author, or add the new
  author alongside?" Move / Add / Cancel (Cancel leaves that book out), 3) type the name
  (suggestions from existing names), for series also a number per book (pre-filled). A name
  matching an existing one (ignoring case) merges into it, and the result says so. Saved as
  hand edits ("authors" / "series" / "number" in `editedFields`), so lookups never undo them.
  Several authors are joined with " & ". Genre: hide only. Type: nothing.
- Pure rules in `LibraryLogic` (`categories(... hidden)`, `matchExisting`, `withAuthor`,
  `withSeries`), unit-tested in `ListManageTest`.

### Hardening pass (step 20, still v0.2.0) - no new features
Found in the audit (code-confirmed) and the fixes:
1. "Laying out 100%" stays for seconds: after the last chapter, the whole book's page list is
   rebuilt (buildViews over every chapter, re-running running-header choice per page), then
   published; only then is the badge cleared. And during layout the full list was rebuilt every
   0.6 s (quadratic work). Fix: per-chapter view cache (each chapter's pages built once per
   slicing config), TOC entries prepared once per layout, progress = laid-out chapters only and
   the badge clears the moment the final pages are published; the disk-cache save runs after,
   silently. Badge moved to the top-left of each page screen (tiny, in the corner margin; moved
   right of a bookmark ribbon). Comic unpack: pruning old unpacked comics moved off the
   critical path.
2. Power: "Looking up book info…" / progress badges had an endless spinner (continuous redraw)
   -> static icon. FLAG_KEEP_SCREEN_ON was always on -> only while reading, and released 10
   minutes after the last touch/button. Menu clock: once a minute, on the minute, only while
   the menu shows. Windows ask for 60 Hz (preferredRefreshRate / preferredDisplayModeId)
   instead of 120 Hz. Old gyro code deleted. Shared background executor: 2 threads, background
   priority, for import / lookups / re-scan; a pause gate stops background work while pages are
   being turned (1.5 s), the app is in the background or the screen is off; background chapter
   layout waits on the same gate and runs at background priority on the engine thread.
   Prefetch current ±2 views (was +3), render cache 120 MB (was 200 MB).
3. Disk: one cache budget (setting, default 500 MB) over unpacked comics, lookup cache,
   layouts, page sizes and converted EPUBs (re-created when needed; covers never counted),
   least-recently-used first; "Clear cache" in Settings.
4. Security: CBR/CB7 unpack sanitises the extension used in file names (path traversal) and
   caps entries (10 000), per-file (256 MB) and total (4 GB) size; every in-memory archive
   read is capped (comic pages 128 MB, XML / metadata 8 MB) against decompression bombs; XML
   is parsed with DOCTYPE/entities stripped and DOCDECL off (no XXE); network = HTTPS only
   (network security config, no cleartext), response size caps (JSON 4 MB, images 10 MB);
   allowBackup=false + data extraction rules exclude everything (the key never leaves the
   device); revoked file permissions give a friendly message; libraries updated (junrar 8.1.2,
   XZ 1.12, commonmark 0.33; MuPDF 1.28.5 and Commons Compress 1.28.0 already latest).
   Release builds: R8 minify + resource shrinking, and Log.v/d/i removed (warnings/errors only).
   Permissions: INTERNET, ACCESS_NETWORK_STATE (retry when back online), VIBRATE (edge tick).
5. **Biggest find**: `EpubSections.heading` cached "no heading" as null, and Kotlin's
   `getOrPut` treats a stored null as missing, so for every page of a heading-less chapter the
   whole chapter HTML was re-read from the EPUB zip and re-scanned. That was the 2-3 s before
   Dune's first page (even with the layout from the disk cache) and the long "100%".

Measured on the Thor (AYN Thor, Android 13), Dune (EPUB, Mode 1), before -> after:
| | before | after |
|---|---|---|
| Release APK | 33.1 MB | 13.8 MB |
| Cold start to library (release, am start -W) | ~195 ms | ~170 ms |
| Dune first page, layout from disk cache (debug log) | 2,890 ms (release 2,274) | 95 ms |
| Dune full layout from disk cache | 3,311 ms | 520 ms |
| Dune with NO layout cache: first page / badge gone | - / long "100%" | 363 ms / 22 ms after the last chapter |
| Memory while reading (release, TOTAL PSS) | 310 MB | 216 MB |
| Idle 60 s on a page: CPU / frames drawn | ~0.02 s / 0 | 0.00 s / 0 |
Idle CPU was already ~0 before; the fixes remove the cases that weren't idle (spinner badges,
screen kept on forever, 120 Hz). Debug log lines added: "Badge: ...", "Built pages for
chapters a..b in N ms", "Display N: asking for 60 Hz".

### Step 21 fixes (shipped as v0.2.0, versionCode 3): RTL manga, small screen blacks out on forward turns
Hypothesis (checked on the Thor below): comic pages were decoded at up to 2x the screen size
(inSampleSize only halves), ~12 MB per manga page, 2 per view; Step 20 cut the render cache to
120 MB, so current ±2 views (~118 MB) no longer fit. The LRU evicts the oldest entries; prefetch
renders each view's slot 0 before slot 1, and in RTL Mode 1 slot 0 is the BOTTOM screen, so the
next view's bottom page was the one thrown out -> decoded on demand -> black meanwhile.
Backward worked because the previous view was rendered more recently.
Fix:
- Comic/picture pages are scaled to exactly the size they are shown at (fit the slot), ~8 MB
  max per page: ±2 views fit easily in the same 120 MB cache.
- Prefetch by VIEW with the display's own slot mapping (`screenPages` used by the UI,
  `prefetchRequests` / `prefetchOrder` by the prefetch, all in Layout.kt, tested in
  PrefetchTest): current, next, previous, then ±2. Normal priority: it shares the one render
  thread with the page being waited for; it stays bounded by the cache and the ±2 views.
- A page turn also ends a screen swap (`show()`); the swap belongs to the view on screen.
- Checked on the Thor (debug build, Blue Period Vol. 2, right-to-left, book mode): 14 turns
  (8 forward, 3 back, 3 forward) were all "slot0 hit, slot1 hit"; both screens showed the new
  view within 1 ms of each other, 24-46 ms after the key press. Spread swap with Y on page 5:
  whole spread fitted on the big screen; Y again split it. Auto split 5 of 192 pages (ratio
  1.40 vs typical 0.70).
- Atomic turns: the screens draw `shownIndex`, not `viewIndex`. A turn commits (both screens
  switch together) only when every page of the new view is rendered; until then the old pages
  stay up, and a house "Loading…" badge appears only after 300 ms.
- Per-turn log: "Turn forward to view N (from M): slot0 hit, slot1 MISS ..." (a miss on a
  normal one-step turn is logged as a warning) and "Screen TOP/BOTTOM shows view N, X ms after
  the turn".
- Version: NOT a new version name. Kept versionName 0.2.0 and raised versionCode to 3, so anyone with
  the first 0.2.0 (versionCode 2) gets a clean update. APK ThorReader-0.2.0.apk; the v0.2.0 git tag
  is moved to this commit (tag only; main is never force-pushed).

### Step 21 addition (also v0.2.0, code 3): swapping a double-page spread, better spread detection
- Swap on a split spread (swipe up, Y, or d-pad right in Mode 1) no longer says "Spread can't
  be swapped": the big (top) screen shows the WHOLE spread fitted to it (aspect kept, black
  around, double-tap / pinch zoom work), the small screen shows the same spread dimmed with a
  house pill "⇄ Swipe up to split"; swiping up on either screen (or Y / d-pad right) splits it
  again. Same slide animation (~300 ms). Turning the page ends it. Modes 1/2, LTR and RTL.
  Code: `screenPages(... swapped)` returns FixedUnit(page, WHOLE) at each screen's own slot for
  a swapped split spread; `isSplitSpread(view)`; toggleSwap pre-renders the whole spread at
  both slot sizes first.
- Spread detection (`spreadDecision` in Layout.kt, unit-tested): Auto = width/height >= 1.2
  AND >= 1.5 x the book's typical (median) page ratio, so a single page with wide margins or
  landscape pages in a landscape book aren't split; Always = every page wider than tall
  (>= 1.05); Never = no splitting. Per-book reader-menu option "Split wide pages: Auto /
  Always / Never" (pref `split_wide`, "bookId:MODE" entries; part of LayoutConfig so changing
  it re-paginates). Every page's ratio and decision is logged once per book and mode (debug).

### Last 0.2 changes (still versionName 0.2.0, versionCode 3)
- Reader menu: battery next to the clock - "17:36 │ ▭ 84%": thin divider, outline battery icon
  (fill = charge, lightning bolt while charging), percentage smaller/lighter than the time;
  warning colour below 20% when not charging. `ui/Battery.kt`: the level comes from the sticky
  ACTION_BATTERY_CHANGED intent when the menu opens, and the receiver is registered only while
  the menu is on screen (unregistered when it closes); no polling. The menu panel is the same
  dark house panel in Paper, Sepia and Night, and the clock isn't mirrored in RTL books, so the
  battery isn't either. Checked on the Thor (16%, charging over USB: bolt, normal colour).
- APK names come from Gradle (`applicationVariants` in app/build.gradle.kts): assembleRelease
  -> `ThorReader-0.2.0.apk`, assembleDebug -> `ThorReader-0.2.0-debug.apk`. No copy step.
- The GitHub release tag is `0.2` (remote tags: `0.1`, `0.2`); it is moved to the final commit
  (tag only).
- Typing on the bottom screen. Every text field (library search, Edit info, Add author/series
  name and number, Settings ComicVine key) is a `TypingField` (`ui/Typing.kt`): it never opens
  the system keyboard on the top screen. Tapping it starts a `TypingSession` (`ThorApp.typing`);
  the field shows the text live with a still caret, so the list filters as you type.
  - Approach A (system keyboard on display 4) was tried first on the Thor and fails: Android
    logs "Ignoring showSoftInput() as view=EditText ... is not served" for a window on the
    bottom screen (the IME only serves the focused top-screen app). So approach B is used.
  - Approach B: the built-in keyboard (`BottomKeyboard`), house style: preview strip with the
    text, caret and a Paste button; QWERTY with shift, a numbers/symbols page, backspace (hold
    to repeat), space, cursor ◀ ▶, Done; number pad for number fields; light haptic tick.
    Mode 3: shown in `TypingPresentation` on the bottom screen, which exists only while typing
    and is dismissed afterwards (bottom screen free again). Modes 1/2: shown on the bottom
    screen's existing page instead of "Choose a book". Mode 4 (one screen): covers the lower
    half of the library. Never while a book is open.
  - Buttons: d-pad moves the key highlight, A types it, B = backspace (on an empty field: done),
    Start = Done, Back = done. Physical/Bluetooth keyboards type straight in (Enter = done).
  - Typing ends on Done/Start/Enter, B on an empty field, Back, a tap outside the field and the
    keyboard (touch flag, `Modifier.typingArea()`; no coordinates, so it works rotated), closing
    search, or the app going to the background. Tests: TypingTest.
- Grouped lists cut off (fixed). Measured on the Thor (uiautomator bounds): the flat list was
  252-1043 px (791 px) but the Author list only 252-647 px (395 px = half), clipping the 4th row.
  Cause: in `CategoryList` the LazyColumn had `weight(1f, fill = false)` and the invisible
  "tap empty space to add" Box under it had `weight(1f)`, so the two split the height 50/50.
  Fix: one shared list, `FillList` (`ui/FillList.kt`: LazyColumn + fillMaxSize + 24 dp bottom
  padding), used by the flat sorts, Author/Series/Genre/Type, the books inside a group,
  Completed and the Add author/series picker (which was capped at 380 dp inside a second
  scroller; its panel now takes the full height on the "Pick books" step). The empty-space tap
  is now a tap handler on the list's parent that only fires below the last row. Settings
  (with Hidden items) is a full-height scrolling panel and was never clipped. After: Author and
  Genre lists 252-1043 px (791 px, same as the flat list), last entry fully reachable, d-pad
  focus scrolls into view. Regression test: LayoutRulesTest (fails on the old code).
  Also: the series-number fields now really get the number pad.

### Step 22 (v0.3.0, versionCode 4): page-turn animations
Settings (reader menu): "Page turn" Curl / Flip / Slide / Fade / None (default Curl, global;
"This book" row = Same as all / a style, per-book override, pref `turn_style_book`), speed
Fast / Normal / Slow (220 / 350 / 600 ms, ease-in-out cubic), Settings: "Simpler animation on
low battery" (default On: below 15 % and not charging -> Fade).

Design (all code in `engine/PageTurn.kt` = pure geometry/timing, unit-tested, and
`ui/PageTurnOverlay.kt` = drawing):
- ONE shared clock: `ReaderSession.turn` (from view, to view, direction, style, duration) and
  `turnT` (0..1, a snapshot state). A single driver coroutine on the main thread's frame clock
  (Choreographer, `withFrameNanos`) advances `turnT`; both windows (MainActivity on display 0,
  Presentation on display 4) read the same value in their draw phase, so they move in the same
  frame. Nothing runs when no turn is active: the overlay is only composed while `turn != null`.
- Atomic commits stay (Step 21): an animation starts only when the target view's pages are
  already rendered (prefetch). If a turn had to wait for pages it commits WITHOUT animation
  ("Loading…" after 300 ms, as before). `shownIndex` switches at the start of the turn; the
  overlay is opaque over each page area, so the live (new) page underneath appears exactly
  when the overlay is removed - no flash.
- Each page area is a REGION. A turn is either one region (a single page / a wide spread
  turns as one sheet) or two regions on either side of a HINGE (book modes 1/2: the two
  screens; modes 3/4: the two halves of a spread, folding at the centre).
  Two regions: the sheet starts on side S (forward = the next-page side: right in LTR, left in
  RTL; backward = the other side). Region on S = SOURCE (phase 1, t 0..0.5): its old page curls
  from the outer edge to the hinge, revealing its new page. Other region = DESTINATION (phase 2,
  t 0.5..1): its new page unrolls from the hinge onto the old page. Axis at the hinge on both
  sides at t = 0.5, angle forced straight there, one easing over the whole t -> continuous
  speed across the hinge. One region: forward = old page curls off from the next-page edge
  over the new page; backward = the same played in reverse with the new page as the sheet.
- Textures: per region, the old and new page are recorded as a `Picture` (rendered bitmap +
  theme colours + bookmark ribbon + comic page-number pill; books' running header and number
  are already in the bitmap) - so they move with their page. Curl needs bitmaps: the picture is
  rasterised on the GPU into a HARDWARE bitmap (books full size = crisp; PDF/comics at half
  size); recycled when the turn ends (max 4 per screen, ~bounded). Slide/Fade/Flip draw the
  pictures directly (no bitmap at all).
- CURL = a GPU cylinder-deformation fragment shader (AGSL `RuntimeShader`, Android 13+, which
  Android's renderer compiles to OpenGL ES 3.2 on the Thor's Adreno 740). Per pixel it inverts
  the cylinder mapping: back of the folded flap (faint mirrored, lightened copy = 85 % paper
  tone; comics: plain paper tone, dark grey in Night), the bent part with a lighting gradient,
  the flat page, and the page underneath with a soft shadow at the cylinder edge and under the
  flap. Night: back and shadows stay dark. Chosen over a separate GLSurfaceView: no extra
  surface / EGL context per screen, no touch interop problems over the page, nothing alive
  when idle. Radius = 0.28 inch converted with each screen's real dpi, so the bend has the same
  physical size on both screens (the two screens have different pixel densities).
- FLIP: the sheet rotates about the hinge (Camera rotateY, slight perspective, darkening near
  90°); two regions: phase 1 0->90° on the source, phase 2 90->0° on the destination.
  SLIDE: old moves out, new moves in, soft shadow on the leading edge. FADE: crossfade.
- Input: a horizontal drag starts an interactive turn if the target view is already rendered:
  the curl corner tracks the finger (axis = perpendicular bisector of corner->finger, corrected
  for the cylinder so the corner lands under the finger), top/bottom thirds lift that corner,
  middle curls straight. Release: complete if t >= 0.35 or a fast flick (> 1000 px/s) in the
  turn direction, else spring back. Taps, d-pad, shoulders, menu ±1/±10: full animation.
  Held buttons (repeat) and one-view jumps from scrubber / go to page / chapters / bookmark: a
  fast Slide (120 ms); bigger jumps: no animation. A new turn mid-animation finishes the
  current one instantly, then starts (no queue). Edge-of-book shadow unchanged.
- Safety: the menu opening, a mode change, the app stopping / lid closing / screen off all
  finish the turn instantly (the position is already saved by goTo). Shader creation failure
  or a hardware-bitmap failure -> Slide for the session, logged. Too slow: a turn whose p95
  frame interval > 34 ms (two frames at 60 Hz) counts as slow; 3 slow curl turns in a row ->
  Slide, logged with the numbers.
- Frame rate stays at the Step 20 60 Hz hint: switching the display to 120 Hz for each turn
  costs a mode switch (a dropped frame) on each screen plus power; measured frame times decide.
- Logs: "Turn anim <style> view a->b: N frames, p50 x ms, p95 y ms" (debug) and fallbacks.
- Tests (PageTurnTest): curl projection (flat part unchanged, fold lands on the axis at height
  2r, corner tracks the finger), hinge handoff timing / continuity, RTL mirroring of sides and
  roles, completion threshold and flick.
Checked on the Thor (debug build, Mode 1):
- Dune (EPUB, Paper), turn slowed to 4-10 s with the debug-only `debug.thorreader.turnms`
  property for screenshots: at ~30 % the right page (bottom screen) lifts from its outer edge
  and folds back toward the hinge, the flap shows the faint mirrored page (its number "5"
  readable mirrored), the next page "7" underneath, soft shadows; at ~60 % the new left page
  "6" lands on the top screen from the hinge outward with the old page's last strip at the
  outer edge. Backward turns the same, mirrored. Flip: the page swings up toward the reader
  around the hinge, darkening, and lands on the other screen.
- Blue Period (CBZ manga, right-to-left), finger drag: the page follows the finger from the
  left page's outer edge; released at 51 % -> completes. Plain paper back, only as wide as the
  printed page (comic textures keep the margins transparent; first try painted the back over
  the black side bars - fixed).
- Frame times, 10 normal-speed curl turns (350 ms): animation clock frame interval p50 16.7 ms,
  p95 16.8 ms (one 33 ms frame in the very first turn); gfxinfo over both windows: 492 frames,
  render time p50 5 ms, p90 6 ms, p95 21 ms, 13 janky (2.6 %) = about one per turn: the first
  frame, which creates the GPU textures before anything moves.
- Idle after a turn: 0 frames rendered and 10 ms of CPU in 15 s (gfxinfo, /proc stat).
- Rapid input (6 presses 80 ms apart): each turn "finished early (new turn)", every page
  pre-rendered ("hit"), no crash. Menu (Start) mid-turn: "finished early (menu opened)". Screen
  off right after a turn: position saved, automatic bookmark on, removed on screen on.
- Not tested on the device: Modes 3/4 (one-screen spreads folding at the centre), the low-
  battery Fade (the Thor was charging), and the slow-device fallback (never triggered).
- Debug tool: `adb shell setprop debug.thorreader.turnms 4000` (debug builds only, read when a
  book opens) slows normal turns down for screen recordings; `setprop ... 0` turns it off.

#### Step 22 tweaks (still v0.3.0, not published yet)
- Corner curl for buttons and taps (`TurnCause.BUTTON`: d-pad, shoulders, Page Up/Down and tap
  zones): the Curl lifts the BOTTOM outer corner (setting "Button turn corner: Bottom / Top",
  pref `turn_corner_bottom`, default Bottom). The fold pivots on that corner's edge (so nothing
  lifts before t = 0), starts at ~26° (`CORNER_THETA` 0.45 rad), stays diagonal through most
  of phase 1 (tilt × (1 − (t/0.5)²)) and is exactly straight at the hinge on both screens; on
  the landing screen the same tilt mirrored (× sin(π·u)) so the leading corner touches down
  first, straight again when flat (`cornerTheta`, `cornerPivotY`, tested). Mirrored for RTL and
  backward turns by the region frame. Swipes unchanged (finger-driven); held buttons still a
  fast slide. Bug found on the Thor: the source screen curled straight because every button
  turn was treated as a "released drag" - fixed (only interactive turns use the release tilt).
- Back of the page: comics / PDF use the page's own paper colour = median colour of a 2 %
  strip along the four edges of the rendered page bitmap (near-black pixels ignored), cached per
  bitmap (WeakHashMap), near-white #F4F1EA if sampling fails; in Night dimmed to 88 %. Books keep
  the theme paper (faint mirrored text; Night dark). Shaded along the bend as before. Shadows
  softer: 0.2 (Night books 0.45).
- Checked on the Thor (Night theme, Mode 1, turns slowed to 6 s, recordings of both screens):
  Spider-Man forward with Page Down - bottom screen: the bottom outer corner peels up
  diagonally showing the next page, back in the comic's cream paper tone; top screen: the new
  page lands corner-first diagonally next to the hinge. Dune: same corner lift; the flap shows
  faint dark mirrored text, no bright flash. All turns frame interval p50 16.7 / p95 16.8 ms.

### Book open / close animation (still v0.3.0, not published yet)
Setting "Open & close animation" (Settings, default On; pref `book_anim`). Off = instant (as
before). Low battery (same rule as page turns) or a failure = a quick 250 ms fade.

Design (`engine/BookAnim.kt` = pure geometry/timing, tested; `ui/BookAnimOverlay.kt` = drawing):
- ONE virtual space for both screens: the top screen at (0,0)-(Wt,Ht), the bottom screen right
  under it, centred: (ox, Ht)-(ox+Wb, Ht+Hb), ox = (Wt-Wb)/2. The cover is positioned in that
  space; each screen draws it shifted by its own offset and clipped to itself, so a cover that
  moves (or swings) across the hinge is one continuous motion. One clock (`ThorApp.bookAnimT`,
  main-thread frame clock), read in the draw phase of both windows.
- The switch happens at t = 0 (the book starts opening / closing in parallel). What the screens
  showed before is kept as a PixelCopy snapshot of each window (library on open; the pages on
  close), so nothing pops. Snapshots and the cover bitmap are recycled when the animation ends.
- Timeline in "open state" s (open: s = t; close: s = 1 - t), 800 ms:
  0-0.15 lift (scale 1.08, soft shadow) while the library dims (to 85 % over 0-0.3); 0.15-0.5 the
  cover moves to its place (and turns ±90° in Modes 1/2 to match the sideways book); 0.5-1 the
  cover swings open around its spine (Camera 3D rotation, perspective, darker toward 90°; past
  90° the inside of the cover, fading out) while the backdrop fades to reveal the live pages
  (s 0.55-0.85).
- Where the cover goes: Mode 3: the right half of the top screen, spine on the centre line
  (RTL: left half), rotateY. Mode 4: the same on the bottom screen - the cover slides down off
  the top screen and in from the bottom screen's top edge. Modes 1/2: the top screen's page area,
  rotated like the content (+90 / -90), spine on the hinge (top screen's bottom edge); it swings
  around the hinge (rotateX) and crosses onto the bottom screen. RTL in Modes 1/2: the cover
  first moves to the bottom screen and swings up onto the top one.
- Never reveal blank pages: the open holds at s = 0.5 (cover closed in place, a static soft
  shadow, NO frames) until the first view is rendered (`ReaderSession.firstViewShown`, set by
  the prefetch when the current view's pages are all rendered, or an error); "Opening…" house
  badge after 300 ms.
- Windows stay alive while the animation needs them: Mode 4 open keeps MainActivity (top) until
  done, then finishes it as before; closing from Mode 4 keeps BottomActivity until done;
  closing from Modes 1/2 keeps the bottom Presentation until done. Then released as now.
- Tap anywhere or A / B / Back: skip to the end (open: to the hold point if pages aren't ready).
- Close: user closes only (menu "Close book", Back); the menu is closed first, then snapshots.
  The cover lands back on its row (row bounds recorded by the library; fallback: shrink/fade).
- Checked on the Thor (debug build, Night theme; debug-only props `debug.thorreader.bookms`
  = duration and `debug.thorreader.bookfreeze` = stop at a point until a tap, for screenshots):
  Mode 1 (Dune): library dims, the cover lifts out of its row (row left empty), grows and turns
  90°, swings around the hinge with its free edge toward the reader; at 80 % the inside of the
  cover lies on the bottom screen over the right page while the left page is revealed.
  Mode 2: same, turned the other way. Mode 3: cover on the right half swings around the centre
  line, revealing the spread. Mode 4: the cover leaves the top screen's bottom edge and enters
  the bottom screen's top edge in the same frame (MainActivity kept until done, then the top
  screen released as before). Blue Period (RTL, Mode 1): the cover goes to the bottom screen and
  swings up. Close in all modes: pages give way to the cover, it turns back and lands in its
  row (Mode 4: MainActivity relaunched within ~30 ms, BottomActivity kept until done).
  Real speed: every open / close 0.81 s, 46-49 frames (~58 fps), no hold needed (layouts
  cached); idle afterwards 0 frames and 0 CPU in 10 s. Recordings of all four modes made.
- Not checked on the device: the low-battery fade (charging) and the "Opening…" hold with a
  slow first page (layouts were cached). Known: the library row's page/progress line updates
  as soon as the library reappears (under the dimmed backdrop), not strictly after landing.

### Step 24: refinements (still v0.3.0, not published yet; a bookshelf tab is postponed to v0.4)
1. Theme colour: one accent token. `Accent` becomes a getter reading `Settings.accentColor`
   (pref `accent_color`, default orange 0xFFE0A050), so every use follows the setting at once;
   `OnAccent` = black or white text, whichever contrasts more with the accent (WCAG luminance).
   Picker like the bookmark one: 8 presets (orange, red, gold, green, teal, blue, purple, pink)
   + Custom hue slider + live preview (chip, toggle, button). Bookmark colour, the green
   "completed" count, the gold star and the warning colour stay fixed.
2. Settings order: Theme colour, Reading, Controls, Animations, Bookmark, Library, Hidden items,
   Book info, About. Book info is collapsed to "Book info · key saved ✓" when a ComicVine key is
   saved and tested OK (pref `comicvine_key_ok`), expanded otherwise.
3. Animations (Settings): Page turn (style + speed + button turn corner), Open & close book
   (on/off + speed 500/800/1200 ms), Screen swap (comics) (on/off + speed 180/300/500 ms; off =
   instant), Edge shadow at first/last page (on/off), Simpler animation on low battery. Speed
   = a segmented "Fast · Normal · Slow" row with the ms under the selected one.
4. Reader menu, in this order: Return / Close; cover, title, author, progress, time + battery;
   bookmark + colour; the four modes as one row of equal compact icon buttons (Book / Reversed /
   Top / Bottom); navigation (slider; −10 −1 Go to page, Chapters +1 +10); Reading (text size,
   same-size and per-screen sizes in book modes, margins, page theme); collapsible "More"
   (reading direction + Swap L1/R1 for RTL books, split wide pages, swap screens, header & page
   number, page turn style, hold-to-menu time, Select button changes mode, show tutorial again).
   Settings shared with Settings use the same composables (`ui/SettingRows.kt`): same names,
   controls and relative order.
5. Inspect book (library row's mode-button popup → "Inspect book"): the cover lifts out of its
   row and becomes a 3D book on the top screen (library hidden; a round X where the gear is).
   - Model: a box drawn with Android Canvas: each visible face is a texture mapped onto its
     projected quad with Matrix.setPolyToPoly (exact perspective for a flat face); back faces
     culled (a box is convex, so no sorting). Faces: front = cover; back = the file's own back
     cover (EPUB manifest image whose id/href/properties say "back"; comics: ComicInfo page
     Type="BackCover", or an image named like "back"), else generated (cover colours, title,
     author, description); spine = generated (cover colours, title + author vertical); top /
     bottom / fore-edge = paper tone with fine page lines. Warm studio light: per-face
     brightness from a fixed warm key light + fill, a soft floor shadow, background a warm
     radial gradient (darker in Night).
   - Size: cover proportions from the cover image; thickness = (source page count × 0.1 mm +
     1 mm covers) relative to an assumed height (180 mm books / manga, 260 mm comics), so a
     22-page comic is thin and Dune thick. Source page count = `Book.pageCount` (comics: images,
     PDF: pages, EPUB: MuPDF pages at the reference size), never the reader's layout count.
   - Input: one-finger drag = trackball rotation with inertia; pinch = zoom; double-tap = front
     view. D-pad rotates 15°, A = front view, B / X = close. Idle spin after 3 s, stops on touch
     and after 20 s. Frames only while dragging, coasting, animating or idle-spinning.
   - Bottom screen: taken over like the keyboard (Presentation, Mode 3) with title, author,
     series + number, type, genres, year, pages, progress and the full description (scrolls),
     plus "Read" (opens with the normal open animation, starting from the 3D book's front).
   - Close: the book turns to the front and shrinks back into its row; bottom screen released.
- Found on the Thor: EPUB page counts come from MuPDF's small reference page (196 × 227 pt),
  so Dune's "1794 source pages" made a brick. Books now convert: printed pages = reference
  pages / 3.4 (a paperback text block is ~3.4× the reference area) -> Dune ≈ 527 pages,
  thickness 0.30 of its height; the details say "≈ 527 printed pages". Comics / PDFs unchanged.
- Existing ComicVine keys (saved before step 24) are tested once, quietly, when Settings opens;
  if OK, Book info folds to "Book info · key saved ✓".
- Checked on the Thor: Settings in the new order (screenshots); blue theme colour applied at
  once everywhere (library switch, chips, menu, Read button), black text chosen on blue, green
  count / gold star unchanged; back to orange. Reader menu in Mode 1 fits without scrolling
  (More folded); More uses the same rows as Settings. Inspect Dune: front view, spine with
  title + author, generated back cover with the description and a barcode, page block with
  lines, warm light and floor shadow, fling coasts, double-tap = front, idle spin, X shrinks it
  back. Spider-Man: 22 pages = thin; its own back cover found (last page, same shape, edge
  density 0.077 < 0.09: the 1976 back-cover ad); d-pad 12 × 15° = back view; Read opens it with
  the open animation from the 3D book (held 400 ms on the cover while the CBR unpacked); B
  closes. Bottom screen details + Read, released on close. Pinch zoom can't be simulated with
  adb (single touch only) - not verified on the device.

### Open & close style: Hinge / Classic (still v0.3.0)
(There was no earlier "slow choreographed open" in the code to replace; this adds Hinge as a new
style next to the existing motion, now called Classic.)
- Settings → Animations → Open & close book → Style: Hinge (default) / Classic; Fast / Normal /
  Slow apply to both (Hinge 0.9 / 1.3 / 1.8 s, Classic 0.5 / 0.8 / 1.2 s). Settings only.
- Hinge (Modes 1 and 2; Modes 3 / 4 always use Classic): glide upright to the top screen's
  centre (0-22 %), turn sideways there for the mode (22-44 %, at 65 % of the final size), travel
  down growing so the spine lands on the hinge (44-78 %), and swing open from 71.2 % (80 % into
  the travel) to the end - the swing pivots on the cover's current spine edge, so travel and
  opening overlap as one motion. Reveal 77-90 %. Opening waits for the first pages at 71.2 %
  (cover closed). Closing is the exact reverse. RTL: the cover ends on the bottom screen with
  its spine on the hinge and swings up (as in Classic). Inspect → Read uses the chosen style from
  the 3D book. Skip (tap / A / B) at any point. `BookAnimMath.hingeBoxAt`, `swingDegFor`,
  `revealFor`, `spineOf`; tests in BookAnimTest.
- Checked on the Thor (frozen frames, Mode 1 and Mode 2): upright at the centre at 22 %, turned
  and travelling with the spine toward the hinge at 60 %, swinging open on the hinge at 80 %;
  Mode 2 turns the other way; closing at 30 % = cover shut, lifting off the hinge. Recordings:
  Hinge Normal / Slow and Classic Normal, open + close, Modes 1 and 2. Frame rates measured
  that evening are NOT representative: the Thor was at 1 % battery and throttling (even a page
  curl, 60 fps earlier the same day, showed p95 117 ms).

### Library grid view and the top-right pill (still v0.3.0)
- Top right: one pill (house style) with three buttons split by hairlines: view toggle | + | gear,
  each 52 x 48 dp, theme-colour highlight while pressed, d-pad left/right moves inside it. The
  toggle shows the view you'll switch TO (grid icon in list view, list icon in grid view). Inspect
  covers it with its round X, as before. `LibraryPill` in LibraryGrid.kt.
- Grid view (`BookGrid`, shared `FillGrid` in FillList.kt): covers only, own aspect ratio, 8 dp
  rounded corners, soft shadow, placeholder cover when none; 7 columns on the top screen
  (`gridColumns`, cells >= 104 dp, gaps = the list's 8 dp token); fills the height, lazy, smooth.
  Tap / A = Inspect from that cover; hold ~0.5 s / X = the book's popup (same as the list's mode
  button). Every sort, groups (names list, then a grid inside), Completed and search results.
  Remembered (`library_grid`). Thumbnails decoded at cell size on a background thread, 16 MB
  memory cache, only for covers on screen; nothing runs while idle. Tests: GridTest.
- Checked on the Thor: list view with the pill (grid icon), grid view (7 columns, 5 covers, list
  icon in the pill), tapping a cover into Inspect (pill replaced by the X).

## v0.3 PAUSED HERE (2026-11-24)
Everything below is committed and pushed on main (last commit: "v0.3.0: library grid view and the
top-right pill"). versionName 0.3.0, versionCode 4, not published, no 0.3.0 tag. The Thor was at
1 % battery during the last tests, so nothing has been judged for smoothness yet.

Built and checked once on the Thor, still needing a proper test pass (on a charged Thor):
1. Curl tweaks (step 22): corner curl from buttons and taps (Bottom / Top corner), the sheet crossing
   the hinge in Modes 1 / 2, comic page backs in the page's own paper colour (dimmed in Night).
2. Open & close style Hinge / Classic: watch the recordings (Hinge Normal / Slow, Classic Normal,
   Modes 1 and 2), check the motion feels continuous with no stops, RTL books, skip at any point,
   Inspect -> Read, Modes 3 / 4 falling back to Classic, frame rates at full battery.
3. Step 24: theme colour (presets and any hue), Settings order, Animations section, reader menu
   declutter, Inspect book (3D spin, zoom, inertia, back cover, thickness, bottom-screen details).
4. Grid view: not yet tried on the Thor - hold a cover / X for the popup, d-pad left/right inside
   the pill, grids inside Author / Series / Genre / Type groups, Completed, search results, the
   cover lifting out of the grid into Inspect (confirm in a recording), the grey cover seen for a
   split second in Inspect of Blue Period Vol. 2 (should load), scrolling with a bigger library.
   The Thor was left in grid view.

Left before 0.3 is finished: fix whatever the test pass finds, then build the final signed
ThorReader-0.3.0.apk and publish (tag only when asked).

### v0.3.0 fixes: Inspect tap to read; the book popup always fits (main)
- Inspect: one tap ON the 3D book opens it at once (no double-tap wait), with the chosen open
  animation starting from where the front cover is right now. A tap = moved less than the
  touch slop and lifted within 300 ms; anything else is a spin as before. Double-tap reset is
  gone: reset is a small round ↺ button next to Read on the bottom screen, and Y. Buttons:
  A = Read, B = close, Y = reset, d-pad turns. Hint: "Tap the book to read · Drag to spin ·
  Pinch to zoom · ↺ or Y resets · B or ✕ closes".
- Book popup (row mode button and grid long-press): never taller than the screen (max height =
  screen minus margins); its items scroll (touch and d-pad - the focused item is scrolled into
  view); "Remove from library" (and its confirmation) is pinned below the scrolling part. The
  "Manga (right-to-left)" toggle is gone from the popup (manga detection stays automatic, the
  direction stays in the reader menu's More). Placement: beside the anchor, shifted up / kept
  inside the screen with a margin, so the last row and the last grid row open fully on screen.
- Checked on the Thor (main debug build): popup on the last list row opens left of the
  button, shifted up, fully on screen; its middle scrolls ("Mark as completed" scrolled into
  view) with "Remove from library" and its confirmation pinned; same on the last grid cover. A
  throwaway test book was added, inspected (tap on the backdrop: nothing; drag: spin; one tap
  on the book: "Read from Inspect" -> hinge open at once), closed and removed again; the
  library order and list view were left as they were. The popup background was made almost
  opaque (bright covers showed through behind the items in the grid).

### v0.3.0 fix: no low-resolution page after a turn lands (main)
- Bug: after a page turn, the new page looked pixelated for a moment, then switched to full
  quality (books and comics, book and single-screen modes). Cause: the curl drew the page being
  turned to from a texture made when the turn started - for comics / PDF at half size, sampled
  without smoothing - so the last frames before landing were blocky; the at-rest page view then
  replaced it.
- Now: the NEW (landing) page is a full-quality texture for the whole curl
  (`PageTurnMath.textureScales`: old page 0.5 for comics / PDF, new page always 1); only the old
  page, moving away, stays lighter, now with linear filtering. The turn's textures are built as
  soon as the turn is composed (`TurnRegion.prepareCurl` in `buildRegions`), never at landing;
  pre-rendered pages are handed to the GPU early (`Bitmap.prepareToDraw` in the prefetch).
- Landing (`ReaderSession.land`): the page views take over only when every screen showing the
  turn has its full-quality landing page ready (the overlay reports `landingTextureReady`) and the
  view's pages are rendered; both screens switch in one state change (same frame, step 21). If not
  ready, the final animation frame (already the full-quality page) is held, at most 1.5 s. A new
  turn during the hold still finishes it instantly. Textures are released when the overlay leaves
  (after the commit), as before (step 20).
- Pixel-exact hand-over: the page view and the turn pictures place the page with the same
  `PageTurnMath.fitRect` (whole pixels) and both use bilinear filtering.
- Log per turn: "Turn landing view N (curl): TOP full-res ready, BOTTOM full-res ready at
  animation end; committed X ms, shown Y ms after animation end" (a warning if a screen was NOT
  ready). Tests: LandingTest (116 tests in total).
- Checked on the Thor (debug build, slowed turns, screen captures every ~80 ms around landing,
  compared pixel by pixel with the page at rest): Spider-Man Mode 1 top screen and Dune Mode 1
  both screens - the last animation frames differ from the at-rest page only by shader rounding
  (max 2 of 255 in under 1 % of pixels), identical right after the commit; no low-resolution
  frame. Per-turn logs at normal speed (normal, rapid repeated and back turns): Dune with Curl,
  Flip, Slide and separate font sizes (8 / 10 pt), Chainsaw Man right-to-left - every landing
  ready on both screens, commit within 1 ms, shown on the next frame. Not covered: holding a
  physical button for the fast slide (adb can't send real key repeats) - try it by hand.
- Note: a button / tap curl now takes ~40-100 ms to show its first frame (was ~20-30 ms) because
  the full-quality textures are built up front.

### v0.3.0 fix: curl no longer switches itself off (main)
- Bug (from the fix above): after a few curl turns the app used Slide instead, for the rest of
  the session, although the setting said Curl. Cause: the "curl too slow on this device" check
  (3 curl turns in a row with frame interval p95 > 34 ms) counted the first frame of each turn,
  which now builds the full-quality textures (20-100 ms). Comics hit it within 3 turns
  (Chainsaw Man: p95 65 / 37 / 36 ms, every later frame 16.7 ms).
- Now: a button / tap turn's first `SETUP_FRAMES` (2) frame intervals are left out of the check
  (`PageTurnMath.curlTurnSlow`); really slow frames while the page moves still count. The turn
  log adds "setup A+B ms, p95 after setup X ms". Tests: 117 in total.
- Checked on the Thor (debug): Chainsaw Man, Spider-Man and Dune, normal, rapid and back turns
  after a fresh start - every turn a curl, setup 20-100 ms, p95 after setup 16.8-16.9 ms, curl
  never switched off.

### v0.3.0 fix: Flip swipes from a corner start as readily as from the middle (main)
- Bug: in Flip (book modes, big screen) a swipe starting near the top or bottom of the page's
  outer edge needed a long stroke before the page moved; from the middle it was immediate.
  Cause: every style used the curl's drag geometry - a touch in the top / bottom third grabs
  that corner, the fold tilts, and the turn's progress is read where the fold crosses the
  page's middle, which barely moves at first.
- Now (`PageTurnMath.dragFold`): only the curl peels the grabbed corner. Flip / Slide / Fade
  use a straight grab at the touch point, so every start along the edge responds like the
  middle (finger drift sideways ignored). The curl is unchanged. Tests: DragStartTest (119).
- Checked by finger on the Thor (Flip, book mode, big screen): corner swipes now feel like the
  middle one. (adb's simulated strokes can't test this: each step is a separate command, so the
  reader menu's press-and-hold fires instead.)

### v0.3.0: page theme back at the top of the reader menu (main)
- Paper / Sepia / Night are three round swatches at the end of the bookmark row
  (`PageThemeSwatches` in ReaderMenu.kt), styled like the bookmark colour swatch: each shows its
  paper colour with "Aa" in its text colour, the current one with the white ring. Books only
  (comics / PDF keep their own colours). The "Page theme" row left the Reading section; Settings
  keeps its own row.
- In books "Go to bookmark (page N)" moves to the line under the bookmark row (no room next to
  the swatches); comics keep it in the row.
- Checked on the Thor (debug, Dune Mode 1): swatches at the row's end, tapping Sepia switches
  the page at once and moves the ring; "Go to bookmark" below with a bookmark elsewhere.

## Where we left off (2026-11-25)

### Branches
- main: Thor Reader v0.3.0 (versionCode 4), NOT published yet, no 0.3.0 tag. Last commit:
  "v0.3.0: Flip swipes from a corner start as readily as from the middle".
- bookloader: Thor Reader 0.3.0-bookloader (import API, share screen) + Thor Bookloader 0.1.0
  (new :bookloader module). Pushed, not merged, not published. Its own PLAN.md has the step 25
  sections (25 to 25e).
- The Thor currently has main's Thor Reader 0.3.0 debug installed, plus Thor Bookloader 0.1.0
  debug. Bookloader's "Import to Thor Reader" only works with the branch build of Thor Reader.

### Done in v0.3 (main)
- Page-turn animations (curl, flip, slide, fade), corner curl from buttons, comic page backs.
- Book open / close animation, styles Hinge (default) / Classic, speeds.
- Theme colour, Settings reorganised, Animations section, reader menu declutter.
- Inspect book (3D) - now with tap to read, ↺ / Y reset, A = Read.
- Library grid view and the view | + | gear pill.
- Book popup always fits the screen (scrolls, Remove pinned, no manga toggle).
- Low-battery simple animations.
- Fix: no low-resolution page after a turn lands (full-quality landing texture, synchronized
  hand-over, pixel-exact placement, per-turn landing log).
- Fix: curl no longer switches itself off (texture setup frames not counted as slow).
- Reader menu: Paper / Sepia / Night swatches next to the bookmark.
- Fix: Flip (and Slide / Fade) swipes from near a corner respond like from the middle.

### Built, not yet fully tested on the Thor (test list before releasing 0.3)
Checked on the Thor already: popup on the last list row and last grid cover (scroll, pinned
Remove, removal), Inspect tap to read from the grid, Hinge frozen frames in Modes 1 / 2, grid
view layout. Still to test, on a CHARGED Thor (the earlier runs were at 1 % battery, so
smoothness was never judged):
1. Page turns: curl from buttons (Bottom / Top corner), the sheet crossing the hinge in Modes
   1 / 2, comic page backs (Night dimmed), swipe follow / spring back, Flip / Slide / Fade,
   right-to-left manga.
2. Open / close: Hinge and Classic at Fast / Normal / Slow, Modes 1-4, right-to-left books,
   skipping (tap / A / B), smooth with no stops.
3. Step 24: theme colour (presets + any hue), Settings order, Animations section, reader menu
   (modes row, More), Inspect (spin, inertia, pinch, idle spin, back cover, thickness, bottom
   panel, ↺, Y, B, tap-to-read from the popup as well as the grid).
4. Grid view: d-pad inside the pill, grids inside Author / Series / Genre / Type groups,
   Completed, search results, a bigger library scrolling, the long-press popup with X.
5. Low battery (below 15 %): fades instead of curls and hinge.

### Next
1. Run the test list above; fix what it finds.
2. Release 0.3.0: final signed ThorReader-0.3.0.apk, tag 0.3.0 (only when asked), publish.
3. v0.4: the Bookshelf (see "3. Bookshelf" in the plan at the top - a visual shelf with each
   book as a generated spine). NOTE: there is no step23.md in the folder; the bookshelf spec
   still has to be written (step23.md, or the next step file).
4. Bookloader branch: keep testing (MangaDex volumes, Gutenberg mirrors, Pepper&Carrot, the
   bottom-screen keyboard, music); decide later whether / how to merge it into main.

### Exact commands (PowerShell, in C:\Users\Dylan\thor-reader)
Thor Reader (main):
    git switch main
    .\gradlew assembleDebug
    adb install -r app\build\outputs\apk\debug\ThorReader-0.3.0-debug.apk
    adb shell am start -n dev.thorreader.app/.MainActivity
    .\gradlew assembleRelease      -> app\build\outputs\apk\release\ThorReader-0.3.0.apk
    .\gradlew testDebugUnitTest    (119 tests)
Bookloader branch (both apps):
    git switch bookloader
    .\gradlew assembleDebug
    adb install -r app\build\outputs\apk\debug\ThorReader-0.3.0-bookloader-debug.apk
    adb install -r bookloader\build\outputs\apk\debug\ThorBookloader-0.1.0-debug.apk
    adb shell am start -n dev.thorreader.bookloader/.MainActivity
    (release: .\gradlew assembleRelease -> ThorReader-0.3.0-bookloader.apk and
     bookloader\build\outputs\apk\release\ThorBookloader-0.1.0.apk; tests: 121 + 18)
Install Thor Reader before Bookloader is no longer required (both declare the permission).
Never use gradle connectedAndroidTest on the Thor: it uninstalls the app and the library.

### Step files (instructions from the user; never committed)
Done: step3 (empty file), step10, step11, step12, step13, step14, step16, step17, step18,
step19, step20, step21 (all v0.1 / v0.2), step22 and step24 (v0.3, built; test list above).
Step 25 (Thor Bookloader): built on the bookloader branch, partly tested on the Thor.
Not in the folder: step1, 2, 4-9, 15 and 23 (the bookshelf spec still has to be written).
