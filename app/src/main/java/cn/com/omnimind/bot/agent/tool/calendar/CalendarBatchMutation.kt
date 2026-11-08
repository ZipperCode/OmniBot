package cn.com.omnimind.bot.agent

/**
 * 日历批量操作变异项。
 *
 * 支持在单一原子事务中执行创建、更新或删除日历事件。
 */
sealed interface CalendarBatchMutation {
    val operation: String

    /**
     * 创建日历事件操作。
     */
    data class Create(val request: CalendarEventCreateRequest) : CalendarBatchMutation {
        override val operation: String get() = "create"
    }

    /**
     * 更新日历事件操作。
     */
    data class Update(val request: CalendarEventUpdateRequest) : CalendarBatchMutation {
        override val operation: String get() = "update"
    }

    /**
     * 删除日历事件操作。
     */
    data class Delete(val eventId: String) : CalendarBatchMutation {
        override val operation: String get() = "delete"
    }
}
