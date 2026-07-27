# PixPin — Exhaustive Feature & Functionality Report

**Product:** PixPin (name from "Pixel Pin")
**Developer:** DepthPixel
**Category:** Screenshot / Screen Pinning / Screen Recording / OCR / Annotation utility
**Platforms:** Windows and macOS (this report focuses on the Windows app, with macOS notes where relevant)
**Official site:** https://pixpin.com/
**Official documentation:** https://pixpin.com/docs/ (full manual used as source)
**Feedback:** feedback@pixpin.com · Business: community@pixpin.com
**License model:** Free core app; a subset of features requires a paid **Membership** (called "Pro" in some changelogs).

---

## 1. Executive Summary

PixPin is an all-in-one capture-and-reference utility whose philosophy is "Capture anything. Pin everything." Its two foundational actions are **Screenshot** and **Pin** (turning any capture — or any clipboard content — into a floating, always-on-top "sticky note"). Around these, PixPin bundles one of the broadest feature sets in the screenshot-tool category:

- **Capture:** static region/window/UI-element screenshot, full-screen, custom/delayed, rounded-corner, cursor capture, pixel-level fine adjustment, color picker/magnifier, screenshot & area history, QR-code recognition, scrolling (long) screenshots, GIF/WebP/MP4 recording, full video screen recording.
- **Pinning (6 pin types):** Image Pin, **Text Pin** (plain + HTML/rich), File Pin, Color Pin, LaTeX Formula Pin, Window Pin (live-projected, Windows) — plus **Pin Groups** for organizing sets of pins, batch operations, history, and mouse-penetration.
- **Annotation (11 tools):** Rectangle/Ellipse, Line/Polyline, Arrow, Serial Number, Pencil, Highlighter, Mosaic/Blur/Smart Eraser, Text, Eraser, Spotlight, Watermark — with undo/redo, color/brush control, re-editing, rotation, sectors/arcs.
- **Recognition & intelligence:** fully-local OCR, multi-language OCR, table→Excel, LaTeX formula recognition, QR recognition, AI-powered translation, Read Aloud.
- **Workflow extras:** Global Mouse gestures (membership), Desktop Floating Icon (drag-to-pin), Cloud Config Sync, a JavaScript automation/scripting engine with a full `pixpin` API, deep customization (themes, toolbars, shortcuts, mouse actions), and an extensive changelog showing very active development (latest builds: stable v3.3.5.7, beta v3.4.2.2, dated mid-2026).

The official manual is organized into: **Start** (What is PixPin / Quick Start / Membership Features / FAQ), **Capture** (Static, Scrolling, GIF), **Pin** (Base Use / Image / Text / File / Color / LaTeX / Pin Group), **Annotate** (Base + 11 tools), **Config** (8+ panels), **Other Features** (Formula, Translate), and **Changelog / Beta Changelog**. This report covers every documented page.

---

## 2. Installation & First Run (Windows)

- Download from https://pixpin.com/download/ — **Installer (.exe)** and **Portable (.zip)** builds for Windows (and a universal `.dmg` for macOS).
- On first run, Windows may auto-hide the tray icon; the docs recommend dragging the PixPin icon into the visible tray area.
- Core operation uses two hotkeys by default:
  - **Screenshot:** `Ctrl + 1`
  - **Pin:** `Ctrl + 2`
- All global and local shortcuts are remappable in **Config → Shortcut/Action**.
- **Windows admin apps:** when an admin-privilege program is foreground, global shortcuts won't fire; enable **Config → System → Start at login + Run as administrator** to fix.
- **Tray icon trigger:** clicking the tray icon screenshots (changeable to middle-click/double-click, or off, in **Config → System → Taskbar Tray**).

---

## 3. Capture Functions

### 3.1 Triggering a Screenshot
- **Shortcut** (default `Ctrl + 1`); remappable. Overly-simple shortcuts (single letters) blocked unless **Allow simple shortcuts** is enabled in Config → System.
- **Tray menu:** right-click (Windows) tray icon → Screenshot.
- **Custom area / delay:** tray menu or `Alt + 1` opens the custom screenshot UI (set area + delay; "Preset" saves common areas/delays).
- **Tray icon click** (Windows) — configurable behavior.
- **Command line (Windows):** `PixPin.exe -r "pixpin.screenShotAndEdit()"` (PixPin must already be running).

