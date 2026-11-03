# Three.js live wallpaper foundation

The built-in r110 bundle lives under `app/src/main/assets/wallpaper/threejs/r110/`:

- `three.r110.min.js` is the bundled Three.js engine.
- `app.r110.js` is the Three.js Editor `APP.Player` runtime.
- `app.json` is the selected Editor project, including its scene, camera, scripts,
  and embedded texture data.
- `index.html` loads the runtime pair and asks the native asset bridge for the
  selected project's JSON.

In **設定 → Three.js壁紙を選ぶ**, a dark grid shows a live, briefly rendered
thumbnail for each item in `ThreeJsArtworkCatalog`. Choosing an artwork stores
its ID and switches the live wallpaper to Three.js. The existing image renderer
remains available under **ライブ壁紙の描画方式**. In Three.js mode,
`MyWallpaperService` hosts a local-only `WebView` on a `Presentation` backed by a
virtual display targeting the wallpaper surface.

The attached `app.r110.js` is a generic player; it is not itself a scene. The
provided `app.json` supplies the scene data it needs. Its textures are embedded
as data URLs, so this artwork has no external image-file dependencies. To add
more built-in artwork, add a catalog entry and its project JSON asset; the engine
and player remain shared app resources rather than per-art files.

Three.js r110 is MIT-licensed; the license notice is retained beside the bundled
engine.
