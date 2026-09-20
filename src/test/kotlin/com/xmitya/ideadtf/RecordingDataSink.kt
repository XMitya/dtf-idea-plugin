package com.xmitya.ideadtf

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataMap
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DataSnapshotProvider
import com.intellij.openapi.actionSystem.UiDataProvider

/**
 * Takes the snapshot a [UiDataProvider] offers.
 *
 * The platform materialises a lazy value only through an async data context over a showing
 * component hierarchy, which a fixture test has not got, so the panel is asked directly.
 */
class RecordingDataSink : DataSink {
    private val values = mutableMapOf<DataKey<*>, Any?>()
    private val lazyValues = mutableMapOf<DataKey<*>, (DataMap) -> Any?>()

    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: DataKey<T>): T? = (values[key] ?: lazyValues[key]?.invoke(NOTHING)) as T?

    override fun <T : Any> set(key: DataKey<T>, data: T?) {
        values[key] = data
    }

    override fun <T : Any> setNull(key: DataKey<T>) {
        values[key] = null
    }

    override fun <T : Any> lazyValue(key: DataKey<T>, data: (DataMap) -> T?) {
        lazyValues[key] = data
    }

    override fun <T : Any> lazyNull(key: DataKey<T>) {
        values[key] = null
    }

    override fun uiDataSnapshot(provider: UiDataProvider) = provider.uiDataSnapshot(this)

    override fun dataSnapshot(provider: DataSnapshotProvider) = provider.dataSnapshot(this)

    @Suppress("DEPRECATION")
    override fun uiDataSnapshot(provider: DataProvider) = Unit

    private companion object {
        /** Nothing else is in the snapshot, and the panel's own value does not look. */
        val NOTHING = object : DataMap {
            override fun <T : Any> get(key: DataKey<T>): T? = null
        }
    }
}