### 3.2 Static Screenshot — Selection & Operations
- **UI Element / Window auto-detection:** moving the mouse over a UI element/window auto-selects its bounds; click to capture or double-click to capture to clipboard. Mouse wheel switches parent/child elements. Toggle in Config → Screenshot → UI Detection (Detect Elements / Window Only / Off). *Note: macOS supports window-level only, not element detection.*
- **OCR text recognition:** select text in the screenshot UI, press `Shift + C` to OCR and copy to clipboard (local).
- **QR code recognition:** auto-detected when present; click the QR button to view/copy content; if URL, open in browser.
- **Color Picker + Magnifier:** the screenshot magnifier shows the pixel under the cursor and its color; `C` copies the value (RGB default), `Shift` toggles to Hex (also HSV/HSL configurable in Config → Screenshot → Magnifier Color Format).
- **Pixel-level fine adjustment:** arrow keys move the selection 1px; `Shift+Arrow` shrinks a side 1px; `Ctrl+Arrow` expands a side 1px; WASD nudges the cursor while holding an edge (magnifier-assisted).
- **Rounded-corner screenshot:** toolbar toggle + radius slider (transparency caveat: paste into clipboard-poor apps may show black corners; save as file instead).
- **Add shadow / border:** optional drop-shadow or border on the captured/pinned/saved image (preview on hover).
- **Manual range input:** type exact width/height in the toolbar.
- **Fixed aspect ratio:** hold `Shift` while sizing for 1:1, or lock ratio via the toolbar button.
- **Capture mouse cursor:** press `` ` `` (key below Esc) to show/hide cursor; or Config → Screenshot → Show Cursor.
- **Screenshot history:** PixPin keeps the last N (default 10) screenshots — load with `<` / `>` in the screenshot UI. Config → Screenshot sets the count (0 disables).
- **Screenshot-area history:** last N (default 10) selection rects — load with `R` / `Shift + R`. Config-settable.

### 3.3 Post-Screenshot Operations (toolbar)
- **Pin:** `Ctrl + T` / `Ctrl + 2` / Pin icon → pins to screen (drag to move, wheel to zoom, right-click for delete/lock/etc.).
- **Save to file:** `Ctrl + S` / Save icon → local file. Formats: **PNG, JPG, BMP, WebP** (and **AVIF** + **PDF** added in v3.0; see §8).
- **Copy to clipboard:** `Ctrl + C` / `Enter` / Copy icon / double-click → paste elsewhere with `Ctrl + V`.

### 3.4 Scrolling / Long Screenshot (Stitching)
- Intelligent stitching algorithm for content longer than the screen (chats, long tables, articles, code).
- **Usage:** screenshot mode → select area → Scrolling Screenshot icon → scroll (wheel/scrollbar); auto-stitched into one long image.
- **Interface controls:** capture-area box (red=capturing, blue=idle/adjustable); live stitched-size readout; move button; direction switch (vertical default ↔ horizontal); start/stop; close; pin; save; copy; thumbnail preview; position indicator (green=matched, red=mismatch → scroll back & re-match).
- **Auto-scroll** (v2.3.8): button auto-scrolls; v2.4.9 made speed auto-adjust by length.
- **Overflow mode** (v3.2): supports images up to ~2 million pixels long.
- **Tips:** smooth, not-too-fast scrolling; complex static content (avoid solid colors/heavy repetition/GIFs/videos); exclude scrollbars; keep title-bar proportion small.

### 3.5 GIF / WebP / MP4 Capture (Motion)
- After selecting a region, the GIF icon starts motion capture. Save formats:
  - **GIF** — max compatibility, older, larger.
  - **WebP** — better compression/quality; most browsers support it.
  - **MP4** — video format, small + high quality (e.g., send via WeChat which lacks WebP support).
- **Recording interface:** timer (hover to set delay), FPS selector, record, pause, (member) record key-press + mouse-click toggles, annotation toolbar live during recording, move, close, re-record, area box.
- **Playback interface:** scrub to time, (member) clip endpoints to trim, play/pause, speed multiplier, mouse-settings (show mouse; member: choose left/right/scroll actions), keyboard-settings (member: show/choose category), progress-bar overlay, format picker, save/quick-save/copy/move/re-record.
- **Shortcut:** during GIF recording, the screenshot shortcut (`Ctrl + 1`) starts/stops.

### 3.6 Screen / Video Recording (full)
- Full screen-recording (MP4-style video), not just GIFs.
- **Mouse/keyboard recording** (membership): overlay click & keypress indicators for tutorials.
- **30 FPS option** (v3.2) plus higher rates; v3.3.1 fixed frame-rate correctness per quality level.
- **PiP camera recording** (Pro, v3.2): webcam picture-in-picture during screen recording.
- **Edit after recording** (membership): trim recorded GIF/video.
- **Moving-window recording** improved (thinner border so it doesn't appear in output).
- **Quick Recording** defaults to last saved path.
- **Post-record preview shortcuts** (v3.0): `Space` pause, `←`/`→` frame step, `Home`/`End` jump to first/last frame.

---

## 4. Pinning System — the Signature Feature

Pin = a floating, always-on-top "smart sticky note." Triggered by `Ctrl + 2` on any clipboard content, or the Pin button during screenshot. **Six pin types** plus groups and batch management.

### 4.1 Image Pin
- **Sources:** pin directly from screenshot toolbar; paste an image from clipboard (`Ctrl + 2`); or copy an **image file** to clipboard then `Ctrl + 2`. Formats: **PNG, JPG, BMP, WebP**.
- **Position:** follows screenshot location when from PixPin; centers otherwise (configurable: Config → Pin → Pin Display Position: Screenshot/Mouse/Main-screen-center/Mouse-screen-center).
- **Image processing (right-click / shortcuts):**
  - Rotate right `1` / left `2` (90°)
  - Flip horizontal `3` / vertical `4`
  - Grayscale `5`
  - Invert color `6`
  - Brightness +10% `7` / −10% `8`
  - Reset all `0`
- **OCR on Image Pin:** text auto-recognized locally; hover to select/copy, or drag selected text into other apps; `Shift + C` copies all. Hold `Alt` while dragging to move the pin without selecting. Auto-recognition toggle in Config → Pin.
- **Window Pin conversion** (v2.4.9): right-click "Convert to Image Pin" snapshots a live Window Pin into a static image pin.

### 4.2 Text Pin  ← *the type you asked about*
- **Trigger:** copy **any text** to the clipboard, then press `Ctrl + 2`. PixPin renders it as a floating text note.
- **Plain text** (from input boxes, Notepad, Sticky Notes) and **HTML / rich text** (from web pages, Word, WPS, VS Code) — formatting (font, color, size) is preserved where possible.
- **Unique menu items:**
  - **Ignore Format** — convert rich/HTML text to plain text.
  - **Text Selection Mode** — select a segment, copy it, or drag it into other software (hold `Alt` to move the pin without selecting).
- **Config (Config → Pin → Text Pin):** max width for plain text (auto-wrap), text padding, show-HTML-style toggle, adapt-to-screen-DPI, font/size/bold, text color, background color, "text selectable by default." 
- **Drag behavior:** holding `Ctrl` + left-drag on the pin drags the **text content** into other apps (e.g., editors). (WeChat caveat: release `Ctrl` before dragging into WeChat's box.)

### 4.3 Color Pin
- Any copied CSS-style color string pins as a colored swatch: `#29B8DB`, `rgb(41,184,219)`, `41, 184, 219`, `orange`.
- Right-click menu copies the value in multiple formats (HEX, RGB, etc.); `Ctrl`+drag drags the HEX code into other software.

