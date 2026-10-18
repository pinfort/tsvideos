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

class LibraryItemNotFoundException : RuntimeException("Collection, tag or recording not found")

class DuplicateLibraryNameException : RuntimeException("A collection or tag with this name already exists")
