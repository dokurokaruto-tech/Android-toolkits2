(function () {
  "use strict";

  var PORTRAIT_REF_ASPECT = 0.72;
  var MAX_PORTRAIT_FOV = 72;
  var DEFAULT_FOV = 50;
  var MAX_PIXEL_RATIO = 2;
  var MAX_FRAME_DELTA_MS = 100;
  var FALLBACK_TICK_MS = 66;
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
  var scene = null;
  var camera = null;
  var baseCameraFov = DEFAULT_FOV;
  var activeEvents = createEmptyEvents();
  var isRunning = false;
  var prevTime = 0;
  var elapsedMs = 0;
  var loadToken = 0;
  var rafId = null;
  var fallbackTimerId = null;
  var activeWorkId = null;
  var fixedViewportOverride = null;

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

    renderer = new THREE.WebGLRenderer({
      antialias: true,
      preserveDrawingBuffer: IS_CAPTURE_MODE,
      powerPreference: "high-performance"
    });
    var dpr = IS_CAPTURE_MODE ? 1 : Math.min(window.devicePixelRatio || 1, MAX_PIXEL_RATIO);
    renderer.setPixelRatio(dpr);
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
    renderer.setClearColor(0x0a0a0c, 1);
  }

  function applyProjectConfig(project) {
    resetRendererState();
    if (!project || !renderer) {
      return;
    }

    if (project.shadows !== undefined) {
      renderer.shadowMap.enabled = Boolean(project.shadows);
    }
    if (project.shadowType !== undefined && SHADOW_TYPES[project.shadowType] !== undefined) {
      renderer.shadowMap.type = SHADOW_TYPES[project.shadowType];
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
            if (handlers[name] !== undefined && events[name] !== undefined) {
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
    clearLoopHandles();
    if (!isRunning) {
      return;
    }
    rafId = requestAnimationFrame(onAnimationTick);
    fallbackTimerId = setTimeout(onFallbackTick, FALLBACK_TICK_MS);
  }

  function renderSingleStep(now) {
    if (!renderer || !scene || !camera) {
      return;
    }

    var rawDelta = now - prevTime;
    prevTime = now;
    var delta = Math.min(Math.max(rawDelta, 0), MAX_FRAME_DELTA_MS);
    elapsedMs += delta;

    dispatchHandlers(activeEvents.update, { time: elapsedMs, delta: delta });
    renderer.render(scene, camera);
  }

  function onAnimationTick() {
    rafId = null;
    if (fallbackTimerId !== null) {
      clearTimeout(fallbackTimerId);
      fallbackTimerId = null;
    }
    if (!isRunning || !renderer || !scene || !camera) {
      return;
    }
    renderSingleStep(performance.now());
    scheduleNextTick();
  }

  function onFallbackTick() {
    fallbackTimerId = null;
    if (rafId !== null) {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
    if (!isRunning || !renderer || !scene || !camera) {
      return;
    }
    renderSingleStep(performance.now());
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
    elapsedMs = 0;
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
    loadWorkJson: function (jsonText, workTag) {
      var token = ++loadToken;
      fixedViewportOverride = null;
      activeWorkId = null;
      stopActiveWork();

      if (!isGlContextHealthy()) {
        createRenderer();
      }

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
      if (!isGlContextHealthy()) {
        activeWorkId = null;
        createRenderer();
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

      if (!isGlContextHealthy()) {
        createRenderer();
      }

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

    pause: function () {
      pauseActiveWork();
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
    createRenderer();
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
