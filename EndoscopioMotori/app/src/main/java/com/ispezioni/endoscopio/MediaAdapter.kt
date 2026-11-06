package com.ispezioni.endoscopio

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** Cella quadrata per la griglia. */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

class MediaAdapter(private val onClick: (MediaRepo.Item) -> Unit) :
    RecyclerView.Adapter<MediaAdapter.VH>() {

    private var items: List<MediaRepo.Item> = emptyList()
    private val cache = LruCache<String, Bitmap>(80)
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.thumb)
        val play: TextView = v.findViewById(R.id.play)
        val name: TextView = v.findViewById(R.id.name)
    }

    fun submit(list: List<MediaRepo.Item>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val key = item.uri.toString()
        holder.name.text = item.name
        holder.play.visibility = if (item.isVideo) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(item) }
        holder.img.tag = key

        val cached = cache.get(key)
        if (cached != null) {
            holder.img.setImageBitmap(cached)
            return
        }
        holder.img.setImageBitmap(null)
        val ctx = holder.itemView.context.applicationContext
        pool.execute {
            val bmp = MediaRepo.thumbnail(ctx, item, 320)
            if (bmp != null) {
                cache.put(key, bmp)
                main.post { if (holder.img.tag == key) holder.img.setImageBitmap(bmp) }
            }
        }
    }
}