### 4.4 File Pin
- Copy **one or multiple files**, press `Ctrl + 2` → pins a file tile (or several). Supported: any file; `Alt + ~` also pins a selected image file from Explorer.
- **Menu:** Open (default program / folder), Copy this/all files, Copy path of this/all files, Show in Explorer.
- `Ctrl`+drag drags all files to other software.
- "Pin Currently Selected File" action supports Q-Dir / QDir (v3.3+).

### 4.5 LaTeX Formula Pin (membership for recognition; pinning LaTeX text is free)
- Copy **LaTeX source code** to clipboard, press `Ctrl + 2` → renders the formula as a high-definition math image (not raw code).
- **If you only have a formula image** (unclear): recognize it via `Shift + F` (VIP) after copy, or right-click pin → Recognize → Formula Recognition (VIP), then re-pin the resulting source for a crisp formula.
- **Unique menu items:** Export as **SVG** (vector, zoom-safe); Copy as **MathML** (paste into MathML-aware web/CMS editors, accessible to screen readers); Copy as **LaTeX source code**; Open/Insert in **Word** as an editable Office math object.

### 4.6 Window Pin (Windows only, v2.3.8)
- When pinning, a **Window Pin** type dynamically projects a specified live window's content onto a Pinned Image — the pin updates as the source window changes. Can be converted to a static Image Pin.

