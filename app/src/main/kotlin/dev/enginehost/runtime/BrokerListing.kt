package dev.enginehost.runtime

import android.os.Parcel
import android.os.Parcelable

/**
 * One directory's entries as IEngineFileBroker.listEntries answers them, in
 * a single transaction so names and details always describe the same
 * moment: `names[i]` is described by `info[3 * i]` (1 file, 2 directory),
 * `info[3 * i + 1]` (size in bytes) and `info[3 * i + 2]` (modified time,
 * ms since the epoch).
 */
class BrokerListing(@JvmField val names: Array<String>, @JvmField val info: LongArray) : Parcelable {
    init {
        require(info.size == names.size * 3) { "three details per name" }
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeStringArray(names)
        out.writeLongArray(info)
    }

    companion object {
        const val KIND_FILE = 1L
        const val KIND_DIRECTORY = 2L

        @JvmField
        val CREATOR = object : Parcelable.Creator<BrokerListing> {
            override fun createFromParcel(source: Parcel): BrokerListing {
                val names = source.createStringArray().orEmpty().map { it ?: "" }.toTypedArray()
                val info = source.createLongArray() ?: LongArray(0)
                return BrokerListing(names, info)
            }

            override fun newArray(size: Int): Array<BrokerListing?> = arrayOfNulls(size)
        }
    }
}
