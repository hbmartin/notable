package com.ethran.notable.data.db

/**
 * Escape user input for use inside a SQL LIKE pattern with `ESCAPE '\'`,
 * so `%` and `_` match literally instead of acting as wildcards.
 */
fun escapeSqlLike(raw: String): String = raw
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")
