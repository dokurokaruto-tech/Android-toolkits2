(function () {
  "use strict";

  var PORTRAIT_REF_ASPECT = 0.72;
  var MAX_PORTRAIT_FOV = 72;
  var DEFAULT_FOV = 50;
  var MAX_PIXEL_RATIO = 2;
  var MAX_FRAME_DELTA_MS = 100;
  var FALLBACK_TICK_MS = 66;
  var PACED_TIMER_THRESHOLD_MS = 20;
  var FPS60_MIN_GAP_MS = 14;
  var CLEAR_BLACK = 0x000000;
  var THUMB_SIZE_PX = 512;
  var THUMB_WARMUP_STEPS = 12;
  var THUMB_STEP_MS = 120;
  var THUMB_JPEG_QUALITY = 0.9;
  var AUTO_ORBIT_RAD_PER_MS = 0.00045;

  var IS_CAPTURE_MODE = Boolean(
    typeof window !== "undefined" &&
      window.location &&
      typeof window.location.search === "string" &&
      window.location.search.indexOf("capture=1") !== -1
  );

  var SCRIPT_ARG_NAMES =
    "player,renderer,scene,camera," +
    "init,start,stop,keydown,keyup,mousedown,mouseup,mousemove," +
    "touchstart,touchend,touchmove,update";

  var SCRIPT_RETURN_EXPR =
    "\nreturn { init: init, start: start, stop: stop, " +
    "keydown: keydown, keyup: keyup, mousedown: mousedown, mouseup: mouseup, " +
    "mousemove: mousemove, touchstart: touchstart, touchend: touchend, " +
    "touchmove: touchmove, update: update };";

  var TONE_MAPPINGS = [
    THREE.NoToneMapping,
    THREE.LinearToneMapping,
    THREE.ReinhardToneMapping,
    THREE.CineonToneMapping,
    THREE.ACESFilmicToneMapping
  ];

  var SHADOW_TYPES = [
    THREE.BasicShadowMap,
    THREE.PCFShadowMap,
    THREE.PCFSoftShadowMap,
    THREE.VSMShadowMap
  ];

  var renderer = null;
  var rendererAttrKey = "";
  var scene = null;
  var camera = null;
  var lastProjectConfig = null;
  var baseCameraFov = DEFAULT_FOV;
  var activeEvents = createEmptyEvents();
  var isRunning = false;
  var prevTime = 0;
  var lastRenderAtMs = -1000;
  var elapsedMs = 0;
  var loadToken = 0;
  var rafId = null;
  var fallbackTimerId = null;
  var activeWorkId = null;
  var fixedViewportOverride = null;

  var powerConfig = {
    frameIntervalMs: 0,
    loopMode: "continuous",
    maxPixelRatio: MAX_PIXEL_RATIO,
    antialias: true,
    shadowMode: "project",
    powerPref: "high-performance"
  };

  function createEmptyEvents() {
    return {
      init: [],
      start: [],
      stop: [],
      keydown: [],
      keyup: [],
      mousedown: [],
      mouseup: [],
      mousemove: [],
      touchstart: [],
      touchend: [],
      touchmove: [],
      update: []
    };
  }

  function computeEffectiveFov(rawFov, aspect) {
    if (!(aspect > 0) || aspect >= PORTRAIT_REF_ASPECT) {
      return rawFov;
    }
    var halfRad = THREE.MathUtils.degToRad(rawFov * 0.5);
    var scaledRad = Math.atan((Math.tan(halfRad) * PORTRAIT_REF_ASPECT) / aspect);
    var adjusted = THREE.MathUtils.radToDeg(scaledRad * 2);
    return Math.min(adjusted, Math.max(rawFov, MAX_PORTRAIT_FOV));
  }

  function computeEffectivePixelRatio() {
    if (IS_CAPTURE_MODE) {
      return 1;
    }
    var cap = Math.max(1, Math.min(powerConfig.maxPixelRatio || 1, MAX_PIXEL_RATIO));
    return Math.min(window.devicePixelRatio || 1, cap);
  }

  function desiredRendererAttrKey() {
    if (IS_CAPTURE_MODE) {
      return "capture";
    }
    return String(Boolean(powerConfig.antialias)) + ":" + String(powerConfig.powerPref || "default");
  }

  function createRenderer() {
    if (renderer && renderer.domElement && renderer.domElement.parentNode) {
      renderer.domElement.parentNode.removeChild(renderer.domElement);
    }
    if (renderer && typeof renderer.dispose === "function") {
      try {
        renderer.dispose();
      } catch (_err) {
      }
    }

    var useAntialias = IS_CAPTURE_MODE ? true : Boolean(powerConfig.antialias);
    var pref = IS_CAPTURE_MODE ? "high-performance" : String(powerConfig.powerPref || "default");
    renderer = new THREE.WebGLRenderer({
      antialias: useAntialias,
      preserveDrawingBuffer: IS_CAPTURE_MODE,
      powerPreference: pref
    });
    rendererAttrKey = desiredRendererAttrKey();
    renderer.setPixelRatio(computeEffectivePixelRatio());
    renderer.outputEncoding = THREE.sRGBEncoding;
    renderer.domElement.addEventListener(
      "webglcontextlost",
      function (event) {
        if (event && typeof event.preventDefault === "function") {
          event.preventDefault();
        }
        pauseActiveWork();
      },
      false
    );
    document.body.appendChild(renderer.domElement);
    applyViewportSize();
  }

  function ensureRenderer() {
    if (!isGlContextHealthy() || rendererAttrKey !== desiredRendererAttrKey()) {
      createRenderer();
    }
  }

  function isGlContextHealthy() {
    if (!renderer || typeof renderer.getContext !== "function") {
      return false;
    }
    var gl = renderer.getContext();
    if (!gl) {
      return false;
    }
    if (typeof gl.isContextLost === "function" && gl.isContextLost()) {
      return false;
    }
    return true;
  }

  function installPmremClearGuard() {
    if (
      !THREE.PMREMGenerator ||
      !THREE.PMREMGenerator.prototype ||
      THREE.PMREMGenerator.prototype.__rgbeClearGuarded
    ) {
      return;
    }
    var proto = THREE.PMREMGenerator.prototype;
    var origFromScene = proto.fromScene;
    if (typeof origFromScene !== "function") {
      return;
    }
    proto.fromScene = function (envScene) {
      var r = this._renderer;
      var needsZeroRgbeClear = Boolean(
        r &&
          envScene &&
          !envScene.background &&
          typeof r.getClearColor === "function" &&
          typeof r.getClearAlpha === "function" &&
          typeof r.setClearColor === "function"
      );
      if (!needsZeroRgbeClear) {
        return origFromScene.apply(this, arguments);
      }
      var savedColor = r.getClearColor().clone();
      var savedAlpha = r.getClearAlpha();
      r.setClearColor(CLEAR_BLACK, 0);
      try {
        return origFromScene.apply(this, arguments);
      } finally {
        r.setClearColor(savedColor, savedAlpha);
      }
    };
    proto.__rgbeClearGuarded = true;
  }

  function resetRendererState() {
    if (!renderer) {
      return;
    }
    renderer.shadowMap.enabled = false;
    renderer.shadowMap.type = THREE.PCFShadowMap;
    renderer.toneMapping = THREE.NoToneMapping;
    renderer.toneMappingExposure = 1.0;
    renderer.physicallyCorrectLights = false;
    renderer.outputEncoding = THREE.sRGBEncoding;
    renderer.setClearColor(CLEAR_BLACK, 1);
  }

  function applyShadowPolicy(project) {
    if (!renderer) {
      return;
    }
    if (!IS_CAPTURE_MODE && powerConfig.shadowMode === "off") {
      renderer.shadowMap.enabled = false;
      return;
    }
    var projectWantsShadows = Boolean(project && project.shadows);
    renderer.shadowMap.enabled = projectWantsShadows;
    if (!projectWantsShadows) {
      return;
    }
    if (!IS_CAPTURE_MODE && powerConfig.shadowMode === "basic") {
      renderer.shadowMap.type = THREE.BasicShadowMap;
      return;
    }
    if (project && project.shadowType !== undefined && SHADOW_TYPES[project.shadowType] !== undefined) {
      renderer.shadowMap.type = SHADOW_TYPES[project.shadowType];
    }
  }

  function applyProjectConfig(project) {
    lastProjectConfig = project || null;
    resetRendererState();
    if (!renderer) {
      return;
    }
    applyShadowPolicy(project);
    if (!project) {
      return;
    }
    if (project.toneMapping !== undefined && TONE_MAPPINGS[project.toneMapping] !== undefined) {
      renderer.toneMapping = TONE_MAPPINGS[project.toneMapping];
    }
    if (project.toneMappingExposure !== undefined) {
      renderer.toneMappingExposure = project.toneMappingExposure;
    }
    if (project.physicallyCorrectLights !== undefined) {
      renderer.physicallyCorrectLights = Boolean(project.physicallyCorrectLights);
    }
  }

  function disposeMaterialTextures(material) {
    var keys = Object.keys(material);
    for (var i = 0; i < keys.length; i++) {
      var value = material[keys[i]];
      if (value && value.isTexture && typeof value.dispose === "function") {
        value.dispose();
      }
    }
  }

  function disposeMaterial(material) {
    if (!material) {
      return;
    }
    if (Array.isArray(material)) {
      for (var i = 0; i < material.length; i++) {
        disposeMaterial(material[i]);
      }
      return;
    }
    disposeMaterialTextures(material);
    if (typeof material.dispose === "function") {
      material.dispose();
    }
  }

  function disposeSceneGraph(targetScene) {
    if (!targetScene) {
      return;
    }

    targetScene.traverse(function (object) {
      if (object.geometry && typeof object.geometry.dispose === "function") {
        object.geometry.dispose();
      }
      if (object.material) {
        disposeMaterial(object.material);
      }
    });

    if (targetScene.background && targetScene.background.isTexture) {
      targetScene.background.dispose();
    }
    if (targetScene.environment && targetScene.environment.isTexture) {
      targetScene.environment.dispose();
    }
  }

  function isUploadableTexture(tex) {
    if (!tex || !tex.isTexture || tex.isRenderTargetTexture) {
      return false;
    }
    if (tex.isDataTexture || tex.isCompressedTexture || tex.isCanvasTexture) {
      return true;
    }
    var img = tex.image;
    if (!img) {
      return false;
    }
    return Boolean(img.src || img.data || img.tagName || img.nodeName);
  }

  function refreshMaterial(mat) {
    if (!mat) {
      return;
    }

    if (mat.envMap && mat.envMap.isWebGLCubeRenderTarget) {
      var cubeTex = mat.envMap.texture;
      cubeTex.mapping = THREE.CubeReflectionMapping;
      mat.envMap = cubeTex;
    }

    var keys = Object.keys(mat);
    for (var i = 0; i < keys.length; i++) {
      var prop = mat[keys[i]];
      if (isUploadableTexture(prop)) {
        prop.needsUpdate = true;
      }
    }
    mat.needsUpdate = true;
  }

  function refreshSceneMaterials(targetScene) {
    if (!targetScene) {
      return;
    }
    targetScene.traverse(function (obj) {
      if (!obj.material) {
        return;
      }
      if (Array.isArray(obj.material)) {
        for (var i = 0; i < obj.material.length; i++) {
          refreshMaterial(obj.material[i]);
        }
      } else {
        refreshMaterial(obj.material);
      }
    });
  }

  function compileSceneScripts(scriptsMap, playerApiRef) {
    var events = createEmptyEvents();
    if (!scriptsMap || !scene) {
      return events;
    }

    var uuids = Object.keys(scriptsMap);
    for (var i = 0; i < uuids.length; i++) {
      var uuid = uuids[i];
      var targetObject = scene.getObjectByProperty("uuid", uuid, true);
      if (!targetObject) {
        continue;
      }

      var scriptList = scriptsMap[uuid];
      for (var j = 0; j < scriptList.length; j++) {
        var script = scriptList[j];
        try {
          var factory = new Function(SCRIPT_ARG_NAMES, script.source + SCRIPT_RETURN_EXPR);
          var handlers = factory.bind(targetObject)(playerApiRef, renderer, scene, camera);
          var handlerNames = Object.keys(handlers);
          for (var k = 0; k < handlerNames.length; k++) {
            var name = handlerNames[k];
            if (typeof handlers[name] === "function" && events[name] !== undefined) {
              events[name].push(handlers[name].bind(targetObject));
            }
          }
        } catch (err) {
          console.error("Three.js script compile error:", script.name || uuid, err);
        }
      }
    }
    return events;
  }

  function ensureFallbackLighting(targetScene) {
    var hasLight = false;
    targetScene.traverse(function (obj) {
      if (obj && obj.isLight) {
        hasLight = true;
      }
    });
    if (hasLight) {
      return;
    }
    var hemi = new THREE.HemisphereLight(0xdde8ff, 0x1a1c24, 0.95);
    hemi.position.set(0, 10, 0);
    targetScene.add(hemi);

    var keyLight = new THREE.DirectionalLight(0xffffff, 0.9);
    keyLight.position.set(5, 8, 6);
    targetScene.add(keyLight);
  }

  function buildAutoCameraForScene(targetScene) {
    var cam = new THREE.PerspectiveCamera(DEFAULT_FOV, 1, 0.05, 2000);
    var center = new THREE.Vector3(0, 0, 0);
    var radius = 2.5;

    if (THREE.Box3 && typeof THREE.Box3 === "function") {
      try {
        var box = new THREE.Box3().setFromObject(targetScene);
        if (!box.isEmpty()) {
          var sphere = new THREE.Sphere();
          box.getBoundingSphere(sphere);
          center.copy(sphere.center);
          if (sphere.radius > 0.001 && isFinite(sphere.radius)) {
            radius = sphere.radius;
          }
        }
      } catch (_err) {
      }
    }

    var dist = Math.max(radius * 2.5, 0.5);
    cam.near = Math.max(dist / 200, 0.01);
    cam.far = Math.max(dist * 25, 100);
    cam.position.set(center.x + dist * 0.65, center.y + dist * 0.42, center.z + dist);
    cam.lookAt(center);
    cam.userData.__orbitCenter = center;
    cam.userData.__orbitDist = dist;
    return cam;
  }

  function attachAutoOrbitIfIdle(events, cam) {
    if (!cam || events.update.length > 0) {
      return;
    }
    var center = (cam.userData && cam.userData.__orbitCenter) || new THREE.Vector3(0, 0, 0);
    var dist = (cam.userData && cam.userData.__orbitDist) || 4;
    events.update.push(function (ev) {
      var angle = (ev && ev.time ? ev.time : 0) * AUTO_ORBIT_RAD_PER_MS;
      cam.position.x = center.x + Math.sin(angle) * dist;
      cam.position.z = center.z + Math.cos(angle) * dist;
      cam.position.y = center.y + dist * 0.36;
      cam.lookAt(center);
    });
  }

  function findEmbeddedCamera(rootObj) {
    var found = null;
    if (!rootObj || typeof rootObj.traverse !== "function") {
      return null;
    }
    rootObj.traverse(function (child) {
      if (!found && child && child.isCamera) {
        found = child;
      }
    });
    return found;
  }

  function isGeometryJson(json) {
    if (!json || typeof json !== "object") {
      return false;
    }
    var metaType = json.metadata && json.metadata.type ? String(json.metadata.type).toLowerCase() : "";
    if (metaType === "buffergeometry" || metaType === "geometry") {
      return true;
    }
    return Boolean(json.data && json.data.attributes && !json.scene && !json.object);
  }

  function buildSceneFromGeometryJson(json) {
    var geoLoader = new THREE.BufferGeometryLoader();
    var geometry = geoLoader.parse(json);
    if (typeof geometry.computeBoundingSphere === "function") {
      geometry.computeBoundingSphere();
    }
    if (typeof geometry.center === "function") {
      geometry.center();
    }
    if (
      geometry.attributes &&
      !geometry.attributes.normal &&
      typeof geometry.computeVertexNormals === "function"
    ) {
      geometry.computeVertexNormals();
    }
    var mat = new THREE.MeshStandardMaterial({
      color: 0xd8dee9,
      roughness: 0.38,
      metalness: 0.18
    });
    var mesh = new THREE.Mesh(geometry, mat);
    var wrappedScene = new THREE.Scene();
    wrappedScene.background = new THREE.Color(0x0a0a0c);
    wrappedScene.add(mesh);
    ensureFallbackLighting(wrappedScene);
    return wrappedScene;
  }

  function parseAnyThreeWork(json, onParsed) {
    if (json && json.scene) {
      var appLoader = new THREE.ObjectLoader();
      var parsedCam = json.camera ? appLoader.parse(json.camera) : null;
      appLoader.parse(json.scene, function (loadedScene) {
        var resolvedCam = parsedCam || findEmbeddedCamera(loadedScene) || buildAutoCameraForScene(loadedScene);
        onParsed(loadedScene, resolvedCam, Boolean(json.camera));
      });
      return;
    }

    if (isGeometryJson(json) && THREE.BufferGeometryLoader) {
      var geoScene = buildSceneFromGeometryJson(json);
      var geoCam = buildAutoCameraForScene(geoScene);
      onParsed(geoScene, geoCam, false);
      return;
    }

    var objLoader = new THREE.ObjectLoader();
    objLoader.parse(json, function (parsedObj) {
      var targetScene = parsedObj;
      if (!parsedObj || !parsedObj.isScene) {
        targetScene = new THREE.Scene();
        targetScene.background = new THREE.Color(0x0a0a0c);
        if (parsedObj) {
          targetScene.add(parsedObj);
        }
      }
      ensureFallbackLighting(targetScene);
      var embeddedCam = findEmbeddedCamera(targetScene);
      var resolvedCam = embeddedCam || buildAutoCameraForScene(targetScene);
      onParsed(targetScene, resolvedCam, Boolean(embeddedCam));
    });
  }

  function dispatchHandlers(list, payload) {
    for (var i = 0; i < list.length; i++) {
      try {
        list[i](payload);
      } catch (err) {
        console.error("Three.js script runtime error:", err);
      }
    }
  }

  function applyViewportSize() {
    var width = fixedViewportOverride
      ? fixedViewportOverride.width
      : Math.max(window.innerWidth || 1, 1);
    var height = fixedViewportOverride
      ? fixedViewportOverride.height
      : Math.max(window.innerHeight || 1, 1);

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

  function clearLoopHandles() {
    if (rafId !== null) {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
    if (fallbackTimerId !== null) {
      clearTimeout(fallbackTimerId);
      fallbackTimerId = null;
    }
  }

  function scheduleNextTick() {
    if (!isRunning) {
      clearLoopHandles();
      return;
    }
    if (powerConfig.loopMode === "single_frame") {
      pauseActiveWork();
      return;
    }
    var targetInterval = Number(powerConfig.frameIntervalMs) || 0;
    if (targetInterval > PACED_TIMER_THRESHOLD_MS) {
      if (rafId !== null) {
        cancelAnimationFrame(rafId);
        rafId = null;
      }
      if (fallbackTimerId !== null) {
        clearTimeout(fallbackTimerId);
      }
      fallbackTimerId = setTimeout(onPacedTick, targetInterval);
      return;
    }
    if (rafId === null) {
      rafId = requestAnimationFrame(onAnimationTick);
    }
    if (fallbackTimerId === null) {
      fallbackTimerId = setTimeout(onFallbackTick, FALLBACK_TICK_MS);
    }
  }

  function renderSingleStep(now) {
    if (!renderer || !scene || !camera) {
      return;
    }

    var rawDelta = now - prevTime;
    prevTime = now;
    lastRenderAtMs = now;
    var delta = Math.min(Math.max(rawDelta, 0), MAX_FRAME_DELTA_MS);
    elapsedMs += delta;

    dispatchHandlers(activeEvents.update, { time: elapsedMs, delta: delta });
    renderer.render(scene, camera);
  }

  function onAnimationTick() {
    rafId = null;
    if (!isRunning || !renderer || !scene || !camera) {
      clearLoopHandles();
      return;
    }
    var now = performance.now();
    var targetInterval = Number(powerConfig.frameIntervalMs) || 0;
    if (targetInterval >= 16 && now - lastRenderAtMs < FPS60_MIN_GAP_MS) {
      scheduleNextTick();
      return;
    }
    renderSingleStep(now);
    scheduleNextTick();
  }

  function onPacedTick() {
    fallbackTimerId = null;
    if (!isRunning || !renderer || !scene || !camera) {
      clearLoopHandles();
      return;
    }
    renderSingleStep(performance.now());
    scheduleNextTick();
  }

  function onFallbackTick() {
    fallbackTimerId = null;
    if (!isRunning || !renderer || !scene || !camera) {
      clearLoopHandles();
      return;
    }
    var now = performance.now();
    if (now - lastRenderAtMs < FALLBACK_TICK_MS * 0.75) {
      fallbackTimerId = setTimeout(onFallbackTick, FALLBACK_TICK_MS);
      return;
    }
    if (rafId !== null) {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
    renderSingleStep(now);
    scheduleNextTick();
  }

  function pauseActiveWork() {
    isRunning = false;
    clearLoopHandles();
  }

  function resumeActiveWork() {
    if (!renderer || !scene || !camera) {
      return false;
    }
    applyViewportSize();
    isRunning = true;
    prevTime = performance.now();
    renderSingleStep(prevTime);
    scheduleNextTick();
    return true;
  }

  function stopActiveWork() {
    pauseActiveWork();
    dispatchHandlers(activeEvents.stop, {});

    activeEvents = createEmptyEvents();
    disposeSceneGraph(scene);
    scene = null;
    camera = null;
    lastProjectConfig = null;
    elapsedMs = 0;
  }

  function purgeEngineCaches() {
    if (THREE && THREE.Cache && typeof THREE.Cache.clear === "function") {
      try {
        THREE.Cache.clear();
      } catch (_err) {
      }
    }
    if (renderer) {
      if (renderer.renderLists && typeof renderer.renderLists.dispose === "function") {
        try {
          renderer.renderLists.dispose();
        } catch (_err2) {
        }
      }
      if (typeof renderer.clear === "function") {
        try {
          renderer.clear();
        } catch (_err3) {
        }
      }
    }
  }

  function prepareLoadedWork(json, loadedScene, resolvedCam, hadExplicitCam, playerApiRef, workTag) {
    scene = loadedScene;
    camera = resolvedCam;
    baseCameraFov = (camera && camera.fov) ? camera.fov : DEFAULT_FOV;
    activeWorkId = workTag || null;
    elapsedMs = 0;
    applyViewportSize();

    activeEvents = compileSceneScripts(json.scripts, playerApiRef);
    if (!hadExplicitCam) {
      attachAutoOrbitIfIdle(activeEvents, camera);
    }
    refreshSceneMaterials(scene);

    dispatchHandlers(activeEvents.init, {});
    refreshSceneMaterials(scene);
    prevTime = performance.now();
    dispatchHandlers(activeEvents.start, {});
  }

  function startLoadedWork(json, loadedScene, resolvedCam, hadExplicitCam, playerApiRef, workTag) {
    prepareLoadedWork(json, loadedScene, resolvedCam, hadExplicitCam, playerApiRef, workTag);
    isRunning = true;
    renderSingleStep(prevTime);
    scheduleNextTick();
  }

  function notifyThumbCaptured(workId, dataUrl) {
    if (
      window.AndroidThreeThumbBridge &&
      typeof window.AndroidThreeThumbBridge.onThumbCaptured === "function"
    ) {
      window.AndroidThreeThumbBridge.onThumbCaptured(workId, dataUrl);
    }
  }

  function notifyThumbFailed(workId, reason) {
    if (
      window.AndroidThreeThumbBridge &&
      typeof window.AndroidThreeThumbBridge.onThumbFailed === "function"
    ) {
      window.AndroidThreeThumbBridge.onThumbFailed(workId, String(reason || "error"));
    }
  }

  var playerApi = {
    setPowerProfile: function (rawCfg) {
      var cfg = typeof rawCfg === "string" ? JSON.parse(rawCfg) : rawCfg;
      if (!cfg || typeof cfg !== "object") {
        return;
      }
      if (cfg.frameIntervalMs !== undefined) {
        powerConfig.frameIntervalMs = Math.max(0, Number(cfg.frameIntervalMs) || 0);
      }
      if (cfg.loopMode === "single_frame" || cfg.loopMode === "continuous") {
        powerConfig.loopMode = cfg.loopMode;
      }
      if (cfg.maxPixelRatio !== undefined) {
        powerConfig.maxPixelRatio = Math.max(1, Math.min(Number(cfg.maxPixelRatio) || 1, MAX_PIXEL_RATIO));
      }
      if (cfg.antialias !== undefined) {
        powerConfig.antialias = Boolean(cfg.antialias);
      }
      if (cfg.shadowMode === "project" || cfg.shadowMode === "basic" || cfg.shadowMode === "off") {
        powerConfig.shadowMode = cfg.shadowMode;
      }
      if (typeof cfg.powerPref === "string" && cfg.powerPref.length > 0) {
        powerConfig.powerPref = cfg.powerPref;
      }

      if (renderer) {
        if (rendererAttrKey !== desiredRendererAttrKey()) {
          createRenderer();
          applyProjectConfig(lastProjectConfig);
          refreshSceneMaterials(scene);
          if (scene && camera) {
            renderer.render(scene, camera);
          }
        } else {
          renderer.setPixelRatio(computeEffectivePixelRatio());
          applyShadowPolicy(lastProjectConfig);
          applyViewportSize();
        }
      }

      if (scene && camera) {
        if (powerConfig.loopMode === "single_frame") {
          pauseActiveWork();
        } else if (isRunning) {
          scheduleNextTick();
        }
      }
    },

    loadWorkJson: function (jsonText, workTag) {
      var token = ++loadToken;
      fixedViewportOverride = null;
      activeWorkId = null;
      stopActiveWork();

      ensureRenderer();

      var json = typeof jsonText === "string" ? JSON.parse(jsonText) : jsonText;
      applyProjectConfig(json.project);

      parseAnyThreeWork(json, function (loadedScene, resolvedCam, hadExplicitCam) {
        if (token !== loadToken) {
          disposeSceneGraph(loadedScene);
          return;
        }
        startLoadedWork(json, loadedScene, resolvedCam, hadExplicitCam, playerApi, workTag);
      });
    },

    loadWork: function (workId) {
      var targetId = String(workId || "");
      var token = ++loadToken;
      var url = "works/" + encodeURIComponent(targetId) + ".json";
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
          playerApi.loadWorkJson(json, targetId);
        })
        .catch(function (err) {
          console.error("Failed to load Three.js work " + targetId + ":", err);
        });
    },

    activateWork: function (workId) {
      var targetId = String(workId || "");
      fixedViewportOverride = null;
      var wasHealthy = isGlContextHealthy() && rendererAttrKey === desiredRendererAttrKey();
      ensureRenderer();
      if (!wasHealthy) {
        activeWorkId = null;
      }
      if (targetId && targetId === activeWorkId && scene && camera) {
        resumeActiveWork();
        return;
      }
      playerApi.loadWork(targetId);
    },

    captureWorkThumbnail: function (workId) {
      var targetId = String(workId || "");
      var token = ++loadToken;
      stopActiveWork();
      fixedViewportOverride = { width: THUMB_SIZE_PX, height: THUMB_SIZE_PX };

      ensureRenderer();

      var url = "works/" + encodeURIComponent(targetId) + ".json";
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
          applyProjectConfig(json.project);
          parseAnyThreeWork(json, function (loadedScene, resolvedCam, hadExplicitCam) {
            if (token !== loadToken) {
              disposeSceneGraph(loadedScene);
              return;
            }
            try {
              prepareLoadedWork(
                json,
                loadedScene,
                resolvedCam,
                hadExplicitCam,
                playerApi,
                targetId
              );
              for (var step = 0; step < THUMB_WARMUP_STEPS; step++) {
                elapsedMs += THUMB_STEP_MS;
                dispatchHandlers(activeEvents.update, {
                  time: elapsedMs,
                  delta: THUMB_STEP_MS
                });
              }
              renderer.render(scene, camera);
              var dataUrl = renderer.domElement.toDataURL("image/jpeg", THUMB_JPEG_QUALITY);
              stopActiveWork();
              notifyThumbCaptured(targetId, dataUrl);
            } catch (captureErr) {
              stopActiveWork();
              notifyThumbFailed(targetId, captureErr);
            }
          });
        })
        .catch(function (err) {
          notifyThumbFailed(targetId, err);
        });
    },

    captureCurrentFrame: function () {
      if (!renderer || !scene || !camera) {
        return "";
      }
      renderer.render(scene, camera);
      return renderer.domElement.toDataURL("image/jpeg", THUMB_JPEG_QUALITY);
    },

    resume: function () {
      return resumeActiveWork();
    },

    pause: function () {
      pauseActiveWork();
    },

    purgeForSleep: function () {
      loadToken++;
      fixedViewportOverride = null;
      activeWorkId = null;
      stopActiveWork();
      purgeEngineCaches();
    },

    stop: function () {
      loadToken++;
      fixedViewportOverride = null;
      activeWorkId = null;
      stopActiveWork();
    },

    resize: function () {
      applyViewportSize();
    }
  };

  function initPlayer() {
    installPmremClearGuard();
    if (IS_CAPTURE_MODE) {
      createRenderer();
    }
    window.addEventListener("resize", applyViewportSize);
    window.ThreeWallpaperPlayer = playerApi;

    if (
      window.AndroidThreeBridge &&
      typeof window.AndroidThreeBridge.onPlayerReady === "function"
    ) {
      window.AndroidThreeBridge.onPlayerReady();
    }
    if (
      window.AndroidThreeThumbBridge &&
      typeof window.AndroidThreeThumbBridge.onCapturerReady === "function"
    ) {
      window.AndroidThreeThumbBridge.onCapturerReady();
    }
  }

  initPlayer();
})();
