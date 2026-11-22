package dev.aegis.browser.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import dev.aegis.browser.R

/** 搜索建议：整行白底（深色为深底），左侧放大镜、中间粗体标题、右侧 ↗，底部细分隔线 */
class SuggestionsAdapter(
    private val context: Context,
    private val items: List<String>,
    private val dark: Boolean
) : BaseAdapter() {

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val row = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_suggestion, parent, false)
        val title = row.findViewById<TextView>(R.id.suggestion_title)
        title.text = items[position]
        title.setTextColor(if (dark) 0xFFE6E9ED.toInt() else 0xFF14171A.toInt())
        val tint = if (dark) 0xFF767C85.toInt() else 0xFF8A9099.toInt()
        row.findViewById<ImageView>(R.id.suggestion_icon).setColorFilter(tint)
        row.findViewById<ImageView>(R.id.suggestion_arrow).setColorFilter(tint)
        row.findViewById<View>(R.id.suggestion_divider)
            .setBackgroundColor(if (dark) 0xFF23272D.toInt() else 0xFFEFF1F3.toInt())
        row.setBackgroundColor(if (dark) 0xFF0F1216.toInt() else 0xFFFFFFFF.toInt())
        return row
    }
}
