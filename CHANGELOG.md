# Changelog

## 0.3 (Thor Reader 0.3.0)

### Library
- **Three ways to see your library**: List, Grid and the new **Cover flow** - a row of books
  standing turned so you see each spine and its cover at an angle. The view button in the top
  pill steps between them.
- **Series collections for manga**: volumes and chapters are recognised from file names; a
  series with 2+ books becomes one stacked tile that opens its collection - cover flow or list,
  with the volumes/chapters you have, the gaps you're missing, and "Ongoing" or "N Completed".
- **Series collections for books**: series lists come from Wikidata (Dune 1-6, Harry Potter
  1-7...), books without series info are matched by title, and the collection shows the books
  you have and the missing ones by title.
- Grid covers show a small icon of the mode each book opens in (Settings -> Library to hide).
- Inside a Series group, a series' books are shown one by one.
- Closing a book lands its cover in its new place in the library.

### Inspect (3D book)
- Tap a cover to lift it into a 3D book you can spin and zoom; tap it to read.
- Spine and back cover in the cover's own colour, with title and author centred on the spine.
- For a book or manga in a series, the series list is on the top screen and the 3D book on the
  bottom one.
- Read from Inspect: the cover lifts straight from the 3D book - at its angle, and from the
  bottom screen when the series list is on top - and swings open, with no flash in between.
- Edit info: choose your own front cover, spine and back cover pictures.

### Reading
- **Page-turn animations**: a realistic paper curl, Flip, Slide, Fade or None, at three speeds.
  In the book modes one sheet turns across the hinge from screen to screen; swipes follow your
  finger.
- **Open / close animation**: the cover lifts out of the library and swings open across the
  screens (styles Hinge and Classic, three speeds). Smoother start in 0.3.
- Paper / Sepia / Night swatches next to the bookmark in the reader menu.
- Night keeps a book's front cover in its own colours.
- The mode badge now appears in the top-left corner, and can be turned off (Settings ->
  General -> "Mode badge when reading starts").
- Books with conversion damage (a "?" inside hyphenated words, e.g. "fief-?complete") are
  read from a repaired copy; your file is never changed.
- Simpler animations below 15 % battery.

### Online (Settings -> Online)
- **Reading progress sync** with CrossPoint (Xteink X4) and KOReader (Kobo, Kindle, PocketBook,
  Boox, Android), or your own sync server. Checked against CrossPoint's current firmware: same
  book fingerprint, positions and percentage; a book is also found when the other device matches
  books by file name instead of file contents (and the other way round). Each book's title,
  author and file name go with its position, so CrossPoint's sync site shows the book instead
  of "Untitled book" ("Send book title and author" to turn this off).
- **OPDS catalogs**: browse, search and download books into your library (Project Gutenberg,
  Internet Archive, Calibre-Web, Kavita, Komga...).
- Online lookups only happen with "Look up book info online" on, and only the series name or
  book title is sent.

### Screens, safety and efficiency
- **Burn-in protection** for the OLED screens: after no input they dim, then go black
  (Settings -> General -> Screen); content shifts by a pixel or two now and then, only as a
  page turns.
- Uses no CPU and draws nothing while idle, in every screen; nothing runs in the background.
- Gaming on the other screen (Modes 3 and 4) is unaffected.
- HTTPS only; secrets kept in the Android Keystore; downloaded and imported files checked
  against oversized and malicious content.
- Settings reorganised into tabs: General, Animations, Library, Online.

## Earlier releases
- 0.2 and 0.1: see their notes on the GitHub Releases page.
