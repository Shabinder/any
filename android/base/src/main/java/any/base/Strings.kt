package any.base

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable

@Stable
interface Strings {
    operator fun invoke(@StringRes id: Int): String

    operator fun invoke(@StringRes id: Int, vararg formatArgs: Any): String

    companion object {
        val None = object : Strings {
            override fun invoke(id: Int): String = ""

            override fun invoke(id: Int, vararg formatArgs: Any): String = ""
        }
    }
}