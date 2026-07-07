# Application Architecture

A map of Notable's core components and how signals flow between them.
Companion documents: `file-structure.md` (where code lives),
`editor-data-flow.md` (data loading and caching in depth),
`editor-state-and-view.md` (state holders), `database-structure.md`.

**It was created by AI, and should be checked for correctness. Refer to code for actual implementation.**

Contents:
- High-level component map
- The editor stack
- `DrawCanvas` — the drawing surface
- `PageView` — one page on screen
- `PageDataManager` — page content and caches
- History (undo/redo)
- Signal flow: `CanvasEventBus`
- Walk-throughs (draw, erase, change page, undo)

---

## High-level component map

```text
MainActivity (fullscreen, key events, lifecycle → CanvasEventBus signals)
└── NotableApp (Compose root, navigation)
    ├── Library / HomeView        — folders, quick pages, notebooks
    ├── PagesView                 — pages of one notebook
    ├── SettingsView              — GlobalAppSettings (persisted via KvProxy)
    └── EditorView                — the note-taking engine (below)
```

Data layer (Hilt singletons): `AppRepository` bundles the Room repositories
(notebooks, pages, strokes, images, folders, key-value store). `PageDataManager`
sits on top of them as the editor's cache. `SyncOrchestrator`/`SyncScheduler`
handle WebDAV sync (see `webdav-sync-technical.md`).

## The editor stack

`EditorView` (Compose) assembles one editor session:

```text
EditorView
├── EditorViewModel      — toolbar/UI state (ToolbarUiState), page navigation,
│                          one-time UI events, canvas commands
├── PageView             — state + rendering of the current page (see below)
├── History              — undo/redo stack, created per PageView
├── EditorControlTower   — routes gestures/commands to page, history, viewmodel
├── EditorGestureReceiver— touch gestures (swipe, pinch, hold, double-tap)
└── EditorSurface        — hosts the DrawCanvas AndroidView
    └── DrawCanvas       — SurfaceView receiving stylus input (see below)
```

Roles in one sentence each:

- **EditorViewModel** owns `ToolbarUiState` (mode, pen, penSettings, menus,
  isDrawing) and performs page navigation against `AppRepository`; it never
  touches bitmaps.
- **EditorControlTower** is the mediator the UI talks to: it coalesces scroll
  input, applies zoom, triggers undo/redo, and manipulates the selection.
- **PageView** owns what is visible: the windowed bitmap of the current page.
- **DrawCanvas** owns how ink gets in: raw stylus input and screen refreshes.

## `DrawCanvas` — the drawing surface

`editor/canvas/DrawCanvas.kt`. A `SurfaceView` whose surface the Onyx firmware
draws pen strokes onto directly (raw drawing), bypassing the normal Android
rendering pipeline for minimal latency.

- **Touch routing** — `dispatchTouchEvent` intercepts stylus/eraser pointers
  before Compose sees them; finger events fall through to Compose gestures.
  On non-Onyx devices stylus events are routed into `OpenGLRenderer`
  (front-buffered GL) instead of the firmware.
- **`OnyxInputHandler`** — wraps the Onyx SDK `TouchHelper`:
  - Creates the `TouchHelper` bound to this canvas and claims ownership of the
    device-wide raw-input surface (`ownsRawInputSurface()`); `surfaceDestroyed`
    only closes the raw-drawing session if its handler is still the owner.
  - `RawInputCallback` receives completed stroke point lists
    (`onRawDrawingTouchPointListReceived`) and erase gestures, then dispatches
    on the current mode: draw, line, select, or erase.
  - `updatePenAndStroke` re-applies stroke style/width/color to the firmware
    whenever pen, pen settings, eraser, mode, or zoom changes. Pen settings are
    resolved with a fallback chain (saved → defaults → hardcoded), so a missing
    entry can't crash input handling.
- **`CanvasRefreshManager`** — the only component that locks the surface canvas
  and blits `page.windowedBitmap` onto it. Provides `refreshUi` (partial or
  full), `drawCanvasToView`, `commitErase` (atomic erase commit that also drops
  the firmware ink overlay), and QuickNav preview restore.
- **`CanvasObserverRegistry`** — subscribes the canvas to every signal it needs
  (see the table below) and guarantees exactly one live observer set even if an
  old canvas leaked.
- **`commitToHistory`** — strokes drawn since the last commit are batched
  (`strokeHistoryBatch`) and registered with `History` as one operation block,
  debounced 500 ms after the pen lifts.

## `PageView` — one page on screen

