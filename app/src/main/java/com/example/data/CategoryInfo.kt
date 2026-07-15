package com.example.data

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.*

data class CategoryInfo(
    val id: String,
    val name: String,
    val icon: String,
    val color: Color
)

val CATEGORIES = listOf(
    CategoryInfo("electricity", "Electricity", "⚡", CatElectricity),
    CategoryInfo("water", "Water", "💧", CatWater),
    CategoryInfo("landtax", "Land Tax", "🌾", CatLandTax),
    CategoryInfo("buildingtax", "Building Tax", "🏠", CatBuildingTax),
    CategoryInfo("cabletv", "Cable / Subscription", "📺", CatCableTv),
    CategoryInfo("other", "Other", "📄", CatOther)
)
