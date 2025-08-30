package com.example.parkingapp.ui.list

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.parkingapp.databinding.ItemParkingBinding

class ParkingAdapter(
    private val onClick: (ParkingRow) -> Unit,
    private val onLongClick: (ParkingRow) -> Unit
) : ListAdapter<ParkingRow, ParkingAdapter.VH>(Diff()) {

    class VH(val b: ItemParkingBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemParkingBinding.inflate(inflater, parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.b.tvStatusDot.text = item.statusDot
        holder.b.tvName.text = item.name
        holder.b.tvDistance.text = formatDistance(item.distanceMeters)
        holder.b.tvStatusLabel.text = item.statusLabel

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item); true }
    }

    private fun formatDistance(meters: Int): String =
        if (meters < 1000) "${meters} m" else String.format("%.1f km", meters / 1000f)

    private class Diff : DiffUtil.ItemCallback<ParkingRow>() {
        override fun areItemsTheSame(oldItem: ParkingRow, newItem: ParkingRow) =
            oldItem.placeId == newItem.placeId
        override fun areContentsTheSame(oldItem: ParkingRow, newItem: ParkingRow) =
            oldItem == newItem
    }
}
