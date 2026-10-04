package com.summer.journal.data.repo

import com.summer.journal.data.local.dao.AttachmentDao
import com.summer.journal.data.local.dao.MemoDao
import com.summer.journal.data.local.entity.AttachmentEntity
import com.summer.journal.data.local.entity.MemoEntity
import com.summer.journal.data.local.entity.MemoListRow
import com.summer.journal.domain.model.Attachment
import com.summer.journal.domain.model.AttachmentType
import com.summer.journal.domain.model.Memo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoRepository @Inject constructor(
    private val memoDao: MemoDao,
    private val attachmentDao: AttachmentDao,
) {

    /**
     * 列表页的 Flow。
     *
     * 注意这里**不带附件内容** —— 列表只需要「有没有音频 / 图片」这种布尔信息。
     * 如果每条 memo 都去查一次附件表，50 条就是 50 次查询；
     * 正确做法是列表只查 memo 表，附件种类用一个轻量查询聚合（见 observeKinds）。
     */
    fun observeAll(): Flow<List<Memo>> = memoDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeFiltered(type: AttachmentType?): Flow<List<Memo>> =
        memoDao.observeFilteredByAttachmentType(type?.name)
            .map { list -> list.map { it.toDomain() } }

    fun search(keyword: String): Flow<List<Memo>> =
        memoDao.search(keyword).map { list -> list.map { it.toDomain() } }

    /** 详情页：正文 + 附件一起给 */
    fun observeDetail(memoId: Long): Flow<Memo?> = combine(
        memoDao.observeAll().map { list -> list.firstOrNull { it.id == memoId } },
        attachmentDao.observeByMemo(memoId),
    ) { memo, attachments ->
        memo?.toDomain()?.copy(attachments = attachments.map { it.toDomain() })
    }

    suspend fun create(title: String = "", body: String = ""): Long {
        val now = System.currentTimeMillis()
        return memoDao.upsert(
            MemoEntity(title = title, body = body, createdAt = now, updatedAt = now)
        )
    }

    suspend fun update(memo: Memo) = memoDao.upsert(
        MemoEntity(
            id = memo.id,
            title = memo.title,
            coverPath = memo.coverUri,
            body = memo.body,
            tags = memo.tags.joinToString(","),
            createdAt = memo.createdAt.toEpochMillis(),
            updatedAt = System.currentTimeMillis(),
        )
    )

    /** 换封面 —— 独立方法，因为列表页的「换封面」按钮不想读整条记录 */
    suspend fun updateCover(memoId: Long, relativePath: String?) =
        memoDao.updateCover(memoId, relativePath, System.currentTimeMillis())

    /** 正文自动保存（详情页防抖后调用） */
    suspend fun updateText(memoId: Long, title: String, body: String) =
        memoDao.updateText(memoId, title, body, System.currentTimeMillis())

    /** OCR 结果追加到正文末尾 */
    suspend fun appendToBody(memoId: Long, addition: String) {
        // 前面补一个换行，避免和原文粘在一起
        val text = if (addition.startsWith("\n")) addition else "\n\n$addition"
        memoDao.appendToBody(memoId, text, System.currentTimeMillis())
    }

    suspend fun addAttachment(memoId: Long, attachment: Attachment): Long =
        attachmentDao.upsert(attachment.copy(memoId = memoId).toEntity())

    suspend fun removeAttachment(attachmentId: Long) = attachmentDao.deleteById(attachmentId)

    /**
     * 删手札：**不立刻删文件**。
     * 先把附件标记为孤儿，交给 WorkManager 过 24 小时清理 —— 防手滑。
     */
    suspend fun deleteSoft(memoId: Long) {
        attachmentDao.markOrphanByMemo(memoId, System.currentTimeMillis())
        memoDao.deleteById(memoId)
    }

    suspend fun orphansBefore(millis: Long): List<Attachment> =
        attachmentDao.listOrphansBefore(millis).map { it.toDomain() }

    suspend fun purgeAttachment(id: Long) = attachmentDao.deleteById(id)

    /* ────────────── 映射 ────────────── */

    private fun MemoEntity.toDomain() = Memo(
        id = id,
        title = title,
        coverUri = coverPath,
        body = body,
        tags = if (tags.isBlank()) emptySet() else tags.split(',').toSet(),
        createdAt = createdAt.toLocalDateTime(),
        updatedAt = updatedAt.toLocalDateTime(),
    )

    /**
     * 列表投影 → 领域模型。
     *
     * `kinds` 是 DAO 用 `GROUP_CONCAT(DISTINCT type)` 聚出来的字符串，
     * 形如 `"AUDIO,IMAGE"`；解析成本项目的 [AttachmentType] 集合后，
     * 卡片的「图片 / 录音」标签才终于有值。
     */
    private fun MemoListRow.toDomain() = memo.toDomain().copy(
        kinds = kinds
            .orEmpty()
            .split(',')
            .mapNotNull { raw ->
                val name = raw.trim()
                if (name.isEmpty()) null
                else runCatching { AttachmentType.valueOf(name) }.getOrNull()
            }
            .toSet(),
    )

    private fun AttachmentEntity.toDomain() = Attachment(
        id = id,
        memoId = memoId,
        type = type,
        relativePath = relativePath,
        displayName = displayName,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        sheetJson = sheetJson,
    )

    private fun Attachment.toEntity() = AttachmentEntity(
        id = id,
        memoId = memoId,
        type = type,
        relativePath = relativePath,
        displayName = displayName,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        sheetJson = sheetJson,
        createdAt = System.currentTimeMillis(),
        orphanSince = null,
    )
}
