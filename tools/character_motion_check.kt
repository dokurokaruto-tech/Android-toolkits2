package com.example.kennys_dokidoki_wallpaper

fun main() {
    check(HomeCharacter.fromSaved(null) == HomeCharacter.HITORI)
    check(HomeCharacter.fromSaved("unknown") == HomeCharacter.HITORI)
    HomeCharacter.entries.forEach { check(HomeCharacter.fromSaved(it.name) == it) }
    val original = BocchiPose(cubeSwing = 0.5f, glitchIntensity = 1f)
    check(HomeCharacter.HITORI.adaptPose(original) === original)
    BocchiExpression.entries.forEach { expression ->
        val expected = when {
            expression.suffix == null -> null
            expression == BocchiExpression.BOCCHI_PANIC -> "live2d/bocchi/bocchi_face_panic.webp"
            else -> "live2d/bocchi/bocchi_face_${expression.suffix}.webp"
        }
        check(HomeCharacter.HITORI.faceAsset(expression) == expected)
    }

    // 喜多の頭タップは cheer 反応（PANIC スロット）になる
    val kitaEngine = BocchiPhysicsEngine()
    kitaEngine.onTapZone(HomeCharacter.KITA.tapZone(BocchiHitZone.HEAD_PANIC), 0.4f, 0.2f)
    check(kitaEngine.step(0.016f).expression == BocchiExpression.BOCCHI_PANIC)

    // 虹夏: ストローク→ドラムヒット変換とポニーテール
    val nijika = HomeCharacter.NIJIKA.adaptPose(BocchiPose(armStrum = -0.6f, hairSideTip = 0.4f))
    check(nijika.drumHitR == 0.6f && nijika.drumHitL == 0f && nijika.armStrum == 0f)
    check(kotlin.math.abs(nijika.ponySwing - 0.52f) < 0.0001f)

    // 全キャラで長時間回し、瞬き・新表情・有限な頂点・顔位置を確認する
    for (character in HomeCharacter.entries) {
        val engine = BocchiPhysicsEngine()
        var blinks = 0
        var hairMoves = 0
        var sawSinging = false
        var sawIdle = false
        var sawSurprise = false
        repeat(3600) {
            // 前半だけスクロールし続ける。後半は待機の仕草が出る余地を作る
            if (it < 900 && it % 90 == 0) {
                engine.onScroll(50f, 1080f)
            }
            if (it == 1200) {
                engine.onTapZone(BocchiHitZone.STAGE_BURST, 0.5f, 0.5f)
            }
            val pose = character.adaptPose(engine.step(0.016f))
            if (character != HomeCharacter.HITORI) {
                check(pose.glitchIntensity == 0f && pose.cubeSwing == 0f)
            }
            if (pose.expression == BocchiExpression.BLINK_CLOSED) {
                blinks++
            }
            if (kotlin.math.abs(pose.hairSideMid) > 0.05f) {
                hairMoves++
            }
            sawSinging = sawSinging || pose.expression == BocchiExpression.SINGING_OPEN
            sawIdle = sawIdle || pose.expression == BocchiExpression.IDLE_CHARM
            sawSurprise = sawSurprise || pose.expression == BocchiExpression.SURPRISED
            val body = FloatArray((BocchiLive2dPolicy.MESH_COLS + 1) * (BocchiLive2dPolicy.MESH_ROWS + 1) * 2)
            val face = FloatArray((BocchiLive2dPolicy.FACE_COLS + 1) * (BocchiLive2dPolicy.FACE_ROWS + 1) * 2)
            BocchiLive2dPolicy.fillBodyMesh(pose, 0f, 0f, 592f, 750f, body)
            BocchiLive2dPolicy.fillFaceMesh(pose, 0f, 0f, 592f, 750f, face)
            check(body.all { v -> v.isFinite() } && face.all { v -> v.isFinite() })
            val corner = BocchiLive2dPolicy.deformUv(BocchiLive2dPolicy.FACE_U0, BocchiLive2dPolicy.FACE_V0, pose)
            check(kotlin.math.abs(face[0] - corner.first * 592f) < 0.001f)
            check(kotlin.math.abs(face[1] - corner.second * 750f) < 0.001f)
        }
        check(blinks > 0 && hairMoves > 0 && sawSinging && sawIdle && sawSurprise)
        println("PASS: $character blink=$blinks hair=$hairMoves (3600 frames)")
    }
    println("PASS: selection, compatibility, head taps, expressions, finite meshes, face alignment")
}
