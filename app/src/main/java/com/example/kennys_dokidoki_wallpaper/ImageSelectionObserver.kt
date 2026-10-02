package com.example.kennys_dokidoki_wallpaper

import androidx.recyclerview.widget.RecyclerView

/** 共有リストを変更してnotifyする既存画面でも、選択を同期する。 */
class ImageSelectionObserver(private val reconcile: () -> Unit) : RecyclerView.AdapterDataObserver() {
    override fun onChanged() = reconcile()

    override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = reconcile()

    override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = reconcile()

    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = reconcile()

    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = reconcile()

    override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = reconcile()
}
