package dev.op.deptor.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.ui.graphics.vector.ImageVector
import dev.op.deptor.domain.model.ExpenseCategory

/**
 * Jak se kategorie zobrazuje.
 *
 * Názvy i ikony jsou tady, ne v doméně: doména si drží jen stabilní id, které jde do
 * databáze. Přejmenování kategorie v UI tak nemá jak rozhodit už uložené výdaje.
 */
val ExpenseCategory.label: String
    get() = when (this) {
        ExpenseCategory.FOOD -> "Jídlo a pití"
        ExpenseCategory.GROCERIES -> "Nákupy potravin"
        ExpenseCategory.TRANSPORT -> "Doprava"
        ExpenseCategory.ACCOMMODATION -> "Ubytování"
        ExpenseCategory.ENTERTAINMENT -> "Zábava"
        ExpenseCategory.SHOPPING -> "Nákupy"
        ExpenseCategory.HEALTH -> "Zdraví"
        ExpenseCategory.UTILITIES -> "Bydlení a služby"
        ExpenseCategory.OTHER -> "Ostatní"
    }

val ExpenseCategory.icon: ImageVector
    get() = when (this) {
        ExpenseCategory.FOOD -> Icons.Default.Restaurant
        ExpenseCategory.GROCERIES -> Icons.Default.LocalGroceryStore
        ExpenseCategory.TRANSPORT -> Icons.Default.DirectionsBus
        ExpenseCategory.ACCOMMODATION -> Icons.Default.Hotel
        ExpenseCategory.ENTERTAINMENT -> Icons.Default.TheaterComedy
        ExpenseCategory.SHOPPING -> Icons.Default.ShoppingBag
        ExpenseCategory.HEALTH -> Icons.Default.LocalHospital
        ExpenseCategory.UTILITIES -> Icons.Default.Receipt
        ExpenseCategory.OTHER -> Icons.Default.Category
    }

/** Popis pro výdaj, který kategorii nemá nebo má neznámou. */
const val UNCATEGORIZED_LABEL = "Bez kategorie"

val UNCATEGORIZED_ICON: ImageVector get() = Icons.Default.Category
