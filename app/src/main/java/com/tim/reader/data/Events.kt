package com.tim.reader.data

/** The kinds of event in the reading history. */
object EventKind {
    /** Time spent reading one book, with the pages or positions covered. */
    const val SESSION = "session"

    /** A read of a book began. Every read has its own ID, so rereads count separately. */
    const val READ_STARTED = "read_started"

    /** A read reached the end of the book. */
    const val READ_FINISHED = "read_finished"

    /** A finished book only starts a new read from before this point. */
    const val REREAD_BEFORE = 0.9f
}
