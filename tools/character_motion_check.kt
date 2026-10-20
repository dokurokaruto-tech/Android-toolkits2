package com.example.kennys_dokidoki_wallpaper

fun main() {
    check(HomeCharacter.fromSaved(null) == HomeCharacter.HITORI)
    check(HomeCharacter.fromSaved("unknown") == HomeCharacter.HITORI)
    HomeCharacter.entries.forEach { check(HomeCharacter.fromSaved(it.name) == it) }
    val original = BocchiPose(cubeSwing = 0.5f, glitchIntensity = 1f)
    check(HomeCharacter.HITORI.adaptPose(original) === original)
    BocchiExpression.entries.forEach {
        val expected = it.key?.let { key -> "live2d/bocchi/bocchi_face_$key.webp" }
        check(HomeCharacter.HITORI.faceAsset(it) == expected)
    }
    val engine = BocchiPhysicsEngine()
    engine.onCharacterChange(HomeCharacter.KITA)
    engine.onTapZone(BocchiHitZone.HEAD_PANIC, 0.4f, 0.2f)
    check(HomeCharacter.KITA.adaptPose(engine.step(0.016f)).expression == BocchiExpression.WINK)
    var blinks = 0
    var hairMoves = 0
    repeat(3600) {
        if (it % 90 == 0) {
            engine.onScroll(50f, 1080f)
        }
        val pose = HomeCharacter.KITA.adaptPose(engine.step(0.016f))
        check(pose.glitchIntensity == 0f && pose.cubeSwing == 0f && pose.ahoge == 0f)
        check(pose.instrument == Instrument.GUITAR && pose.armLeft == 0f)
        if (pose.expression == BocchiExpression.BLINK_CLOSED) {
            blinks++
        }
        if (kotlin.math.abs(pose.hairSideMid) > 0.05f) {
            hairMoves++
        }
        val body = FloatArray((BocchiLive2dPolicy.MESH_COLS + 1) * (BocchiLive2dPolicy.MESH_ROWS + 1) * 2)
        val face = FloatArray((BocchiLive2dPolicy.FACE_COLS + 1) * (BocchiLive2dPolicy.FACE_ROWS + 1) * 2)
        BocchiLive2dPolicy.fillBodyMesh(pose, 0f, 0f, 592f, 750f, body)
        BocchiLive2dPolicy.fillFaceMesh(pose, 0f, 0f, 592f, 750f, face)
        check(body.all { it.isFinite() } && face.all { it.isFinite() })
        val corner = BocchiLive2dPolicy.deformUv(BocchiLive2dPolicy.FACE_U0, BocchiLive2dPolicy.FACE_V0, pose)
        check(kotlin.math.abs(face[0] - corner.first * 592f) < 0.001f)
        check(kotlin.math.abs(face[1] - corner.second * 750f) < 0.001f)
    }
    check(blinks > 0 && hairMoves > 0)
    println("PASS: selection, compatibility, Kita tap, blink, hair, finite body/face meshes, face alignment (3600 frames)")
}