### 4.7 Pin Window — Common Operations (all types)
- **Move:** drag, or arrow keys for fine move.
- **Zoom / Opacity:** mouse wheel zooms; `Ctrl + Mouse Wheel` adjusts opacity. Middle-click resets to original size/opacity; middle-click again restores last zoom/opacity.
- **Lock** (`L` / menu): prevents move/zoom/opacity; shadow turns orange when locked.
- **Annotate** (`Space` / menu): summon annotation toolbar on the pin; "Hide Annotation" available.
- **Shadow states:** blue (active), gray (inactive), orange (locked), green (mouse-penetration).
- **Always on Top** (`T` / menu): default on; toggle off.
- **Close:** `Ctrl + W` / `Esc` / double-click / menu. Closed pins go to history; reopen with `Ctrl + 3`.
- **Destroy** (`Ctrl + D` / menu): permanent; not recoverable.
- **Mouse Penetration:** lets clicks pass through the pin to underlying content; enabled via a configurable "Toggle Mouse Penetration" shortcut (no default to avoid accidents).
- **Drag content** (`Ctrl` + left-drag): drags Current Image (Image Pin), text (Text), file path (File), HEX (Color), rendered formula (LaTeX).
- **Color Picker on pin:** `Alt` opens magnifier + pixel color; `C` copies; `Shift` toggles format.
- **Thumbnail mode** (`R` / `Shift`+double-click): shows only part of the pin; right-drag selects region; `Shift`+left-drag moves content. Re-opening restores last thumbnail. Vertical scroll with `Shift + Mouse Wheel` (v3.3+).
- **Window Title:** `F2` / menu sets a title shown at pin bottom (position configurable).

### 4.8 Pin Groups & Batch Operations
- **Pin Groups:** create multiple named collections (with optional color labels; shadow color follows group color). Each group has **independent screen pins and history pins**; new pins belong to the active group; closed pins restore only within their group. Switch via tray menu or `Ctrl + ~` (hold modifier to pick another group). Move a pin between groups via right-click → Move to Pin Group. Manage (rename, recolor, reorder by drag, delete, clear) in tray menu → Manage Pin Groups. Counts shown as `screen/total`.
- **Batch (tray menu):** Adjust Pins to Ensure Visibility (re-window off-screen pins); Show All as Thumbnails; Cancel All Thumbnails; Save All As; Close All Pins; Hide/Show All Pins; Destroy All Pins.
- **Multi-select** (v2.4.9): hold `Ctrl` (Cmd on macOS) to multi-select pins for synchronized move/zoom/save; v3.2 added **Auto Align** for multi-select.
- **History:** default 10 history pins; `Ctrl + 3` restores last closed; count configurable (0 disables).

---

## 5. Annotation Tools (11 tools)

Available on screenshots and pinned images (`Space` on a pin, or menu). All support **undo/redo** (`Ctrl+Z` / `Ctrl+Y`), **color** (palette + custom picker; `Ctrl`+wheel for transparency; shared color across tools unless disabled in Config), **brush size** (mouse wheel), **re-adjust** (switch to the tool, click to move — disable in Config), and **delete** (`Delete` when selected). Tool shortcuts: Geometric `Shift+1`, Polyline `Shift+2`, Arrow `Shift+3`, Serial `Shift+4`, Pencil `Shift+5`, Highlighter `Shift+6`, Mosaic `Shift+7`, Text `Shift+8`, Eraser `Shift+9`.

1. **Rectangle / Ellipse:** solid/hollow, line types (solid/dashed/dotted); `Shift` for square/circle; rotation handle; rounded-corner control (Alt+wheel adjusts); ellipse → sector (inner handle) → arc (center handle).
2. **Line / Polyline:** click points to build a polyline (double/right-click to finish); `Shift` snaps to 45°; wheel adjusts thickness.
3. **Arrow:** straight / double-headed / hollow / solid; line types; `Shift` snaps to 45°; wheel thickness. (v3.2: arrow-text direction & background color; optional no-comment-on-double-click; triangle arrow style added v2.4.9.)
4. **Serial Number:** click to drop numbered/lettered/Roman markers; `+`/`-` adjust, `×` clear, `A` adds a comment; types 1-2-3 / A-B-C / I-II-III; wheel sizes; drag extends an arrow indicator. Great for step-by-step tutorials.
5. **Pencil:** freehand; wheel thickness; `Shift` draws straight line (snap angle configurable: free/45/30/15/10/5°); smooth stroke option; max-width cap; cursor style (cross+dot/cross/dot).
6. **Highlighter:** smear or box-select to highlight; blend modes Multiply (default) and semi-transparent (more visible on dark backgrounds).
7. **Mosaic / Blur / Smart Eraser:** hide sensitive info. **Mosaic** = pixel-block average; **Blur** = soft transition; **Smart Eraser** (membership) = auto-detects background texture for natural fill (ideal on solid/ID-card/WeChat-chat backgrounds). Brush or rectangle mode; intensity slider for Mosaic/Blur (Smart Eraser auto-fills). Auto-Mosaic privacy option (v3.2).
8. **Text:** click to place a text box; bold/italic/stroke (with stroke color)/font/size (wheel)/color/rotate/scale/delete; word-wrap modes (none/any/word-boundary).
9. **Eraser:** removes annotations; re-editing can be enabled (box mode). v3.0 added a "Clear All Annotations" button.
10. **Spotlight:** dims everything except a focused area (for emphasis).
11. **Watermark:** overlay text; v3.2 added centered-display option.

