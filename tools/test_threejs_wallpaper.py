import json
import pathlib
import re
import subprocess
import unittest
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS_DIR = ROOT / "app/src/main/assets/threejs"
SRC_DIR = ROOT / "app/src/main/java/com/example/kennys_dokidoki_wallpaper"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class ThreeJsBundledAssetsTest(unittest.TestCase):
    def test_required_assets_exist_and_have_valid_schema(self):
        self.assertTrue((ASSETS_DIR / "three.min.js").is_file())
        self.assertTrue((ASSETS_DIR / "player.html").is_file())
        self.assertTrue((ASSETS_DIR / "player.js").is_file())

        for work_id in ("32", "36", "38"):
            work_path = ASSETS_DIR / "works" / f"{work_id}.json"
            self.assertTrue(work_path.is_file(), f"Missing bundled work {work_id}.json")
            data = json.loads(work_path.read_text(encoding="utf-8"))
            self.assertEqual(data["metadata"]["type"], "App")
            self.assertIn("project", data)
            self.assertIn("camera", data)
            self.assertIn("scene", data)
            self.assertIn("scripts", data)
            self.assertGreaterEqual(len(data["scripts"]), 1)

    def test_player_executes_all_three_works_and_stops_cleanly(self):
        node_script = r"""
const fs = require('fs');
const vm = require('vm');
const path = require('path');

const assetsDir = process.argv[1];
const threeCode = fs.readFileSync(path.join(assetsDir, 'three.min.js'), 'utf8');
const playerCode = fs.readFileSync(path.join(assetsDir, 'player.js'), 'utf8');

function createFakeGL(canvas) {
  const gl = {
    canvas,
    VERSION: 0x1F02, SHADING_LANGUAGE_VERSION: 0x8B8C, VENDOR: 0x1F00, RENDERER: 0x1F01,
    MAX_TEXTURE_SIZE: 0x0D33, MAX_CUBE_MAP_TEXTURE_SIZE: 0x851C,
    MAX_TEXTURE_IMAGE_UNITS: 0x8872, MAX_VERTEX_TEXTURE_IMAGE_UNITS: 0x8B4C,
    MAX_COMBINED_TEXTURE_IMAGE_UNITS: 0x8B4D, MAX_VERTEX_ATTRIBS: 0x8869,
    MAX_VERTEX_UNIFORM_VECTORS: 0x8DFB, MAX_VARYING_VECTORS: 0x8DFC,
    MAX_FRAGMENT_UNIFORM_VECTORS: 0x8DFD, MAX_SAMPLES: 0x8D57,
    RED_BITS: 0x0D52, GREEN_BITS: 0x0D53, BLUE_BITS: 0x0D54, ALPHA_BITS: 0x0D55,
    DEPTH_BITS: 0x0D56, STENCIL_BITS: 0x0D57,
    COMPILE_STATUS: 0x8B81, LINK_STATUS: 0x8B82, ACTIVE_UNIFORMS: 0x8B86,
    ACTIVE_ATTRIBUTES: 0x8B89, FRAMEBUFFER_COMPLETE: 0x8CD5,
    getParameter(p) {
      if (p === this.VERSION) return 'WebGL 1.0';
      if (p === this.SHADING_LANGUAGE_VERSION) return 'WebGL GLSL ES 1.0';
      if (p === this.VENDOR || p === this.RENDERER) return 'MockGL';
      if (p === this.MAX_TEXTURE_SIZE || p === this.MAX_CUBE_MAP_TEXTURE_SIZE) return 4096;
      if (p === this.MAX_SAMPLES) return 4;
      return 16;
    },
    getExtension(name) {
      if (name === 'ANGLE_instanced_arrays') {
        return {
          drawArraysInstancedANGLE() {},
          drawElementsInstancedANGLE() {},
          vertexAttribDivisorANGLE() {}
        };
      }
      if (name === 'OES_vertex_array_object') {
        return {
          createVertexArrayOES() { return {}; },
          bindVertexArrayOES() {},
          deleteVertexArrayOES() {}
        };
      }
      return {};
    },
    getContextAttributes() { return { alpha: false, antialias: true, depth: true, stencil: true }; },
    getShaderPrecisionFormat() { return { rangeMin: 127, rangeMax: 127, precision: 23 }; },
    createTexture() { return {}; }, deleteTexture() {}, bindTexture() {}, activeTexture() {},
    texParameteri() {}, texParameterf() {}, texImage2D() {}, texImage3D() {},
    texSubImage2D() {}, compressedTexImage2D() {}, copyTexImage2D() {},
    generateMipmap() {}, pixelStorei() {},
    createFramebuffer() { return {}; }, deleteFramebuffer() {}, bindFramebuffer() {},
    framebufferTexture2D() {}, framebufferRenderbuffer() {},
    checkFramebufferStatus() { return this.FRAMEBUFFER_COMPLETE; },
    createRenderbuffer() { return {}; }, deleteRenderbuffer() {}, bindRenderbuffer() {},
    renderbufferStorage() {}, renderbufferStorageMultisample() {}, blitFramebuffer() {},
    createBuffer() { return {}; }, deleteBuffer() {}, bindBuffer() {}, bufferData() {}, bufferSubData() {},
    createProgram() { return {}; }, deleteProgram() {}, attachShader() {}, linkProgram() {}, useProgram() {},
    getProgramParameter(p, pname) { return pname === this.LINK_STATUS ? true : 0; },
    getProgramInfoLog() { return ''; },
    createShader() { return {}; }, deleteShader() {}, shaderSource() {}, compileShader() {},
    getShaderParameter() { return true; }, getShaderInfoLog() { return ''; },
    getActiveUniform() { return { name: 'u', size: 1, type: 0x1406 }; },
    getActiveAttrib() { return { name: 'a', size: 1, type: 0x1406 }; },
    getUniformLocation() { return {}; }, getAttribLocation() { return 0; },
    enableVertexAttribArray() {}, disableVertexAttribArray() {}, vertexAttribPointer() {},
    vertexAttrib1f() {}, vertexAttrib2f() {}, vertexAttrib3f() {}, vertexAttrib4f() {},
    uniform1i() {}, uniform1f() {}, uniform2f() {}, uniform3f() {}, uniform4f() {},
    uniform1iv() {}, uniform1fv() {}, uniform2fv() {}, uniform3fv() {}, uniform4fv() {},
    uniformMatrix3fv() {}, uniformMatrix4fv() {},
    enable() {}, disable() {}, blendEquation() {}, blendFunc() {},
    blendEquationSeparate() {}, blendFuncSeparate() {},
    depthFunc() {}, depthMask() {}, depthRange() {},
    clearDepth() {}, clearStencil() {}, clearColor() {}, clear() {},
    colorMask() {}, cullFace() {}, frontFace() {}, lineWidth() {}, polygonOffset() {},
    scissor() {}, viewport() {}, stencilFunc() {}, stencilOp() {}, stencilMask() {},
     stencilFuncSeparate() {}, stencilOpSeparate() {}, stencilMaskSeparate() {},
    drawArrays() {}, drawElements() {}, drawArraysInstanced() {}, drawElementsInstanced() {},
    vertexAttribDivisor() {}, createVertexArray() { return {}; }, bindVertexArray() {}, deleteVertexArray() {},
    isContextLost() { return false; }, getError() { return 0; }, finish() {}, flush() {}
  };
  return new Proxy(gl, {
    get(target, prop) {
      if (prop in target) return target[prop];
      return typeof prop === 'string' && prop === prop.toUpperCase() ? 1 : function () {};
    }
  });
}

let nowMs = 0;
let rafSeq = 0;
const rafMap = new Map();
const issues = [];
let bridgeReady = 0;

function makeCanvas() {
  return {
    width: 540,
    height: 1170,
    style: {},
    addEventListener() {},
    removeEventListener() {},
    getContext(type) {
      if (type === 'webgl' || type === 'experimental-webgl' || type === 'webgl2') {
        return createFakeGL(this);
      }
      return { drawImage() {}, fillRect() {}, getImageData() { return { data: new Uint8ClampedArray(4) }; } };
    }
  };
}

class FakeImage {
  constructor() { this.width = 16; this.height = 16; }
  addEventListener(type, fn) {
    if (type === 'load') this._onload = fn;
  }
  removeEventListener() {}
  set src(v) {
    this._src = v;
    Promise.resolve().then(() => {
      if (this._onload) this._onload({});
      if (this.onload) this.onload({});
    });
  }
  get src() { return this._src; }
}

const container = {
  innerHTML: '',
  children: [],
  appendChild(el) { this.children.push(el); },
  removeChild(el) { this.children = this.children.filter(c => c !== el); }
};

const sandbox = {
  console: {
    log() {},
    info() {},
    warn(...a) { issues.push('WARN:' + a.join(' ')); },
    error(...a) { issues.push('ERR:' + a.join(' ')); }
  },
  performance: { now: () => nowMs },
  innerWidth: 540,
  innerHeight: 1170,
  devicePixelRatio: 1,
  Image: FakeImage,
  HTMLImageElement: FakeImage,
  document: {
    body: container,
    getElementById(id) { return id === 'stage' ? container : null; },
    createElement(tag) {
      if (tag === 'canvas') return makeCanvas();
      if (tag === 'img') return new FakeImage();
      return { style: {}, addEventListener() {}, removeEventListener() {} };
    },
    createElementNS(_ns, tag) { return this.createElement(tag); },
    addEventListener() {},
    removeEventListener() {}
  },
  addEventListener() {},
  removeEventListener() {},
  requestAnimationFrame(cb) {
    const id = ++rafSeq;
    rafMap.set(id, cb);
    return id;
  },
  cancelAnimationFrame(id) {
    rafMap.delete(id);
  },
  fetch(relPath) {
    sandbox.fetchCount = (sandbox.fetchCount || 0) + 1;
    const text = fs.readFileSync(path.join(assetsDir, relPath), 'utf8');
    return Promise.resolve({
      ok: true,
      status: 200,
      text: () => Promise.resolve(text),
      json: () => Promise.resolve(JSON.parse(text))
    });
  },
  AndroidThreeBridge: {
    onPlayerReady() { bridgeReady += 1; }
  },
  setTimeout(cb) {
    const id = ++rafSeq;
    sandbox.fallbackTimers.set(id, cb);
    return id;
  },
  clearTimeout(id) {
    sandbox.fallbackTimers.delete(id);
  }
};
sandbox.fallbackTimers = new Map();
sandbox.window = sandbox;
sandbox.self = sandbox;

vm.createContext(sandbox);
vm.runInContext(threeCode, sandbox);
vm.runInContext(playerCode, sandbox);

const hostWait = (ms) => new Promise(r => setTimeout(r, ms));

function stepFrames(count) {
  for (let i = 0; i < count; i++) {
    nowMs += 16.67;
    const entries = Array.from(rafMap.entries());
    rafMap.clear();
    for (const [, fn] of entries) {
      fn(nowMs);
    }
  }
}

(async () => {
  if (bridgeReady !== 1) {
    throw new Error('Expected AndroidThreeBridge.onPlayerReady once, got ' + bridgeReady);
  }
  for (const id of ['32', '36', '38', '32']) {
    sandbox.ThreeWallpaperPlayer.activateWork(id);
    await hostWait(15);
    stepFrames(25);
  }
  const fetchesBeforePause = sandbox.fetchCount;
  sandbox.ThreeWallpaperPlayer.pause();
  if (rafMap.size !== 0 || sandbox.fallbackTimers.size !== 0) {
    throw new Error('Expected 0 pending callbacks after pause()');
  }
  sandbox.ThreeWallpaperPlayer.activateWork('32');
  if (sandbox.fetchCount !== fetchesBeforePause) {
    throw new Error('Expected resume of same work without re-fetch');
  }
  // Simulate stalled rAF where fallback timer drives a frame
  const fallbackEntries = Array.from(sandbox.fallbackTimers.entries());
  sandbox.fallbackTimers.clear();
  nowMs += 66;
  for (const [, fn] of fallbackEntries) {
    fn();
  }
  stepFrames(10);
  sandbox.ThreeWallpaperPlayer.resize(720, 1600);
  stepFrames(5);
  sandbox.ThreeWallpaperPlayer.stop();
  if (rafMap.size !== 0 || sandbox.fallbackTimers.size !== 0) {
    throw new Error('Expected 0 pending callbacks after stop()');
  }
  if (issues.length > 0) {
    throw new Error(issues.join('\n'));
  }
})().catch(err => {
  console.error(err);
  process.exit(1);
});
"""
        proc = subprocess.run(
            ["node", "-e", node_script, str(ASSETS_DIR)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr or proc.stdout)


class ThreeJsKotlinWiringTest(unittest.TestCase):
    def test_image_set_and_persistence_wire_source_kind(self):
        policy_src = (SRC_DIR / "ThreeJsPolicy.kt").read_text(encoding="utf-8")
        image_set_src = (SRC_DIR / "ImageSet.kt").read_text(encoding="utf-8")
        self.assertIn("enum class WallpaperSourceKind", policy_src)
        self.assertIn("THREE_JS", policy_src)
        self.assertIn("if (sourceKind != WallpaperSourceKind.IMAGES)", image_set_src)

        data_mgr_src = (SRC_DIR / "DataManager.kt").read_text(encoding="utf-8")
        self.assertIn('put("sourceKind", set.sourceKind.name)', data_mgr_src)
        self.assertIn("WallpaperSourceKind.fromStored", data_mgr_src)

        backup_src = (SRC_DIR / "BackupManager.kt").read_text(encoding="utf-8")
        self.assertIn('put("sourceKind", set.sourceKind.name)', backup_src)
        self.assertIn("WallpaperSourceKind.fromStored", backup_src)

    def test_editor_locks_tags_when_three_js_button_is_enabled(self):
        editor_src = (SRC_DIR / "ImageTagEditorActivity.kt").read_text(encoding="utf-8")
        picker_src = (SRC_DIR / "CategorizedTagPickerAdapter.kt").read_text(encoding="utf-8")

        self.assertIn("createThreeJsButton()", editor_src)
        self.assertIn("ThreeJsPolicy.toggledKind(currentSourceKind)", editor_src)
        self.assertIn("pickerAdapter.updateSelectionState(tagState)", editor_src)
        self.assertIn("if (currentSourceKind == WallpaperSourceKind.IMAGES)", editor_src)
        self.assertIn("imageSet!!.sourceKind = currentSourceKind", editor_src)

        self.assertIn("fun updateSelectionState(state: TagSelectionState)", picker_src)
        self.assertIn("selectionState == TagSelectionState.LOCKED", picker_src)

    def test_tag_editor_avoids_material_widgets_under_appcompat_theme(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        activity = next(
            node for node in manifest.iter("activity")
            if node.get(ANDROID_NS + "name") == ".ImageTagEditorActivity"
        )
        self.assertEqual("@style/Theme.TagEditorOverlay", activity.get(ANDROID_NS + "theme"))

        editor_src = (SRC_DIR / "ImageTagEditorActivity.kt").read_text(encoding="utf-8")
        self.assertNotIn("MaterialButton(", editor_src)
        self.assertNotIn("com.google.android.material", editor_src)
        self.assertIn("AppCompatButton(this, null, 0)", editor_src)

    def test_wallpaper_service_preserves_gestures_and_random_next_work(self):
        service_src = (SRC_DIR / "MyWallpaperService.kt").read_text(encoding="utf-8")
        driver_src = (SRC_DIR / "ThreeJsWallpaperDriver.kt").read_text(encoding="utf-8")

        self.assertIn("setTouchEventsEnabled(true)", service_src)
        self.assertIn("if (activeSet != null && activeSet.sourceKind == WallpaperSourceKind.THREE_JS)", service_src)
        self.assertIn("advanceRandomThreeJsWork(activeSet)", service_src)
        self.assertIn("ThreeJsPolicy.pickNextRandomWork(", service_src)
        self.assertIn("threeJsDriver?.pause()", service_src)
        self.assertIn("teardownThreeJsDriver()", service_src)
        self.assertNotIn("pauseTimers", driver_src)

        quiet_block = service_src.split("private fun goQuiet()", 1)[1].split("private fun reloadCurrent()", 1)[0]
        self.assertIn("threeJsDriver?.pause()", quiet_block)
        self.assertNotIn("threeJsDriver?.stop()", quiet_block)

        self.assertIn("setCancelable(false)", driver_src)
        self.assertIn("FLAG_NOT_FOCUSABLE", driver_src)
        self.assertIn("FLAG_NOT_TOUCHABLE", driver_src)
        self.assertIn("FLAG_ALT_FOCUSABLE_IM", driver_src)
        self.assertIn("vd.resize(width, height", driver_src)

        pause_block = driver_src.split("fun pause()", 1)[1].split("fun stop()", 1)[0]
        self.assertNotIn("view.onPause()", pause_block)
        self.assertNotIn("setOnImageAvailableListener(null", pause_block)
        self.assertIn("drainImageReader(reader)", pause_block)

    def test_new_kotlin_files_follow_function_length_and_no_boolean_params(self):
        checked_files = [
            SRC_DIR / "ThreeJsPolicy.kt",
            SRC_DIR / "ThreeJsPowerPolicy.kt",
            SRC_DIR / "ThreeJsPowerStore.kt",
            SRC_DIR / "ThreeJsPowerSettingsActivity.kt",
            SRC_DIR / "ThreeJsInspectorPolicy.kt",
            SRC_DIR / "ThreeJsWorkStore.kt",
            SRC_DIR / "ThreeJsThumbnailCapturer.kt",
            SRC_DIR / "ThreeJsManagerActivity.kt",
            SRC_DIR / "ThreeJsBrowserActivity.kt",
            SRC_DIR / "ThreeJsWallpaperDriver.kt",
        ]
        fun_pattern = re.compile(r"\bfun\s+([A-Za-z0-9_]+)\s*\(([^)]*)\)")
        for file_path in checked_files:
            content = file_path.read_text(encoding="utf-8")
            for match in fun_pattern.finditer(content):
                fn_name, params = match.group(1), match.group(2)
                self.assertLess(
                    len(fn_name),
                    30,
                    f"Function {fn_name} in {file_path.name} exceeds 29 characters",
                )
                self.assertNotIn(
                    ": Boolean",
                    params,
                    f"Function {fn_name} in {file_path.name} uses a Boolean parameter",
                )


class ThreeJsManagerAndBrowserTest(unittest.TestCase):
    def test_player_supports_thumbnails_scene_json_and_geometry_json(self):
        node_script = r"""
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const assetsDir = process.argv[1];
const threeCode = fs.readFileSync(path.join(assetsDir, 'three.min.js'), 'utf8');
const playerCode = fs.readFileSync(path.join(assetsDir, 'player.js'), 'utf8');

class FakeImage {
  constructor() {
    this.width = 64;
    this.height = 64;
    this.tagName = 'IMG';
    this._src = '';
    this._onload = null;
  }
  addEventListener(type, cb) {
    if (type === 'load') this._onload = cb;
  }
  removeEventListener() {}
  set src(val) {
    this._src = val;
    Promise.resolve().then(() => {
      if (this._onload) this._onload({});
    });
  }
  get src() { return this._src; }
}

let rendererOptions = null;
let renderSizes = [];
const capturedThumbs = {};
let capturerReady = false;
let rafMap = new Map();
let rafSeq = 1;

const sandbox = {
  console,
  Math,
  Date,
  Promise,
  JSON,
  Object,
  Array,
  String,
  Number,
  Boolean,
  Function,
  Float32Array,
  Uint16Array,
  Uint32Array,
  Uint8Array,
  Int32Array,
  performance: { now: () => 1000 },
  devicePixelRatio: 2,
  innerWidth: 1080,
  innerHeight: 2400,
  location: { search: '?capture=1' },
  addEventListener: () => {},
  removeEventListener: () => {},
  requestAnimationFrame: (cb) => {
    const id = rafSeq++;
    rafMap.set(id, cb);
    return id;
  },
  cancelAnimationFrame: (id) => rafMap.delete(id),
  setTimeout: () => 1,
  clearTimeout: () => {},
  Image: FakeImage,
  document: {
    body: { appendChild: () => {}, removeChild: () => {} },
    createElementNS: (_ns, tag) => {
      if (tag === 'img') return new FakeImage();
      return {
        style: {},
        addEventListener: () => {},
        removeEventListener: () => {},
        toDataURL: (mime) => `data:${mime || 'image/jpeg'};base64,FAKEJPEGDATA`
      };
    },
    createElement: (tag) => {
      if (tag === 'img') return new FakeImage();
      return {
        style: {},
        addEventListener: () => {},
        removeEventListener: () => {},
        toDataURL: (mime) => `data:${mime || 'image/jpeg'};base64,FAKEJPEGDATA`
      };
    }
  },
  AndroidThreeThumbBridge: {
    onCapturerReady: () => { capturerReady = true; },
    onThumbCaptured: (id, dataUrl) => { capturedThumbs[id] = dataUrl; },
    onThumbFailed: (id, reason) => { throw new Error(`Thumb failed for ${id}: ${reason}`); }
  },
  fetch: (url) => {
    const rel = url.replace(/^\//, '');
    const text = fs.readFileSync(path.join(assetsDir, rel), 'utf8');
    return Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve(JSON.parse(text))
    });
  }
};
sandbox.window = sandbox;
sandbox.self = sandbox;

vm.createContext(sandbox);
vm.runInContext(threeCode, sandbox);

sandbox.THREE.WebGLRenderer = function FakeRenderer(opts) {
  rendererOptions = opts;
  this.domElement = {
    style: {},
    parentNode: null,
    addEventListener: () => {},
    toDataURL: (mime) => `data:${mime};base64,CAPTURED_THUMB_BYTES`
  };
  this.shadowMap = { enabled: false, type: 0 };
  this.toneMapping = 0;
  this.toneMappingExposure = 1;
  this.physicallyCorrectLights = false;
  this.outputEncoding = 0;
  this.setPixelRatio = () => {};
  this.setSize = (w, h) => { renderSizes.push([w, h]); };
  this.setClearColor = () => {};
  this.getContext = () => ({ isContextLost: () => false });
  this.render = (scn, cam) => {
    if (!scn || !cam) throw new Error('Missing scene or camera');
  };
  this.dispose = () => {};
};

vm.runInContext(playerCode, sandbox);

const hostWait = (ms) => new Promise(r => setTimeout(r, ms));

(async () => {
  if (!capturerReady) throw new Error('Expected onCapturerReady');
  if (!rendererOptions || rendererOptions.preserveDrawingBuffer !== true) {
    throw new Error('Expected preserveDrawingBuffer=true in capture mode');
  }

  for (const id of ['32', '36', '38']) {
    sandbox.ThreeWallpaperPlayer.captureWorkThumbnail(id);
    await hostWait(20);
    if (!capturedThumbs[id] || !capturedThumbs[id].startsWith('data:image/jpeg;base64,')) {
      throw new Error('Missing captured thumbnail for ' + id);
    }
  }
  const has512 = renderSizes.some(([w, h]) => w === 512 && h === 512);
  if (!has512) throw new Error('Expected 512x512 thumbnail viewport');

  // Standalone Scene/Object JSON (no camera, no project)
  const sceneOnlyJson = {
    metadata: { version: 4.5, type: 'Object' },
    geometries: [{ uuid: 'g-box', type: 'BoxBufferGeometry', width: 2, height: 2, depth: 2 }],
    materials: [{ uuid: 'm-std', type: 'MeshStandardMaterial', color: 0xff5533 }],
    object: {
      uuid: 'root-scene',
      type: 'Scene',
      children: [{ uuid: 'mesh-1', type: 'Mesh', geometry: 'g-box', material: 'm-std' }]
    }
  };
  sandbox.ThreeWallpaperPlayer.loadWorkJson(sceneOnlyJson, 'user_scene');
  await hostWait(15);
  const frameSnap = sandbox.ThreeWallpaperPlayer.captureCurrentFrame();
  if (!frameSnap.startsWith('data:image/jpeg;base64,')) {
    throw new Error('Expected captureCurrentFrame() JPEG data URL');
  }

  // Standalone BufferGeometry JSON
  const geometryOnlyJson = {
    metadata: { version: 4.5, type: 'BufferGeometry' },
    data: {
      attributes: {
        position: {
          itemSize: 3,
          type: 'Float32Array',
          array: [0, 0, 0, 1, 0, 0, 0, 1, 0],
          normalized: false
        }
      }
    }
  };
  sandbox.ThreeWallpaperPlayer.loadWorkJson(geometryOnlyJson, 'user_geo');
  await hostWait(15);
  sandbox.ThreeWallpaperPlayer.stop();
})().catch(err => {
  console.error(err);
  process.exit(1);
});
"""
        proc = subprocess.run(
            ["node", "-e", node_script, str(ASSETS_DIR)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr or proc.stdout)

    def test_browser_devtools_js_hooks_capture_fetch_xhr_and_dom_json(self):
        browser_src = (SRC_DIR / "ThreeJsBrowserActivity.kt").read_text(encoding="utf-8")
        hook_script = browser_src.split('DEVTOOLS_HOOK_SCRIPT = """', 1)[1].split('""".trimIndent()', 1)[0]
        scan_script = browser_src.split('DOM_JSON_SCAN_SCRIPT = """', 1)[1].split('""".trimIndent()', 1)[0]
        # Unescape Kotlin raw-string have no backslash escapes, so pass directly to Node!
        node_test = r"""
const vm = require('vm');
const hookCode = process.argv[1];
const scanCode = process.argv[2];

const captured = [];
let detectedRev = null;

class FakeXHR {
  open(method, url) {
    this._m = method;
    this._u = url;
  }
  getResponseHeader(name) {
    return name.toLowerCase() === 'content-type' ? 'application/json' : '';
  }
  addEventListener(ev, fn) {
    if (ev === 'load') this._onload = fn;
  }
  send() {
    this.status = 200;
    this.responseType = '';
    this.responseText = '{"metadata":{"type":"App"},"camera":{},"scene":{}}';
    if (this._onload) this._onload();
  }
}

const sandbox = {
  URL,
  JSON,
  String,
  RegExp,
  THREE: { REVISION: '128' },
  location: { href: 'https://showcase.example/gallery/index.html' },
  setTimeout: (fn) => { fn(); return 1; },
  fetch: (url) => Promise.resolve({
    url: new URL(url, 'https://showcase.example/gallery/index.html').href,
    status: 200,
    headers: { get: () => 'application/json; charset=utf-8' },
    clone: () => ({
      text: () => Promise.resolve('{"metadata":{"type":"Object"},"object":{"type":"Scene"}}')
    })
  }),
  XMLHttpRequest: FakeXHR,
  performance: {
    getEntriesByType: () => [
      { name: 'https://showcase.example/gallery/038/app.json' },
      { name: 'https://showcase.example/gallery/three.min.js' }
    ]
  },
  document: {
    baseURI: 'https://showcase.example/gallery/index.html',
    querySelectorAll: (sel) => {
      if (sel === 'iframe') return [];
      if (sel.startsWith('a[href]')) {
        return [
          { getAttribute: (k) => (k === 'href' ? './models/robot.json' : null) },
          { getAttribute: (k) => (k === 'href' ? './readme.html' : null) }
        ];
      }
      if (sel === 'script:not([src])') {
        return [{ textContent: 'loader.load("scenes/nebula.json?v=2");' }];
      }
      return [];
    }
  },
  AndroidDevToolsBridge: {
    onJsResponseCaptured: (url, method, status, ct, body) => {
      captured.push({ url, method, status, ct, body });
    },
    onThreeDetected: (rev) => { detectedRev = rev; }
  }
};
sandbox.window = sandbox;

vm.createContext(sandbox);
vm.runInContext(hookCode, sandbox);

(async () => {
  await sandbox.window.fetch('039/app.json');
  const xhr = new sandbox.XMLHttpRequest();
  xhr.open('GET', '040/app.json');
  xhr.send();
  await new Promise(r => setTimeout(r, 10));

  if (detectedRev !== '128') {
    throw new Error('Expected THREE r128 detection, got ' + detectedRev);
  }
  if (captured.length !== 2) {
    throw new Error('Expected 2 captured fetch/XHR JSON responses, got ' + captured.length);
  }
  const scannedJson = JSON.parse(vm.runInContext(scanCode, sandbox));
  if (scannedJson.length !== 3) {
    throw new Error('Expected 3 scanned JSON URLs, got ' + JSON.stringify(scannedJson));
  }
})().catch(err => {
  console.error(err);
  process.exit(1);
});
"""
        proc = subprocess.run(
            ["node", "-e", node_test, hook_script, scan_script],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr or proc.stdout)

    def test_settings_manifest_store_and_gallery_wiring(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        activity_names = {
            node.get(ANDROID_NS + "name") for node in manifest.iter("activity")
        }
        self.assertIn(".ThreeJsManagerActivity", activity_names)
        self.assertIn(".ThreeJsBrowserActivity", activity_names)

        main_src = (SRC_DIR / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("private lateinit var settingsLayout: LinearLayout", main_src)
        self.assertIn("ThreeJsManagerActivity::class.java", main_src)
        self.assertIn('createSettingsRow("three.js の管理"', main_src)

        mgr_src = (SRC_DIR / "ThreeJsManagerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("ThreeJsBrowserActivity::class.java", mgr_src)
        self.assertIn("ActivityResultContracts.OpenDocument()", mgr_src)
        self.assertIn("ThreeJsWorkStore.renameWork", mgr_src)
        self.assertIn("ThreeJsWorkStore.updateWorkJson", mgr_src)
        self.assertIn("ThreeJsWorkStore.deleteWork", mgr_src)
        self.assertIn("ThreeJsInspectorPolicy.compactForEditor", mgr_src)
        self.assertIn("ThreeJsInspectorPolicy.expandFromEditor", mgr_src)

        browser_src = (SRC_DIR / "ThreeJsBrowserActivity.kt").read_text(encoding="utf-8")
        self.assertIn("ThreeJsInspectorPolicy.DEFAULT_BROWSER_URL", browser_src)
        self.assertIn("ThreeJsWorkStore.addUserWork", browser_src)
        self.assertIn("shouldInterceptRequest", browser_src)
        self.assertIn("onConsoleMessage", browser_src)

        tile_xml = (ROOT / "app/src/main/res/layout/item_threejs_work_tile.xml").read_text(
            encoding="utf-8"
        )
        self.assertIn('layout_constraintDimensionRatio="1:1"', tile_xml)

        service_src = (SRC_DIR / "MyWallpaperService.kt").read_text(encoding="utf-8")
        self.assertIn("ThreeJsWorkStore.loadWorks(applicationContext)", service_src)

        driver_src = (SRC_DIR / "ThreeJsWallpaperDriver.kt").read_text(encoding="utf-8")
        self.assertIn("ThreeJsWorkStore.workOrFirst(appContext, workId)", driver_src)
        self.assertIn("ThreeJsAssetHost.interceptRequest(appContext, request)", driver_src)

    def test_pmrem_work_36_uses_zero_rgbe_clear_without_white_blowout(self):
        node_script = r"""
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const assetsDir = process.argv[1];
const threeCode = fs.readFileSync(path.join(assetsDir, 'three.min.js'), 'utf8');
const playerCode = fs.readFileSync(path.join(assetsDir, 'player.js'), 'utf8');
const work36Json = fs.readFileSync(path.join(assetsDir, 'works/36.json'), 'utf8');

const pmremCubeUvClears = [];
let currentRenderTarget = null;
let clearColor = null;
let clearAlpha = 1;

const sandbox = {
  console,
  Math,
  Date,
  Promise,
  JSON,
  Object,
  Array,
  String,
  Number,
  Boolean,
  Function,
  Float32Array,
  Uint8Array,
  performance: { now: () => 500 },
  devicePixelRatio: 2,
  innerWidth: 1080,
  innerHeight: 2400,
  location: { search: '' },
  addEventListener: () => {},
  removeEventListener: () => {},
  requestAnimationFrame: () => 1,
  cancelAnimationFrame: () => {},
  setTimeout: () => 1,
  clearTimeout: () => {},
  document: {
    body: { appendChild: () => {}, removeChild: () => {} },
    createElementNS: () => ({ style: {}, addEventListener: () => {} }),
    createElement: () => ({ style: {}, addEventListener: () => {} })
  }
};
sandbox.window = sandbox;
sandbox.self = sandbox;

vm.createContext(sandbox);
vm.runInContext(threeCode, sandbox);

const THREE = sandbox.THREE;
clearColor = new THREE.Color(0x000000);

THREE.WebGLRenderer = function FakePmremRenderer() {
  this.domElement = { style: {}, parentNode: null, addEventListener: () => {} };
  this.shadowMap = { enabled: false, type: 0 };
  this.toneMapping = THREE.NoToneMapping;
  this.toneMappingExposure = 1;
  this.physicallyCorrectLights = false;
  this.outputEncoding = THREE.sRGBEncoding;
  this.autoClear = true;
  this.setPixelRatio = () => {};
  this.setSize = () => {};
  this.getContext = () => ({ isContextLost: () => false });
  this.compile = () => {};
  this.getRenderTarget = () => currentRenderTarget;
  this.setRenderTarget = (rt) => { currentRenderTarget = rt; };
  this.getClearColor = () => clearColor;
  this.getClearAlpha = () => clearAlpha;
  this.setClearColor = (col, alpha) => {
    clearColor = (col && col.isColor) ? col.clone() : new THREE.Color(col);
    if (alpha !== undefined) clearAlpha = alpha;
  };
  this.render = (scn, _cam) => {
    if (currentRenderTarget && currentRenderTarget.texture &&
        currentRenderTarget.texture.name === 'PMREM.cubeUv' &&
        scn && scn.children && scn.children.length === 4) {
      pmremCubeUvClears.push({
        r: clearColor.r,
        g: clearColor.g,
        b: clearColor.b,
        a: clearAlpha
      });
    }
  };
  this.dispose = () => {};
};

vm.runInContext(playerCode, sandbox);

// Load 36.json via playerApi
sandbox.ThreeWallpaperPlayer.loadWorkJson(work36Json, '36');

if (pmremCubeUvClears.length !== 6) {
  throw new Error('Expected 6 cubeUV face renders for 36.json PMREM, got ' + pmremCubeUvClears.length);
}
for (const c of pmremCubeUvClears) {
  const rgbeRadiance = Math.max(c.r, c.g, c.b) * Math.pow(2, c.a * 255 - 128);
  if (c.r !== 0 || c.g !== 0 || c.b !== 0 || c.a !== 0 || rgbeRadiance !== 0) {
    throw new Error('Non-zero RGBE clear during PMREM fromScene: ' + JSON.stringify(c) + ' -> ' + rgbeRadiance);
  }
}
if (clearColor.getHex() !== 0x000000 || clearAlpha !== 1) {
  throw new Error('Expected renderer clearColor restored to (0x000000, 1) after PMREM');
}
sandbox.ThreeWallpaperPlayer.stop();
"""
        proc = subprocess.run(
            ["node", "-e", node_script, str(ASSETS_DIR)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr or proc.stdout)

    def test_home_hud_set_item_count_tracks_live_threejs_store(self):
        policy_src = (SRC_DIR / "ThreeJsPolicy.kt").read_text(encoding="utf-8")
        service_src = (SRC_DIR / "MyWallpaperService.kt").read_text(encoding="utf-8")
        store_src = (SRC_DIR / "ThreeJsWorkStore.kt").read_text(encoding="utf-8")

        self.assertNotIn(
            "threeWorkCount: Int = ThreeJsCatalog.BUILT_IN_WORKS.size",
            policy_src,
            "setItemCount must not fall back to static BUILT_IN_WORKS.size",
        )
        self.assertRegex(
            policy_src,
            r"fun\s+setItemCount\s*\(\s*kind:\s*WallpaperSourceKind,\s*activeImageCount:\s*Int,\s*threeWorkCount:\s*Int\s*\)",
        )
        self.assertIn(
            "ThreeJsWorkStore.workCount(applicationContext)",
            service_src,
        )
        self.assertRegex(
            service_src,
            r"ThreeJsPolicy\.setItemCount\(\s*currentSet\.sourceKind,\s*activeImageCount,\s*threeWorkCount\s*\)",
        )
        self.assertIn("readCachedWorksLocked", store_src)
        self.assertIn("cacheManifestLocked", store_src)

    def test_player_power_profiles_and_purge_for_sleep(self):
        node_script = r"""
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const assetsDir = process.argv[1];
const threeCode = fs.readFileSync(path.join(assetsDir, 'three.min.js'), 'utf8');
const playerCode = fs.readFileSync(path.join(assetsDir, 'player.js'), 'utf8');

class FakeImage {
  constructor() { this.width = 16; this.height = 16; }
  addEventListener(type, fn) { if (type === 'load') this._onload = fn; }
  removeEventListener() {}
  set src(v) {
    this._src = v;
    Promise.resolve().then(() => { if (this._onload) this._onload({}); });
  }
  get src() { return this._src; }
}

let nowMs = 1000;
let rafSeq = 1;
const rafMap = new Map();
const timerMap = new Map();
const createdRenderers = [];
let renderCalls = 0;
let renderListsDisposed = 0;

const sandbox = {
  console, Math, Date, Promise, JSON, Object, Array, String, Number, Boolean, Function,
  Float32Array, Uint16Array, Uint32Array, Uint8Array, Int32Array,
  performance: { now: () => nowMs },
  devicePixelRatio: 3,
  innerWidth: 720,
  innerHeight: 1600,
  location: { search: '' },
  addEventListener: () => {},
  removeEventListener: () => {},
  requestAnimationFrame: (cb) => { const id = rafSeq++; rafMap.set(id, cb); return id; },
  cancelAnimationFrame: (id) => rafMap.delete(id),
  setTimeout: (cb, ms) => { const id = rafSeq++; timerMap.set(id, { cb, ms }); return id; },
  clearTimeout: (id) => timerMap.delete(id),
  Image: FakeImage,
  document: {
    body: { appendChild: () => {}, removeChild: () => {} },
    createElementNS: (_ns, tag) => tag === 'img' ? new FakeImage() : { style: {}, addEventListener: () => {} },
    createElement: (tag) => tag === 'img' ? new FakeImage() : { style: {}, addEventListener: () => {} }
  },
  fetch: (rel) => {
    const raw = fs.readFileSync(path.join(assetsDir, rel), 'utf8');
    return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(JSON.parse(raw)) });
  },
  AndroidThreeBridge: { onPlayerReady: () => {} }
};
sandbox.window = sandbox;
sandbox.self = sandbox;

vm.createContext(sandbox);
vm.runInContext(threeCode, sandbox);

const THREE = sandbox.THREE;
THREE.WebGLRenderer = function (opts) {
  const rec = { opts, pixelRatio: 1, shadowMap: { enabled: false, type: THREE.PCFShadowMap }, cleared: 0 };
  createdRenderers.push(rec);
  this.domElement = { style: {}, addEventListener: () => {}, removeEventListener: () => {} };
  this.shadowMap = rec.shadowMap;
  this.toneMapping = THREE.NoToneMapping;
  this.toneMappingExposure = 1;
  this.physicallyCorrectLights = false;
  this.outputEncoding = THREE.sRGBEncoding;
  this.renderLists = { dispose: () => { renderListsDisposed += 1; } };
  this.setPixelRatio = (dpr) => { rec.pixelRatio = dpr; };
  this.setSize = () => {};
  this.setClearColor = () => {};
  this.getClearColor = () => new THREE.Color(0x000000);
  this.getClearAlpha = () => 1;
  this.getRenderTarget = () => null;
  this.setRenderTarget = () => {};
  this.getContext = () => ({ isContextLost: () => false });
  this.render = () => { renderCalls += 1; };
  this.clear = () => { rec.cleared += 1; };
  this.dispose = () => {};
};

vm.runInContext(playerCode, sandbox);

// 1. Lazy renderer creation: no throwaway renderer before setPowerProfile + activateWork
if (createdRenderers.length !== 0) {
  throw new Error('Expected 0 WebGLRenderers before first work activation, got ' + createdRenderers.length);
}

sandbox.ThreeWallpaperPlayer.setPowerProfile({
  frameIntervalMs: 33,
  loopMode: 'continuous',
  maxPixelRatio: 1,
  antialias: false,
  shadowMode: 'off',
  powerPref: 'low-power'
});

const shadowedJson = {
  metadata: { version: 4.5, type: 'App' },
  project: { shadows: true, shadowType: 2 },
  camera: {
    metadata: { version: 4.5, type: 'Object' },
    object: { uuid: 'cam-1', type: 'PerspectiveCamera', fov: 50, aspect: 1, near: 0.1, far: 100 }
  },
  scene: {
    metadata: { version: 4.5, type: 'Object' },
    object: { uuid: 'scn-1', type: 'Scene', children: [] }
  },
  scripts: {}
};
sandbox.ThreeWallpaperPlayer.loadWorkJson(shadowedJson, 'eco_work');

if (createdRenderers.length !== 1) {
  throw new Error('Expected exactly 1 WebGLRenderer after activation');
}
if (createdRenderers[0].opts.antialias !== false || createdRenderers[0].opts.powerPreference !== 'low-power') {
  throw new Error('Expected low-power non-MSAA renderer: ' + JSON.stringify(createdRenderers[0].opts));
}
if (createdRenderers[0].pixelRatio !== 1) {
  throw new Error('Expected DPR capped at 1, got ' + createdRenderers[0].pixelRatio);
}
if (createdRenderers[0].shadowMap.enabled !== false) {
  throw new Error('Expected shadowMode=off to disable shadows');
}
// 2. At 30 FPS (33ms > 20ms), rAF must stay 0 so VSync is not woken at 60-120Hz
if (rafMap.size !== 0 || timerMap.size !== 1) {
  throw new Error(`Expected 0 rAF and 1 paced timer at 33ms, got rAF=${rafMap.size} timers=${timerMap.size}`);
}

// 3. Single-frame freeze renders and stops all callbacks (0% GPU/CPU)
sandbox.ThreeWallpaperPlayer.setPowerProfile({ loopMode: 'single_frame' });
if (rafMap.size !== 0 || timerMap.size !== 0) {
  throw new Error('Expected 0 callbacks in single_frame freeze mode');
}

// 4. Resume back to continuous
sandbox.ThreeWallpaperPlayer.setPowerProfile({ loopMode: 'continuous', frameIntervalMs: 50 });
if (!sandbox.ThreeWallpaperPlayer.resume() || timerMap.size !== 1) {
  throw new Error('Expected resume() to restart paced timer without re-fetch');
}

// 5. purgeForSleep disposes renderLists and leaves 0 callbacks
sandbox.ThreeWallpaperPlayer.purgeForSleep();
if (rafMap.size !== 0 || timerMap.size !== 0 || renderListsDisposed < 1) {
  throw new Error('Expected purgeForSleep() to clear timers and dispose renderLists');
}
"""
        proc = subprocess.run(
            ["node", "-e", node_script, str(ASSETS_DIR)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr or proc.stdout)

    def test_power_settings_ui_and_initial_fade_wiring(self):
        manifest_root = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        activities = {
            el.attrib.get(f"{ANDROID_NS}name")
            for el in manifest_root.findall("./application/activity")
        }
        self.assertIn(".ThreeJsPowerSettingsActivity", activities)

        power_layout = ROOT / "app/src/main/res/layout/activity_threejs_power_settings.xml"
        self.assertTrue(power_layout.is_file())
        power_xml_raw = power_layout.read_text(encoding="utf-8")
        self.assertNotIn("android:tint=", power_xml_raw)

        main_src = (SRC_DIR / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn('"three.js の設定"', main_src)
        self.assertIn('createSettingsRow("three.js の管理"', main_src)
        self.assertIn("ThreeJsPowerSettingsActivity::class.java", main_src)

        manager_src = (SRC_DIR / "ThreeJsManagerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("btn_threejs_power_settings", manager_src)

        service_src = (SRC_DIR / "MyWallpaperService.kt").read_text(encoding="utf-8")
        self.assertIn("ThreeJsPolicy.INITIAL_FADE_MS", service_src)
        self.assertIn("ThreeJsPolicy.resolveLoadTransition(currentThreeDisplayState())", service_src)
        self.assertIn("prepareThreeFadeFromBlack()", service_src)

        advance_block = service_src.split("private fun advanceRandomThreeJsWork(", 1)[1].split(
            "private fun syncActiveWallpaper(", 1
        )[0]
        self.assertIn("completeThreeFadeOpaque()", advance_block)
        self.assertNotIn("prepareThreeFadeFromBlack()", advance_block)


if __name__ == "__main__":
    unittest.main()
