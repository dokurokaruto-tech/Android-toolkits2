# Three.js live wallpaper foundation

The built-in r110 bundle lives together under
`app/src/main/assets/wallpaper/threejs/r110/`:

- `three.r110.min.js` is the bundled Three.js engine.
- `app.r110.js` is the Three.js Editor `APP.Player` runtime.
- `index.html` loads both in that order and provides a small generated scene to
  smoke-test the WebGL rendering path.

The app's **設定 → ライブ壁紙の描画方式** choice selects either the existing image
renderer or this built-in Three.js renderer. In Three.js mode,
`MyWallpaperService` hosts a local-only `WebView` on a `Presentation` backed by a
virtual display targeting the wallpaper surface. The two JavaScript files are
therefore an app-owned runtime bundle; neither is exposed as a separate user
wallpaper or imported as a user library.

## Scene data limitation

The attached `app.r110.js` is a generic player, not an art-specific scene. Its
`load(json)` method needs a Three.js Editor project JSON containing at least a
scene and camera. No such project JSON was supplied with the two runtime files,
so `index.html` currently generates a simple animated torus-knot scene strictly
as a rendering smoke test. Replacing that smoke-test scene with a particular
artwork will require the corresponding project JSON (and any textures or other
referenced assets) plus a bundle-loading path.

Three.js r110 is MIT-licensed; the license notice is retained beside the bundled
engine.
