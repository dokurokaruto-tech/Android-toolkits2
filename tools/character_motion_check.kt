package com.example.kennys_dokidoki_wallpaper

fun main() {
    check(HomeCharacter.fromSaved(null) == HomeCharacter.HITORI)
    check(HomeCharacter.fromSaved("unknown") == HomeCharacter.HITORI)
    HomeCharacter.entries.forEach { check(HomeCharacter.fromSaved(it.name) == it) }
    val original = BocchiPose(cubeSwing = 0.5f, glitchIntensity = 1f)
    check(HomeCharacter.HITORI.adaptPose(original) === original)
    BocchiExpression.entries.forEach { check(HomeCharacter.HITORI.faceAsset(it) == it.assetName) }

    val tapEngine = BocchiPhysicsEngine()
    tapEngine.onTapZone(HomeCharacter.KITA.tapZone(BocchiHitZone.HEAD_PANIC), 0.4f, 0.2f)
    check(tapEngine.step(0.016f).expression == BocchiExpression.WINK_JOY)
    check(
        BocchiLive2dPolicy.hitZone(0.5f, 0.8f, HomeCharacter.NIJIKA.rig) ==
            BocchiHitZone.GUITAR_STRUM
    )

    for (character in HomeCharacter.entries) {
        val engine = BocchiPhysicsEngine()
        var blinks = 0
        var hairMoves = 0
        var maxDrumLeft = 0f
        var maxDrumRight = 0f
        val expressions = mutableSetOf<BocchiExpression>()
        repeat(3600) {
            if (it % 90 == 0) {
                engine.onScroll(50f, 1080f)
            }
            val pose = character.adaptPose(engine.step(0.016f))
            expressions += pose.expression
            if (character != HomeCharacter.HITORI) {
                check(pose.glitchIntensity == 0f)
            }
            if (character == HomeCharacter.KITA || character == HomeCharacter.RYO) {
                check(pose.ahoge == 0f && pose.drumLeft == 0f && pose.drumRight == 0f)
            }
            if (pose.expression == BocchiExpression.BLINK_CLOSED) {
                blinks++
            }
            if (kotlin.math.abs(pose.hairSideMid) > 0.05f) {
                hairMoves++
            }
            maxDrumLeft = maxOf(maxDrumLeft, pose.drumLeft)
            maxDrumRight = maxOf(maxDrumRight, pose.drumRight)
            val body = FloatArray((BocchiLive2dPolicy.MESH_COLS + 1) * (BocchiLive2dPolicy.MESH_ROWS + 1) * 2)
            val face = FloatArray((BocchiLive2dPolicy.FACE_COLS + 1) * (BocchiLive2dPolicy.FACE_ROWS + 1) * 2)
            BocchiLive2dPolicy.fillBodyMesh(pose, 0f, 0f, 592f, 750f, body, character.rig)
            BocchiLive2dPolicy.fillFaceMesh(pose, 0f, 0f, 592f, 750f, face, character.rig)
            check(body.all { it.isFinite() } && face.all { it.isFinite() })
            val corner = BocchiLive2dPolicy.deformUv(character.rig.face.u0, character.rig.face.v0, pose, character.rig)
            check(kotlin.math.abs(face[0] - corner.first * 592f) < 0.001f)
            check(kotlin.math.abs(face[1] - corner.second * 750f) < 0.001f)
        }
        check(blinks > 0 && hairMoves > 0)
        if (character == HomeCharacter.NIJIKA) {
            check(maxDrumLeft > 0.3f && maxDrumRight > 0.3f)
        }
        println("ok ${character.name}: blink=$blinks hair=$hairMoves drums=$maxDrumLeft/$maxDrumRight")
    }
    println("PASS: selection, compatibility, head emote, drum hits, idle emotes, finite meshes (4x3600 frames)")
}
