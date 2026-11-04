(function () {
  "use strict";

  var DEFAULT_FOV = 50;
  var PORTRAIT_REF_ASPECT = 0.72;
  var MAX_PORTRAIT_FOV = 72;
  var MAX_PIXEL_RATIO = 1.5;
  var DEG_TO_HALF_RAD = Math.PI / 360;
  var HALF_RAD_TO_DEG = 360 / Math.PI;
  var CLEAR_BLACK = 0x000000;

  var SCRIPT_EVENTS = [
    "init",
    "start",
    "stop",
    "keydown",
    "keyup",
    "mousedown",
    "mouseup",
    "mousemove",
    "touchstart",
    "touchend",
    "touchmove",
    "update"
  ];

  var MATERIAL_MAP_KEYS = [
    "map",
    "alphaMap",
    "bumpMap",
    "normalMap",
    "roughnessMap",
    "metalnessMap",
    "emissiveMap",
    "aoMap"
  ];

  var renderer = null;
  var scene = null;
  var camera = null;
  var baseCameraFov = DEFAULT_FOV;
  var activeEvents = createEmptyEvents();
  var loadToken = 0;
  var rafId = null;
  var isRunning = false;
  var prevTime = 0;

  function createEmptyEvents() {
    var map = {};
    for (var i = 0; i < SCRIPT_EVENTS.length; i++) {
      map[SCRIPT_EVENTS[i]] = [];
    }
    return map;
  }

  // Widen vertical FOV on tall portrait screens so horizontal framing stays intact.
  function computeEffectiveFov(baseFov, aspect) {
    if (!baseFov || aspect <= 0 || aspect >= PORTRAIT_REF_ASPECT) {
      return baseFov || DEFAULT_FOV;
    }

    var halfRad = baseFov * DEG_TO_HALF_RAD;
    var adaptedRad = Math.atan(Math.tan(halfRad) * (PORTRAIT_REF_ASPECT / aspect));
    var adaptedDeg = adaptedRad * HALF_RAD_TO_DEG;
    return Math.min(adaptedDeg, MAX_PORTRAIT_FOV);
  }

  // Reset WebGLRenderer state so settings from a previous work never leak.
  function resetRendererState() {
    if (!renderer) {
      return;
    }

    renderer.shadowMap.enabled = false;
    renderer.shadowMap.type = THREE.PCFShadowMap;
    renderer.toneMapping = THREE.NoToneMapping;
    renderer.toneMappingExposure = 1;
    renderer.physicallyCorrectLights = false;
    renderer.xr.enabled = false;
    renderer.setClearColor(CLEAR_BLACK, 1);
  }

  function applyProjectConfig(project) {
    resetRendererState();
    if (!project || !renderer) {
      return;
    }

    if (project.shadows !== undefined) {
      renderer.shadowMap.enabled = project.shadows === true;
    }
    if (project.shadowType !== undefined) {
      renderer.shadowMap.type = project.shadowType;
    }
    if (project.toneMapping !== undefined) {
      renderer.toneMapping = project.toneMapping;
    }
    if (project.toneMappingExposure !== undefined) {
      renderer.toneMappingExposure = project.toneMappingExposure;
    }
    if (project.physicallyCorrectLights !== undefined) {
      renderer.physicallyCorrectLights = project.physicallyCorrectLights === true;
    }
  }

  // Normalize script-assigned render targets and upload edited texture parameters.
  function refreshSceneMaterials(rootScene) {
    if (!rootScene) {
      return;
    }

    rootScene.traverse(function (node) {
      if (!node.material) {
        return;
      }

      var materials = Array.isArray(node.material) ? node.material : [node.material];
      for (var i = 0; i < materials.length; i++) {
        var mat = materials[i];
        if (!mat) {
          continue;
        }

        if (mat.envMap && mat.envMap.isWebGLCubeRenderTarget && mat.envMap.texture) {
          if (mat.envMap.texture.mapping === THREE.UVMapping) {
            mat.envMap.texture.mapping = THREE.CubeReflectionMapping;
          }
          mat.envMap = mat.envMap.texture;
        }

        for (var k = 0; k < MATERIAL_MAP_KEYS.length; k++) {
          var tex = mat[MATERIAL_MAP_KEYS[k]];
          if (!tex) {
            continue;
          }
          var hasSource = tex.isDataTexture ||
            Boolean(tex.image && (tex.image.src || tex.image.data || tex.image.tagName));
          if (hasSource) {
            tex.needsUpdate = true;
          }
        }
        mat.needsUpdate = true;
      }
    });
  }

  function disposeMaterialMaps(mat) {
    for (var i = 0; i < MATERIAL_MAP_KEYS.length; i++) {
      var tex = mat[MATERIAL_MAP_KEYS[i]];
      if (tex && typeof tex.dispose === "function") {
        tex.dispose();
      }
    }
    if (mat.envMap && typeof mat.envMap.dispose === "function") {
      mat.envMap.dispose();
    }
  }

  // Release GPU buffers and textures from the outgoing scene before loading the next work.
  function disposeSceneGraph(oldScene) {
    if (!oldScene) {
      return;
    }

    oldScene.traverse(function (node) {
      if (node.geometry && typeof node.geometry.dispose === "function") {
        node.geometry.dispose();
      }
      if (!node.material) {
        return;
      }

      var materials = Array.isArray(node.material) ? node.material : [node.material];
      for (var i = 0; i < materials.length; i++) {
        var mat = materials[i];
        if (!mat) {
          continue;
        }
        disposeMaterialMaps(mat);
        if (typeof mat.dispose === "function") {
          mat.dispose();
        }
      }
    });

    if (oldScene.environment && typeof oldScene.environment.dispose === "function") {
      oldScene.environment.dispose();
    }
    if (oldScene.background && typeof oldScene.background.dispose === "function") {
      oldScene.background.dispose();
    }
  }

  // Bind Three.js Editor App scripts to their target scene objects by UUID.
  function compileSceneScripts(scriptsSpec, playerApi) {
    var handlers = createEmptyEvents();
    if (!scriptsSpec || !scene) {
      return handlers;
    }

    var wrapParams = "player,renderer,scene,camera," + SCRIPT_EVENTS.join(",");
    var returnPairs = [];
    for (var e = 0; e < SCRIPT_EVENTS.length; e++) {
      var evName = SCRIPT_EVENTS[e];
      returnPairs.push(evName + ":" + evName);
    }
    var wrapReturn = "{" + returnPairs.join(",") + "}";

    var uuids = Object.keys(scriptsSpec);
    for (var u = 0; u < uuids.length; u++) {
      var uuid = uuids[u];
      var target = scene.getObjectByProperty("uuid", uuid, true);
      if (!target) {
        continue;
      }

      var list = scriptsSpec[uuid] || [];
      for (var i = 0; i < list.length; i++) {
        var script = list[i];
        if (!script || !script.source) {
          continue;
        }

        var factory = new Function(wrapParams, script.source + "\nreturn " + wrapReturn + ";");
        var exported = factory.bind(target)(playerApi, renderer, scene, camera);
        for (var k = 0; k < SCRIPT_EVENTS.length; k++) {
          var key = SCRIPT_EVENTS[k];
          if (typeof exported[key] === "function") {
            handlers[key].push(exported[key].bind(target));
          }
        }
      }
    }

    return handlers;
  }

  function dispatchHandlers(list, payload) {
    if (!list) {
      return;
    }
    for (var i = 0; i < list.length; i++) {
      try {
        list[i](payload);
      } catch (err) {
        console.error("Script handler error:", err);
      }
    }
  }

  function applyViewportSize() {
    var width = Math.max(window.innerWidth || 1, 1);
    var height = Math.max(window.innerHeight || 1, 1);

    if (camera) {
      var aspect = width / height;
      camera.aspect = aspect;
      if (camera.isPerspectiveCamera) {
        camera.fov = computeEffectiveFov(baseCameraFov, aspect);
      }
      camera.updateProjectionMatrix();
    }

    if (renderer) {
      renderer.setSize(width, height, false);
    }
  }

  function renderFrame() {
    if (!isRunning || !renderer || !scene || !camera) {
      return;
    }

    rafId = requestAnimationFrame(renderFrame);

    var now = performance.now();
    var delta = now - prevTime;
    prevTime = now;

    dispatchHandlers(activeEvents.update, { time: now, delta: delta });
    renderer.render(scene, camera);
  }

  function stopActiveWork() {
    if (rafId !== null) {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
    if (isRunning) {
      dispatchHandlers(activeEvents.stop, {});
    }

    isRunning = false;
    activeEvents = createEmptyEvents();
    disposeSceneGraph(scene);
    scene = null;
    camera = null;
  }

  function startLoadedWork(json, loadedScene, playerApi) {
    scene = loadedScene;
    applyViewportSize();

    activeEvents = compileSceneScripts(json.scripts, playerApi);
    refreshSceneMaterials(scene);

    dispatchHandlers(activeEvents.init, {});
    prevTime = performance.now();
    dispatchHandlers(activeEvents.start, {});

    isRunning = true;
    rafId = requestAnimationFrame(renderFrame);
  }

  var playerApi = {
    loadWorkJson: function (jsonText) {
      var token = ++loadToken;
      stopActiveWork();

      var json = typeof jsonText === "string" ? JSON.parse(jsonText) : jsonText;
      applyProjectConfig(json.project);

      var loader = new THREE.ObjectLoader();
      camera = loader.parse(json.camera);
      baseCameraFov = (camera && camera.fov) ? camera.fov : DEFAULT_FOV;

      loader.parse(json.scene, function (loadedScene) {
        if (token !== loadToken) {
          disposeSceneGraph(loadedScene);
          return;
        }
        startLoadedWork(json, loadedScene, playerApi);
      });
    },

    loadWork: function (workId) {
      var token = ++loadToken;
      var url = "works/" + encodeURIComponent(workId) + ".json";
      fetch(url)
        .then(function (res) {
          if (!res.ok) {
            throw new Error("HTTP " + res.status);
          }
          return res.json();
        })
        .then(function (json) {
          if (token !== loadToken) {
            return;
          }
          playerApi.loadWorkJson(json);
        })
        .catch(function (err) {
          console.error("Failed to load Three.js work " + workId + ":", err);
        });
    },

    stop: function () {
      loadToken++;
      stopActiveWork();
    },

    resize: function () {
      applyViewportSize();
    }
  };

  function initPlayer() {
    renderer = new THREE.WebGLRenderer({
      antialias: true,
      powerPreference: "high-performance"
    });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, MAX_PIXEL_RATIO));
    renderer.outputEncoding = THREE.sRGBEncoding;
    document.body.appendChild(renderer.domElement);

    applyViewportSize();
    window.addEventListener("resize", applyViewportSize);
    window.ThreeWallpaperPlayer = playerApi;

    if (
      window.AndroidThreeBridge &&
      typeof window.AndroidThreeBridge.onPlayerReady === "function"
    ) {
      window.AndroidThreeBridge.onPlayerReady();
    }
  }

  initPlayer();
})();
