package me.pinfort.tsvideos.library.persistence

import me.pinfort.tsvideos.library.domain.LibraryKind

/** Table identifiers come only from a closed enum, never from request text. */
internal class LibraryOrganizationSql {
    private fun kind(parameters: Map<String, Any>) = parameters["kind"] as LibraryKind

    private fun select(parameters: Map<String, Any>): String {
        val kind = kind(parameters)
        return """
            SELECT l.id, l.name,
                (SELECT COUNT(*) FROM ${kind.associationTable} a JOIN program p ON p.id=a.program_id
                    WHERE a.label_id=l.id) AS program_count
            FROM ${kind.table} l
            """.trimIndent()
    }

    fun list(parameters: Map<String, Any>) = "${select(parameters)} ORDER BY l.name_key, l.id"

    fun find(parameters: Map<String, Any>) = "${select(parameters)} WHERE l.id=#{id}"

    fun lock(parameters: Map<String, Any>) = "SELECT id FROM ${kind(parameters).table} WHERE id=#{id} FOR UPDATE"

    fun insert(parameters: Map<String, Any>) = "INSERT INTO ${kind(parameters).table}(name,name_key) VALUES(#{name},#{nameKey})"

    fun reuse(parameters: Map<String, Any>) = insert(parameters) + " ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)"

    fun rename(parameters: Map<String, Any>) = "UPDATE ${kind(parameters).table} SET name=#{name}, name_key=#{nameKey} WHERE id=#{id}"

    fun delete(parameters: Map<String, Any>) = "DELETE FROM ${kind(parameters).table} WHERE id=#{id}"

    fun assign(parameters: Map<String, Any>) =
        """
        INSERT INTO ${kind(parameters).associationTable}(label_id,program_id) VALUES(#{id},#{programId})
        ON DUPLICATE KEY UPDATE program_id=program_id
        """.trimIndent()

    fun unassign(parameters: Map<String, Any>) =
        "DELETE FROM ${kind(parameters).associationTable} WHERE label_id=#{id} AND program_id=#{programId}"

    fun forProgram(parameters: Map<String, Any>) =
        """
        ${select(parameters)} JOIN ${kind(parameters).associationTable} a ON a.label_id=l.id
        JOIN program p ON p.id=a.program_id WHERE p.id=#{programId} ORDER BY l.name_key,l.id
        """.trimIndent()
}
