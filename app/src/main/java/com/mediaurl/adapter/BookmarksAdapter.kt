package com.mediaurl.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mediaurl.R
import com.mediaurl.model.BookmarkItem

class BookmarksAdapter(
    private var bookmarkList: List<BookmarkItem> = emptyList(),
    private val onItemClick: (BookmarkItem) -> Unit,
    private val onDeleteClick: (BookmarkItem) -> Unit
) : RecyclerView.Adapter<BookmarksAdapter.BookmarkViewHolder>() {

    fun updateList(newList: List<BookmarkItem>) {
        this.bookmarkList = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
        return BookmarkViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        val item = bookmarkList[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = bookmarkList.size

    inner class BookmarkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvBookmarkTitle)
        private val tvUrl: TextView = itemView.findViewById(R.id.tvBookmarkUrl)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteBookmark)

        fun bind(bookmark: BookmarkItem) {
            tvTitle.text = bookmark.title
            tvUrl.text = bookmark.url

            itemView.setOnClickListener {
                onItemClick(bookmark)
            }

            btnDelete.setOnClickListener {
                onDeleteClick(bookmark)
            }
        }
    }
}
