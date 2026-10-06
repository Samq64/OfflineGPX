// In VTM's package for ReadBuffer's package-private constructor; VTM reads a header only while opening a tile source.
package org.oscim.tiling.source.mapfile

import org.oscim.tiling.source.mapfile.header.MapFileHeader
import java.io.File
import java.io.RandomAccessFile

/** Null unless the header passes the checks VTM's tile source makes on opening [file]. */
internal fun readMapFileHeader(file: File): MapFileHeader? = RandomAccessFile(file, "r").use { handle ->
    MapFileHeader().takeIf { it.readHeader(ReadBuffer(handle.channel), handle.length()).isSuccess }
}
