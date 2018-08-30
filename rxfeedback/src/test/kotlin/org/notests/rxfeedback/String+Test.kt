package org.notests.rxfeedback

import arrow.core.None
import arrow.core.Option
import arrow.core.Some

/**
 * Created by juraj on 13/11/2017.
 */

var String.needsToAppendDot: Option<Unit>
    get() =
        if (this == "initial" || this == "initial_." || this == "initial_._.") {
            Some(Unit)
        } else None
    set(_) {}

var String.needsToAppend: Option<String>
    get() =
        when {
            this == "initial" -> Some("_a")
            this == "initial_a" -> Some("_b")
            this == "initial_a_b" -> Some("_c")
            else -> None
        }
    set(_) {}

var String.needsToAppendParallel: Set<String>
    get() =
        if (this.contains("_a") && this.contains("_b")) {
            setOf("_c")
        } else {
            var result = emptySet<String>()
            if (!this.contains("_a")) {
                result = result.plus("_a")
            }
            if (!this.contains("_b")) {
                result = result.plus("_b")
            }
            result
        }
    set(_) {}