**Toolbar customization (Config → Customized Toolbar):** primary area (always visible), secondary area (on hover of the toggle), floating button (most-recently-used). Edit panel reorders, moves, or deletes buttons. Annotation button layout editable (Config → Annotation → Annotation Button Layout).

---

## 6. Recognition & Intelligence

### 6.1 Text Recognition (OCR)
- **Fully local** — images are **not uploaded** to the cloud (privacy). `Shift + C` copies selected text; redesigned editable results window (v3.0/3.3) with auto-close options (v3.4), resizable, faster, better skewed-text accuracy.
- **Languages:** Simplified Chinese, Traditional Chinese, English, Japanese (base, v3.0); membership adds Korean, French, German, Spanish, Portuguese (multi-language). v3.2 added a lightweight Simplified-Chinese model (faster, slightly less accurate); v2.4.9 enhanced Korean.
- **Punctuation handling:** convert full-width → half-width (handy for code).

### 6.2 Table Recognition (membership)
- Recognize tables in images → export to **Excel** (right-click pin, or `Shift + Q` during screenshot). v3.3 added preview/edit, copy-to-Excel, printing, and macOS AirDrop sharing.

### 6.3 LaTeX Formula Recognition (membership)
- Recognize math formulas in images as **LaTeX** (`Shift + F` during screenshot, or pin right-click → Recognize → Formula). Window supports import from file/clipboard, thumbnail list, editable LaTeX editor, live render preview, original-image compare, export as Image/PNG-JPG/SVG, copy LaTeX/Image/MathML, insert into Word, delete formulas, quick-copy via double-click/right-click. Auto-removes spaces (v3.3).

### 6.4 QR Code Recognition
- Auto-detected in screenshots/pins; Config → Screenshot → QR Code Detection Mode (Disabled / Manual button / Automatic). v3.2 added "recognize QR & copy result" preset and "Directly Show Content" mode; fixed copy issues.

### 6.5 Translation (membership; also usable via your own API key)
- **Image translation** (screenshots & image pins) and **text translation** (text pins). Trigger: `Ctrl + Q`, pin right-click → Translate, or screenshot toolbar Extension → Translate.
- **AI translation (v3.0):** integrated large language models for context-aware, multi-language translation (far better than the legacy image-to-image mode, which remains available).
- **Editable source + re-translate + specify source language** (v3.3 Pro). Default target language changeable in Config (e.g., to English).
- **Providers:** cloud-based; members use it directly. Non-members can supply their own **Youdao** or **Baidu** Translate API key (Config → System → Translation / Config → Translation Settings).
- **Read Aloud:** text/translation can be read aloud (fixed on macOS in v3.4).

---

## 7. Global Mouse, Floating Icon & Automation

### 7.1 Global Mouse (membership; Windows only)
Perform capture from inside any other app via modifier + drag:
- `Win` + Left-drag → Screenshot and Pin
- `Win` + Middle-drag → Screenshot and Copy Image
- `Win` + Right-drag → Screenshot and Copy Recognized Text
- (macOS `Option` maps to `Win`; default macOS shortcut unset in v3.4 to avoid conflicts.) All configurable; disabled when global shortcuts are disabled.

### 7.2 Desktop Floating Icon (v3.0)
- A draggable desktop orb. Click for quick actions; **drag images or text directly onto it to pin** (full mouse-only workflow). Right-click menu: hide-in-fullscreen, hide-during-screenshot (both default on), choose apps that hide the ball (v3.3+). v3.2: can retract halfway into the screen edge. Toggle via Config → System → Show Desktop Toolbar. *Note: drag-to-pin needs "Run as administrator" **unchecked** on Windows.*

