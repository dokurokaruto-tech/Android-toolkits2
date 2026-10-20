package com.example.kennys_dokidoki_wallpaper

fun main() {
    check(HomeCharacter.fromSaved(null) == HomeCharacter.HITORI)
    check(HomeCharacter.fromSaved("unknown") == HomeCharacter.HITORI)
    HomeCharacter.entries.forEach { check(HomeCharacter.fromSaved(it.name) == it) }

    // 全キャラが自分の顔ボックスと手足を持つ
    val boxes = HomeCharacter.entries.map { CharacterRigs.of(it).faceBox }
    check(boxes.toSet().size == boxes.size)
    boxes.forEach { check(it.u1 > it.u0 && it.v1 > it.v0 && it.u1 <= 1f && it.v1 <= 1f) }

    var blinks = 0
    var hairMoves = 0
    val expressionsSeen = mutableSetOf<BocchiExpression>()

    for (character in HomeCharacter.entries) {
        val rig = CharacterRigs.of(character)
        val engine = BocchiPhysicsEngine(character.motion)
        var localBlinks = 0
        var localHair = 0
        var stickMotion = 0f
        val hits = mutableSetOf<DrumPad>()

        repeat(3600) { frame ->
            if (frame % 90 == 0) {
                engine.onScroll(50f, 1080f)
            }
            if (frame % 700 == 350) {
                engine.onTapZone(BocchiHitZone.HEAD_PANIC, 0.4f, 0.2f)
            }
            val pose = engine.step(0.016f)
            expressionsSeen += pose.expression
            if (pose.expression == BocchiExpression.BLINK_CLOSED) localBlinks++
            if (kotlin.math.abs(pose.hairSideMid) > 0.04f) localHair++
            stickMotion = maxOf(stickMotion, kotlin.math.abs(pose.stickArm))
            if (pose.drumHit != DrumPad.NONE) hits += pose.drumHit

            val body = FloatArray((BocchiLive2dPolicy.MESH_COLS + 1) * (BocchiLive2dPolicy.MESH_ROWS + 1) * 2)
            val face = FloatArray((BocchiLive2dPolicy.FACE_COLS + 1) * (BocchiLive2dPolicy.FACE_ROWS + 1) * 2)
            BocchiLive2dPolicy.fillBodyMesh(pose, rig, 0f, 0f, 592f, 750f, body)
            BocchiLive2dPolicy.fillFaceMesh(pose, rig, 0f, 0f, 592f, 750f, face)
            check(body.all { it.isFinite() } && face.all { it.isFinite() })

            // 頂点はリグの外周から大きく外れない
            for (index in body.indices step 2) {
                check(body[index] > -120f && body[index] < 720f)
                check(body[index + 1] > -80f && body[index + 1] < 830f)
            }
            val corner = BocchiLive2dPolicy.deformUv(rig.faceBox.u0, rig.faceBox.v0, pose, rig)
            check(kotlin.math.abs(face[0] - corner.first * 592f) < 0.01f)
            check(kotlin.math.abs(face[1] - corner.second * 750f) < 0.01f)

            // 顔パッチがボディからはみ出さない
            val box = rig.faceBox
            check(box.u0 >= 0f && box.v0 >= 0f && box.u1 <= 1f && box.v1 <= 1f)
        }

        check(localBlinks > 0) { "${character.name}: blink" }
        check(localHair > 0) { "${character.name}: hair" }
        if (character.motion.playsDrums) {
            check(stickMotion > 0.4f) { "${character.name}: stick" }
            check(hits.contains(DrumPad.SNARE) && hits.contains(DrumPad.KICK)) { "${character.name}: hits" }
        }
        blinks += localBlinks
        hairMoves += localHair
        println("PASS ${character.name}: blinks=$localBlinks hair=$localHair stick=${"%.2f".format(stickMotion)} hits=${hits.size}")
    }

    check(blinks > 0 && hairMoves > 0)
    check(expressionsSeen.size >= 4) { "expressions seen: $expressionsSeen" }
    println("PASS: 4 characters, 3600 frames each, expressions=$expressionsSeen")
}
