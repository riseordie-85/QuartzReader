package com.tim.reader.data

/** The kinds of event in the reading history. */
object EventKind {
    /** Time spent reading one book, with the pages or positions covered. */
    const val SESSION = "session"

    /** A read of a book began. Every read has its own ID, so rereads count separately. */
    const val READ_STARTED = "read_started"

    /** A read reached the end of the book. */
    const val READ_FINISHED = "read_finished"

    /** Stars and a short review for a book. The newest one counts. */
    const val RATING = "rating"

    /** Stars and a short review for a whole series, matched by its title. */
    const val SERIES_RATING = "series_rating"

    /** A goal was set, changed or removed. Each goal keeps its ID in read_id, and the newest event counts. */
    const val GOAL = "goal"

    /** A goal was reached for one period, so it's celebrated once. */
    const val GOAL_DONE = "goal_done"

    /** An achievement tier was unlocked, so its toast shows once. */
    const val ACHIEVEMENT = "achievement"

    /** A finished book only starts a new read from before this point. */
    const val REREAD_BEFORE = 0.9f
}