### 7.3 Scripting / Automation Engine (JavaScript)
PixPin embeds a JS-syntax script engine. **Invoke:** Config → Shortcut/Action → Add New Action (shortcut or tray menu); or Windows command line `PixPin.exe -r "script"` / `-f "file"`.
**Key `pixpin` API:** screenshot (`screenShotAndEdit()`, `screenShot(ShotAction)`, `directScreenShot(rect, action)`, `longScreenShot(area)`, `gifScreenShot(area)`, `openCustomScreenShot()`, `setScreenShotRect(...)`, `genRect(...)`, `genRectUnderCursor(w,h)`, `getSpRect(type)` for screen-under-mouse/all-screens/window-under-mouse/last-shot); pin (`pinFromCilpBoard()`, `destoryAllPin()`, `saveAllPinImageTo(path)`, `restoreLastClosedPin()`, `trigMousePenetration()`, `switchPinGroup()`, `hideOrShowAllPin()`); system (`disableShortcuts(bool)`, `runSystem(cmd)`, `runSystemSync(cmd)` returning `{code,output,error}`, `openConfigurationWindow()`).
**`ShotAction` enum:** `Copy, Pin, LongShot, GifShot, CopyOcrText, Save, QuickSave, Translate, Close, OcrTable`.
**Preset actions:** Screenshot and Pin, Screenshot and Copy OCR, Screenshot and Save, Screenshot and Quick Save, Preset Area Screenshot, Scrolling Screenshot, GIF Capture, Pause GIF Capture. **Built-in actions:** Screenshot, Custom Screenshot, Screenshot and Copy, Pin, Restore Last Closed Pin, Close All Pin Windows, Hide/Show All Pin Windows, Enable/Disable Mouse Through. You can also set a **global exclusion/ignore list** so hotkeys are disabled in specified processes (v3.3).

---

## 8. Save, Export & Clipboard

- **Formats:** PNG, JPG, BMP, WebP, **AVIF** and **PDF** (v3.0; PDF page margins + pagination as Pro options in v3.2). PNG/WebP preserve transparency; JPG/BMP do not (relevant for rounded corners).
- **Quality:** 0–100 (invalid for lossless PNG).
- **Manual Save** (`Ctrl + S`): configurable default path with variables — `%title%` (target window title), `%process%` (process name, `!` strips exe suffix), `%os%`, `%computername%`, plus system env vars; `#` modifier truncates (e.g., `%title#10%`); save-by-year-month folders via `$yyyy$/$MM$`.
- **Quick Save** (`Ctrl + Shift + S`): direct save to preset path, no dialog; optional notification.
- **Auto Save:** auto-saves on successful screenshot (copy/pin/manual); trigger-type option (v3.2); **auto clean-up of old files** by age (v3.2).
- **Save and Copy to Clipboard:** optional.
- **Copy Image as File** (Config → System → Clipboard): copies a temp file path instead of raw image — fixes paste into clipboard-transparency-poor apps and enables paste-into-folder.
- **Copy Image Path** button in save dialog (v3.0). File-size estimate in redesigned save dialog (v3.0).

---

## 9. Configuration & Customization (exhaustive)

Deep config via tray → Config. Panels:

- **System:** UI language (English / Simplified Chinese); Optimize Screenshot Response Speed; Auto Start on Boot + Run as Administrator; Allow Simple Global Shortcuts; Clipboard (Copy Image as File + format); Translation provider (Youdao/Baidu API key); Taskbar Tray click behavior (Windows); Auto Check for Updates.
- **Appearance:** Theme Mode (Light/Dark/follow system); Theme Font; Theme Color (applied to shadows, toolbar borders, selection); Locked Window Color; Unselected-Area mask color; Shadow-during-Mouse-Through color; Tray Icon style (Auto/Icon/Custom color); Selection Border Width; Anchor Point Display (none/4-dir/8-dir); Toolbar Icon Size (v3.3); Save Dialog Style (legacy/modern); screenshot selection-switch animation toggle.
- **Screenshot:** UI Detection mode; Show Cursor (+ shortcut); Guide Lines (off/Alt/always) + types; Magnifier grid lines, shortcut tips, zoom level, grid color, color format; Close-confirm; Shortcut tips; QR Detection Mode; Screenshot history count; Area history count; Pin-and-Copy toggle; Scrolling default direction; GIF encoder dither type.
- **Pin:** Restore unclosed pins on startup; Lock Type (prevent move+close / move / close); Restore order; Display Position; Close-confirm (key & double-click); Show shadow; History-pin count; Default opacity; Zoom & Opacity step (normal & fine); Reduce-opacity-on-mouse-through; Mouse-wheel zoom mode (6 anchor options); Middle-click zoom mode; Resize-with-border; Duplicate behavior; Replace behavior (`Ctrl+V`/drag); Title position; **Image Pin** (copy displayed vs original, auto-detect text, smooth zoom, hide detected text in thumbnail, detected-text layout & punctuation); **Text Pin** (max width, padding, show HTML, adapt DPI, font/color/background, selectable by default).
- **Save:** Image quality; Save-and-copy; Manual save path + remember extension; Quick Save (path, notification); Auto Save (path, trigger, cleanup); variable/path rules.
- **Annotate:** Shared color across tools; Edit Color Palette (add/remove/reorder); Annotation re-editing mode (enable/disable/completely-disable); Quick Annotation (right-click auto-selects element range); Pencil cursor type & max width & Shift-drawing snap & smooth stroke; Serial Number (modify-following-on-delete); Text wrap mode; Eraser re-editing; Remember last tool (pin & screenshot); Annotation Button Layout.
- **Shortcut / Action:** remap all global shortcuts & custom actions; built-in + preset actions; script actions; shortcut-conflict detection; **Disable All Global Shortcuts** (also disables Global Mouse); **per-program exclusion/ignore list**.
- **Built-in Shortcut:** separate *local* shortcuts (only active when PixPin UI is focused) for Screenshot+Pin, Screenshot, Pin, Image Pin — covering close, copy, save, quick-save, detect-text, undo/redo, clear annotations, magnifier/guide, color copy/format, cursor move, annotation-mode switches, pin destroy/lock/paste/always-on-top/config/thumbnail, image rotate/flip/grayscale/invert/brightness/reset.
- **Mouse:** map mouse actions on pins — zoom, opacity, close, copy-content-and-close, destroy, reset (middle), thumbnail (Shift+double-click), copy content, copy text (Shift+right), menu (right), adjust position/size (left-drag), ignore-OCR adjust (`Alt`+left-drag), drag content (`Ctrl`+left-drag).
- **Global Mouse:** configure the `Win`+drag gestures (membership).
- **Customized Toolbar:** primary/secondary/floating button areas; edit/delete/reorder tools.
- **Translate Settings:** provider API key (Youdao/Baidu), image vs text translation services, legacy vs AI translation mode.
- **Account:** membership management; hide personal info (v3.2); **Cloud Config Sync** (membership) across devices.

