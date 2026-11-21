package com.lostf1sh.pixelplayeross.data.service.player

import com.lostf1sh.pixelplayeross.data.model.MixTransition

/**
 * Undo and redo for the mix editor.
 *
 * A drag sends an edit every frame; those belong to one step. An edit with the same [record] group
 * as the one before, within [groupMs] of it, continues that step instead of starting a new one.
 * An edit without a group (picking a preset, a window length) is always a step of its own.
 */
class MixHistory(private val limit: Int = 100, private val groupMs: Long = 600L) {

    private val undoStack = ArrayDeque<MixTransition>()
    private val redoStack = ArrayDeque<MixTransition>()
    private var lastGroup: String? = null
    private var lastAtMs = Long.MIN_VALUE

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** The mix went from [before] to something else at [nowMs]; [group] names a continuing gesture. */
    fun record(before: MixTransition, group: String?, nowMs: Long) {
        val continues = group != null && group == lastGroup && nowMs - lastAtMs <= groupMs
        lastGroup = group
        lastAtMs = nowMs
        if (continues) return
        undoStack.addLast(before)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** The mix to go back to from [current], or null when there is none. */
    fun undo(current: MixTransition): MixTransition? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        lastGroup = null
        return previous
    }

    /** The mix [undo] went back from, or null when there is none. */
    fun redo(current: MixTransition): MixTransition? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        lastGroup = null
        return next
    }
}
