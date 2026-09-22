package ai.nextbillion.offlinedemo

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import ai.nextbillion.navigation.core.offline.OfflineRegionListRow
import ai.nextbillion.navigation.core.offline.OfflineRegionProgress
import ai.nextbillion.navigation.core.offline.OfflineSubtaskProgress
import ai.nextbillion.navigation.core.offline.OfflineTaskState
import ai.nextbillion.offlinedemo.databinding.ItemRegionBinding
import ai.nextbillion.offlinedemo.databinding.ItemRegionGroupBinding

/**
 * Collapsible Admin-L1 -> child-region presentation for the flat SDK catalog.
 *
 * [AsyncListDiffer] computes large expand/search diffs off the main thread. Group stable IDs are
 * allocated from a negative sequence instead of hashing names, avoiding collisions between two
 * states with the same hash or with the SDK's positive region IDs.
 */
class RegionAdapter(
    private val onRowClick: (OfflineRegionListRow) -> Unit,
    private val onActionClick: (OfflineRegionListRow) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var sourceRows: List<OfflineRegionListRow> = emptyList()
    private val displayItems = AsyncListDiffer(
        this,
        object : DiffUtil.ItemCallback<DisplayItem>() {
            override fun areItemsTheSame(oldItem: DisplayItem, newItem: DisplayItem): Boolean =
                oldItem.identity == newItem.identity

            override fun areContentsTheSame(oldItem: DisplayItem, newItem: DisplayItem): Boolean =
                oldItem == newItem
        },
    )
    private var progressByRegion: Map<Long, OfflineRegionProgress> = emptyMap()
    private val expandedStateKeys = linkedSetOf<String>()
    private val stateStableIds = mutableMapOf<String, Long>()
    private var nextStateStableId = Long.MIN_VALUE
    private var forceExpandAll = false
    private var selectedRegionId: Long? = null

    init {
        setHasStableIds(true)
    }

    fun submitRows(value: List<OfflineRegionListRow>, expandAll: Boolean = false) {
        sourceRows = value
        forceExpandAll = expandAll
        if (selectedRegionId != null && value.none { it.regionId == selectedRegionId }) {
            selectedRegionId = null
        }
        rebuildDisplayItems()
    }

    fun restoreExpandedStateKeys(keys: Collection<String>) {
        expandedStateKeys.clear()
        expandedStateKeys.addAll(keys)
        rebuildDisplayItems()
    }

    fun expandedStateKeys(): ArrayList<String> = ArrayList(expandedStateKeys)

    fun clearExpandedStateKeys() {
        expandedStateKeys.clear()
        rebuildDisplayItems()
    }

    fun submitProgress(value: List<OfflineRegionProgress>) {
        val previous = progressByRegion
        progressByRegion = value.associateBy { it.regionId }
        displayItems.currentList.forEachIndexed { index, item ->
            val county = item as? DisplayItem.County ?: return@forEachIndexed
            if (previous[county.row.regionId] != progressByRegion[county.row.regionId]) {
                notifyItemChanged(index)
            }
        }
    }

    fun select(regionId: Long) {
        val previous = selectedRegionId
        if (previous == regionId) return
        selectedRegionId = regionId
        countyPosition(previous)?.let(::notifyItemChanged)
        countyPosition(regionId)?.let(::notifyItemChanged)
    }

    override fun getItemId(position: Int): Long = when (val item = displayItems.currentList[position]) {
        is DisplayItem.State -> stateStableIds.getOrPut(item.key) { nextStateStableId++ }
        is DisplayItem.County -> item.row.regionId
    }

    override fun getItemViewType(position: Int): Int = when (displayItems.currentList[position]) {
        is DisplayItem.State -> VIEW_TYPE_STATE
        is DisplayItem.County -> VIEW_TYPE_COUNTY
    }

    override fun getItemCount(): Int = displayItems.currentList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            VIEW_TYPE_STATE -> StateViewHolder(
                ItemRegionGroupBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                ),
            )
            else -> RegionViewHolder(
                ItemRegionBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                ),
            )
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = displayItems.currentList[position]) {
            is DisplayItem.State -> (holder as StateViewHolder).bind(item)
            is DisplayItem.County -> (holder as RegionViewHolder).bind(
                item.row,
                progressByRegion[item.row.regionId],
                selectedRegionId == item.row.regionId,
            )
        }
    }

    private fun rebuildDisplayItems() {
        // linkedMapOf preserves the catalog's pre-sorted state order. Search mode expands matching
        // children temporarily without modifying the user's saved expansion choices.
        val groups = sourceRows.groupByTo(linkedMapOf(), ::stateKey)
        val next = buildList {
            groups.forEach { (key, counties) ->
                val expanded = forceExpandAll || key in expandedStateKeys
                add(
                    DisplayItem.State(
                        key = key,
                        title = normalizedState(counties.firstOrNull()?.adminL1),
                        countyCount = counties.size,
                        expanded = expanded,
                        collapsible = !forceExpandAll,
                    ),
                )
                if (expanded) counties.forEach { add(DisplayItem.County(it)) }
            }
        }
        displayItems.submitList(next)
    }

    private fun toggleState(item: DisplayItem.State) {
        if (!expandedStateKeys.add(item.key)) expandedStateKeys.remove(item.key)
        rebuildDisplayItems()
    }

    private fun countyPosition(regionId: Long?): Int? = regionId?.let { id ->
        displayItems.currentList.indexOfFirst { item ->
            item is DisplayItem.County && item.row.regionId == id
        }.takeIf { it >= 0 }
    }

    private inner class StateViewHolder(private val binding: ItemRegionGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DisplayItem.State) {
            val resources = binding.root.resources
            binding.regionGroupName.text = item.title
            binding.regionGroupCount.text = resources.getQuantityString(
                R.plurals.region_child_count,
                item.countyCount,
                item.countyCount,
            )
            binding.regionGroupChevron.setText(
                if (item.expanded) R.string.group_expanded_indicator
                else R.string.group_collapsed_indicator,
            )
            binding.root.contentDescription = if (item.collapsible) {
                resources.getString(
                    if (item.expanded) R.string.collapse_state else R.string.expand_state,
                    item.title,
                )
            } else {
                item.title
            }
            binding.root.isClickable = item.collapsible
            binding.root.setOnClickListener(if (item.collapsible) ({ toggleState(item) }) else null)
        }
    }

    private inner class RegionViewHolder(private val binding: ItemRegionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: OfflineRegionListRow, progress: OfflineRegionProgress?, selected: Boolean) {
            binding.root.isActivated = selected
            binding.regionName.text = binding.root.context.getString(
                R.string.region_name_format,
                row.displayName,
                row.regionId,
            )
            val route = row.routeRegion
            val map = row.mapRegion
            val combinedSize = DownloadSizeMath.sumKnown(row.routeSizeBytes, row.mapSizeBytes)
            binding.regionMetadata.text = buildString {
                append(
                    listOfNotNull(row.adminL2, row.adminL3)
                        .filter { it.isNotBlank() && !it.equals(row.displayName, ignoreCase = true) }
                        .distinct()
                        .joinToString(" / "),
                )
                if (isNotEmpty()) append('\n')
                append("Route ${formatBytes(binding.root.context, row.routeSizeBytes)}")
                append(" · Map ${formatBytes(binding.root.context, row.mapSizeBytes)}")
                append(" · Total ${formatBytes(binding.root.context, combinedSize)}")
            }
            val routeProgress = progress?.routeNetwork
            val mapProgress = progress?.mapTile
            val routePercent = routeProgress?.safePercent ?: catalogRoutePercent(row)
            val mapPercent = mapProgress?.safePercent ?: 0
            binding.routeProgress.progress = routePercent
            binding.mapProgress.progress = mapPercent
            binding.regionState.text = buildString {
                append("Route ${stateLabel(routeProgress, route?.downloadStatus?.name)} $routePercent%")
                append(" · Map ${stateLabel(mapProgress, if (map != null) "NOT_STARTED" else null)} $mapPercent%")
                if (route?.updateAvailable == true) append(" · UPDATE AVAILABLE")
            }
            val action = actionFor(row, progress)
            binding.regionActionButton.setText(action.label)
            binding.regionActionButton.isEnabled = action.enabled
            binding.regionActionButton.setOnClickListener { onActionClick(row) }
            binding.root.setOnClickListener { onRowClick(row) }
        }
    }

    private fun catalogRoutePercent(row: OfflineRegionListRow): Int {
        val route = row.routeRegion ?: return 0
        if (route.tilesTotal <= 0L) return if (route.isDownloaded) 100 else 0
        return ((route.tilesDone.toDouble() / route.tilesTotal.toDouble()) * 100.0)
            .toInt().coerceIn(0, 100)
    }

    private fun stateLabel(progress: OfflineSubtaskProgress?, fallback: String?): String =
        progress?.state?.name ?: fallback ?: "UNAVAILABLE"

    private fun actionFor(
        row: OfflineRegionListRow,
        progress: OfflineRegionProgress?,
    ): RegionAction {
        if (!row.hasRoute || !row.hasMap) return RegionAction(R.string.unavailable, false)
        return when (progress?.overallState) {
            OfflineTaskState.QUEUED,
            OfflineTaskState.RUNNING,
            -> RegionAction(R.string.pause, true)
            OfflineTaskState.PAUSED,
            OfflineTaskState.PARTIAL,
            -> RegionAction(R.string.resume, true)
            OfflineTaskState.FAILED -> RegionAction(R.string.retry, true)
            OfflineTaskState.COMPLETED -> RegionAction(R.string.view_details, true)
            else -> RegionAction(R.string.download, true)
        }
    }

    private fun stateKey(row: OfflineRegionListRow): String =
        "${row.country.orEmpty().trim()}\u0000${normalizedState(row.adminL1)}"

    private fun normalizedState(value: String?): String =
        value?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown state"

    private fun formatBytes(context: Context, bytes: Long?): String =
        bytes?.takeIf { it >= 0L }?.let { android.text.format.Formatter.formatFileSize(context, it) }
            ?: "—"

    private sealed interface DisplayItem {
        val identity: String

        data class State(
            val key: String,
            val title: String,
            val countyCount: Int,
            val expanded: Boolean,
            val collapsible: Boolean,
        ) : DisplayItem {
            override val identity: String = "state:$key"
        }

        data class County(val row: OfflineRegionListRow) : DisplayItem {
            override val identity: String = "county:${row.regionId}"
        }
    }

    private data class RegionAction(val label: Int, val enabled: Boolean)

    private companion object {
        const val VIEW_TYPE_STATE = 0
        const val VIEW_TYPE_COUNTY = 1
    }
}
