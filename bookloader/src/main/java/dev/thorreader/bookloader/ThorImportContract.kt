package dev.thorreader.bookloader

/**
 * Thor Reader's trusted import API - an identical copy of Thor Reader's
 * app/src/main/java/dev/thorreader/app/importer/ImportContract.kt (ContractTest checks that the
 * two copies match).
 */
object ThorImportContract {
    const val THOR_PACKAGE = "dev.thorreader.app"
    const val ACTION_IMPORT_BOOKS = "dev.thorreader.action.IMPORT_BOOKS"
    const val ACTION_CHOOSE_FOLDER = "dev.thorreader.action.CHOOSE_IMPORT_FOLDER"
    const val PERMISSION_IMPORT = "dev.thorreader.permission.IMPORT"

    const val MSG_IMPORT = 1
    const val MSG_PROGRESS = 2
    const val MSG_RESULT = 3

    const val EXTRA_URIS = "dev.thorreader.extra.URIS"
    const val EXTRA_METAS = "dev.thorreader.extra.METAS"
    const val EXTRA_RESULTS = "dev.thorreader.extra.RESULTS"

    const val M_TITLE = "title"
    const val M_AUTHORS = "authors"
    const val M_SERIES = "series"
    const val M_SERIES_NUMBER = "series_number"
    const val M_TYPE = "type"
    const val M_GENRES = "genres"
    const val M_DIRECTION = "direction"
    const val M_MODE = "mode"

    const val R_URI = "uri"
    const val R_STATUS = "status"
    const val R_BOOK_ID = "book_id"
    const val R_MESSAGE = "message"
}
