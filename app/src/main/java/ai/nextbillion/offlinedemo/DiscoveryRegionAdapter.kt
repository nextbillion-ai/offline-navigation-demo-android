package ai.nextbillion.offlinedemo

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import ai.nextbillion.navigation.core.offline.OfflineRegionListRow
import ai.nextbillion.navigation.core.offline.OfflineRegionProgress
import ai.nextbillion.offlinedemo.databinding.ItemDiscoveryRegionBinding

/**
 * Selectable result list shared by radius and along-route discovery.
 *
 * A result is eligible only when the joined catalog row contains both Route and Map packages.
 * Selection is stored by stable region id instead of adapter position because [AsyncListDiffer]
 * may reorder rows while calculating a catalog/result update on a background thread.
 */
class DiscoveryRegionAdapter(
    private val onSelectionChanged: (Set<Long>) -> Unit,
) : RecyclerView.Adapter<DiscoveryRegionAdapter.RegionViewHolder>() {
    private val rows = AsyncListDiffer(
        this,
        object : DiffUtil.ItemCallback<OfflineRegionListRow>() {
            override fun areItemsTheSame(
                oldItem: OfflineRegionListRow,
                newItem: OfflineRegionListRow,
            ): Boolean = oldItem.regionId == newItem.regionId

            override fun areContentsTheSame(
                oldItem: OfflineRegionListRow,
                newItem: OfflineRegionListRow,
            ): Boolean = oldItem == newItem
        },
    )
    private var progressByRegion: Map<Long, OfflineRegionProgress> = emptyMap()
    private val selectedIds = linkedSetOf<Long>()
    private var pendingRestoredSelection: Set<Long>? = null

    init {
        setHasStableIds(true)
    }

    fun submitRows(value: List<OfflineRegionListRow>) {
        // Never carry a selection into a new query when that region is no longer in the result.
        selectedIds.retainAll(value.mapTo(mutableSetOf()) { it.regionId })
        rows.submitList(value) {
            pendingRestoredSelection?.let {
                pendingRestoredSelection = null
                applyRestoredSelection(it)
            } ?: onSelectionChanged(selectedIds.toSet())
        }
    }

    fun submitProgress(value: List<OfflineRegionProgress>) {
        val previous = progressByRegion
        progressByRegion = value.associateBy { it.regionId }
        // Rebind only rows whose live state changed; do not invalidate a potentially large result.
        rows.currentList.forEachIndexed { index, row ->
            if (previous[row.regionId] != progressByRegion[row.regionId]) notifyItemChanged(index)
        }
    }

    fun selectAllOrClear() {
        val previous = selectedIds.toSet()
        val eligible = rows.currentList.filter { it.hasRoute && it.hasMap }
            .mapTo(linkedSetOf()) { it.regionId }
        if (eligible.isNotEmpty() && selectedIds.containsAll(eligible)) selectedIds.clear()
        else {
            selectedIds.clear()
            selectedIds.addAll(eligible)
        }
        rows.currentList.forEachIndexed { index, row ->
            if ((row.regionId in previous) != (row.regionId in selectedIds)) notifyItemChanged(index)
        }
        onSelectionChanged(selectedIds.toSet())
    }

    fun selectedRegionIds(): Set<Long> = selectedIds.toSet()

    fun restoreSelection(regionIds: Collection<Long>) {
        // submitList() is asynchronous. Defer restoration until its commit callback when needed.
        pendingRestoredSelection = regionIds.toSet()
        if (rows.currentList.isEmpty()) return
        pendingRestoredSelection = null
        applyRestoredSelection(regionIds)
    }

    private fun applyRestoredSelection(regionIds: Collection<Long>) {
        val eligibleIds = rows.currentList.filter { it.hasRoute && it.hasMap }
            .mapTo(mutableSetOf()) { it.regionId }
        selectedIds.clear()
        selectedIds.addAll(regionIds.filter { it in eligibleIds })
        rows.currentList.indices.forEach(::notifyItemChanged)
        onSelectionChanged(selectedIds.toSet())
    }

    fun allEligibleSelected(): Boolean {
        val eligible = rows.currentList.filter { it.hasRoute && it.hasMap }
        return eligible.isNotEmpty() && eligible.all { it.regionId in selectedIds }
    }

    override fun getItemId(position: Int): Long = rows.currentList[position].regionId
    override fun getItemCount(): Int = rows.currentList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RegionViewHolder =
        RegionViewHolder(
            ItemDiscoveryRegionBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
        )

    override fun onBindViewHolder(holder: RegionViewHolder, position: Int) {
        val row = rows.currentList[position]
        holder.bind(row, progressByRegion[row.regionId], row.regionId in selectedIds)
    }

    inner class RegionViewHolder(
        private val binding: ItemDiscoveryRegionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: OfflineRegionListRow, progress: OfflineRegionProgress?, selected: Boolean) {
            val eligible = row.hasRoute && row.hasMap
            binding.discoveryRegionCheck.isChecked = selected
            binding.discoveryRegionCheck.isEnabled = eligible
            binding.discoveryRegionName.text = binding.root.context.getString(
                R.string.region_name_format,
                row.displayName,
                row.regionId,
            )
            binding.discoveryRegionDetails.text = buildString {
                append(
                    listOfNotNull(row.country, row.adminL1, row.adminL2, row.adminL3)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" / "),
                )
                append("\nRoute ${formatBytes(row.routeSizeBytes)} · Map ${formatBytes(row.mapSizeBytes)}")
                if (!eligible) append(" · Combined download unavailable")
                progress?.let {
                    append("\n${it.overallState} · ${it.overallPercent.coerceIn(0, 100)}%")
                }
            }
            binding.discoveryRegionProgress.progress = progress?.overallPercent?.coerceIn(0, 100) ?: 0
            binding.root.alpha = if (eligible) 1f else 0.58f
            binding.root.setOnClickListener {
                if (!eligible) return@setOnClickListener
                if (!selectedIds.add(row.regionId)) selectedIds.remove(row.regionId)
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::notifyItemChanged)
                onSelectionChanged(selectedIds.toSet())
            }
        }

        private fun formatBytes(bytes: Long?): String = bytes?.takeIf { it >= 0L }?.let {
            Formatter.formatFileSize(binding.root.context, it)
        } ?: "—"
    }
}
