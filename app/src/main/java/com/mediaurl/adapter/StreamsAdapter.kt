package com.mediaurl.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mediaurl.R
import com.mediaurl.manager.StreamExtractor
import com.mediaurl.model.DetectedStream

class StreamsAdapter(
    private var streamList: List<DetectedStream> = emptyList(),
    private val onSendToSupabase: ((DetectedStream) -> Unit)? = null,
    private val onItemClick: ((DetectedStream) -> Unit)? = null
) : RecyclerView.Adapter<StreamsAdapter.StreamViewHolder>() {

    fun updateList(newList: List<DetectedStream>) {
        this.streamList = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_detected_stream, parent, false)
        return StreamViewHolder(view)
    }

    override fun onBindViewHolder(holder: StreamViewHolder, position: Int) {
        val item = streamList[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = streamList.size

    inner class StreamViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFormatBadge: TextView = itemView.findViewById(R.id.tvFormatBadge)
        private val tvStreamDomain: TextView = itemView.findViewById(R.id.tvStreamDomain)
        private val tvTimeAgo: TextView = itemView.findViewById(R.id.tvTimeAgo)
        private val tvStreamUrl: TextView = itemView.findViewById(R.id.tvStreamUrl)
        private val btnCopyUrl: TextView = itemView.findViewById(R.id.btnCopyUrl)
        private val btnCopyCurl: TextView = itemView.findViewById(R.id.btnCopyCurl)
        private val btnSendToSupabase: TextView = itemView.findViewById(R.id.btnSendToSupabase)

        fun bind(stream: DetectedStream) {
            tvFormatBadge.text = stream.format
            tvStreamDomain.text = stream.domain
            tvTimeAgo.text = stream.formattedTime
            tvStreamUrl.text = stream.url

            btnCopyUrl.setOnClickListener {
                StreamExtractor.copyToClipboard(itemView.context, stream.url, "Stream URL")
            }

            btnCopyCurl.setOnClickListener {
                StreamExtractor.copyToClipboard(itemView.context, stream.curlCommand, "cURL Command")
            }

            btnSendToSupabase.setOnClickListener {
                onSendToSupabase?.invoke(stream)
            }

            itemView.setOnClickListener {
                onItemClick?.invoke(stream)
            }
        }
    }
}
