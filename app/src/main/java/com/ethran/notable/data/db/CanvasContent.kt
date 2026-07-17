package com.ethran.notable.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import java.util.Date
import java.util.UUID
import javax.inject.Inject

enum class LinkTargetType {
    PAGE,
    NOTEBOOK,
    URL,
    PDF_ATTACHMENT,
}

enum class AttachmentStorageMode {
    MANAGED,
    OBSERVED,
}

@Entity(
    foreignKeys = [ForeignKey(
        entity = Page::class,
        parentColumns = ["id"],
        childColumns = ["pageId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class CanvasText(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(index = true) val pageId: String,
    val markdown: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val fontSize: Float = 24f,
    val color: Int = 0xFF000000.toInt(),
    val alignment: String = "NORMAL",
    val backgroundColor: Int = 0x00000000,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
)

@Entity(
    foreignKeys = [ForeignKey(
        entity = Page::class,
        parentColumns = ["id"],
        childColumns = ["pageId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class CanvasLink(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(index = true) val pageId: String,
    val label: String,
    val target: String,
    val targetType: LinkTargetType,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: Int = 0xFF000000.toInt(),
    val fontSize: Float = 24f,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
)

@Entity(
    foreignKeys = [ForeignKey(
        entity = Page::class,
        parentColumns = ["id"],
        childColumns = ["pageId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class Attachment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(index = true) val pageId: String,
    val displayName: String,
    val mimeType: String = "application/pdf",
    val storageMode: AttachmentStorageMode,
    val relativePath: String? = null,
    val checksum: String? = null,
    val size: Long? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
)

/** Device-local URI grant for an observed attachment. This entity is never synchronized. */
@Entity(
    foreignKeys = [ForeignKey(
        entity = Attachment::class,
        parentColumns = ["id"],
        childColumns = ["attachmentId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class AttachmentBinding(
    @PrimaryKey val attachmentId: String,
    val uri: String,
    val updatedAt: Date = Date(),
)

@Dao
interface CanvasTextDao {
    @Query("SELECT * FROM CanvasText WHERE pageId = :pageId")
    suspend fun getByPage(pageId: String): List<CanvasText>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun create(items: List<CanvasText>)

    @Update
    suspend fun update(items: List<CanvasText>)

    @Query("DELETE FROM CanvasText WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}

@Dao
interface CanvasLinkDao {
    @Query("SELECT * FROM CanvasLink WHERE pageId = :pageId")
    suspend fun getByPage(pageId: String): List<CanvasLink>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun create(items: List<CanvasLink>)

    @Update
    suspend fun update(items: List<CanvasLink>)

    @Query("DELETE FROM CanvasLink WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM Attachment WHERE pageId = :pageId")
    suspend fun getByPage(pageId: String): List<Attachment>

    @Query("SELECT * FROM Attachment WHERE id = :id")
    suspend fun getById(id: String): Attachment?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun create(items: List<Attachment>)

    @Query("DELETE FROM Attachment WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}

@Dao
interface AttachmentBindingDao {
    @Query("SELECT * FROM AttachmentBinding WHERE attachmentId = :attachmentId")
    suspend fun get(attachmentId: String): AttachmentBinding?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(binding: AttachmentBinding)

    @Query("DELETE FROM AttachmentBinding WHERE attachmentId = :attachmentId")
    suspend fun delete(attachmentId: String)
}

class CanvasTextRepository @Inject constructor(private val dao: CanvasTextDao) {
    suspend fun create(items: List<CanvasText>) = dao.create(items)
    suspend fun update(items: List<CanvasText>) = dao.update(items)
    suspend fun delete(ids: List<String>) = dao.delete(ids)
    suspend fun getByPage(pageId: String) = dao.getByPage(pageId)
}

class CanvasLinkRepository @Inject constructor(private val dao: CanvasLinkDao) {
    suspend fun create(items: List<CanvasLink>) = dao.create(items)
    suspend fun update(items: List<CanvasLink>) = dao.update(items)
    suspend fun delete(ids: List<String>) = dao.delete(ids)
    suspend fun getByPage(pageId: String) = dao.getByPage(pageId)
}

class AttachmentRepository @Inject constructor(
    private val dao: AttachmentDao,
    private val bindingDao: AttachmentBindingDao,
) {
    suspend fun create(items: List<Attachment>) = dao.create(items)
    suspend fun delete(ids: List<String>) = dao.delete(ids)
    suspend fun getByPage(pageId: String) = dao.getByPage(pageId)
    suspend fun getById(id: String) = dao.getById(id)
    suspend fun getBinding(attachmentId: String) = bindingDao.get(attachmentId)
    suspend fun putBinding(binding: AttachmentBinding) = bindingDao.put(binding)
    suspend fun deleteBinding(attachmentId: String) = bindingDao.delete(attachmentId)
}
