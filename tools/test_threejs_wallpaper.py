import json
import pathlib
import re
import subprocess
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS_DIR = ROOT / "app/src/main/assets/threejs"
SRC_DIR = ROOT / "app/src/main/java/com/example/kennys_dokidoki_wallpaper"


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
    if (this._onload) this._onload({});
    if (this.onload) this.onload({});
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
  setTimeout,
  clearTimeout
};
sandbox.window = sandbox;
sandbox.self = sandbox;

vm.createContext(sandbox);
vm.runInContext(threeCode, sandbox);
vm.runInContext(playerCode, sandbox);

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
    sandbox.ThreeWallpaperPlayer.loadWork(id);
    await new Promise(r => setTimeout(r, 15));
    stepFrames(25);
  }
  sandbox.ThreeWallpaperPlayer.resize(720, 1600);
  stepFrames(5);
  sandbox.ThreeWallpaperPlayer.stop();
  if (rafMap.size !== 0) {
    throw new Error('Expected 0 pending rAF callbacks after stop(), got ' + rafMap.size);
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

    def test_wallpaper_service_preserves_gestures_and_random_next_work(self):
        service_src = (SRC_DIR / "MyWallpaperService.kt").read_text(encoding="utf-8")
        driver_src = (SRC_DIR / "ThreeJsWallpaperDriver.kt").read_text(encoding="utf-8")

        self.assertIn("setTouchEventsEnabled(true)", service_src)
        self.assertIn("if (activeSet != null && activeSet.sourceKind == WallpaperSourceKind.THREE_JS)", service_src)
        self.assertIn("advanceRandomThreeJsWork(activeSet)", service_src)
        self.assertIn("ThreeJsPolicy.pickNextRandomWork(", service_src)
        self.assertIn("threeJsDriver?.stop()", service_src)
        self.assertNotIn("pauseTimers", driver_src)

    def test_new_kotlin_files_follow_function_length_and_no_boolean_params(self):
        checked_files = [
            SRC_DIR / "ThreeJsPolicy.kt",
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


if __name__ == "__main__":
    unittest.main()
