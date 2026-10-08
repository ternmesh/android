package org.ternmesh.app

import android.app.Application
import org.ternmesh.app.node.NodeRepository

/** Holds the one repository, which outlives any screen. */
class TernApplication : Application() {
    val repository: NodeRepository by lazy { NodeRepository(this) }
}