---

## 10. Membership (Paid) vs Free

**Free for everyone:** static/region/window/element & full-screen screenshot, custom/delayed screenshot, color picker + magnifier, fine adjustment, rounded corners, shadow/border, screenshot & area history, scrolling/long screenshot (+auto-scroll, overflow), GIF/WebP/MP4 capture, basic video screen recording, **local OCR** (base languages), all 11 annotation tools, all 6 pin types (Text/Image/Color/File/LaTeX/Window), pin groups, batch ops, history, mouse penetration, floating icon, full config & shortcuts, scripting engine, PDF/AVIF export.

**Membership / Pro adds (highlights):** Global Mouse gestures; Smart Eraser; mouse/keyboard recording in GIF/video; post-record editing/trimming; AI Translation + editable source/source-language; multi-language OCR; Table recognition → Excel (+preview/edit/print/AirDrop); LaTeX formula recognition; Cloud config sync; PiP camera recording; PDF margins/pagination; extended translation languages; lightweight OCR model; Read Aloud.

*PixPin is "not completely free software" — a minority of features are gated — but free for company/commercial use with no scenario restrictions.*

---

## 11. Selected Changelog Highlights (development is very active)

**v3.4.2.2 (beta, Jul 24 2026):** text-recognition auto-close window; macOS Global Mouse default shortcut unset; macOS screenshot/pin/floating-ball/recognition fixes; Read Aloud fix on macOS; table/translation Pro fixes.

**v3.3.5.7 (stable, Jul 13 2026):** global exclusion list for shortcuts; floating ball per-app hide; thumbnail `Shift+Scroll` vertical; macOS AirDrop for screenshots/pins; translation editable source + source language (Pro); table preview/edit/copy-Excel/print/AirDrop (Pro); toolbar icon size; redesigned editable OCR window; disconnected-display pin auto-move; color picker remembers last format.

**v3.2.1.3 (May 13 2026):** Overflow mode (≈2M px); 30 FPS recording; multi-select Auto Align + z-order memory; centered watermark; arrow-text direction/bg; text annotations support arrows; auto-save type trigger + old-file cleanup; many new config toggles; Pro PiP camera, PDF margins/pagination, more translation languages.

**v3.0.8.0 (Mar 27 2026 — major release):** full UI redesign; **AI translation** (LLM-based); stronger OCR (zh/zh-tw/en/ja); redesigned save dialog with **AVIF + PDF**, size estimate, Pro encoding preview; **Desktop Floating Icon** with drag-to-pin; post-record preview shortcuts; "Copy Image Path"; resizable OCR dialog; WebP export speed-up.

**v2.4.9.6 (Jan 23 2026):** pin multi-select; annotations drag-to-copy with `Alt`; Window Pin → Image Pin conversion; auto-scroll speed auto-adjust; Korean OCR enhancement; rounded-corner jaggies reduced; save-path `&` modifier; `F5` refresh screenshot; rectangle rounded-corner `Alt`+wheel; triangle arrow; magnifier annotation `Shift` range; script "screenshot and save to path."