`editor/PageView.kt`. Manages the state and rendering of the single page the
user is editing. Its responsibilities (and the ongoing decomposition, upstream
issue #259):

1. **Viewport geometry** — scroll offset and zoom level, plus transforms
   between *screen* coordinates (pixels on the SurfaceView) and *page*
   coordinates (the infinite-canvas space strokes are stored in). The pure math
   lives in `PageViewGeometry` (zoom snapping, shift coverage, uncovered-band
   computation); `PageView` keeps thin helpers (`toScreenCoordinates`,
   `applyZoom`, …) that read the live scroll/zoom.
2. **Rendering** — owns `windowedBitmap`/`windowedCanvas`, a screen-sized
   buffer holding the rendered page. Scrolling shifts the bitmap and redraws
   only the uncovered bands; zooming rescales a snapshot and back-fills.
   `CanvasRefreshManager` pushes this bitmap to the actual surface.
3. **Data proxying** — strokes/images/height/scroll are read from and written
   to `PageDataManager` (`page.strokes` is a view over the manager's cache).
   Long-term direction: `EditorViewModel` should talk to `PageDataManager`
   directly and `PageView` should only be told what to draw.

Page switching (`changePage`) persists the old page's bitmap, points the
manager at the new id, restores zoom, reuses a cached bitmap when available,
and kicks off asynchronous stroke loading.

## `PageDataManager` — page content and caches

`data/PageDataManager.kt`, documented in depth in `editor-data-flow.md`.
The injected single source of truth for page content:

- Loads strokes/images/page metadata from Room, guarded by per-page mutexes;
  neighbouring pages of a notebook are pre-loaded (`cacheNeighbors`).
- In-memory caches for strokes (with id indexes), images, page bitmaps,
  backgrounds (`CachedBackground`), scroll and zoom per page; memory-bounded
  and wired into `onTrimMemory`.
- Write-through persistence: `saveStrokesToDb`, `removeImagesFromDb`, … update
  the cache and enqueue DB writes, bumping the parent notebook's timestamp.
- Persists exit bitmaps in batches to serve as previews/placeholders.

## History (undo/redo)

`editor/state/History.kt`. A bounded (5 blocks) two-stack model:

- An `Operation` is the *inverse* action: drawing strokes registers
  `DeleteStroke(ids)`; erasing registers `AddStroke(strokes)` (the deleted
  strokes travel inside the operation so undo can re-insert them).
- `addOperationsToHistory(block)` pushes onto the undo stack and clears redo.
- `undoRedo` pops a block, applies each operation via `PageView`
  (`treatOperation` returns the inverse to push on the opposite stack and the
  affected rect), then redraws the union rect and refreshes the UI.
- Ordering with in-flight ink: before undoing, `History` emits
  `commitHistorySignalImmediately` and awaits `commitCompletion`, forcing
  `DrawCanvas.commitToHistory()` so the just-drawn strokes join the stack
  first.
- `cleanHistory()` clears both stacks on page change.

## Signal flow: `CanvasEventBus`

`editor/canvas/CanvasEventBus.kt` is the editor's event backbone — a singleton
of shared flows connecting the ViewModel/UI world to the canvas world.

| Signal | Emitted by | Handled by | Effect |
|---|---|---|---|
| `refreshUi` | drawing utils, selection, viewmodel | observer → `CanvasRefreshManager.refreshUiSuspend` | repaint after drawing settles |
| `refreshUiImmediately` | scroll/zoom consumer, page init | observer (conflated, deduped by scroll+zoom) | low-latency repaint |
| `forceUpdate` | loading, gestures end | observer → redraw area + refresh | re-render bitmap region from data |
| `isDrawing` | viewmodel, gestures, focus loss | observer → `updateIsDrawing` | enable/disable raw pen input |
| `drawingInProgress` (mutex) | stroke handlers | scroll/zoom/undo paths | wait for in-flight ink |
| `commitHistorySignal` / `…Immediately` (+`commitCompletion`) | stroke handlers, History | observer → `commitToHistory` | batch strokes into history |
| `onFocusChange` | MainActivity, EditorView | observer → re-apply pen, repaint | recover after system dialogs |
| `reinitSignal` | MainActivity.onRestart | observer → `drawCanvas.init()` | rebuild surface after sleep |
| `reloadFromDb` | background change, sync | observer → `page.refreshCurrentPage()` | re-read page from Room |
| `clearPageSignal` | toolbar menu | observer → `cleanAllStrokes` | wipe page (undoable) |
| `changePage` | QuickNav, scrubber | EditorControlTower → viewmodel | navigate to page id |
| `hardwarePageTurn` | MainActivity key events | EditorView → control tower | volume/page-key navigation |
| `addImageByUri` | image picker flow | `ImageHandler` | insert picked image |
| `rectangleToSelectByGesture` | hold gesture | observer → `selectRectangle` | rectangle selection |
| `saveCurrent`, `previewPage`, `restoreCanvas`, `isScrubbing` | QuickNav / PageScrubber | observers | live page previews while scrubbing |
| `closeMenusSignal` | canvas | EditorView → viewmodel | close open menus when pen touches |

(There is a second, app-wide bus — `data/events/AppEventBus` — used for
non-canvas events such as action hints/snacks.)

## Walk-throughs

**Drawing a stroke (Onyx):** firmware renders ink instantly → pen lifts →
`onRawDrawingTouchPointListReceived` → mode `Draw` → under `drawingInProgress`
lock: points are transformed to page coordinates (`copyInput`), scribble-erase
is considered, then `handleDraw` builds the `Stroke`, adds it via
`page.addStrokes` (cache + DB through `PageDataManager`), draws it into
`windowedBitmap`, and batches its id → `commitHistorySignal` (debounced) folds
the batch into `History`.

**Erasing:** eraser gesture → `handleErase` selects intersected strokes
(`SelectionRegion`), removes them from the page, registers
`Operation.AddStroke` in history → `commitErase` repaints the union of the
gesture track and the erased strokes' bounds in one atomic pass.

**Changing page:** swipe/QuickNav → `EditorViewModel.changePage` persists the
new open page and updates `ToolbarUiState.pageId` → `EditorView`'s
`snapshotFlow` sees the id change → `page.changePage(newId)` (bitmap swap +
async load) and navigation state update; `History` is cleared.

**Undo:** toolbar/gesture → `History.undo()` → force-commit pending ink →
pop block, apply inverse operations through `PageView` → redraw affected rect →
`refreshUi`.
