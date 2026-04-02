package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.network.Filters.FilterList

class Procomic : SomeSuperClass() {
    // ... other code 
    override fun getFilterList(): FilterList = super.getFilterList()  // Fixed line 86 to prevent infinite recursion
    // ... other code
}