**v2.3.8.0 (Jan 5 2026):** scrolling auto-scroll; (Windows) Window Pin; shake-to-hide-other-pins; QR recognition; magnifier color-format customization; "Pin with PixPin" context menu (Windows); disable shortcuts in full-screen game mode.

*(Earlier versions introduced Smart Eraser, LaTeX/table recognition, translation, cloud sync, annotation arrow-text, etc. Full history in the docs Changelog/Beta Changelog sections.)*

---

## 12. Known Limitations & Troubleshooting (official FAQ)

- **Browser element auto-select fails** (Chrome/Edge) → enable `Native accessibility API support` + `Web accessibility` at `chrome://accessibility`.
- **HDR washed-out screenshots** → Config → Screenshot → Screenshot Mode = Performance Mode (may not fix all GPU/monitor combos).
- **Rounded corners white/black after paste** → enable Copy Image as File (PNG) in clipboard config (some Windows apps mishandle transparent clipboard images).
- **Rounded corners white/black after save** → save as PNG/WebP (JPG/BMP lack alpha).
- **WeChat** forces white background on transparent images (platform limit; recipient can re-save as PNG). WeChat also rejects `Ctrl`+drag content — release `Ctrl` first.
- **Middle-click tray icon broken** on Windows 11 < 24H2 (OS bug; upgrade).
- **Right-click menu disappears during screenshot** if shortcut contains `Alt` → use a non-Alt shortcut or delayed custom screenshot.
- **Auto-exit after selecting area** from translation software's auto-copy → enable *Ignore Copy Key Macro*.
- **Scrolling mismatch** → scroll back to last green-matched position and re-match; keep content static & scrolling smooth.
- **macOS 12/13 instability on v3.0** → stay on classic 2.4.9.6.
- **Windows 7 missing DLL on v3.0** → apply the noted open-source/api-ms patch or ADVAPI32 fix.
- **Pin lag after v3.0 upgrade** → uncheck "Default Text Selectable" in Config → Pin.

---

## 13. External Reception (cited)

- **X.PIN:** "It can capture scrolling screenshots, record GIFs, and even do OCR. This might be the most powerful free screenshot tool."
- **iplaysoft.com:** "Screenshotting, pinning, annotating, editing, text recognition (OCR), scrolling screenshots, GIF recording, screen recording… features many people desperately need for office work and editing."
- **ghxi.com:** "The screenshot tool of my dreams — someone finally made it!"
- **SourceForge:** notes built-in color picker, multiple capture modes (full-screen, region), and annotation tools (text labels, shapes, freehand).
- **softrankings.com:** positions PixPin as "smart screen recording and note-taking for productive workflows," citing long-page stitching, smart selection, and quick text extraction.

---

## 14. Quick-Reference Cheat Sheet

| Action | Default Shortcut |
|---|---|
| Screenshot | `Ctrl + 1` |
| Pin (clipboard: image/text/file/color/LaTeX) | `Ctrl + 2` |
| Pin image file from Explorer | copy file → `Ctrl + 2` (or `Alt + ~`) |
| Full-screen select | `Ctrl + A` (×2 = all monitors) |
| Custom / delayed / fixed-ratio screenshot | `Alt + 1` |
| Translate (pin/image/text) | `Ctrl + Q` |
| Table recognize (during shot) | `Shift + Q` |
| LaTeX recognize (during shot) | `Shift + F` |
| Copy all OCR text on pin | `Shift + C` |
| Annotation toolbar on pin | `Space` |
| Image processing (pin): rotate/flip/gray/invert/bright/reset | `1`/`2`/`3`/`4`/`5`/`6`/`7`/`8`/`0` |
| Restore last closed pin | `Ctrl + 3` |
| Destroy pin | `Ctrl + D` |
| Close pin | `Ctrl + W` / `Esc` / double-click |
| Lock pin | `L` |
| Always on top | `T` |
| Thumbnail mode | `R` / `Shift`+double-click |
| Switch pin group | `Ctrl + ~` |
| Quick Save | `Ctrl + Shift + S` |
| Global mouse: screenshot+pin / copy img / copy text | `Win`+Left / Middle / Right drag |
| Refresh screenshot | `F5` |
| Annotation tools | Geometric `Shift+1` … Eraser `Shift+9` |

---

*Report compiled from PixPin's official site (pixpin.com), the complete official documentation (pixpin.com/docs — every Start/Capture/Pin/Annotate/Config/Other/Changelog page), official changelogs (v2.3.7 → v3.4.2.2), FAQ, and cited third-party reviews. Feature availability varies by version and Free vs Membership tier; verify against the latest docs at https://pixpin.com/docs/.*
