(function () {
  "use strict";

  var PORTRAIT_REF_ASPECT = 0.72;
  var MAX_PORTRAIT_FOV = 72;
  var DEFAULT_FOV = 50;
  var MAX_PIXEL_RATIO = 2;
  var MAX_FRAME_DELTA_MS = 100;

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
  var activeWorkId = null;

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
    renderer.setClearColor(0x000000, 1);
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

  function compileSceneScripts(scriptsMap, playerApi) {
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
          var handlers = factory.bind(targetObject)(playerApi, renderer, scene, camera);
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

  function renderFrame() {
    if (!isRunning || !renderer || !scene || !camera) {
      return;
    }

    rafId = requestAnimationFrame(renderFrame);
    renderSingleStep(performance.now());
  }

  function pauseActiveWork() {
    if (rafId !== null) {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
    isRunning = false;
  }

  function resumeActiveWork() {
    if (!renderer || !scene || !camera) {
      return false;
    }
    applyViewportSize();
    prevTime = performance.now();
    renderSingleStep(prevTime);
    if (!isRunning) {
      isRunning = true;
      rafId = requestAnimationFrame(renderFrame);
    }
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

  function startLoadedWork(json, loadedScene, playerApi, workTag) {
    scene = loadedScene;
    activeWorkId = workTag || null;
    elapsedMs = 0;
    applyViewportSize();

    activeEvents = compileSceneScripts(json.scripts, playerApi);
    refreshSceneMaterials(scene);

    dispatchHandlers(activeEvents.init, {});
    refreshSceneMaterials(scene);
    prevTime = performance.now();
    dispatchHandlers(activeEvents.start, {});

    isRunning = true;
    renderSingleStep(prevTime);
    rafId = requestAnimationFrame(renderFrame);
  }

  var playerApi = {
    loadWorkJson: function (jsonText, workTag) {
      var token = ++loadToken;
      activeWorkId = null;
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
        startLoadedWork(json, loadedScene, playerApi, workTag);
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
      if (targetId && targetId === activeWorkId && scene && camera) {
        resumeActiveWork();
        return;
      }
      playerApi.loadWork(targetId);
    },

    pause: function () {
      pauseActiveWork();
    },

    stop: function () {
      loadToken++;
      activeWorkId = null;
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
