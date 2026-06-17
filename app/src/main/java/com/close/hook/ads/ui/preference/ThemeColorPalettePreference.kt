package com.close.hook.ads.ui.preference

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.databinding.ItemThemeColorSwatchBinding
import com.close.hook.ads.preference.PrefManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors

/**
 * Inline Preference that renders the theme color palette as a horizontal row
 * of multi-tone swatches, matching the system wallpaper colour picker style.
 *
 * - First swatch (when Dynamic Colors are available) toggles followSystemAccent.
 * - Each preset is rendered as a circle split into three sectors —
 *   primary on the top, secondary/tertiary mid-tones on the bottom.
 * - Selection is shown as a rounded-square ring wrapping the circle.
 * - Long-press on a swatch shows the colour name as a tooltip.
 *
 * Selecting a swatch persists the choice and recreates the host Activity.
 * The horizontal scroll position is preserved across that recreate so the
 * user is not bounced back to the start of the row.
 */
class ThemeColorPalettePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    private var recyclerRef: RecyclerView? = null

    init {
        layoutResource = R.layout.preference_theme_color_palette
        isPersistent = false
    }

    override fun onAttached() {
        super.onAttached()
        refreshSummary()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val recycler = holder.findViewById(R.id.swatch_recycler) as RecyclerView
        recyclerRef = recycler
        recycler.layoutManager =
            LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        val entries = buildEntries()
        recycler.adapter = SwatchAdapter(entries) { entry ->
            applySelection(entry)
        }
        val pendingScroll = pendingScrollX
        if (pendingScroll > 0) {
            pendingScrollX = 0
            recycler.post { recycler.scrollBy(pendingScroll, 0) }
        }
    }

    private fun refreshSummary() {
        summary = currentSummary(buildEntries())
    }

    private fun buildEntries(): List<SwatchEntry> = buildList {
        if (DynamicColors.isDynamicColorAvailable()) {
            add(SwatchEntry.Dynamic(isSelected = PrefManager.followSystemAccent))
        }
        val selected = !PrefManager.followSystemAccent
        for (key in PRESET_KEYS) {
            add(SwatchEntry.Color(key, isSelected = selected && PrefManager.themeColor == key))
        }
    }

    private fun applySelection(entry: SwatchEntry) {
        pendingScrollX = recyclerRef?.computeHorizontalScrollOffset() ?: 0
        when (entry) {
            is SwatchEntry.Dynamic -> PrefManager.followSystemAccent = true
            is SwatchEntry.Color -> {
                PrefManager.followSystemAccent = false
                PrefManager.themeColor = entry.key
            }
        }
        (context as? Activity)?.recreate()
    }

    private fun currentSummary(entries: List<SwatchEntry>): CharSequence {
        if (PrefManager.followSystemAccent && DynamicColors.isDynamicColorAvailable()) {
            return context.getString(R.string.theme_color_summary_dynamic)
        }
        val current = entries.firstOrNull { it is SwatchEntry.Color && it.key == PrefManager.themeColor }
                as? SwatchEntry.Color
        val label = current?.let { context.getString(displayNameRes(it.key)) }
            ?: context.getString(R.string.color_default)
        return context.getString(R.string.theme_color_summary_custom, label)
    }

    private sealed class SwatchEntry {
        abstract val isSelected: Boolean

        data class Dynamic(override val isSelected: Boolean) : SwatchEntry()
        data class Color(val key: String, override val isSelected: Boolean) : SwatchEntry()
    }

    private class SwatchAdapter(
        private val entries: List<SwatchEntry>,
        private val onClick: (SwatchEntry) -> Unit
    ) : RecyclerView.Adapter<SwatchAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemThemeColorSwatchBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(entries[position], onClick)
        }

        class VH(private val binding: ItemThemeColorSwatchBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(entry: SwatchEntry, onClick: (SwatchEntry) -> Unit) {
                val context = binding.root.context
                val label: String = when (entry) {
                    is SwatchEntry.Dynamic -> {
                        val fill = MaterialColors.getColor(
                            binding.root,
                            com.google.android.material.R.attr.colorPrimaryContainer
                        )
                        binding.palette.setSolid(fill)
                        binding.swatchIcon.setImageResource(R.drawable.outline_format_color_fill_24)
                        binding.swatchIcon.imageTintList = ColorStateList.valueOf(
                            MaterialColors.getColor(
                                binding.root,
                                com.google.android.material.R.attr.colorOnPrimaryContainer
                            )
                        )
                        binding.swatchIcon.visibility = View.VISIBLE
                        context.getString(R.string.theme_color_dynamic)
                    }
                    is SwatchEntry.Color -> {
                        val snake = entry.key.removePrefix("MATERIAL_").lowercase()
                        // Saturated primary on top, mid-tone secondary/tertiary (level 80)
                        // on the bottom — pastel containers (level 90) read as washed-out,
                        // level-40 reads as muddy. Level-80 keeps colour identity while
                        // staying clearly lighter than primary40.
                        val primary = paletteColor(context, snake, "primary_40")
                        val secondary = paletteColor(context, snake, "secondary_80")
                        val tertiary = paletteColor(context, snake, "tertiary_80")
                        binding.palette.setQuadrants(primary, secondary, tertiary)
                        binding.swatchIcon.visibility = View.GONE
                        context.getString(displayNameRes(entry.key))
                    }
                }
                binding.swatchRoot.contentDescription = label
                binding.swatchRoot.tooltipText = label
                val density = context.resources.displayMetrics.density
                binding.selectionBox.strokeWidth =
                    if (entry.isSelected) (density * 2f).toInt() else 0
                binding.swatchRoot.setOnClickListener { onClick(entry) }
            }

            @ColorInt
            private fun paletteColor(context: Context, snake: String, suffix: String): Int {
                val name = "md_theme_material_${snake}_palette_$suffix"
                val id = context.resources.getIdentifier(name, "color", context.packageName)
                return if (id != 0) context.getColor(id) else 0
            }
        }
    }

    companion object {
        /**
         * Horizontal scroll offset to restore on the next bind. Stored as a process-wide
         * singleton so it survives the activity recreate triggered by [applySelection].
         */
        private var pendingScrollX: Int = 0

        // Mirrors materialThemeBuilder block in app/build.gradle.kts.
        private val PRESET_KEYS = listOf(
            "MATERIAL_DEFAULT",
            "MATERIAL_SAKURA",
            "MATERIAL_RED",
            "MATERIAL_PINK",
            "MATERIAL_PURPLE",
            "MATERIAL_DEEP_PURPLE",
            "MATERIAL_INDIGO",
            "MATERIAL_BLUE",
            "MATERIAL_LIGHT_BLUE",
            "MATERIAL_CYAN",
            "MATERIAL_TEAL",
            "MATERIAL_GREEN",
            "MATERIAL_LIGHT_GREEN",
            "MATERIAL_LIME",
            "MATERIAL_YELLOW",
            "MATERIAL_AMBER",
            "MATERIAL_ORANGE",
            "MATERIAL_DEEP_ORANGE",
            "MATERIAL_BROWN",
            "MATERIAL_BLUE_GREY",
        )

        private fun displayNameRes(key: String): Int = when (key) {
            "MATERIAL_SAKURA" -> R.string.color_sakura
            "MATERIAL_RED" -> R.string.color_red
            "MATERIAL_PINK" -> R.string.color_pink
            "MATERIAL_PURPLE" -> R.string.color_purple
            "MATERIAL_DEEP_PURPLE" -> R.string.color_deep_purple
            "MATERIAL_INDIGO" -> R.string.color_indigo
            "MATERIAL_BLUE" -> R.string.color_blue
            "MATERIAL_LIGHT_BLUE" -> R.string.color_light_blue
            "MATERIAL_CYAN" -> R.string.color_cyan
            "MATERIAL_TEAL" -> R.string.color_teal
            "MATERIAL_GREEN" -> R.string.color_green
            "MATERIAL_LIGHT_GREEN" -> R.string.color_light_green
            "MATERIAL_LIME" -> R.string.color_lime
            "MATERIAL_YELLOW" -> R.string.color_yellow
            "MATERIAL_AMBER" -> R.string.color_amber
            "MATERIAL_ORANGE" -> R.string.color_orange
            "MATERIAL_DEEP_ORANGE" -> R.string.color_deep_orange
            "MATERIAL_BROWN" -> R.string.color_brown
            "MATERIAL_BLUE_GREY" -> R.string.color_blue_grey
            else -> R.string.color_default
        }
    }
}
