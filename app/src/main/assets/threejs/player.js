/*
 * three.js 壁紙プレイヤー。
 *
 * three.js Editor が書き出した JSON（scene / camera / scripts / project）を
 * 公式 APP.Player と同じ手順で読み込み、壁紙として描き続ける。
 *
 *   native ── ThreePlayer.start(id) ──> JSON 取得 ──> scene/camera 解析
 *        ──> scripts 実行（this=object）──> update ループ ──> renderer.render
 *
 * ジェスチャーは native 側が処理するので、ここではタッチを一切扱わない。
 * 旧版 three.js 表記の作品も動くよう、読み込み後に最小限の互換正規化を行う。
 */
(function () {
	'use strict';

	var SCRIPT_EVENTS = [ 'init', 'start', 'stop', 'keydown', 'keyup', 'pointerdown', 'pointerup', 'pointermove', 'update' ];

	var renderer = null;
	var loader = new THREE.ObjectLoader();
	var camera = null;
	var scene = null;
	var events = {};
	var startTime = 0;
	var prevTime = 0;
	var running = false;
	var firstFrameSent = false;
	var currentWorkId = null;

	function native() {
		return window.ThreeJsNative || null;
	}

	function reportError( message ) {
		if ( window.console && console.error ) {
			console.error( '[ThreePlayer] ' + message );
		}
		var bridge = native();
		if ( bridge && bridge.onError ) {
			bridge.onError( String( message ) );
		}
	}

	function createRenderer() {
		if ( renderer ) {
			return;
		}
		renderer = new THREE.WebGLRenderer( { antialias: true, powerPreference: 'low-power' } );
		renderer.setPixelRatio( window.devicePixelRatio || 1 );
		renderer.outputEncoding = THREE.sRGBEncoding;
		document.body.appendChild( renderer.domElement );
	}

	function applyProject( project ) {
		if ( ! project ) {
			return;
		}
		if ( project.shadows !== undefined ) renderer.shadowMap.enabled = project.shadows;
		if ( project.shadowType !== undefined ) renderer.shadowMap.type = project.shadowType;
		if ( project.toneMapping !== undefined ) renderer.toneMapping = project.toneMapping;
		if ( project.toneMappingExposure !== undefined ) renderer.toneMappingExposure = project.toneMappingExposure;
		if ( project.physicallyCorrectLights !== undefined ) renderer.physicallyCorrectLights = project.physicallyCorrectLights;
	}

	// 旧作品は envMap にレンダーターゲットそのものを入れる。新版は texture を要求する。
	function normalizeLegacyEnvMaps() {
		if ( ! scene ) {
			return;
		}
		scene.traverse( function ( object ) {
			var material = object.material;
			if ( ! material ) {
				return;
			}
			var materials = Array.isArray( material ) ? material : [ material ];
			materials.forEach( function ( m ) {
				var env = m.envMap;
				if ( env && env.isWebGLRenderTarget && env.texture ) {
					m.envMap = env.texture;
					m.needsUpdate = true;
				}
			} );
		} );
	}

	function resetEvents() {
		events = {};
		SCRIPT_EVENTS.forEach( function ( name ) {
			events[ name ] = [];
		} );
	}

	function runScripts( json ) {
		var scriptWrapParams = 'player,renderer,scene,camera';
		var scriptWrapResultObj = {};
		SCRIPT_EVENTS.forEach( function ( name ) {
			scriptWrapParams += ',' + name;
			scriptWrapResultObj[ name ] = name;
		} );
		var scriptWrapResult = JSON.stringify( scriptWrapResultObj ).replace( /"/g, '' );

		for ( var uuid in json.scripts ) {
			var object = scene.getObjectByProperty( 'uuid', uuid, true );
			if ( object === undefined ) {
				continue;
			}
			var scripts = json.scripts[ uuid ];
			for ( var i = 0; i < scripts.length; i ++ ) {
				var script = scripts[ i ];
				var functions = ( new Function( scriptWrapParams, script.source + '\nreturn ' + scriptWrapResult + ';' ).bind( object ) )( playerApi, renderer, scene, camera );
				for ( var name in functions ) {
					if ( functions[ name ] === undefined || events[ name ] === undefined ) {
						continue;
					}
					events[ name ].push( functions[ name ].bind( object ) );
				}
			}
		}
	}

	function dispatch( array, event ) {
		for ( var i = 0, l = array.length; i < l; i ++ ) {
			array[ i ]( event );
		}
	}

	function loadWork( json ) {
		stopLoop();
		createRenderer();
		applyProject( json.project );

		scene = loader.parse( json.scene );
		camera = loader.parse( json.camera );
		setSize( window.innerWidth, window.innerHeight );

		resetEvents();
		runScripts( json );
		normalizeLegacyEnvMaps();
		dispatch( events.init, undefined );
	}

	function setSize( width, height ) {
		if ( width <= 0 || height <= 0 ) {
			return;
		}
		if ( camera ) {
			camera.aspect = width / height;
			camera.updateProjectionMatrix();
		}
		if ( renderer ) {
			renderer.setSize( width, height );
		}
	}

	function animate() {
		var time = performance.now();
		try {
			dispatch( events.update, { time: time - startTime, delta: time - prevTime } );
		} catch ( e ) {
			reportError( e.message || e );
		}

		renderer.render( scene, camera );
		prevTime = time;

		if ( ! firstFrameSent ) {
			firstFrameSent = true;
			var bridge = native();
			if ( bridge && bridge.onFirstFrame ) {
				bridge.onFirstFrame( currentWorkId || '' );
			}
		}
	}

	function startLoop() {
		if ( running || ! renderer || ! scene ) {
			return;
		}
		running = true;
		startTime = prevTime = performance.now();
		dispatch( events.start, undefined );
		renderer.setAnimationLoop( animate );
	}

	function stopLoop() {
		if ( ! running ) {
			return;
		}
		running = false;
		if ( renderer ) {
			renderer.setAnimationLoop( null );
		}
		dispatch( events.stop, undefined );
	}

	function fetchWorkJson( id, onLoaded ) {
		var bridge = native();
		if ( bridge && bridge.workJson ) {
			try {
				onLoaded( JSON.parse( bridge.workJson( id ) ) );
			} catch ( e ) {
				reportError( 'workJson failed: ' + ( e.message || e ) );
			}
			return;
		}

		// ブラウザ単体テスト用フォールバック。
		fetch( 'works/' + id + '.json' )
			.then( function ( response ) { return response.json(); } )
			.then( onLoaded )
			.catch( function ( e ) { reportError( 'fetch failed: ' + ( e.message || e ) ); } );
	}

	function start( id ) {
		currentWorkId = id;
		firstFrameSent = false;
		fetchWorkJson( id, function ( json ) {
			try {
				loadWork( json );
				startLoop();
				var bridge = native();
				if ( bridge && bridge.onReady ) {
					bridge.onReady( id );
				}
			} catch ( e ) {
				reportError( 'load failed: ' + ( e.message || e ) );
			}
		} );
	}

	function dispose() {
		stopLoop();
		if ( renderer ) {
			renderer.dispose();
			if ( renderer.domElement && renderer.domElement.parentNode ) {
				renderer.domElement.parentNode.removeChild( renderer.domElement );
			}
		}
		renderer = null;
		scene = null;
		camera = null;
	}

	var playerApi = {
		start: start,
		setSize: setSize,
		pause: stopLoop,
		resume: startLoop,
		dispose: dispose,
		render: function ( time ) {
			dispatch( events.update, { time: time * 1000, delta: 0 } );
			if ( renderer && scene && camera ) {
				renderer.render( scene, camera );
			}
		}
	};

	window.ThreePlayer = playerApi;

	window.addEventListener( 'resize', function () {
		setSize( window.innerWidth, window.innerHeight );
	} );
})();
