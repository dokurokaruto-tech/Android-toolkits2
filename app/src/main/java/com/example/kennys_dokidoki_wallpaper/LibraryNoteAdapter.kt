package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textview.MaterialTextView

/** メールボックス風の一覧。各項目に表示するのは題名だけ。 */
class LibraryNoteAdapter(
    private val onPick: (LibraryNote) -> Unit
) : ListAdapter<LibraryNote, LibraryNoteAdapter.NoteHolder>(DIFF) {

    class NoteHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: MaterialTextView = view.findViewById(R.id.tv_library_note_title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_library_note, parent, false)
        return NoteHolder(view)
    }

    override fun onBindViewHolder(holder: NoteHolder, position: Int) {
        val note = getItem(position)
        holder.title.text = note.title
        holder.itemView.contentDescription = note.title
        holder.itemView.setOnClickListener { onPick(note) }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<LibraryNote>() {
            override fun areItemsTheSame(oldItem: LibraryNote, newItem: LibraryNote): Boolean =
                oldItem.kind == newItem.kind && oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: LibraryNote, newItem: LibraryNote): Boolean =
                oldItem == newItem
        }
    }
}
