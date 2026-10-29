package com.example.scanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun Context.themeColor(attribute: Int): Int {
    val value = TypedValue().also { theme.resolveAttribute(attribute, it, true) }
    return if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, value.resourceId) else value.data
}

class DeviceAdapter : ListAdapter<ScanDevice, DeviceAdapter.Holder>(object : DiffUtil.ItemCallback<ScanDevice>() {
    override fun areItemsTheSame(oldItem: ScanDevice, newItem: ScanDevice) = oldItem.address == newItem.address
    override fun areContentsTheSame(oldItem: ScanDevice, newItem: ScanDevice) = oldItem == newItem
}) {
    private val expanded = mutableSetOf<String>()
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context)
    override fun onBindViewHolder(holder: Holder, position: Int) { holder.bind(getItem(position)) }
    fun clearExpansion() { expanded.clear() }
    fun refreshAge() { currentList.forEachIndexed { i, device -> if (device.address in expanded) notifyItemChanged(i) } }

    inner class Holder(private val context: Context) : RecyclerView.ViewHolder(LinearLayout(context)) {
        private val root = itemView as LinearLayout
        private fun label(size: Float, bold: Boolean = false) = TextView(context).apply {
            textSize = size
            if (bold) setTypeface(null, Typeface.BOLD)
        }
        private val name = label(16f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        private val signal = label(16f, true).apply { gravity = Gravity.END; typeface = Typeface.MONOSPACE }
        private val address = label(13f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        private val properties = label(12f)
        private val details = label(12f).apply { setTextIsSelectable(true); setPadding(0, context.dp(8), 0, context.dp(4)) }
        private fun tool(icon: Int, description: String) = ImageButton(context).apply {
            setImageResource(icon); contentDescription = description; tooltipText = description
            background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let { a ->
                try { a.getDrawable(0) } finally { a.recycle() }
            }
        }
        private val copy = com.google.android.material.button.MaterialButton(context, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Copy"; textSize = 12f; minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0); contentDescription = "Copy MAC address"; tooltipText = contentDescription
        }
        private val more = tool(android.R.drawable.arrow_down_float, "Device details")
        init {
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(context.dp(16), context.dp(12), context.dp(12), 0)
            root.layoutParams = RecyclerView.LayoutParams(-1, -2)
            val heading = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(12) })
                addView(signal, LinearLayout.LayoutParams(context.dp(96), -2))
            }
            val identity = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(address, LinearLayout.LayoutParams(0, -2, 1f))
                addView(copy, LinearLayout.LayoutParams(context.dp(64), context.dp(48)))
                addView(more, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
            }
            root.addView(heading); root.addView(identity); root.addView(properties); root.addView(details)
            root.addView(View(context).apply { setBackgroundColor(context.themeColor(com.google.android.material.R.attr.colorOutlineVariant)) },
                LinearLayout.LayoutParams(-1, context.dp(1)).apply { topMargin = context.dp(12) })
        }
        fun bind(device: ScanDevice) {
            name.text = device.name?.takeIf { it.isNotBlank() } ?: "Unnamed device"
            signal.text = "${device.rssi} dBm"
            signal.setTextColor(context.themeColor(if (device.rssi >= -65) com.google.android.material.R.attr.colorPrimary else android.R.attr.textColorPrimary))
            address.text = device.address
            val source = when {
                device.sources.size > 1 -> "BLE + Classic"
                ScanMode.BLE in device.sources -> "BLE advertisement"
                else -> "Classic discovery"
            }
            properties.text = "$source | ${device.bond}\n${device.deviceType}" +
                (device.connectable?.let { if (it) " | Connectable" else " | Non-connectable" } ?: "")
            val isExpanded = device.address in expanded
            more.setImageResource(if (isExpanded) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
            more.contentDescription = if (isExpanded) "Hide device details" else "Device details"
            details.visibility = if (isExpanded) View.VISIBLE else View.GONE
            details.text = buildString {
                append("Last seen: ${((SystemClock.elapsedRealtime() - device.seenAt) / 1000).coerceAtLeast(0)} s ago | Observations: ${device.observations}")
                device.txPower?.let { append("\nAdvertised TX power: $it dBm") }
                append(when {
                    device.sources.size > 1 -> "\nService UUIDs (advertised/cached): "
                    ScanMode.BLE in device.sources -> "\nAdvertised service UUIDs: "
                    else -> "\nCached service UUIDs: "
                })
                append(device.services.ifEmpty { listOf("Not reported") }.joinToString("\n"))
                if (ScanMode.BLE in device.sources) {
                    append("\nManufacturer data: ")
                    append(device.manufacturers.ifEmpty { listOf("Not reported") }.joinToString("\n"))
                }
            }
            more.setOnClickListener {
                if (!expanded.add(device.address)) expanded.remove(device.address)
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) notifyItemChanged(bindingAdapterPosition)
            }
            copy.setOnClickListener {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Bluetooth MAC", device.address))
                if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, "MAC address copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
