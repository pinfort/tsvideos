package me.pinfort.tsvideos.core.domain

/** Manual organization labels. TAG is a user-managed library_tag, distinct from detector tags (ExecutedFileTag). */
enum class LibraryKind(
    val table: String,
) {
    COLLECTION("library_collection"),
    TAG("library_tag"),
    ;

    val associationTable: String get() = "${table}_program"
}

data class LibraryLabel(
    val id: Long,
    val name: String,
    val programCount: Long,
)

data class ProgramOrganization(
    val collections: List<LibraryLabel>,
    val tags: List<LibraryLabel>,
)

/** Narrows a program search to members of a manual collection and/or tag; both null means no narrowing. */
data class LibraryProgramFilter(
    val collectionId: Long? = null,
    /** A manual library_tag ID, not a detector tag from executed_file_tag. */
    val libraryTagId: Long? = null,
) {
    init {
        require(collectionId == null || collectionId > 0) { "collectionId must be positive" }
        require(libraryTagId == null || libraryTagId > 0) { "libraryTagId must be positive" }
    }

    val isEmpty: Boolean get() = collectionId == null && libraryTagId == null
}

class LibraryItemNotFoundException : RuntimeException("Collection, tag or recording not found")

class DuplicateLibraryNameException : RuntimeException("A collection or tag with this name already exists")
