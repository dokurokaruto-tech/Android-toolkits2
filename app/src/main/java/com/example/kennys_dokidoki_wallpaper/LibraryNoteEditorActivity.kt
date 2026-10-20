package com.example.kennys_dokidoki_wallpaper

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** エッセイ／やりたい事リストの題名と本文を編集する Material 3 画面。 */
class LibraryNoteEditorActivity : AppCompatActivity() {

    private lateinit var kind: LibraryNoteKind
    private lateinit var noteId: String
    private lateinit var titleField: TextInputEditText
    private lateinit var bodyField: TextInputEditText
    private lateinit var titleLayout: TextInputLayout
    private var lastSavedTitle: String? = null
    private var lastSavedBody: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library_note_editor)

        kind = LibraryNoteKind.fromDriveKey(intent.getStringExtra(EXTRA_KIND)) ?: run {
            finish()
            return
        }
        noteId = intent.getStringExtra(EXTRA_ID) ?: LibraryNoteStore.newId()
        titleField = findViewById(R.id.et_library_note_title)
        bodyField = findViewById(R.id.et_library_note_body)
        titleLayout = findViewById(R.id.til_library_note_title)
        titleField.doAfterTextChanged { if (!it.isNullOrBlank()) titleLayout.error = null }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_library_note_editor)
        toolbar.title = kind.displayName
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener(::onToolbarMenuItemClick)
        findViewById<com.google.android.material.textview.MaterialTextView>(R.id.tv_library_note_type)
            .text = kind.displayName

        LibraryNoteStore.load(this, kind, noteId)?.let { note ->
            titleField.setText(note.title)
            bodyField.setText(note.body)
            lastSavedTitle = note.title
            lastSavedBody = note.body
        }
    }

    private fun onToolbarMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_library_note_save -> {
            saveAndClose()
            true
        }
        else -> false
    }

    private fun saveAndClose() {
        val title = titleField.text?.toString()?.trim().orEmpty()
        if (title.isBlank()) {
            titleLayout.error = "題名を入力してください。"
            titleField.requestFocus()
            return
        }
        titleLayout.error = null
        persist(title, bodyField.text?.toString().orEmpty())
        Toast.makeText(this, "保存しました。", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 別画面へ移る場合も、題名が付いた下書きはローカルに残す。 */
    override fun onPause() {
        if (::titleField.isInitialized && ::bodyField.isInitialized) {
            persistDraft()
        }
        super.onPause()
    }

    private fun persistDraft() {
        val title = titleField.text?.toString()?.trim().orEmpty()
        if (title.isBlank()) {
            return
        }
        persist(title, bodyField.text?.toString().orEmpty())
    }

    private fun persist(title: String, body: String) {
        if (title == lastSavedTitle && body == lastSavedBody) {
            return
        }
        LibraryNoteStore.save(this, LibraryNote(noteId, kind, title, body))
        lastSavedTitle = title
        lastSavedBody = body
        DiaryDriveSync.syncInBackground(this)
    }

    companion object {
        const val EXTRA_KIND = "LIBRARY_NOTE_KIND"
        const val EXTRA_ID = "LIBRARY_NOTE_ID"
    }
}
